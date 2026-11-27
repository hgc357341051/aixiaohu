@file:Suppress("DEPRECATION")
package com.aixiaohuadb.exec

import kotlinx.coroutines.delay

/**
 * 无障碍手势的最小可测接口；VoiceAccessibilityService 实现它，
 * 单测可用轻量 fake 验证坐标映射与步上延迟，不需要 Android Service 实例。
 *
 * 全部为 suspend：一次手势必须真正完成（onCompleted/onCancelled）后才允许派发下一步，
 * 否则连续宏会在“上一手势进行中”被系统拒绝，表现为随机丢步。
 * 坐标已是当前屏幕物理像素。
 */
interface GestureSink {
    suspend fun tap(x: Int, y: Int): Boolean
    suspend fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Int): Boolean

    /** 默认不支持；支持并发手势的服务覆写此方法。 */
    suspend fun multiGesture(pointers: List<GesturePointer>, durationMs: Int): Boolean = false
}

/**
 * 主执行链路：通过无障碍手势派发点击/滑动。
 * 坐标必须先由 [mapper] 从 1080×2340 基准换算为当前物理像素，
 * 与 ADB 执行器共用同一 mapper 实例，保证两条链路落点一致。
 *
 * [guard] 在每步派发前检查取消信号（语音 disable 时翻转），
 * 一旦取消立即抛 CancellationException，不再派发后续步骤。
 */
class AccessibilityExecutor(
    private val serviceProvider: () -> GestureSink?,
    private val mapper: CoordinateMapper,
    private val screenSizeProvider: () -> Pair<Int, Int>,
    private val guard: ExecutionGuard = ExecutionGuard.NONE,
) : Executor {
    override val name: String = "无障碍服务"

    override fun isAvailable(): Boolean = serviceProvider() != null

    override suspend fun execute(action: CommandAction): ExecResult {
        guard.ensureActive()
        val service = serviceProvider()
            ?: return ExecResult(false, "无障碍服务未开启")
        val (screenWidth, screenHeight) = screenSizeProvider()
        if (screenWidth <= 0 || screenHeight <= 0) {
            return ExecResult(false, "无法获取有效屏幕尺寸")
        }
        val executed = mutableListOf<String>()
        var attempted = false
        for (step in action.steps) {
            guard.ensureActive()
            when (step) {
                is ExecStep.Tap -> {
                    val (x, y) = mapper.map(step.x, step.y, screenWidth, screenHeight)
                    val ok = service.tap(x, y)
                    // 只有派发成功才算 attempted：派发前失败保持 false 允许降级
                    if (ok) attempted = true
                    if (!ok) {
                        return ExecResult(false, "点击失败：${step.key}，已停止后续步骤", attempted = attempted)
                    }
                    executed += step.key.ifEmpty { "tap($x,$y)" }
                    delay(step.delayAfterMs.clampDelayMs())
                }
                is ExecStep.MultiGesture -> {
                    val mapped = step.pointers.map { pointer ->
                        GesturePointer(
                            pointer.points.map { point ->
                                val (x, y) = mapper.map(point.x, point.y, screenWidth, screenHeight)
                                GesturePoint(x, y, point.timeMs)
                            },
                        )
                    }
                    val ok = service.multiGesture(mapped, step.effectiveDurationMs)
                    if (ok) attempted = true
                    if (!ok) {
                        return ExecResult(false, "多指手势不受当前执行器支持或派发失败", attempted = attempted)
                    }
                    executed += "multi(${mapped.size}指/${step.effectiveDurationMs}ms)"
                    delay(step.delayAfterMs.clampDelayMs())
                }
                is ExecStep.Swipe -> {
                    val (fx, fy) = mapper.map(step.fromX, step.fromY, screenWidth, screenHeight)
                    val (tx, ty) = mapper.map(step.toX, step.toY, screenWidth, screenHeight)
                    val ok = service.swipe(fx, fy, tx, ty, step.effectiveDurationMs)
                    if (ok) attempted = true
                    if (!ok) {
                        return ExecResult(false, "滑动失败，已停止后续步骤", attempted = attempted)
                    }
                    executed += "swipe($fx,$fy→$tx,$ty)"
                    delay(step.delayAfterMs.clampDelayMs())
                }
                is ExecStep.Wait -> delay(step.ms.clampDelayMs())
                is ExecStep.Verify -> executed += "verify:${step.key}"
            }
        }
        return ExecResult(true, "无障碍执行完成：${action.name}（${executed.size} 步）", attempted = attempted)
    }
}
