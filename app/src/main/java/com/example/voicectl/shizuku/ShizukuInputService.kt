package com.example.voicectl.shizuku

import android.content.res.Resources
import androidx.annotation.Keep
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 运行在 Shizuku shell 进程中的输入服务。
 *
 * 安全边界：
 * 1. 只实现 AIDL 的强类型 int 方法，没有字符串命令入口；
 * 2. 只用 Runtime.exec(arrayOf(...))，命令名固定为 /system/bin/input，子命令为字面量；
 * 3. 收到参数后用与 App 进程相同的 CommandValidator 再校验一次；
 * 4. 不做 sh -c、不读 /data/data、不反射隐藏 API、不 Hook。
 */
@Keep
class ShizukuInputService : IShizukuInputService.Stub() {

    private val screenSize: Pair<Int, Int>
        get() {
            val metrics = Resources.getSystem().displayMetrics
            return metrics.widthPixels to metrics.heightPixels
        }

    /** Shizuku UserService 约定的销毁事务：内部无持久资源，清理后主动退出 shell 进程。 */
    override fun destroy() {
        pendingBindCleanup()
        System.exit(0)
    }

    override fun tap(x: Int, y: Int): Int = validated(InputCommand.Tap(x, y)) { cmd ->
        execInput(arrayOf("tap", cmd.x.toString(), cmd.y.toString()))
    }

    override fun swipe(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Int,
    ): Int = validated(
        InputCommand.Swipe(x1, y1, x2, y2, durationMs),
    ) { cmd ->
        execInput(
            arrayOf(
                "swipe",
                cmd.fromX.toString(),
                cmd.fromY.toString(),
                cmd.toX.toString(),
                cmd.toY.toString(),
                cmd.durationMs.toString(),
            ),
        )
    }

    override fun keyEvent(keyCode: Int): Int = validated(InputCommand.KeyEvent(keyCode)) { cmd ->
        execInput(arrayOf("keyevent", cmd.keyCode.toString()))
    }

    /**
     * 真实探测：确认目标机上 /system/bin/input 存在且可执行。
     * 返回 0 表示可用，非 0 表示不可用；不注入任何按键事件。
     */
    override fun probe(): Int {
        val binary = File(INPUT_BINARY)
        return if (binary.exists() && binary.canExecute()) 0 else EXIT_PROBE_FAILED
    }

    private fun pendingBindCleanup() {
        // 预留：当前服务不持有线程/文件句柄，无需额外释放。
    }

    private fun <T : InputCommand> validated(command: T, block: (T) -> Int): Int = try {
        val (width, height) = screenSize
        @Suppress("UNCHECKED_CAST")
        block(CommandValidator.validate(command, width, height) as T)
    } catch (e: IllegalArgumentException) {
        EXIT_INVALID_ARGUMENT
    }

    /** 固定可执行文件 + 字面量子命令 + 已校验数字，绝不拼接外部字符串。 */
    private fun execInput(args: Array<String>): Int {
        val process = try {
            Runtime.getRuntime().exec(arrayOf(INPUT_BINARY, *args))
        } catch (e: Exception) {
            return EXIT_EXEC_FAILED
        }
        return try {
            if (process.waitFor(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.exitValue()
            } else {
                process.destroy()
                EXIT_TIMEOUT
            }
        } catch (e: InterruptedException) {
            // 恢复中断标志，避免上层线程误以为从未被中断。
            Thread.currentThread().interrupt()
            process.destroy()
            EXIT_EXEC_FAILED
        } catch (e: Exception) {
            process.destroy()
            EXIT_EXEC_FAILED
        }
    }

    private companion object {
        const val INPUT_BINARY = "/system/bin/input"
        const val COMMAND_TIMEOUT_MS = 3_000L
        const val EXIT_INVALID_ARGUMENT = 126
        const val EXIT_EXEC_FAILED = 125
        const val EXIT_TIMEOUT = 124
        const val EXIT_PROBE_FAILED = 1
    }
}
