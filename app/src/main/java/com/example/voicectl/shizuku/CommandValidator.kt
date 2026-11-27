package com.example.voicectl.shizuku

/**
 * 纯 Kotlin 边界校验，可在 JVM 单测中直接运行，不依赖任何 Android API。
 * 同一份规则在 App 进程（发出的命令）与 shell 进程（收到的命令）各执行一次。
 */
object CommandValidator {
    const val MAX_DURATION_MS = 10_000

    /** 只允许系统导航/确认三个无副作用的按键，杜绝任意 keyevent。 */
    val ALLOWED_KEY_CODES: Set<Int> = setOf(KEYCODE_HOME, KEYCODE_BACK, KEYCODE_ENTER)

    const val KEYCODE_HOME = 3
    const val KEYCODE_BACK = 4
    const val KEYCODE_ENTER = 66

    fun validate(command: InputCommand, width: Int, height: Int): InputCommand {
        require(width > 0 && height > 0) { "屏幕宽高必须为正数：$width×$height" }
        return when (command) {
            is InputCommand.Tap -> {
                checkCoordinate(command.x, width, "x")
                checkCoordinate(command.y, height, "y")
                command
            }
            is InputCommand.Swipe -> {
                checkCoordinate(command.fromX, width, "fromX")
                checkCoordinate(command.fromY, height, "fromY")
                checkCoordinate(command.toX, width, "toX")
                checkCoordinate(command.toY, height, "toY")
                require(command.durationMs in 0..MAX_DURATION_MS) {
                    "durationMs=${command.durationMs} 超出 [0, $MAX_DURATION_MS]"
                }
                command
            }
            is InputCommand.KeyEvent -> {
                require(command.keyCode in ALLOWED_KEY_CODES) {
                    "keyCode=${command.keyCode} 不在白名单 $ALLOWED_KEY_CODES 内"
                }
                command
            }
        }
    }

    private fun checkCoordinate(value: Int, bound: Int, name: String) {
        require(value in 0 until bound) { "$name=$value 超出 [0, $bound)" }
    }
}
