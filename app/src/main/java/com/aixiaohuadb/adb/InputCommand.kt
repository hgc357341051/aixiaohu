package com.aixiaohuadb.adb

/**
 * 对外唯一的输入命令模型：强类型输入，不存在任意字符串命令入口。
 *
 * 所有数值都在 App 进程侧经 [CommandValidator] 校验后才允许下发给 adbd。
 * Tap/Swipe/KeyEvent 由固定字面量模板拼成 `input` 参数；MultiTouch 由
 * [AdbMultiTouchScript] 拼成固定 `sendevent` 序列，命令动词不可由外部数据决定。
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

    /** [points] 的坐标为当前显示方向下的物理像素，时间与 [durationMs] 同为毫秒。 */
    data class MotionPoint(val x: Int, val y: Int, val timeMs: Long)

    data class MotionPointer(val points: List<MotionPoint>)

    data class MultiTouch(
        val pointers: List<MotionPointer>,
        val durationMs: Int,
        val devicePath: String = "",
    ) : InputCommand()
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
    is InputCommand.MultiTouch ->
        "多指 ${command.pointers.size} 根 / ${command.durationMs}ms"
}
