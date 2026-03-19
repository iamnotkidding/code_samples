package com.autotouch.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.autotouch.app.R
import com.autotouch.app.parser.ScriptCommand
import com.autotouch.app.parser.ScriptParser
import com.autotouch.app.receiver.NotificationActionReceiver
import com.autotouch.app.ui.MainActivity
import kotlinx.coroutines.*
import java.io.File

/**
 * 스크립트 실행 포그라운드 서비스
 *
 * - 백그라운드에서 스크립트 파싱 & 실행
 * - 알림창에 실행 현황 업데이트
 * - 알림 버튼으로 시작/일시정지/중지
 * - 중지 시 MainActivity로 복귀
 */
class ScriptExecutorService : Service() {

    companion object {
        private const val TAG = "ScriptExecutor"
        const val CHANNEL_ID = "autotouch_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.autotouch.ACTION_START"
        const val ACTION_STOP = "com.autotouch.ACTION_STOP"
        const val ACTION_PAUSE = "com.autotouch.ACTION_PAUSE"

        const val EXTRA_SCRIPT_PATH = "script_path"
        const val EXTRA_SCRIPT_CONTENT = "script_content"

        var isRunning = false
            private set
        var isPaused = false
            private set
        var currentStatus: String = "대기 중"
            private set

        // 상태 변경 리스너 (MainActivity에서 관찰)
        var onStatusChanged: ((String, Int, Int) -> Unit)? = null
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var executionJob: Job? = null
    private var commands: List<ScriptCommand> = emptyList()
    private var currentCommandIndex = 0
    private var totalCommands = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val scriptPath = intent.getStringExtra(EXTRA_SCRIPT_PATH)
                val scriptContent = intent.getStringExtra(EXTRA_SCRIPT_CONTENT)
                startExecution(scriptPath, scriptContent)
            }
            ACTION_PAUSE -> togglePause()
            ACTION_STOP -> stopExecution()
            else -> {
                // 서비스 시작만 (포그라운드 유지)
                startForeground(NOTIFICATION_ID, buildNotification("준비 완료", 0, 0))
            }
        }
        return START_NOT_STICKY
    }

    // ═══════════════════════════════════════════════
    //  스크립트 실행
    // ═══════════════════════════════════════════════

    private fun startExecution(scriptPath: String?, scriptContent: String?) {
        if (isRunning) {
            Log.w(TAG, "이미 실행 중입니다")
            return
        }

        // 접근성 서비스 확인
        if (!AutoTouchAccessibilityService.isConnected) {
            updateStatus("⚠ 접근성 서비스를 활성화하세요", 0, 0)
            showToast("접근성 서비스가 비활성화 상태입니다")
            return
        }

        // 스크립트 파싱
        val parser = ScriptParser()
        val parseResult = when {
            scriptContent != null -> parser.parseString(scriptContent)
            scriptPath != null -> {
                val file = File(scriptPath)
                if (!file.exists()) {
                    updateStatus("⚠ 파일을 찾을 수 없음", 0, 0)
                    return
                }
                parser.parse(file.inputStream())
            }
            else -> {
                updateStatus("⚠ 스크립트가 없습니다", 0, 0)
                return
            }
        }

        // 파싱 에러 확인
        if (parseResult.errors.isNotEmpty()) {
            val errorMsg = parseResult.errors.joinToString("\n") {
                "Line ${it.lineNumber}: ${it.message}"
            }
            Log.w(TAG, "파싱 에러:\n$errorMsg")
        }

        // 실행 가능한 명령만 필터 (주석 제외)
        commands = parseResult.commands.filter { it !is ScriptCommand.Comment }
        totalCommands = commands.size

        if (commands.isEmpty()) {
            updateStatus("⚠ 실행할 명령이 없습니다", 0, 0)
            return
        }

        isRunning = true
        isPaused = false
        currentCommandIndex = 0

        startForeground(NOTIFICATION_ID, buildNotification("실행 중...", 0, totalCommands))

        executionJob = serviceScope.launch {
            try {
                executeCommands(commands)
            } catch (e: CancellationException) {
                Log.i(TAG, "실행 취소됨")
            } catch (e: Exception) {
                Log.e(TAG, "실행 오류", e)
                updateStatus("⚠ 오류: ${e.message}", currentCommandIndex, totalCommands)
            } finally {
                onExecutionFinished()
            }
        }
    }

    private suspend fun executeCommands(commands: List<ScriptCommand>) {
        val accessibilityService = AutoTouchAccessibilityService.instance
            ?: throw IllegalStateException("접근성 서비스를 사용할 수 없음")

        // 반복 스택 (중첩 반복 지원)
        data class RepeatFrame(
            val startIndex: Int,
            val totalCount: Int,  // 0 = 무한
            var currentIteration: Int = 0
        )
        val repeatStack = ArrayDeque<RepeatFrame>()

        var i = 0
        while (i < commands.size) {
            // 일시정지 체크
            while (isPaused && isRunning) {
                delay(200)
            }
            if (!isRunning) break

            val cmd = commands[i]
            currentCommandIndex = i + 1

            when (cmd) {
                is ScriptCommand.RepeatStart -> {
                    repeatStack.addLast(RepeatFrame(
                        startIndex = i + 1,
                        totalCount = cmd.count
                    ))
                    updateStatus(
                        "🔁 반복 시작 (${if (cmd.count == 0) "∞" else cmd.count}회)",
                        currentCommandIndex, totalCommands
                    )
                    i++
                    continue
                }

                is ScriptCommand.RepeatEnd -> {
                    if (repeatStack.isNotEmpty()) {
                        val frame = repeatStack.last()
                        frame.currentIteration++

                        val shouldRepeat = frame.totalCount == 0 ||
                                frame.currentIteration < frame.totalCount

                        if (shouldRepeat && isRunning) {
                            i = frame.startIndex
                            updateStatus(
                                "🔁 반복 ${frame.currentIteration + 1}/" +
                                        "${if (frame.totalCount == 0) "∞" else frame.totalCount}",
                                currentCommandIndex, totalCommands
                            )
                            continue
                        } else {
                            repeatStack.removeLast()
                        }
                    }
                    i++
                    continue
                }

                is ScriptCommand.ShowToast -> {
                    withContext(Dispatchers.Main) {
                        showToast(cmd.message)
                    }
                    i++
                    continue
                }

                else -> {
                    // 일반 명령 실행
                    val statusEmoji = when (cmd) {
                        is ScriptCommand.Click, is ScriptCommand.ClickByText,
                        is ScriptCommand.ClickById, is ScriptCommand.ClickByDesc -> "👆"
                        is ScriptCommand.LongClick, is ScriptCommand.LongClickByText -> "👆⏱"
                        is ScriptCommand.Scroll -> "📜"
                        is ScriptCommand.Swipe -> "👉"
                        is ScriptCommand.InputText -> "⌨️"
                        is ScriptCommand.Wait, is ScriptCommand.WaitForText -> "⏳"
                        is ScriptCommand.Home, is ScriptCommand.Back,
                        is ScriptCommand.Recents -> "🔘"
                        is ScriptCommand.LaunchApp -> "🚀"
                        is ScriptCommand.Pinch -> "🔍"
                        else -> "▶️"
                    }

                    updateStatus(
                        "$statusEmoji $currentCommandIndex/$totalCommands: ${cmd.description}",
                        currentCommandIndex, totalCommands
                    )

                    val success = accessibilityService.executeCommand(cmd)
                    if (!success) {
                        Log.w(TAG, "명령 실행 실패 (Line ${cmd.lineNumber}): ${cmd.description}")
                    }

                    // 명령 간 짧은 딜레이 (안정성)
                    delay(100)
                }
            }
            i++
        }

        updateStatus("✅ 스크립트 실행 완료", totalCommands, totalCommands)
    }

    private fun togglePause() {
        if (!isRunning) return
        isPaused = !isPaused
        val status = if (isPaused) "⏸ 일시정지" else "▶ 재개"
        updateStatus(status, currentCommandIndex, totalCommands)
    }

    private fun stopExecution() {
        isRunning = false
        isPaused = false
        executionJob?.cancel()
        executionJob = null
        updateStatus("⏹ 중지됨", currentCommandIndex, totalCommands)

        // MainActivity로 복귀
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("from_stop", true)
        }
        startActivity(mainIntent)

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun onExecutionFinished() {
        isRunning = false
        isPaused = false
        updateNotification("✅ 완료", totalCommands, totalCommands)
    }

    // ═══════════════════════════════════════════════
    //  알림 관리
    // ═══════════════════════════════════════════════

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AutoTouch 스크립트 실행",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "스크립트 실행 상태를 표시합니다"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(
        status: String,
        current: Int,
        total: Int
    ): Notification {
        // 메인 액티비티 열기
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("AutoTouch")
            .setContentText(status)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        // 프로그레스 바
        if (total > 0) {
            builder.setProgress(total, current, false)
            builder.setSubText("$current / $total")
        }

        // 액션 버튼 추가
        if (isRunning) {
            // 일시정지 / 재개 버튼
            val pauseIntent = PendingIntent.getBroadcast(
                this, 1,
                Intent(ACTION_PAUSE).apply {
                    setClass(this@ScriptExecutorService, NotificationActionReceiver::class.java)
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(
                R.drawable.ic_pause,
                if (isPaused) "재개" else "일시정지",
                pauseIntent
            )

            // 중지 버튼
            val stopIntent = PendingIntent.getBroadcast(
                this, 2,
                Intent(ACTION_STOP).apply {
                    setClass(this@ScriptExecutorService, NotificationActionReceiver::class.java)
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(R.drawable.ic_stop, "중지", stopIntent)
        }

        return builder.build()
    }

    private fun updateStatus(status: String, current: Int, total: Int) {
        currentStatus = status
        updateNotification(status, current, total)
        onStatusChanged?.invoke(status, current, total)
    }

    private fun updateNotification(status: String, current: Int, total: Int) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(status, current, total))
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        isPaused = false
        executionJob?.cancel()
        serviceScope.cancel()
    }
}
