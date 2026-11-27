package com.example.voicectl.shizuku

/**
 * 对外的唯一命令模型：只有三种强类型，不存在 execute(String) / exec() 入口。
 * 所有数值在 App 进程与 shell 进程各校验一次。
 */
sealed class InputCommand {
    data class Tap(val x: Int, val y: Int) : InputCommand()

    data class Swipe(
        val fromX: Int,
        val fromY: Int,
        val toX: Int,
        val toY: Int,
        val durationMs: Int,
    ) : InputCommand()

    data class KeyEvent(val keyCode: Int) : InputCommand()
}
