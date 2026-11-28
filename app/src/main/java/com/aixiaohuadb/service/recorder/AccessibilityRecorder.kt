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
import android.view.ViewGroup
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
import kotlinx.coroutines.withTimeoutOrNull
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
    private var chipParams: WindowManager.LayoutParams? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    /** 胶囊是否已折叠成小圆点 */
    private var chipFolded = false
    /** 胶囊贴边：记录逻辑 y，避免拖拽后 updateChip 丢失位置 */
    private var chipX = 0
    private var chipY = 0

    private val steps = mutableListOf<MacroStep>()
    private var startedAtMs = 0L
    /** 启动后的短暂窗口：忽略触发录制的这次触摸，避免把"点新建宏"本身录成一步。 */
    private var acceptInputFromMs = 0L
    /** 暂停 = 录制层透传给游戏，不再记录也不转发 */
    private var paused = false
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
        // 统一时间基准：MotionEvent.eventTime 基于 uptimeMillis（不含深度睡眠）。
        // 过去用 elapsedRealtime 计算窗口，手机睡过觉后 acceptInputFromMs 远大于 eventTime，
        // 导致每一次手势都被早退、既不记录也不转发 —— "录制时点游戏没反应"的根因。
        startedAtMs = SystemClock.uptimeMillis()
        acceptInputFromMs = startedAtMs + 350L
        paused = false
        lastUpAtMs = startedAtMs
        running = true
        showChip()
        ticker = scope.launch {
            while (running) {
                val elapsed = SystemClock.uptimeMillis() - startedAtMs
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
        if (!running || paused) return

        // 启动后 350ms 的窗口只用于"不把点新建宏本身录成一步"，
        // 但手势必须照常转发给游戏 —— 否则这段时间的点击被吞、游戏没反应。
        val withinWarmup = gesture.downAtMs < acceptInputFromMs
        if (!withinWarmup) {
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
                listener.onTick(SystemClock.uptimeMillis() - startedAtMs, steps.size)
            }
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
                // 注入期间暂停/恢复也要串行：togglePause 若在锁外改 NOT_TOUCHABLE，
                // 飞行中的注入手势会被重新可触摸的录制层吃回自己身上。
                setTouchable(false)
                try {
                    // 必须给回调兜底：dispatchGesture 被接受但系统不回调时，
                    // 录制层会永久停在 NOT_TOUCHABLE，表现为"录制中但完全点不动"。
                    val dispatched = withTimeoutOrNull(duration.toLong() + TOUCH_RESTORE_SLACK_MS) {
                        service.multiGesture(pointers, duration)
                    }
                    if (dispatched == false) {
                        // 调度被拒：明确提示，不再静默丢弃
                        Log.w("AccessibilityRecorder", "multiGesture rejected")
                    }
                } finally {
                    // 暂停期间不能恢复成可触摸，否则透传会被打断
                    setTouchable(!paused)
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

    /**
     * 录制胶囊：贴右边缘 + 可拖拽吸附 + 可折叠 + 半透明。
     * 默认贴边而非顶部中央，避免压住游戏小地图/状态区；按钮间距 >=8dp 防误触。
     */
    private fun showChip() {
        if (chip != null) return
        val expanded = buildExpandedChipView()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                // 关键：区域外触摸透传给游戏，胶囊不该吞掉边界外的技能点击
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        // 默认贴右边缘、避开顶部状态栏与底部技能区
        val (screenW, _) = DisplaySize.current(context)
        expanded.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        chipX = (screenW - expanded.measuredWidth - dp(8f)).coerceAtLeast(0)
        chipY = dp(96f)
        params.x = chipX
        params.y = chipY
        params.width = WindowManager.LayoutParams.WRAP_CONTENT
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        bindChipDrag(expanded, params)
        val result = runCatching { windowManager.addView(expanded, params) }
        if (result.isSuccess) {
            chip = expanded
            chipParams = params
            applyChipAlpha()
        } else {
            Log.w("AccessibilityRecorder", "chip addView failed: ${result.exceptionOrNull()?.message}")
        }
    }

    /** 半透明：游戏中弱化遮挡，手指按住时提亮 */
    private fun applyChipAlpha(dim: Boolean = true) {
        chip?.alpha = if (dim) 0.78f else 1f
    }

    /** 折叠 / 展开胶囊，折叠态只留一个小球。remove + add 才能真正替换 View。 */
    private fun toggleChipFold() {
        val old = chip ?: return
        val params = chipParams ?: return
        // updateViewLayout 无法替换 View，必须先 remove 再 add
        runCatching { windowManager.removeView(old) }
        chipFolded = !chipFolded
        val next = if (chipFolded) buildFoldedChip() else buildExpandedChipView()
        // 折叠态宽度变小，重新贴边吸附，避免展开后超出屏幕
        next.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val (screenW, _) = DisplaySize.current(context)
        chipX = chipX.coerceIn(0, (screenW - next.measuredWidth - dp(8f)).coerceAtLeast(0))
        params.x = chipX
        params.y = chipY
        bindChipDrag(next, params)
        val add = runCatching { windowManager.addView(next, params) }
        if (add.isSuccess) {
            chip = next
            chipParams = params
            applyChipAlpha()
        } else {
            // 新 View 挂载失败：把旧的加回去，避免胶囊凭空消失
            chipFolded = !chipFolded
            chip = old
            runCatching { windowManager.addView(old, params) }
            Log.w("AccessibilityRecorder", "fold restore failed: ${add.exceptionOrNull()?.message}")
        }
    }

    private fun buildFoldedChip(): View = TextView(context).apply {
        text = "●"
        setTextColor(0xFFEF4444.toInt())
        textSize = 18f
        gravity = Gravity.CENTER
        minWidth = dp(44f)
        minHeight = dp(44f)
        isClickable = true
        tag = "record_folded"
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFF0B1220.toInt())
            setStroke(dp(1f), 0xFFEF4444.toInt())
        }
        setOnClickListener { toggleChipFold() }
    }

    /** 展开态胶囊：计时 + 暂停 + 完成 + 收起 + 取消。showChip 与折叠切换共用。 */
    private fun buildExpandedChipView(): View {
        fun chipButton(label: String, primary: Boolean, onClick: () -> Unit) = TextView(context).apply {
            text = label
            textSize = 11f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            minWidth = dp(52f)
            minHeight = dp(40f)
            setPadding(dp(10f), 0, dp(10f), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(12f).toFloat()
                setColor(if (primary) 0xFF17C9A3.toInt() else 0xFF16202E.toInt())
                setStroke(dp(1f), if (primary) 0xFF3DF5C8.toInt() else 0xFF334155.toInt())
            }
            setOnClickListener { onClick() }
        }

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            background = GradientDrawable().apply {
                cornerRadius = dp(20f).toFloat()
                setColor(0xFF0B1220.toInt())
                setStroke(dp(1f), 0xFFEF4444.toInt())
            }
            setPadding(dp(10f), dp(5f), dp(10f), dp(5f))

            addView(TextView(context).apply {
                text = "● 00:00 · 0 步"
                setTextColor(Color.WHITE)
                textSize = 11f
                maxLines = 1
                tag = "record_label"
                setPadding(0, 0, dp(8f), 0)
            })
            addView(chipButton("暂停", primary = false) { togglePause() }.apply {
                tag = "record_pause"
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(40f),
                ).apply { rightMargin = dp(8f) }
            })
            addView(chipButton("完成", primary = true) { listener.onFinishRequested() }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(40f),
                ).apply { rightMargin = dp(8f) }
            })
            addView(chipButton("收起", primary = false) { toggleChipFold() }.apply {
                tag = "record_fold"
            })
            addView(TextView(context).apply {
                text = "×"
                setTextColor(0xFFEF4444.toInt())
                textSize = 16f
                gravity = Gravity.CENTER
                minWidth = dp(40f)
                minHeight = dp(40f)
                setOnClickListener { listener.onCancelRequested() }
            })
        }
    }

    /** 胶囊拖拽 + 松手贴边吸附 */
    private fun bindChipDrag(view: View, params: WindowManager.LayoutParams) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        val slop = dp(6f)
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = params.x; startY = params.y
                    dragging = false
                    applyChipAlpha(false)
                    false // 让子按钮仍可点击
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop)) {
                        dragging = true
                    }
                    if (dragging) {
                        val (screenW, screenH) = DisplaySize.current(context)
                        params.x = (startX + dx).toInt().coerceIn(0, (screenW - params.width).coerceAtLeast(0))
                        params.y = (startY + dy).toInt().coerceIn(dp(48f), (screenH - params.height - dp(48f)).coerceAtLeast(dp(48f)))
                        chipX = params.x; chipY = params.y
                        runCatching { windowManager.updateViewLayout(v, params) }
                        true
                    } else false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    applyChipAlpha(true)
                    if (dragging) {
                        val (screenW, _) = DisplaySize.current(context)
                        // 贴左右边缘：中心过半屏则吸右，否则吸左
                        params.x = if (params.x + params.width / 2 >= screenW / 2) {
                            (screenW - params.width - dp(8f)).coerceAtLeast(0)
                        } else dp(8f)
                        chipX = params.x; chipY = params.y
                        runCatching { windowManager.updateViewLayout(v, params) }
                        true // 拖动结束不触发点击
                    } else false
                }
                else -> false
            }
        }
    }

    /**
     * 暂停 / 继续录制。
     *
     * 暂停时录制层切到 NOT_TOUCHABLE：触摸直接落到游戏，用户可以正常操作，
     * 但不会被录进宏。这是"透传开关"的实现，不需要用户退出录制。
     */
    fun togglePause(): Boolean {
        if (!running) return false
        paused = !paused
        // 触摸开关与转发串行：注入飞行中若直接改 NOT_TOUCHABLE 会打断手势
        scope.launch { forwardMutex.withLock { setTouchable(!paused) } }
        // 恢复瞬间的那次抬手会落在刚变可触摸的录制层上，忽略掉，避免多点出一步
        if (!paused) acceptInputFromMs = SystemClock.uptimeMillis() + 250L
        chip?.findViewWithTag<TextView>("record_pause")?.text = if (paused) "继续" else "暂停"
        chip?.findViewWithTag<TextView>("record_label")?.text =
            if (paused) "⏸ 已暂停 · ${steps.size} 步" else "● 录制中 · ${steps.size} 步"
        return paused
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
        if (paused) {
            label.text = "⏸ 已暂停 · $stepCount 步"
            return
        }
        val totalSeconds = (elapsedMs / 1000L).toInt()
        label.text = "● %02d:%02d · %d 步".format(totalSeconds / 60, totalSeconds % 60, stepCount)
    }

    private fun dp(value: Float): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val LONG_PRESS_MS = 450
        const val TOUCH_RESTORE_SLACK_MS = 1_500L
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

                MotionEvent.ACTION_CANCEL -> {
                    // 系统取消（如多指中某指抬起触发）不要直接丢弃已采样手势，
                    // 冲刷成一次记录+转发，否则这段操作游戏收不到也不进宏。
                    if (pointers.isNotEmpty()) {
                        pointers.values.forEach { trace -> trace.upAtMs = now }
                        val primaryId = pointers.keys.first()
                        onGesture(
                            RecordedGesture(
                                pointers = pointers.values.toList(),
                                primarySamples = pointers[primaryId]?.samples.orEmpty(),
                                downAtMs = downAtMs,
                                upAtMs = now,
                            ),
                        )
                    }
                    pointers.clear()
                }
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
