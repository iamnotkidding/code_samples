package com.autotouch.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.autotouch.app.service.ScriptExecutorService

/**
 * 알림창의 버튼 액션을 처리하는 BroadcastReceiver
 *
 * - 시작: 스크립트 실행 시작
 * - 일시정지/재개: 실행 토글
 * - 중지: 실행 중단 및 MainActivity 복귀
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val serviceIntent = Intent(context, ScriptExecutorService::class.java)

        when (intent.action) {
            ScriptExecutorService.ACTION_START -> {
                serviceIntent.action = ScriptExecutorService.ACTION_START
                // 필요시 스크립트 경로 전달
                intent.getStringExtra(ScriptExecutorService.EXTRA_SCRIPT_PATH)?.let {
                    serviceIntent.putExtra(ScriptExecutorService.EXTRA_SCRIPT_PATH, it)
                }
                intent.getStringExtra(ScriptExecutorService.EXTRA_SCRIPT_CONTENT)?.let {
                    serviceIntent.putExtra(ScriptExecutorService.EXTRA_SCRIPT_CONTENT, it)
                }
                context.startForegroundService(serviceIntent)
            }

            ScriptExecutorService.ACTION_PAUSE -> {
                serviceIntent.action = ScriptExecutorService.ACTION_PAUSE
                context.startService(serviceIntent)
            }

            ScriptExecutorService.ACTION_STOP -> {
                serviceIntent.action = ScriptExecutorService.ACTION_STOP
                context.startService(serviceIntent)
            }
        }
    }
}
