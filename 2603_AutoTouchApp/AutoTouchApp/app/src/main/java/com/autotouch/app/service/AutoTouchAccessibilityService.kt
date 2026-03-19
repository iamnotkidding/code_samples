package com.autotouch.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.autotouch.app.parser.ScriptCommand
import com.autotouch.app.parser.ScrollDirection
import kotlinx.coroutines.*
import kotlin.coroutines.resume

/**
 * 접근성 서비스: UI Automation의 핵심 엔진
 *
 * - 다른 앱의 UI 요소를 탐색/클릭/스크롤
 * - 제스처(스와이프, 핀치) 실행
 * - 텍스트 입력 처리
 */
class AutoTouchAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AutoTouchA11y"
        var instance: AutoTouchAccessibilityService? = null
            private set
        var isConnected = false
            private set
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isConnected = true
        Log.i(TAG, "접근성 서비스 연결됨")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 이벤트 모니터링 (필요시 WaitForText 등에서 활용)
    }

    override fun onInterrupt() {
        Log.w(TAG, "접근성 서비스 중단됨")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        isConnected = false
        serviceScope.cancel()
        Log.i(TAG, "접근성 서비스 종료됨")
    }

    // ═══════════════════════════════════════════════
    //  명령 실행
    // ═══════════════════════════════════════════════

    suspend fun executeCommand(command: ScriptCommand): Boolean {
        return when (command) {
            is ScriptCommand.Click -> performClick(command.x.toFloat(), command.y.toFloat())
            is ScriptCommand.ClickByText -> performClickByText(command.text, command.index)
            is ScriptCommand.ClickById -> performClickById(command.resourceId)
            is ScriptCommand.ClickByDesc -> performClickByDesc(command.contentDesc)
            is ScriptCommand.LongClick -> performLongClick(
                command.x.toFloat(), command.y.toFloat(), command.durationMs
            )
            is ScriptCommand.LongClickByText -> performLongClickByText(
                command.text, command.durationMs
            )
            is ScriptCommand.Scroll -> performScroll(command.direction, command.amount)
            is ScriptCommand.Swipe -> performSwipe(
                command.startX.toFloat(), command.startY.toFloat(),
                command.endX.toFloat(), command.endY.toFloat(),
                command.durationMs
            )
            is ScriptCommand.InputText -> performInput(command.text)
            is ScriptCommand.Wait -> {
                delay(command.durationMs)
                true
            }
            is ScriptCommand.WaitForText -> performWaitForText(command.text, command.timeoutMs)
            is ScriptCommand.Home -> {
                performGlobalAction(GLOBAL_ACTION_HOME)
                delay(500)
                true
            }
            is ScriptCommand.Back -> {
                performGlobalAction(GLOBAL_ACTION_BACK)
                delay(300)
                true
            }
            is ScriptCommand.Recents -> {
                performGlobalAction(GLOBAL_ACTION_RECENTS)
                delay(500)
                true
            }
            is ScriptCommand.LaunchApp -> performLaunchApp(command.packageName)
            is ScriptCommand.Pinch -> performPinch(
                command.centerX.toFloat(), command.centerY.toFloat(),
                command.zoomIn, command.scale
            )
            is ScriptCommand.Comment -> true  // 주석은 건너뜀
            is ScriptCommand.ShowToast -> true  // Service에서 별도 처리
            is ScriptCommand.Screenshot -> true  // Service에서 별도 처리
            is ScriptCommand.RepeatStart, is ScriptCommand.RepeatEnd -> true  // 흐름 제어
        }
    }

    // ═══════════════════════════════════════════════
    //  좌표 기반 제스처
    // ═══════════════════════════════════════════════

    private suspend fun performClick(x: Float, y: Float): Boolean {
        return dispatchGesture(buildClickGesture(x, y, 1, 50))
    }

    private suspend fun performLongClick(x: Float, y: Float, durationMs: Long): Boolean {
        return dispatchGesture(buildClickGesture(x, y, 1, durationMs))
    }

    private suspend fun performSwipe(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        durationMs: Long
    ): Boolean {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return dispatchGesture(gesture)
    }

    private fun buildClickGesture(
        x: Float, y: Float, startDelay: Long, duration: Long
    ): GestureDescription {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x, y)
        }
        return GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, startDelay, duration))
            .build()
    }

    // ═══════════════════════════════════════════════
    //  노드 탐색 기반 동작
    // ═══════════════════════════════════════════════

    private suspend fun performClickByText(text: String, index: Int): Boolean {
        val node = findNodeByText(text, index)
        if (node != null) {
            return clickNode(node)
        }
        Log.w(TAG, "텍스트 \"$text\" 노드를 찾을 수 없음")
        return false
    }

    private suspend fun performClickById(resourceId: String): Boolean {
        val node = findNodeById(resourceId)
        if (node != null) {
            return clickNode(node)
        }
        Log.w(TAG, "ID \"$resourceId\" 노드를 찾을 수 없음")
        return false
    }

    private suspend fun performClickByDesc(contentDesc: String): Boolean {
        val node = findNodeByDesc(contentDesc)
        if (node != null) {
            return clickNode(node)
        }
        Log.w(TAG, "설명 \"$contentDesc\" 노드를 찾을 수 없음")
        return false
    }

    private suspend fun performLongClickByText(text: String, durationMs: Long): Boolean {
        val node = findNodeByText(text, 0)
        if (node != null) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            node.recycle()
            return performLongClick(
                rect.centerX().toFloat(), rect.centerY().toFloat(), durationMs
            )
        }
        return false
    }

    private suspend fun clickNode(node: AccessibilityNodeInfo): Boolean {
        // 먼저 노드 자체의 클릭 액션 시도
        if (node.isClickable) {
            val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            node.recycle()
            delay(200)
            return result
        }
        // 클릭 불가능하면 좌표 기반 클릭
        val rect = Rect()
        node.getBoundsInScreen(rect)
        node.recycle()
        return performClick(rect.centerX().toFloat(), rect.centerY().toFloat())
    }

    // ═══════════════════════════════════════════════
    //  스크롤
    // ═══════════════════════════════════════════════

    private suspend fun performScroll(direction: ScrollDirection, amount: Int): Boolean {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val centerX = width / 2f
        val centerY = height / 2f
        val scrollDistance = height / 3f
        val horizontalDistance = width / 3f

        var success = true
        repeat(amount) {
            val result = when (direction) {
                ScrollDirection.DOWN -> performSwipe(
                    centerX, centerY + scrollDistance / 2,
                    centerX, centerY - scrollDistance / 2,
                    300
                )
                ScrollDirection.UP -> performSwipe(
                    centerX, centerY - scrollDistance / 2,
                    centerX, centerY + scrollDistance / 2,
                    300
                )
                ScrollDirection.LEFT -> performSwipe(
                    centerX - horizontalDistance / 2, centerY,
                    centerX + horizontalDistance / 2, centerY,
                    300
                )
                ScrollDirection.RIGHT -> performSwipe(
                    centerX + horizontalDistance / 2, centerY,
                    centerX - horizontalDistance / 2, centerY,
                    300
                )
            }
            if (!result) success = false
            delay(200)
        }
        return success
    }

    // ═══════════════════════════════════════════════
    //  텍스트 입력
    // ═══════════════════════════════════════════════

    private suspend fun performInput(text: String): Boolean {
        val focusedNode = findFocusedInput()
        if (focusedNode != null) {
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text
                )
            }
            val result = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            focusedNode.recycle()
            delay(200)
            return result
        }
        Log.w(TAG, "포커스된 입력 필드를 찾을 수 없음")
        return false
    }

    // ═══════════════════════════════════════════════
    //  텍스트 대기
    // ═══════════════════════════════════════════════

    private suspend fun performWaitForText(text: String, timeoutMs: Long): Boolean {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (isTextOnScreen(text)) return true
            delay(500)
        }
        Log.w(TAG, "텍스트 \"$text\" 대기 시간 초과 (${timeoutMs}ms)")
        return false
    }

    private fun isTextOnScreen(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(text)
        val found = nodes?.isNotEmpty() == true
        nodes?.forEach { it.recycle() }
        root.recycle()
        return found
    }

    // ═══════════════════════════════════════════════
    //  앱 실행
    // ═══════════════════════════════════════════════

    private suspend fun performLaunchApp(packageName: String): Boolean {
        return try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                startActivity(intent)
                delay(1500)
                true
            } else {
                Log.w(TAG, "앱을 찾을 수 없음: $packageName")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "앱 실행 실패: $packageName", e)
            false
        }
    }

    // ═══════════════════════════════════════════════
    //  핀치 제스처
    // ═══════════════════════════════════════════════

    private suspend fun performPinch(
        centerX: Float, centerY: Float, zoomIn: Boolean, scale: Float
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false

        val distance = 100f * scale
        val halfDist = distance / 2f

        val path1 = Path()
        val path2 = Path()

        if (zoomIn) {
            // 줌인: 두 손가락이 중심에서 바깥으로
            path1.moveTo(centerX, centerY)
            path1.lineTo(centerX - halfDist, centerY - halfDist)
            path2.moveTo(centerX, centerY)
            path2.lineTo(centerX + halfDist, centerY + halfDist)
        } else {
            // 줌아웃: 두 손가락이 바깥에서 중심으로
            path1.moveTo(centerX - halfDist, centerY - halfDist)
            path1.lineTo(centerX, centerY)
            path2.moveTo(centerX + halfDist, centerY + halfDist)
            path2.lineTo(centerX, centerY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path1, 0, 400))
            .addStroke(GestureDescription.StrokeDescription(path2, 0, 400))
            .build()

        return dispatchGesture(gesture)
    }

    // ═══════════════════════════════════════════════
    //  노드 검색 유틸리티
    // ═══════════════════════════════════════════════

    private fun findNodeByText(text: String, index: Int): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val nodes = root.findAccessibilityNodeInfosByText(text)
        val node = nodes?.getOrNull(index)
        // 반환하지 않는 노드는 recycle
        nodes?.forEachIndexed { i, n -> if (i != index) n.recycle() }
        root.recycle()
        return node
    }

    private fun findNodeById(resourceId: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val nodes = root.findAccessibilityNodeInfosByViewId(resourceId)
        val node = nodes?.firstOrNull()
        nodes?.drop(1)?.forEach { it.recycle() }
        root.recycle()
        return node
    }

    private fun findNodeByDesc(contentDesc: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return findNodeRecursive(root) { node ->
            node.contentDescription?.toString()?.contains(contentDesc, ignoreCase = true) == true
        }
    }

    private fun findFocusedInput(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return findNodeRecursive(root) { node ->
            node.isFocused && node.isEditable
        }
    }

    private fun findNodeRecursive(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (predicate(root)) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val result = findNodeRecursive(child, predicate)
            if (result != null) return result
            child.recycle()
        }
        return null
    }

    // ═══════════════════════════════════════════════
    //  제스처 디스패치 (coroutine 연동)
    // ═══════════════════════════════════════════════

    private suspend fun dispatchGesture(gesture: GestureDescription): Boolean {
        return suspendCancellableCoroutine { continuation ->
            val callback = object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(true)
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(false)
                }
            }
            val dispatched = dispatchGesture(gesture, callback, null)
            if (!dispatched) {
                if (continuation.isActive) continuation.resume(false)
            }
        }
    }
}
