package com.autotouch.app.parser

/**
 * 스크립트에서 파싱된 개별 명령을 나타내는 sealed class
 */
sealed class ScriptCommand {
    abstract val lineNumber: Int
    abstract val description: String

    /** 화면의 특정 좌표를 클릭 */
    data class Click(
        val x: Int,
        val y: Int,
        override val lineNumber: Int = 0,
        override val description: String = "Click ($x, $y)"
    ) : ScriptCommand()

    /** 텍스트로 요소를 찾아 클릭 */
    data class ClickByText(
        val text: String,
        val index: Int = 0,  // 동일 텍스트가 여러 개일 때 인덱스
        override val lineNumber: Int = 0,
        override val description: String = "Click text \"$text\""
    ) : ScriptCommand()

    /** Resource ID로 요소를 찾아 클릭 */
    data class ClickById(
        val resourceId: String,
        override val lineNumber: Int = 0,
        override val description: String = "Click id \"$resourceId\""
    ) : ScriptCommand()

    /** Content Description으로 요소를 찾아 클릭 */
    data class ClickByDesc(
        val contentDesc: String,
        override val lineNumber: Int = 0,
        override val description: String = "Click desc \"$contentDesc\""
    ) : ScriptCommand()

    /** 길게 누르기 (좌표) */
    data class LongClick(
        val x: Int,
        val y: Int,
        val durationMs: Long = 1000,
        override val lineNumber: Int = 0,
        override val description: String = "LongClick ($x, $y) ${durationMs}ms"
    ) : ScriptCommand()

    /** 길게 누르기 (텍스트) */
    data class LongClickByText(
        val text: String,
        val durationMs: Long = 1000,
        override val lineNumber: Int = 0,
        override val description: String = "LongClick text \"$text\" ${durationMs}ms"
    ) : ScriptCommand()

    /** 스크롤 (방향 지정) */
    data class Scroll(
        val direction: ScrollDirection,
        val amount: Int = 1,  // 스크롤 횟수
        override val lineNumber: Int = 0,
        override val description: String = "Scroll $direction x$amount"
    ) : ScriptCommand()

    /** 스와이프 (좌표 지정) */
    data class Swipe(
        val startX: Int,
        val startY: Int,
        val endX: Int,
        val endY: Int,
        val durationMs: Long = 300,
        override val lineNumber: Int = 0,
        override val description: String = "Swipe ($startX,$startY)->($endX,$endY) ${durationMs}ms"
    ) : ScriptCommand()

    /** 텍스트 입력 */
    data class InputText(
        val text: String,
        override val lineNumber: Int = 0,
        override val description: String = "Input \"$text\""
    ) : ScriptCommand()

    /** 대기 (밀리초) */
    data class Wait(
        val durationMs: Long,
        override val lineNumber: Int = 0,
        override val description: String = "Wait ${durationMs}ms"
    ) : ScriptCommand()

    /** 특정 텍스트가 화면에 나타날 때까지 대기 */
    data class WaitForText(
        val text: String,
        val timeoutMs: Long = 10000,
        override val lineNumber: Int = 0,
        override val description: String = "WaitFor \"$text\" timeout=${timeoutMs}ms"
    ) : ScriptCommand()

    /** 홈 버튼 */
    data class Home(
        override val lineNumber: Int = 0,
        override val description: String = "Home"
    ) : ScriptCommand()

    /** 뒤로가기 버튼 */
    data class Back(
        override val lineNumber: Int = 0,
        override val description: String = "Back"
    ) : ScriptCommand()

    /** 최근 앱 목록 */
    data class Recents(
        override val lineNumber: Int = 0,
        override val description: String = "Recents"
    ) : ScriptCommand()

    /** 앱 실행 (패키지명) */
    data class LaunchApp(
        val packageName: String,
        override val lineNumber: Int = 0,
        override val description: String = "Launch \"$packageName\""
    ) : ScriptCommand()

    /** 반복 시작 */
    data class RepeatStart(
        val count: Int,  // 0이면 무한 반복
        override val lineNumber: Int = 0,
        override val description: String = "Repeat ${if (count == 0) "∞" else count} times"
    ) : ScriptCommand()

    /** 반복 종료 */
    data class RepeatEnd(
        override val lineNumber: Int = 0,
        override val description: String = "End Repeat"
    ) : ScriptCommand()

    /** 스크린샷 저장 */
    data class Screenshot(
        val filename: String = "",
        override val lineNumber: Int = 0,
        override val description: String = "Screenshot"
    ) : ScriptCommand()

    /** 알림 표시 */
    data class ShowToast(
        val message: String,
        override val lineNumber: Int = 0,
        override val description: String = "Toast \"$message\""
    ) : ScriptCommand()

    /** 핀치 줌 */
    data class Pinch(
        val centerX: Int,
        val centerY: Int,
        val zoomIn: Boolean,
        val scale: Float = 2.0f,
        override val lineNumber: Int = 0,
        override val description: String = "Pinch ${if (zoomIn) "In" else "Out"} ($centerX,$centerY)"
    ) : ScriptCommand()

    /** 주석 / 코멘트 (실행되지 않음) */
    data class Comment(
        val text: String,
        override val lineNumber: Int = 0,
        override val description: String = "// $text"
    ) : ScriptCommand()
}

enum class ScrollDirection {
    UP, DOWN, LEFT, RIGHT
}
