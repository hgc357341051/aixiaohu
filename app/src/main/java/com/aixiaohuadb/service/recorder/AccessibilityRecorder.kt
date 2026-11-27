package com.aixiaohuadb.service.recorder

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.aixiaohuadb.domain.macro.MacroStep
import com.aixiaohuadb.domain.macro.NormPoint
import com.aixiaohuadb.domain.macro.ScreenSpec
import com.aixiaohuadb.exec.DisplaySize
import com.aixiaohuadb.exec.GesturePoint
import com.aixiaohuadb.exec.GesturePointer
import com.aixiaohuadb.service.VoiceAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot

/**
 * 免 ADB 录制器：全屏无障碍覆盖层接管触摸，抬手时用同一套 dispatchGesture 转发给游戏。
 *
 * 设计来源（Ultrebo）：
 * - 录制层可触摸，记录 rawX/rawY 与 eventTime；
 * - 转发前把录制层临时设为 NOT_TOUCHABLE，派发手势后立即恢复，让注入落到游戏；
 * - 抬手间隔直接成为下一步的 delayBeforeMs。
 *
 * 与截图标注的区别：记录的是用户真实操作，不是"在截图上点一个坐标"。
 */
class AccessibilityRecorder(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onStep(step: MacroStep)
        fun onTick(elapsedMs: Long, stepCount: Int)
        /** 胶囊上的"完成"，由调用方收尾并命名。 */
        fun onFinishRequested()
        fun onCancelRequested()
        /** 录制层不可用时的原因，调用方负责提示。 */
        fun onError(message: String)
    }

    /** 必须用无障碍服务的 WindowManager：TYPE_ACCESSIBILITY_OVERLAY 只有它能添加成功。 */
    private lateinit var windowManager: WindowManager
    private var accessibility: android.accessibilityservice.AccessibilityService? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** 转发必须串行：两次手势交错会让 NOT_TOUCHABLE 窗口互相打断。 */
    private val forwardMutex = Mutex()

    private var overlay: RecorderView? = null
    private var chip: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    private val steps = mutableListOf<MacroStep>()
    private var startedAtMs = 0L
    /** 启动后的短暂窗口：忽略触发录制的这次触摸，避免把"点新建宏"本身录成一步。 */
    private var acceptInputFromMs = 0L
    private var lastUpAtMs = 0L
    private var ticker: Job? = null
    private var running = false


    fun start(): Boolean {
        if (running) return true
        val service = VoiceAccessibilityService.instance
        if (service == null) {
            listener.onError("无障碍服务未连接，无法录制")
            return false
        }
        accessibility = service
        windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = RecorderView(context) { gesture ->
            handleGesture(gesture)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val addedResult = runCatching { windowManager.addView(view, params) }
        Log.d("AccessibilityRecorder", "addView ok=${addedResult.isSuccess} err=${addedResult.exceptionOrNull()?.message}")
        val added = addedResult.isSuccess
        if (!added) {
            listener.onError("无法添加录制层，请检查无障碍服务是否被系统回收")
            return false
        }

        overlay = view
        overlayParams = params
        steps.clear()
        startedAtMs = SystemClock.elapsedRealtime()
        acceptInputFromMs = startedAtMs + 350L
        lastUpAtMs = startedAtMs
        running = true
        showChip()
        ticker = scope.launch {
            while (running) {
                val elapsed = SystemClock.elapsedRealtime() - startedAtMs
                updateChip(elapsed, steps.size)
                listener.onTick(elapsed, steps.size)
                delay(500L)
            }
        }
        return true
    }

    /** 结束录制：停止接收触摸并返回已记录步骤。 */
    fun stop(): List<MacroStep> {
        if (!running) return steps.toList()
        running = false
        removeOverlay()
        removeChip()
        ticker?.cancel()
        ticker = null
        return steps.toList()
    }

    fun cancel() {
        running = false
        removeOverlay()
        removeChip()
        ticker?.cancel()
        ticker = null
        steps.clear()
    }

    fun destroy() {
        cancel()
        scope.cancel()
    }

    private fun handleGesture(gesture: RecordedGesture) {
        if (!running) return
        if (gesture.downAtMs < acceptInputFromMs) return
        // 每次手势都重新取当前显示尺寸：录制中切横屏/竖屏时，
        // 用启动时的旧尺寸归一化会把坐标算到屏幕外。
        val (width, height) = DisplaySize.current(context)
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        // 多指只保留主指写进文档（P0 限制），但转发时按原样派发，游戏看到的是真实手势。
        val step = buildStep(gesture, safeWidth, safeHeight)
        if (step != null) {
            steps += step
            listener.onStep(step)
            listener.onTick(SystemClock.elapsedRealtime() - startedAtMs, steps.size)
        }
        lastUpAtMs = gesture.upAtMs
        forward(gesture)
    }

    private fun buildStep(gesture: RecordedGesture, screenWidth: Int, screenHeight: Int): MacroStep? {
        val samples = gesture.primarySamples.filter { it.timeMs >= gesture.downAtMs }
        if (samples.isEmpty()) return null
        val start = samples.first()
        val end = samples.last()
        val duration = (gesture.upAtMs - gesture.downAtMs).coerceAtLeast(0L).toInt()
        val distance = hypot(
            (end.x - start.x).toDouble(),
            (end.y - start.y).toDouble(),
        )
        val delay = (gesture.downAtMs - lastUpAtMs).coerceIn(0L, 90_000L)

        val normalized = samples.map { sample ->
            NormPoint.of(
                (sample.x / screenWidth).coerceIn(0f, 1f),
                (sample.y / screenHeight).coerceIn(0f, 1f),
            )
        }

        return when {
            // 长按：位移极小但按住超过 450ms
            distance < dp(12f).toDouble() && duration >= LONG_PRESS_MS -> MacroStep.LongPress(
                delayBeforeMs = delay,
                x = normalized.first().x,
                y = normalized.first().y,
                durationMs = duration.coerceIn(MacroStep.MIN_DURATION_MS, MacroStep.MAX_DURATION_MS),
            )

            // 点击：位移小于阈值
            distance < dp(12f).toDouble() -> MacroStep.Tap(
                delayBeforeMs = delay,
                x = normalized.first().x,
                y = normalized.first().y,
                durationMs = duration.coerceIn(MacroStep.MIN_DURATION_MS, 2_000),
            )

            // 滑动：保留采样轨迹，回放时按当前屏幕还原
            else -> MacroStep.Swipe(
                delayBeforeMs = delay,
                points = if (normalized.size >= 2) normalized else listOf(normalized.first(), normalized.first()),
                durationMs = duration.coerceIn(MacroStep.MIN_DURATION_MS, MacroStep.MAX_DURATION_MS),
            )
        }
    }

    /**
     * 转发：录制层临时不可触摸，等手势真正完成后恢复。
     *
     * 必须等完成，不能"派发后立即恢复"：注入的 MotionEvent 也要经过窗口命中测试，
     * 录制层可触摸时会把手势吃回自己身上，游戏完全收不到。
     * 代价是转发期间（一次点击约 60ms、一次滑动约 300ms）用户的新触摸会穿透到游戏，
     * 由 [forwardMutex] 串行化避免两次转发交错。
     */
    private fun forward(gesture: RecordedGesture) {
        val service = VoiceAccessibilityService.instance ?: return
        if (gesture.pointers.isEmpty()) return
        val (width, height) = DisplaySize.current(context)
        val maxX = width.coerceAtLeast(1) - 1
        val maxY = height.coerceAtLeast(1) - 1
        val base = gesture.downAtMs
        val pointers = gesture.pointers.map { pointer ->
            GesturePointer(
                pointer.samples.map { sample ->
                    GesturePoint(
                        x = sample.x.toInt().coerceIn(0, maxX),
                        y = sample.y.toInt().coerceIn(0, maxY),
                        timeMs = (sample.timeMs - base).coerceAtLeast(0L),
                    )
                },
            )
        }.filter { it.points.isNotEmpty() }
        if (pointers.isEmpty()) return
        val duration = (gesture.upAtMs - gesture.downAtMs).coerceIn(1L, 10_000L).toInt()

        scope.launch {
            forwardMutex.withLock {
                setTouchable(false)
                try {
                    service.multiGesture(pointers, duration)
                } finally {
                    setTouchable(true)
                }
            }
        }
    }

    private fun setTouchable(touchable: Boolean) {
        val params = overlayParams ?: return
        val view = overlay ?: return
        params.flags = if (touchable) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun showChip() {
        if (chip != null) return
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(22f).toFloat()
                setColor(0xFF0B1220.toInt())
                setStroke(dp(1f), 0xFFEF4444.toInt())
            }
            setPadding(dp(14f), dp(8f), dp(8f), dp(8f))
        }
        val label = TextView(context).apply {
            text = "● 录制中 00:00 · 0 步"
            setTextColor(Color.WHITE)
            textSize = 12f
            tag = "record_label"
        }
        root.addView(label)
        root.addView(TextView(context).apply {
            text = "完成"
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
            background = GradientDrawable().apply {
                cornerRadius = dp(14f).toFloat()
                setColor(0xFF0E7490.toInt())
            }
            setOnClickListener { listener.onFinishRequested() }
        })
        root.addView(TextView(context).apply {
            text = "×"
            setTextColor(0xFFEF4444.toInt())
            textSize = 16f
            setPadding(dp(12f), dp(2f), dp(6f), dp(6f))
            setOnClickListener { listener.onCancelRequested() }
        })

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(28f)
        }
        val chipResult = runCatching { windowManager.addView(root, params) }
        Log.d("AccessibilityRecorder", "chip addView ok=${chipResult.isSuccess} err=${chipResult.exceptionOrNull()?.message}")
        chip = root
    }

    private fun removeOverlay() {
        overlay?.let { runCatching { windowManager.removeView(it) } }
        overlay = null
        overlayParams = null
    }

    private fun removeChip() {
        chip?.let { runCatching { windowManager.removeView(it) } }
        chip = null
    }

    fun updateChip(elapsedMs: Long, stepCount: Int) {
        val label = chip?.findViewWithTag<TextView>("record_label") ?: return
        val totalSeconds = (elapsedMs / 1000L).toInt()
        label.text = "● 录制中 %02d:%02d · %d 步".format(totalSeconds / 60, totalSeconds % 60, stepCount)
    }

    private fun dp(value: Float): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val LONG_PRESS_MS = 450
    }

    // ---------------- 触摸采集 ----------------

    private class RecorderView(
        context: Context,
        private val onGesture: (RecordedGesture) -> Unit,
    ) : View(context) {
        private val pointers = LinkedHashMap<Int, PointerTrace>()
        private var downAtMs = 0L

        init {
            isClickable = true
            isFocusable = false
            setBackgroundColor(Color.TRANSPARENT)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val now = event.eventTime
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pointers.clear()
                    downAtMs = now
                    startPointer(event, 0, now)
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    val index = event.actionIndex
                    startPointer(event, index, now)
                }

                MotionEvent.ACTION_MOVE -> {
                    for (index in 0 until event.pointerCount) {
                        val id = event.getPointerId(index)
                        val trace = pointers[id] ?: continue
                        val x = event.getRawX(index)
                        val y = event.getRawY(index)
                        val last = trace.samples.lastOrNull()
                        // 1dp 级抖动不采样，避免滑动轨迹膨胀成几百个点
                        if (last == null || hypot((x - last.x).toDouble(), (y - last.y).toDouble()) >= 3.0) {
                            trace.samples += Sample(x, y, now)
                        }
                    }
                }

                MotionEvent.ACTION_POINTER_UP -> {
                    finishPointer(event, event.actionIndex, now)
                }

                MotionEvent.ACTION_UP -> {
                    finishPointer(event, 0, now)
                    // 其余手指若没有独立的 POINTER_UP，一律按本次抬手时间收尾
                    pointers.values.forEach { trace -> trace.upAtMs = now }
                    if (pointers.isNotEmpty()) {
                        val primaryId = pointers.keys.first()
                        val gesture = RecordedGesture(
                            pointers = pointers.values.toList(),
                            primarySamples = pointers[primaryId]?.samples.orEmpty(),
                            downAtMs = downAtMs,
                            upAtMs = now,
                        )
                        onGesture(gesture)
                    }
                    pointers.clear()
                }

                MotionEvent.ACTION_CANCEL -> pointers.clear()
            }
            return true
        }

        private fun startPointer(event: MotionEvent, index: Int, now: Long) {
            val id = event.getPointerId(index)
            pointers[id] = PointerTrace(
                id = id,
                downAtMs = now,
                upAtMs = now,
                samples = mutableListOf(Sample(event.getRawX(index), event.getRawY(index), now)),
            )
        }

        private fun finishPointer(event: MotionEvent, index: Int, now: Long) {
            val id = event.getPointerId(index)
            pointers[id]?.let { trace ->
                trace.upAtMs = now
                if (trace.samples.isEmpty()) {
                    trace.samples += Sample(event.getRawX(index), event.getRawY(index), now)
                }
            }
        }
    }
}

/** 录制中的一次手势；多指时 [pointers] 有多个元素。 */
data class RecordedGesture(
    val pointers: List<PointerTrace>,
    val primarySamples: List<Sample>,
    val downAtMs: Long,
    val upAtMs: Long,
) {
}

data class PointerTrace(
    val id: Int,
    val downAtMs: Long,
    var upAtMs: Long,
    val samples: MutableList<Sample>,
)

data class Sample(val x: Float, val y: Float, val timeMs: Long)
