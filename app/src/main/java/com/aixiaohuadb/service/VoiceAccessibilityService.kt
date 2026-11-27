package com.aixiaohuadb.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import com.aixiaohuadb.exec.GesturePlanner
import com.aixiaohuadb.exec.GesturePoint
import com.aixiaohuadb.exec.GesturePointer
import com.aixiaohuadb.exec.GestureSink
import com.aixiaohuadb.exec.DisplaySize
import com.aixiaohuadb.exec.clampSwipeDurationMs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * 无障碍执行服务：仅在用户主动触发后派发点击手势，不扫描屏幕文字、不注入、不 Hook。
 *
 * 所有手势都等待系统回调完成后才返回：系统同一时刻只允许一个手势，
 * 旧实现的“立即返回 + continueStroke 拆段”会丢失多指轨迹并让连续步骤互相顶掉。
 */
class VoiceAccessibilityService : AccessibilityService(), GestureSink {
    /** 语音链与手动链可能同时派发，串行化后第二个手势才不会拿到 "gesture in progress"。 */
    private val dispatchMutex = Mutex()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 前台窗口包名事件直达 ForegroundGameMonitor，悬浮按钮即时显隐
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            ForegroundGameMonitor.notifyForegroundPackage(event.packageName?.toString())
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /**
     * 派发一次点击；坐标为屏幕物理像素，由 CoordinateMapper 换算后传入。
     * 起点终点故意相差 1px，避免部分 ROM 把零距离手势判为无效点击。
     */
    override suspend fun tap(x: Int, y: Int): Boolean =
        dispatchAwait(tapPath(x, y), GesturePlanner.TAP_DURATION_MS)

    /** 派发一次滑动；参数均为 [CoordinateMapper] 换算后的物理像素 */
    override suspend fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Int): Boolean {
        val duration = durationMs.clampSwipeDurationMs().toLong()
        val path = Path().apply {
            moveTo(fromX.toFloat(), fromY.toFloat())
            lineTo(toX.toFloat(), toY.toFloat())
        }
        return dispatchAwait(path, duration)
    }

    /**
     * 多指并发手势：每根手指一条完整 Path、一个独立起始时间，一次性放进同一个
     * GestureDescription。错峰按下的手指不会再被强行拉到 0ms。
     */
    override suspend fun multiGesture(pointers: List<GesturePointer>, durationMs: Int): Boolean {
        val strokes = GesturePlanner.plan(pointers, durationMs) ?: return false
        if (strokes.isEmpty() || strokes.size > GestureDescription.getMaxStrokeCount()) return false

        val gesture = runCatching {
            val builder = GestureDescription.Builder()
            strokes.forEach { stroke ->
                builder.addStroke(
                    GestureDescription.StrokeDescription(
                        pathFor(stroke.points),
                        stroke.startMs,
                        stroke.durationMs,
                        false,
                    ),
                )
            }
            builder.build()
        }.getOrNull() ?: return false
        return dispatchAwait(gesture)
    }

    private fun pathFor(points: List<GesturePoint>): Path {
        if (points.isEmpty()) return Path()
        if (points.size == 1) return tapPath(points.first().x, points.first().y)

        val (width, height) = displaySize()
        // 先收敛到屏幕内再判重：越界点被夹到同一边界时，Path 会退化成零长度。
        val clamped = points.map { point ->
            point.x.coerceIn(0, width - 1) to point.y.coerceIn(0, height - 1)
        }
        val first = clamped.first()
        if (clamped.all { it == first }) return tapPath(first.first, first.second)

        return Path().apply {
            moveTo(first.first.toFloat(), first.second.toFloat())
            clamped.drop(1).forEach { (x, y) -> lineTo(x.toFloat(), y.toFloat()) }
        }
    }

    /** 1px 的有效路径；贴边时向内收，避免 Path 越界导致 dispatchGesture 直接拒绝。 */
    private fun tapPath(x: Int, y: Int): Path {
        val (width, height) = displaySize()
        val sx = x.coerceIn(0, width - 1)
        val sy = y.coerceIn(0, height - 1)
        var ex = if (sx < width - 1) sx + 1 else (sx - 1).coerceAtLeast(0)
        var ey = sy
        if (ex == sx) {
            ey = if (sy < height - 1) sy + 1 else (sy - 1).coerceAtLeast(0)
        }
        return Path().apply {
            moveTo(sx.toFloat(), sy.toFloat())
            lineTo(ex.toFloat(), ey.toFloat())
        }
    }

    private fun displaySize(): Pair<Int, Int> = DisplaySize.current(this)

    /**
     * 等待系统真正回调完成。
     *
     * 一旦 dispatchGesture 返回 true，就不能再用超时把结果判成“未派发”：
     * 手势可能已经落地，降级到 ADB 会造成重复点击。此处的等待由上层
     * [com.aixiaohuadb.exec.executionBudgetMs] 的 withTimeout 兜底；
     * 协程被取消时会释放 Mutex，不会永久占住派发通道。
     */
    private suspend fun dispatchAwait(path: Path, durationMs: Long): Boolean {
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs.coerceAtLeast(1L)))
            .build()
        return dispatchAwait(gesture)
    }

    private suspend fun dispatchAwait(gesture: GestureDescription): Boolean =
        withContext(Dispatchers.Main.immediate) {
            dispatchMutex.withLock {
                suspendCancellableCoroutine { continuation ->
                    val callback = object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            if (continuation.isActive) continuation.resume(true)
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            if (continuation.isActive) continuation.resume(false)
                        }
                    }
                    val accepted = runCatching { dispatchGesture(gesture, callback, null) }
                        .getOrDefault(false)
                    if (!accepted && continuation.isActive) continuation.resume(false)
                }
            }
        }

    companion object {
        @Volatile
        var instance: VoiceAccessibilityService? = null
            private set
    }
}
