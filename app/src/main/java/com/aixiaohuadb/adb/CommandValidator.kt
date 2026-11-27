package com.aixiaohuadb.adb

/**
 * 输入命令边界校验：坐标按真实屏幕宽高校验（基准 1080×2340 先由
 * CoordinateMapper 映射到真实屏幕），滑动时长 50..5000ms 是全局唯一边界来源，
 * 按键限 HOME/BACK/ENTER。执行前后各校验一次（fail-closed）。
 */
object CommandValidator {
    const val BASE_WIDTH = 1080
    const val BASE_HEIGHT = 2340
    const val MIN_DURATION_MS = 50
    const val MAX_DURATION_MS = 5_000
    const val MAX_MULTITOUCH_DURATION_MS = 60_000
    const val MAX_POINTERS = 10
    private val DEVICE_PATH = Regex("^/dev/input/event\\d+$")

    /** A11y / ADB / 编解码 / UI 共用的滑动时长边界 */
    fun clampSwipeDurationMs(value: Int): Int = value.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)

    /** 多指手势可能持续更长，但仍受 60s 硬上限约束。 */
    fun clampMultiTouchDurationMs(value: Int): Int =
        value.coerceIn(MIN_DURATION_MS, MAX_MULTITOUCH_DURATION_MS)

    /** 允许通过 adb 发送的按键，避免任意 keycode 造成误操作。 */
    val ALLOWED_KEY_CODES: Set<Int> = setOf(
        3,  // KEYCODE_HOME
        4,  // KEYCODE_BACK
        66, // KEYCODE_ENTER
    )

    /** 使用基准尺寸校验，便于纯 JVM 单测。 */
    fun validate(command: InputCommand): ValidationResult =
        validate(command, BASE_WIDTH, BASE_HEIGHT)

    /** 使用真实屏幕尺寸校验。 */
    fun validate(command: InputCommand, screenWidth: Int, screenHeight: Int): ValidationResult {
        require(screenWidth > 0 && screenHeight > 0) { "屏幕宽高必须为正数" }
        val reason = when (command) {
            is InputCommand.Tap -> when {
                command.x !in 0 until screenWidth -> "x 越界：${command.x}（屏幕宽 $screenWidth）"
                command.y !in 0 until screenHeight -> "y 越界：${command.y}（屏幕高 $screenHeight）"
                else -> null
            }

            is InputCommand.Swipe -> when {
                command.fromX !in 0 until screenWidth -> "起点 x 越界：${command.fromX}"
                command.fromY !in 0 until screenHeight -> "起点 y 越界：${command.fromY}"
                command.toX !in 0 until screenWidth -> "终点 x 越界：${command.toX}"
                command.toY !in 0 until screenHeight -> "终点 y 越界：${command.toY}"
                command.durationMs !in MIN_DURATION_MS..MAX_DURATION_MS ->
                    "时长越界：${command.durationMs}（允许 $MIN_DURATION_MS..$MAX_DURATION_MS）"
                else -> null
            }

            is InputCommand.KeyEvent -> when {
                command.keyCode !in ALLOWED_KEY_CODES -> "按键不在白名单：${command.keyCode}"
                else -> null
            }

            is InputCommand.MultiTouch -> when {
                command.devicePath.isNotBlank() &&
                    !DEVICE_PATH.matches(command.devicePath) -> "触摸设备路径非法"
                command.pointers.size !in 2..MAX_POINTERS ->
                    "多指数量越界：${command.pointers.size}（允许 2..$MAX_POINTERS）"
                command.durationMs !in MIN_DURATION_MS..MAX_MULTITOUCH_DURATION_MS ->
                    "多指时长越界：${command.durationMs}"
                else -> {
                    val badPoint = command.pointers.asSequence()
                        .flatMap { it.points.asSequence() }
                        .firstOrNull { point ->
                            point.x !in 0 until screenWidth ||
                                point.y !in 0 until screenHeight ||
                                point.timeMs < 0L ||
                                point.timeMs > command.durationMs
                        }
                    if (badPoint != null) "多指轨迹点越界：($badPoint)"
                    else if (command.pointers.any { it.points.isEmpty() }) "多指轨迹为空"
                    else null
                }
            }
        }
        return if (reason == null) ValidationResult.Valid else ValidationResult.Invalid(reason)
    }

    sealed class ValidationResult {
        data object Valid : ValidationResult()
        data class Invalid(val reason: String) : ValidationResult()
    }
}
