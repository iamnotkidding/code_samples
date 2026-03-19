package com.autotouch.app.parser

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/**
 * TXT 스크립트 파서
 *
 * 스크립트 문법:
 * ─────────────────────────────────────────────────
 * # 주석 (코멘트)
 * // 주석 (코멘트)
 *
 * CLICK x y                       좌표 클릭
 * CLICK_TEXT "메뉴"                텍스트로 클릭
 * CLICK_TEXT "메뉴" 1              동일 텍스트 중 2번째(0-based)
 * CLICK_ID "com.app:id/btn"       리소스 ID로 클릭
 * CLICK_DESC "설정 버튼"           ContentDescription으로 클릭
 *
 * LONG_CLICK x y                  길게 누르기 (기본 1초)
 * LONG_CLICK x y 2000             길게 누르기 (2초)
 * LONG_CLICK_TEXT "항목" 1500      텍스트로 길게 누르기
 *
 * SCROLL UP                       위로 스크롤
 * SCROLL DOWN 3                   아래로 3회 스크롤
 * SCROLL LEFT                     좌로 스크롤
 * SCROLL RIGHT                    우로 스크롤
 *
 * SWIPE x1 y1 x2 y2              스와이프 (기본 300ms)
 * SWIPE x1 y1 x2 y2 500          스와이프 (500ms)
 *
 * INPUT "텍스트 입력"              텍스트 입력
 *
 * WAIT 2000                       2초 대기
 * WAIT_FOR "완료" 5000             "완료" 텍스트 5초 대기
 *
 * HOME                            홈 버튼
 * BACK                            뒤로가기
 * RECENTS                         최근 앱
 *
 * LAUNCH "com.example.app"        앱 실행
 *
 * REPEAT 5                        5회 반복 시작
 * REPEAT 0                        무한 반복 시작
 * END_REPEAT                      반복 종료
 *
 * TOAST "메시지"                   알림 토스트
 *
 * PINCH_IN x y                    핀치 줌인
 * PINCH_OUT x y 3.0               핀치 줌아웃 (scale 3.0)
 * ─────────────────────────────────────────────────
 */
class ScriptParser {

    data class ParseResult(
        val commands: List<ScriptCommand>,
        val errors: List<ParseError>
    )

    data class ParseError(
        val lineNumber: Int,
        val line: String,
        val message: String
    )

    fun parse(input: InputStream): ParseResult {
        val commands = mutableListOf<ScriptCommand>()
        val errors = mutableListOf<ParseError>()
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))

        reader.useLines { lines ->
            lines.forEachIndexed { index, rawLine ->
                val lineNumber = index + 1
                val line = rawLine.trim()

                // 빈 줄이나 주석은 건너뜀
                if (line.isEmpty()) return@forEachIndexed
                if (line.startsWith("#") || line.startsWith("//")) {
                    commands.add(ScriptCommand.Comment(
                        text = line.removePrefix("#").removePrefix("//").trim(),
                        lineNumber = lineNumber
                    ))
                    return@forEachIndexed
                }

                try {
                    val cmd = parseLine(line, lineNumber)
                    if (cmd != null) {
                        commands.add(cmd)
                    } else {
                        errors.add(ParseError(lineNumber, line, "알 수 없는 명령어"))
                    }
                } catch (e: Exception) {
                    errors.add(ParseError(lineNumber, line, e.message ?: "파싱 오류"))
                }
            }
        }

        return ParseResult(commands, errors)
    }

    fun parseString(script: String): ParseResult {
        return parse(script.byteInputStream(Charsets.UTF_8))
    }

    private fun parseLine(line: String, lineNumber: Int): ScriptCommand? {
        val tokens = tokenize(line)
        if (tokens.isEmpty()) return null

        val command = tokens[0].uppercase()
        val args = tokens.drop(1)

        return when (command) {
            "CLICK" -> parseClick(args, lineNumber)
            "CLICK_TEXT" -> parseClickByText(args, lineNumber)
            "CLICK_ID" -> parseClickById(args, lineNumber)
            "CLICK_DESC" -> parseClickByDesc(args, lineNumber)
            "LONG_CLICK" -> parseLongClick(args, lineNumber)
            "LONG_CLICK_TEXT" -> parseLongClickByText(args, lineNumber)
            "SCROLL" -> parseScroll(args, lineNumber)
            "SWIPE" -> parseSwipe(args, lineNumber)
            "INPUT" -> parseInput(args, lineNumber)
            "WAIT" -> parseWait(args, lineNumber)
            "WAIT_FOR" -> parseWaitFor(args, lineNumber)
            "HOME" -> ScriptCommand.Home(lineNumber)
            "BACK" -> ScriptCommand.Back(lineNumber)
            "RECENTS" -> ScriptCommand.Recents(lineNumber)
            "LAUNCH" -> parseLaunch(args, lineNumber)
            "REPEAT" -> parseRepeat(args, lineNumber)
            "END_REPEAT" -> ScriptCommand.RepeatEnd(lineNumber)
            "TOAST" -> parseToast(args, lineNumber)
            "PINCH_IN" -> parsePinch(args, true, lineNumber)
            "PINCH_OUT" -> parsePinch(args, false, lineNumber)
            "SCREENSHOT" -> ScriptCommand.Screenshot(
                filename = args.firstOrNull()?.removeSurrounding("\"") ?: "",
                lineNumber = lineNumber
            )
            else -> null
        }
    }

    /**
     * 토큰 분리: 따옴표 안의 문자열은 하나의 토큰으로 처리
     * 예: CLICK_TEXT "Hello World" 2  →  ["CLICK_TEXT", "Hello World", "2"]
     */
    private fun tokenize(line: String): List<String> {
        val tokens = mutableListOf<String>()
        var i = 0
        val sb = StringBuilder()
        var inQuotes = false

        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' -> {
                    if (inQuotes) {
                        tokens.add(sb.toString())
                        sb.clear()
                        inQuotes = false
                    } else {
                        if (sb.isNotEmpty()) {
                            tokens.add(sb.toString())
                            sb.clear()
                        }
                        inQuotes = true
                    }
                }
                ch.isWhitespace() && !inQuotes -> {
                    if (sb.isNotEmpty()) {
                        tokens.add(sb.toString())
                        sb.clear()
                    }
                }
                else -> sb.append(ch)
            }
            i++
        }
        if (sb.isNotEmpty()) tokens.add(sb.toString())
        return tokens
    }

    // ── 개별 명령어 파서들 ──

    private fun parseClick(args: List<String>, ln: Int): ScriptCommand {
        require(args.size >= 2) { "CLICK은 x y 좌표가 필요합니다" }
        return ScriptCommand.Click(
            x = args[0].toInt(),
            y = args[1].toInt(),
            lineNumber = ln
        )
    }

    private fun parseClickByText(args: List<String>, ln: Int): ScriptCommand {
        require(args.isNotEmpty()) { "CLICK_TEXT는 텍스트가 필요합니다" }
        return ScriptCommand.ClickByText(
            text = args[0],
            index = args.getOrNull(1)?.toIntOrNull() ?: 0,
            lineNumber = ln
        )
    }

    private fun parseClickById(args: List<String>, ln: Int): ScriptCommand {
        require(args.isNotEmpty()) { "CLICK_ID는 리소스 ID가 필요합니다" }
        return ScriptCommand.ClickById(
            resourceId = args[0],
            lineNumber = ln
        )
    }

    private fun parseClickByDesc(args: List<String>, ln: Int): ScriptCommand {
        require(args.isNotEmpty()) { "CLICK_DESC는 설명 텍스트가 필요합니다" }
        return ScriptCommand.ClickByDesc(
            contentDesc = args[0],
            lineNumber = ln
        )
    }

    private fun parseLongClick(args: List<String>, ln: Int): ScriptCommand {
        require(args.size >= 2) { "LONG_CLICK은 x y 좌표가 필요합니다" }
        return ScriptCommand.LongClick(
            x = args[0].toInt(),
            y = args[1].toInt(),
            durationMs = args.getOrNull(2)?.toLongOrNull() ?: 1000,
            lineNumber = ln
        )
    }

    private fun parseLongClickByText(args: List<String>, ln: Int): ScriptCommand {
        require(args.isNotEmpty()) { "LONG_CLICK_TEXT는 텍스트가 필요합니다" }
        return ScriptCommand.LongClickByText(
            text = args[0],
            durationMs = args.getOrNull(1)?.toLongOrNull() ?: 1000,
            lineNumber = ln
        )
    }

    private fun parseScroll(args: List<String>, ln: Int): ScriptCommand {
        require(args.isNotEmpty()) { "SCROLL은 방향이 필요합니다 (UP/DOWN/LEFT/RIGHT)" }
        val direction = when (args[0].uppercase()) {
            "UP" -> ScrollDirection.UP
            "DOWN" -> ScrollDirection.DOWN
            "LEFT" -> ScrollDirection.LEFT
            "RIGHT" -> ScrollDirection.RIGHT
            else -> throw IllegalArgumentException("잘못된 스크롤 방향: ${args[0]}")
        }
        return ScriptCommand.Scroll(
            direction = direction,
            amount = args.getOrNull(1)?.toIntOrNull() ?: 1,
            lineNumber = ln
        )
    }

    private fun parseSwipe(args: List<String>, ln: Int): ScriptCommand {
        require(args.size >= 4) { "SWIPE는 x1 y1 x2 y2 좌표가 필요합니다" }
        return ScriptCommand.Swipe(
            startX = args[0].toInt(),
            startY = args[1].toInt(),
            endX = args[2].toInt(),
            endY = args[3].toInt(),
            durationMs = args.getOrNull(4)?.toLongOrNull() ?: 300,
            lineNumber = ln
        )
    }

    private fun parseInput(args: List<String>, ln: Int): ScriptCommand {
        require(args.isNotEmpty()) { "INPUT은 텍스트가 필요합니다" }
        return ScriptCommand.InputText(text = args[0], lineNumber = ln)
    }

    private fun parseWait(args: List<String>, ln: Int): ScriptCommand {
        require(args.isNotEmpty()) { "WAIT는 시간(ms)이 필요합니다" }
        return ScriptCommand.Wait(durationMs = args[0].toLong(), lineNumber = ln)
    }

    private fun parseWaitFor(args: List<String>, ln: Int): ScriptCommand {
        require(args.isNotEmpty()) { "WAIT_FOR는 텍스트가 필요합니다" }
        return ScriptCommand.WaitForText(
            text = args[0],
            timeoutMs = args.getOrNull(1)?.toLongOrNull() ?: 10000,
            lineNumber = ln
        )
    }

    private fun parseLaunch(args: List<String>, ln: Int): ScriptCommand {
        require(args.isNotEmpty()) { "LAUNCH는 패키지명이 필요합니다" }
        return ScriptCommand.LaunchApp(packageName = args[0], lineNumber = ln)
    }

    private fun parseRepeat(args: List<String>, ln: Int): ScriptCommand {
        require(args.isNotEmpty()) { "REPEAT는 반복 횟수가 필요합니다 (0=무한)" }
        return ScriptCommand.RepeatStart(count = args[0].toInt(), lineNumber = ln)
    }

    private fun parseToast(args: List<String>, ln: Int): ScriptCommand {
        require(args.isNotEmpty()) { "TOAST는 메시지가 필요합니다" }
        return ScriptCommand.ShowToast(message = args[0], lineNumber = ln)
    }

    private fun parsePinch(args: List<String>, zoomIn: Boolean, ln: Int): ScriptCommand {
        require(args.size >= 2) { "PINCH는 x y 좌표가 필요합니다" }
        return ScriptCommand.Pinch(
            centerX = args[0].toInt(),
            centerY = args[1].toInt(),
            zoomIn = zoomIn,
            scale = args.getOrNull(2)?.toFloatOrNull() ?: 2.0f,
            lineNumber = ln
        )
    }
}
