package com.autotouch.app.ui

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.autotouch.app.R
import com.autotouch.app.parser.ScriptParser
import com.autotouch.app.service.AutoTouchAccessibilityService
import com.autotouch.app.service.ScriptExecutorService
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 메인 액티비티
 *
 * 기능:
 * - 접근성 서비스 상태 표시 & 활성화 유도
 * - TXT 스크립트 파일 로드
 * - 스크립트 직접 편집
 * - 실행/중지 제어
 * - 실행 현황 표시
 * - 샘플 스크립트 제공
 */
class MainActivity : AppCompatActivity() {

    // ── UI 요소 ──
    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var btnEnableAccessibility: Button
    private lateinit var btnLoadScript: Button
    private lateinit var btnSampleScript: Button
    private lateinit var etScriptEditor: EditText
    private lateinit var tvParseStatus: TextView
    private lateinit var btnValidate: Button
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvExecutionStatus: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgress: TextView
    private lateinit var scrollView: ScrollView

    // ── 파일 선택 ──
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { loadScriptFromUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupListeners()
        requestPermissions()
        updateAccessibilityStatus()

        // 서비스에서 복귀 시
        if (intent?.getBooleanExtra("from_stop", false) == true) {
            tvExecutionStatus.text = "⏹ 스크립트 실행이 중지되었습니다"
        }
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityStatus()
        updateExecutionUI()

        // 실행 상태 리스너 등록
        ScriptExecutorService.onStatusChanged = { status, current, total ->
            runOnUiThread {
                tvExecutionStatus.text = status
                if (total > 0) {
                    progressBar.max = total
                    progressBar.progress = current
                    tvProgress.text = "$current / $total"
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        ScriptExecutorService.onStatusChanged = null
    }

    // ═══════════════════════════════════════════════
    //  초기화
    // ═══════════════════════════════════════════════

    private fun initViews() {
        tvAccessibilityStatus = findViewById(R.id.tvAccessibilityStatus)
        btnEnableAccessibility = findViewById(R.id.btnEnableAccessibility)
        btnLoadScript = findViewById(R.id.btnLoadScript)
        btnSampleScript = findViewById(R.id.btnSampleScript)
        etScriptEditor = findViewById(R.id.etScriptEditor)
        tvParseStatus = findViewById(R.id.tvParseStatus)
        btnValidate = findViewById(R.id.btnValidate)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        tvExecutionStatus = findViewById(R.id.tvExecutionStatus)
        progressBar = findViewById(R.id.progressBar)
        tvProgress = findViewById(R.id.tvProgress)
        scrollView = findViewById(R.id.scrollView)
    }

    private fun setupListeners() {
        btnEnableAccessibility.setOnClickListener {
            openAccessibilitySettings()
        }

        btnLoadScript.setOnClickListener {
            filePickerLauncher.launch("text/plain")
        }

        btnSampleScript.setOnClickListener {
            showSampleScripts()
        }

        btnValidate.setOnClickListener {
            validateScript()
        }

        btnStart.setOnClickListener {
            startExecution()
        }

        btnStop.setOnClickListener {
            stopExecution()
        }
    }

    // ═══════════════════════════════════════════════
    //  접근성 서비스 관리
    // ═══════════════════════════════════════════════

    private fun updateAccessibilityStatus() {
        val enabled = isAccessibilityServiceEnabled()
        if (enabled) {
            tvAccessibilityStatus.text = "✅ 접근성 서비스 활성화됨"
            tvAccessibilityStatus.setTextColor(
                ContextCompat.getColor(this, R.color.status_active)
            )
            btnEnableAccessibility.visibility = android.view.View.GONE
        } else {
            tvAccessibilityStatus.text = "⚠ 접근성 서비스를 활성화해야 합니다"
            tvAccessibilityStatus.setTextColor(
                ContextCompat.getColor(this, R.color.status_warning)
            )
            btnEnableAccessibility.visibility = android.view.View.VISIBLE
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_GENERIC
        )
        return enabledServices.any {
            it.resolveInfo.serviceInfo.packageName == packageName
        }
    }

    private fun openAccessibilitySettings() {
        AlertDialog.Builder(this)
            .setTitle("접근성 서비스 활성화")
            .setMessage(
                "AutoTouch가 다른 앱의 화면을 제어하려면 접근성 서비스를 활성화해야 합니다.\n\n" +
                "설정 → 접근성 → AutoTouch → 사용 으로 이동합니다."
            )
            .setPositiveButton("설정으로 이동") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ═══════════════════════════════════════════════
    //  스크립트 로드 & 검증
    // ═══════════════════════════════════════════════

    private fun loadScriptFromUri(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri) ?: return
            val reader = BufferedReader(InputStreamReader(inputStream))
            val content = reader.readText()
            reader.close()
            etScriptEditor.setText(content)
            validateScript()
        } catch (e: Exception) {
            Toast.makeText(this, "파일 로드 실패: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun validateScript() {
        val script = etScriptEditor.text.toString().trim()
        if (script.isEmpty()) {
            tvParseStatus.text = "스크립트를 입력하세요"
            tvParseStatus.setTextColor(
                ContextCompat.getColor(this, R.color.text_secondary)
            )
            return
        }

        val parser = ScriptParser()
        val result = parser.parseString(script)

        val commandCount = result.commands.count {
            it !is com.autotouch.app.parser.ScriptCommand.Comment
        }

        if (result.errors.isEmpty()) {
            tvParseStatus.text = "✅ 파싱 성공: ${commandCount}개 명령어"
            tvParseStatus.setTextColor(
                ContextCompat.getColor(this, R.color.status_active)
            )
        } else {
            val errorText = buildString {
                append("⚠ ${result.errors.size}개 오류 발견\n")
                result.errors.take(5).forEach { error ->
                    append("  Line ${error.lineNumber}: ${error.message}\n")
                }
                if (result.errors.size > 5) {
                    append("  ... 외 ${result.errors.size - 5}개")
                }
            }
            tvParseStatus.text = errorText
            tvParseStatus.setTextColor(
                ContextCompat.getColor(this, R.color.status_error)
            )
        }
    }

    // ═══════════════════════════════════════════════
    //  실행 제어
    // ═══════════════════════════════════════════════

    private fun startExecution() {
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "접근성 서비스를 먼저 활성화하세요", Toast.LENGTH_SHORT).show()
            openAccessibilitySettings()
            return
        }

        val script = etScriptEditor.text.toString().trim()
        if (script.isEmpty()) {
            Toast.makeText(this, "실행할 스크립트가 없습니다", Toast.LENGTH_SHORT).show()
            return
        }

        // 포그라운드 서비스 시작
        val intent = Intent(this, ScriptExecutorService::class.java).apply {
            action = ScriptExecutorService.ACTION_START
            putExtra(ScriptExecutorService.EXTRA_SCRIPT_CONTENT, script)
        }
        startForegroundService(intent)

        tvExecutionStatus.text = "▶ 실행 시작..."
        btnStart.isEnabled = false
        btnStop.isEnabled = true

        Toast.makeText(this, "스크립트 실행 시작 - 알림창에서 제어 가능", Toast.LENGTH_SHORT).show()
    }

    private fun stopExecution() {
        val intent = Intent(this, ScriptExecutorService::class.java).apply {
            action = ScriptExecutorService.ACTION_STOP
        }
        startService(intent)

        btnStart.isEnabled = true
        btnStop.isEnabled = false
        tvExecutionStatus.text = "⏹ 중지됨"
    }

    private fun updateExecutionUI() {
        if (ScriptExecutorService.isRunning) {
            btnStart.isEnabled = false
            btnStop.isEnabled = true
            tvExecutionStatus.text = ScriptExecutorService.currentStatus
        } else {
            btnStart.isEnabled = true
            btnStop.isEnabled = false
        }
    }

    // ═══════════════════════════════════════════════
    //  샘플 스크립트
    // ═══════════════════════════════════════════════

    private fun showSampleScripts() {
        val samples = arrayOf(
            "기본 클릭 & 스크롤",
            "앱 실행 & 메뉴 탐색",
            "반복 자동화",
            "텍스트 입력 자동화"
        )

        AlertDialog.Builder(this)
            .setTitle("샘플 스크립트 선택")
            .setItems(samples) { _, which ->
                etScriptEditor.setText(getSampleScript(which))
                validateScript()
            }
            .show()
    }

    private fun getSampleScript(index: Int): String {
        return when (index) {
            0 -> """
# 기본 클릭 & 스크롤 예제
# 화면 중앙 클릭 후 아래로 스크롤

WAIT 1000
CLICK 540 960
WAIT 500
SCROLL DOWN 3
WAIT 1000
CLICK_TEXT "더보기"
WAIT 500
BACK
            """.trimIndent()

            1 -> """
# 앱 실행 & 메뉴 탐색 예제
# 설정 앱을 열고 Wi-Fi 메뉴 진입

LAUNCH "com.android.settings"
WAIT 2000
CLICK_TEXT "네트워크 및 인터넷"
WAIT 1000
CLICK_TEXT "Wi-Fi"
WAIT 2000
BACK
BACK
HOME
            """.trimIndent()

            2 -> """
# 반복 자동화 예제
# 5회 반복하며 스크롤 & 클릭

WAIT 1000

REPEAT 5
  SCROLL DOWN
  WAIT 500
  CLICK 540 800
  WAIT 1000
  BACK
  WAIT 500
END_REPEAT

TOAST "반복 완료!"
            """.trimIndent()

            3 -> """
# 텍스트 입력 자동화 예제
# 검색창에 텍스트 입력

WAIT 1000
CLICK_ID "com.google.android.googlequicksearchbox:id/search_edit_frame"
WAIT 500
INPUT "안드로이드 자동화"
WAIT 1000
BACK
HOME
            """.trimIndent()

            else -> ""
        }
    }

    // ═══════════════════════════════════════════════
    //  권한 요청
    // ═══════════════════════════════════════════════

    private fun requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    100
                )
            }
        }
    }
}
