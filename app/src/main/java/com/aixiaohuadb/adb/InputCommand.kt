package com.aixiaohuadb.adb

/**
 * 对外唯一的输入命令模型：只有三种强类型，不存在任意字符串命令入口。
 *
 * 所有数值都在 App 进程侧经 [CommandValidator] 校验后才允许下发给 adbd。
 * 执行时由固定的字面量模板拼成 `input tap/swipe/keyevent` 参数，
 * 命令动词不可由外部数据决定，杜绝命令注入。
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

/** 命令执行结果；[attempted] 表示已真实派发过点击，失败时禁止降级重放。 */
data class CommandResult(
    val ok: Boolean,
    val message: String = "",
    val attempted: Boolean = false,
)

/** 强类型命令的执行边界；真实客户端与单测桩都实现它。 */
interface InputCommandExecutor {
    suspend fun execute(command: InputCommand): CommandResult
}

/** 把命令转成可读描述，仅用于 UI 与日志展示，不参与执行。 */
fun describeCommand(command: InputCommand): String = when (command) {
    is InputCommand.Tap -> "点击 (${command.x}, ${command.y})"
    is InputCommand.Swipe ->
        "滑动 (${command.fromX}, ${command.fromY}) → (${command.toX}, ${command.toY}) ${command.durationMs}ms"
    is InputCommand.KeyEvent -> "按键 ${command.keyCode}"
}
