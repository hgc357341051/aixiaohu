package com.aixiaohuadb.exec

import kotlinx.coroutines.Job

/** 一条指令的可执行步骤序列；Tap 坐标为 1080×2340 基准坐标 */
data class CommandAction(
    val commandId: Int,
    val name: String,
    val steps: List<ExecStep>,
)

/** 手势轨迹上的一个采样点；坐标为 1080×2340 基准，时间相对整套手势开始。 */
data class GesturePoint(
    val x: Int,
    val y: Int,
    val timeMs: Long,
)

/** 一根手指的完整轨迹；至少包含一个点。 */
data class GesturePointer(
    val points: List<GesturePoint>,
) {
    val startMs: Long get() = points.firstOrNull()?.timeMs ?: 0L
    val endMs: Long get() = points.lastOrNull()?.timeMs ?: 0L
}

/**
 * 宏步骤。坐标一律为 1080×2340 基准坐标，派发前由各 Executor 内 CoordinateMapper 换算。
 * [delayAfterMs] 为本步成功派发后的间隔，两个执行器行为一致。
 */
sealed class ExecStep {
    data class Tap(
        val x: Int,
        val y: Int,
        val key: String = "",
        val delayAfterMs: Long = 0L,
    ) : ExecStep()

    data class Swipe(
        val fromX: Int,
        val fromY: Int,
        val toX: Int,
        val toY: Int,
        val durationMs: Int = 300,
        val delayAfterMs: Long = 0L,
    ) : ExecStep()

    /**
     * 多指手势：多根手指的轨迹在同一个时间轴上并发派发，而不是串行的 Tap/Swipe。
     * [devicePath]/[panelMaxX]/[panelMaxY]/[rotation] 只作为录制来源提示，
     * 回放时 ADB 链路会重新探测当前触摸设备并以当前方向换算，避免路径过期导致注入失败。
     */
    data class MultiGesture(
        val pointers: List<GesturePointer>,
        val durationMs: Int,
        val delayAfterMs: Long = 0L,
        val devicePath: String = "",
        val panelMaxX: Int = 0,
        val panelMaxY: Int = 0,
        val rotation: Int = 0,
    ) : ExecStep()

    /** 仅兼容旧宏数据；新录制一律使用步上 delayAfterMs */
    @Deprecated("使用步上 delayAfterMs 表达间隔", ReplaceWith("ExecStep.Tap(0, 0, delayAfterMs = ms)"))
    data class Wait(val ms: Long) : ExecStep()

    data class Verify(val key: String) : ExecStep()
}

/** 步骤自身声明的最大耗时，供执行超时上限计算；逐项夹到 0..60_000 防止溢出 */
/** 一条步骤自身声明的延迟；手势时长不计入此项，避免改变旧宏的预算语义。 */
val ExecStep.maxDelayMs: Long
    get() = when (this) {
        is ExecStep.Tap -> delayAfterMs
        is ExecStep.Swipe -> delayAfterMs
        is ExecStep.MultiGesture -> delayAfterMs
        is ExecStep.Wait -> ms
        is ExecStep.Verify -> 0L
    }.clampDelayMs()

/** 步骤本身占用执行器的时间（滑动/多指手势），用于计算真实超时预算。 */
val ExecStep.estimatedDurationMs: Long
    get() = when (this) {
        is ExecStep.Swipe -> effectiveDurationMs.toLong()
        is ExecStep.MultiGesture -> durationMs.clampMultiTouchDurationMs().toLong()
        else -> 0L
    }

/** 步上延迟与 Wait 的统一边界，编解码/UI/执行器共用 */
const val MAX_DELAY_MS: Long = 60_000L

fun Long.clampDelayMs(): Long = coerceIn(0L, MAX_DELAY_MS)

/** 滑动时长的唯一边界来源是 [com.aixiaohuadb.adb.CommandValidator] */
fun Int.clampSwipeDurationMs(): Int =
    com.aixiaohuadb.adb.CommandValidator.clampSwipeDurationMs(this)

fun Int.clampMultiTouchDurationMs(): Int =
    com.aixiaohuadb.adb.CommandValidator.clampMultiTouchDurationMs(this)

val ExecStep.Swipe.effectiveDurationMs: Int get() = durationMs.clampSwipeDurationMs()
val ExecStep.MultiGesture.effectiveDurationMs: Int get() = durationMs.clampMultiTouchDurationMs()

/**
 * 三条执行入口共用的超时预算：10s 保底，按每步延迟 + 手势时长求和 + 5s，上限 60s。
 * 逐项 coerce 后再 sumOf，避免超长 delay 累加导致 Long 溢出。
 */
fun executionBudgetMs(steps: List<ExecStep>): Long =
    if (steps.isEmpty()) 10_000L
    else (steps.sumOf { it.maxDelayMs + it.estimatedDurationMs } + 5_000L)
        .coerceAtLeast(10_000L)
        .coerceAtMost(60_000L)

/**
 * 执行取消信号：disable 时翻转，执行器在每步派发前检查并立即停止。
 * lambda 形式便于 JVM 单测注入 Fake，不需要 Android 或协程 Job 实例。
 */
fun interface ExecutionGuard {
    fun isCancelled(): Boolean

    companion object {
        val NONE: ExecutionGuard = ExecutionGuard { false }
    }
}

fun ExecutionGuard.ensureActive() {
    if (isCancelled()) throw kotlinx.coroutines.CancellationException("语音已关闭，指令执行已取消")
}

fun (() -> Boolean).asGuard(): ExecutionGuard = ExecutionGuard(this)

/**
 * 手动回放专用执行令牌：每次回放创建一个新实例，与语音生命周期的取消令牌完全隔离。
 *
 * 用户「开启过语音又关闭」后，voiceLifecycle 的 token 永远是 cancelled；
 * 手动点宏按钮不能共用它，否则按钮会红闪且点击/滑动不落地。
 */
class ManualExecutionToken {
    private val job = Job()
    val guard: ExecutionGuard = ExecutionGuard { job.isCancelled }
    val isCancelled: Boolean get() = job.isCancelled
    fun cancel() { job.cancel() }
}

/**
 * [attempted] 表示本执行器是否已经真实派发过至少一条点击命令。
 * 一旦为 true，失败时执行链禁止降级重试，否则会造成同一 action 被重复点击。
 */
data class ExecResult(
    val success: Boolean,
    val message: String = "",
    val attempted: Boolean = false,
)

interface Executor {
    val name: String
    fun isAvailable(): Boolean
    suspend fun execute(action: CommandAction): ExecResult
}

/** 最后一道只读提示：绝不执行点击、注入或 Hook */
object ReadOnlyExecutor : Executor {
    override val name: String = "只读提示"
    override fun isAvailable(): Boolean = true
    override suspend fun execute(action: CommandAction): ExecResult =
        ExecResult(false, "当前无可用执行链路，请手动操作：${action.name}")
}
