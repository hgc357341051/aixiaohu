package com.aixiaohuadb.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.aixiaohuadb.App
import com.aixiaohuadb.exec.CommandLibrary
import com.aixiaohuadb.exec.ExecStep
import com.aixiaohuadb.inference.api.ControlWord
import com.aixiaohuadb.machine.VoiceEvent
import com.aixiaohuadb.machine.VoiceState
import com.aixiaohuadb.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 统一悬浮控制中心：悬浮球（语音入口）+ 指令按钮 + 自定义宏按钮 + 游戏内调节面板 + 逐点录制备份。
 *
 * 显示规则：球全局显示（语音入口）；按钮仅在王者荣耀前台（或面板打开时预览）显示。
 * 执行：COMMAND → CommandLibrary.actionFor；MACRO → MacroResolver；全局 CAS 执行锁防连点；
 * 反馈用按钮自身闪色（绿成功 / 红失败）替代 Toast。
 */
class OverlayControlService : Service() {
    private lateinit var windowManager: WindowManager
    private var serviceScope: CoroutineScope? = null
    private val executing = AtomicBoolean(false)

    // 视图集合
    private var ballPair: Pair<TextView, WindowManager.LayoutParams>? = null
    private val buttonViews = mutableMapOf<String, Pair<TextView, WindowManager.LayoutParams>>()
    private var panelHolder: OverlayViews.PanelHolder? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var captureView: FrameLayout? = null
    private var captureParams: WindowManager.LayoutParams? = null
    private var namingView: View? = null
    private var namingParams: WindowManager.LayoutParams? = null

    // 状态缓存
    private var widgets: List<OverlayWidget> = emptyList()
    private var ballCfg = BallConfig()
    private var gameVisible = false
    private var controlMode = ControlMode.VOICE
    private var panelOpen = false
    private var selectedTargetId: String? = null

    // 录制
    private class RecordedPoint(val x: Int, val y: Int, val gapMs: Long)
    private val recordedPoints = mutableListOf<RecordedPoint>()
    private var lastPointAt = 0L

    private data class Quad(
        val widgets: List<OverlayWidget>,
        val ball: BallConfig,
        val game: Boolean,
        val mode: ControlMode,
    )

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        OverlayStore.init(applicationContext)
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        serviceScope?.launch {
            combine(
                OverlayStore.widgets,
                OverlayStore.ball,
                ForegroundGameMonitor.gameForeground,
                OverlayStore.controlMode,
            ) { w, b, g, m -> Quad(w, b, g, m) }.collect { (w, b, g, m) ->
                widgets = w
                ballCfg = b
                gameVisible = g
                val modeChanged = controlMode != m
                controlMode = m
                reconcile()
                // 模式变化且面板打开时重建面板，实时反映状态/模式徽标
                if (modeChanged && panelOpen) buildPanel()
            }
        }
        // 前台兜底轮询：无障碍事件直达为主通道，500ms 轮询保证无障碍未开启时也能切换
        serviceScope?.launch {
            while (isActive) {
                ForegroundGameMonitor.publish(ForegroundGameMonitor.isGameForeground(applicationContext))
                delay(500)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        serviceScope?.cancel()
        listOfNotNull(ballPair?.first).forEach { runCatching { windowManager.removeView(it) } }
        buttonViews.values.forEach { (v, _) -> runCatching { windowManager.removeView(v) } }
        buttonViews.clear()
        listOfNotNull(panelHolder?.root, captureView, namingView).forEach {
            runCatching { windowManager.removeView(it) }
        }
        ballPair = null
        panelHolder = null
        captureView = null
        namingView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------- 统一渲染 ----------------

    private fun reconcile() {
        // 悬浮球：全局显示
        if (ballCfg.visible && ballPair == null) addBall()
        if (!ballCfg.visible && ballPair != null) removeBall()
        ballPair?.let { (view, params) ->
            OverlayViews.applyBallStyle(this, view, params, ballCfg, controlMode)
            val (x, y) = OverlayGestures.clampPosition(
                params.x, params.y, params.width, params.height,
                resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels,
            )
            params.x = x; params.y = y
            runCatching { windowManager.updateViewLayout(view, params) }
        }

        // 按钮：游戏前台或面板预览时显示
        val shown = if (gameVisible || panelOpen) widgets.filter { it.enabled } else emptyList()
        buttonViews.keys.toList().forEach { id ->
            val cfg = shown.firstOrNull { it.id == id }
            if (cfg == null) removeButton(id) else updateButton(cfg)
        }
        shown.forEach { w -> if (!buttonViews.containsKey(w.id)) addButton(w) }
    }

    private fun addBall() {
        val view = OverlayViews.createBall(this)
        val params = baseParams()
        OverlayViews.applyBallStyle(this, view, params, ballCfg, controlMode)
        val screen = resources.displayMetrics
        params.x = (ballCfg.fx * (screen.widthPixels - params.width).coerceAtLeast(0)).toInt()
        params.y = (ballCfg.fy * (screen.heightPixels - params.height).coerceAtLeast(0)).toInt()
        bindBallGestures(view, params)
        runCatching { windowManager.addView(view, params) }
        ballPair = view to params
    }

    private fun removeBall() {
        ballPair?.let { (v, _) -> runCatching { windowManager.removeView(v) } }
        ballPair = null
    }

    private fun addButton(w: OverlayWidget) {
        val view = OverlayViews.createButton(this)
        val params = baseParams()
        OverlayViews.applyButtonStyle(this, view, params, w)
        val screen = resources.displayMetrics
        params.x = (w.fx * (screen.widthPixels - params.width).coerceAtLeast(0)).toInt()
        params.y = (w.fy * (screen.heightPixels - params.height).coerceAtLeast(0)).toInt()
        bindButtonGestures(view, params, w.id)
        runCatching { windowManager.addView(view, params) }
        buttonViews[w.id] = view to params
    }

    private fun updateButton(w: OverlayWidget) {
        val pair = buttonViews[w.id] ?: return
        OverlayViews.applyButtonStyle(this, pair.first, pair.second, w)
        val (x, y) = OverlayGestures.clampPosition(
            pair.second.x, pair.second.y, pair.second.width, pair.second.height,
            resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels,
        )
        pair.second.x = x; pair.second.y = y
        runCatching { windowManager.updateViewLayout(pair.first, pair.second) }
    }

    private fun removeButton(id: String) {
        buttonViews.remove(id)?.let { (v, _) -> runCatching { windowManager.removeView(v) } }
    }

    private fun baseParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    // ---------------- 悬浮球手势 ----------------

    private fun bindBallGestures(view: TextView, params: WindowManager.LayoutParams) {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // 单击直接展开操作面板（用户核心诉求），面板内含语音/手动操作入口
                openPanel()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                // 双击快捷暂停/恢复语音，仅在语音模式有意义；手动模式由模式开关统一管理语音
                if (controlMode == ControlMode.VOICE) {
                    (application as App).container.stateMachine.send(
                        VoiceEvent.ControlDetected(ControlWord.C3_PAUSE, 1f),
                    )
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                // 长按同样是面板入口，兼容从旧版本迁移过来的操作习惯
                openPanel()
            }
        })
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        view.setOnTouchListener { _, e ->
            detector.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX; downRawY = e.rawY
                    startX = params.x; startY = params.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!ballCfg.locked &&
                        OverlayGestures.exceededSlop(e.rawX - downRawX, e.rawY - downRawY, slop.toFloat())
                    ) {
                        if (!dragging) {
                            dragging = true
                            OverlayGestures.lift(view)
                        }
                        params.x = startX + (e.rawX - downRawX).toInt()
                        params.y = startY + (e.rawY - downRawY).toInt()
                        val (cx, cy) = OverlayGestures.clampPosition(
                            params.x, params.y, params.width, params.height,
                            resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels,
                        )
                        params.x = cx; params.y = cy
                        runCatching { windowManager.updateViewLayout(view, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging && e.actionMasked == MotionEvent.ACTION_UP) {
                        val targetX = OverlayGestures.snapEdgeX(
                            params.x, params.width, resources.displayMetrics.widthPixels,
                        )
                        OverlayGestures.settle(view, params.x, targetX, { nx ->
                            params.x = nx
                            runCatching { windowManager.updateViewLayout(view, params) }
                        }, {
                            saveBallPosition(params)
                        })
                    }
                    dragging = false
                    true
                }
                else -> true
            }
        }
    }


    // ---------------- 按钮手势 ----------------

    private fun bindButtonGestures(view: TextView, params: WindowManager.LayoutParams, widgetId: String) {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        view.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX; downRawY = e.rawY
                    startX = params.x; startY = params.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isWidgetLocked(widgetId).not() &&
                        OverlayGestures.exceededSlop(e.rawX - downRawX, e.rawY - downRawY, slop.toFloat())
                    ) {
                        if (!dragging) {
                            dragging = true
                            OverlayGestures.lift(view)
                        }
                        params.x = startX + (e.rawX - downRawX).toInt()
                        params.y = startY + (e.rawY - downRawY).toInt()
                        val (cx, cy) = OverlayGestures.clampPosition(
                            params.x, params.y, params.width, params.height,
                            resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels,
                        )
                        params.x = cx; params.y = cy
                        runCatching { windowManager.updateViewLayout(view, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (e.actionMasked == MotionEvent.ACTION_UP) {
                        if (dragging) {
                            val targetX = OverlayGestures.snapEdgeX(
                                params.x, params.width, resources.displayMetrics.widthPixels,
                            )
                            OverlayGestures.settle(view, params.x, targetX, { nx ->
                                params.x = nx
                                runCatching { windowManager.updateViewLayout(view, params) }
                            }, {
                                saveWidgetPosition(widgetId, params)
                            })
                        } else {
                            widgets.firstOrNull { it.id == widgetId }?.let { execute(it, view) }
                        }
                    }
                    dragging = false
                    true
                }
                else -> true
            }
        }
    }

    private fun isWidgetLocked(widgetId: String): Boolean =
        widgets.firstOrNull { it.id == widgetId }?.locked == true

    // ---------------- 执行与反馈 ----------------

    private fun execute(w: OverlayWidget, view: TextView) {
        val container = (application as App).container
        val action = when (w.kind) {
            WidgetKind.COMMAND -> if (w.commandId in 0..13) CommandLibrary.actionFor(w.commandId) else null
            WidgetKind.MACRO -> OverlayStore.macroByWidgetId(w.id)?.let { MacroResolver.buildCommandAction(it) }
        }
        if (action == null || action.steps.isEmpty()) {
            flashResult(w, view, false)
            return
        }
        if (container.stateMachine.currentState() == VoiceState.EXECUTE) {
            flashResult(w, view, false)
            return
        }
        if (!executing.compareAndSet(false, true)) return
        serviceScope?.launch {
            val outcome = runCatching {
                withTimeout(10_000L) {
                    withContext(Dispatchers.IO) { container.executorChain.execute(action) }
                }
            }
            executing.set(false)
            val ok = outcome.getOrNull()?.success == true
            container.telemetry.event("exec", "overlay_${if (ok) "ok" else "fail"}_${w.id}")
            flashResult(w, view, ok)
        }
    }

    /** 按钮自身闪色替代 Toast：绿=成功，红=失败，600ms 后还原 */
    private fun flashResult(w: OverlayWidget, view: TextView, ok: Boolean) {
        OverlayViews.flash(view, ok, this, w.label.length, w.sizeDp)
        serviceScope?.launch {
            delay(600)
            val current = widgets.firstOrNull { it.id == w.id } ?: return@launch
            buttonViews[w.id]?.let { (v, p) ->
                OverlayViews.applyButtonStyle(this@OverlayControlService, v, p, current)
            }
        }
    }

    // ---------------- 游戏内调节面板 ----------------

    private fun openPanel() {
        if (panelOpen) {
            closePanel()
            return
        }
        panelOpen = true
        selectedTargetId = null
        buildPanel()
        reconcile()
    }

    private fun buildPanel() {
        removePanelView()
        val targets = buildList {
            add(null to "悬浮球")
            widgets.filter { it.enabled }.forEach { add(it.id to it.label) }
        }
        val isBall = selectedTargetId == null
        val sizeDp: Int
        val alpha: Int
        val locked: Boolean
        if (isBall) {
            sizeDp = ballCfg.sizeDp; alpha = ballCfg.alphaPercent; locked = ballCfg.locked
        } else {
            val w = widgets.firstOrNull { it.id == selectedTargetId } ?: return
            sizeDp = w.sizeDp; alpha = w.alphaPercent; locked = w.locked
        }
        val machine = (application as App).container.stateMachine
        val voicePaused = when (machine.currentState()) {
            VoiceState.PAUSED, VoiceState.MANUAL_SILENT, VoiceState.RECOG_OFF, VoiceState.ERROR -> true
            else -> false
        }
        panelHolder = OverlayViews.createPanel(
            this, targets, selectedTargetId, sizeDp, alpha, locked, ballCfg.visible,
            controlMode, buildStatusText(), voicePaused,
            OverlayViews.PanelCallbacks(
                onSelectTarget = { id ->
                    selectedTargetId = id
                    buildPanel()
                },
                onSizeChange = { v ->
                    panelHolder?.sizeLabel?.text = "大小 ${v}dp"
                    if (isBall) {
                        OverlayStore.updateBallLive(v, ballCfg.alphaPercent)
                    } else {
                        selectedTargetId?.let { id ->
                            widgets.firstOrNull { it.id == id }?.let {
                                OverlayStore.updateWidgetLive(id, v, it.alphaPercent)
                            }
                        }
                    }
                },
                onAlphaChange = { v ->
                    panelHolder?.alphaLabel?.text = "不透明度 $v%"
                    if (isBall) {
                        OverlayStore.updateBallLive(ballCfg.sizeDp, v)
                    } else {
                        selectedTargetId?.let { id ->
                            widgets.firstOrNull { it.id == id }?.let {
                                OverlayStore.updateWidgetLive(id, it.sizeDp, v)
                            }
                        }
                    }
                },
                onLockChange = { c ->
                    if (isBall) OverlayStore.setBallLocked(c)
                    else selectedTargetId?.let { OverlayStore.setWidgetLocked(it, c) }
                },
                onBallVisibleChange = { c -> OverlayStore.setBallVisible(c) },
                onModeChange = { mode -> OverlayStore.setControlMode(mode) },
                onPauseVoice = {
                    (application as App).container.stateMachine.send(
                        VoiceEvent.ControlDetected(ControlWord.C3_PAUSE, 1f),
                    )
                },
                onResumeVoice = {
                    (application as App).container.stateMachine.send(VoiceEvent.ManualResume)
                },
                onStartRecord = { startCapture() },
                onOpenSettings = {
                    closePanel()
                    runCatching {
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
                onClose = { closePanel() },
            ),
        )
        // 相对悬浮球定位：球在左半屏则面板放其右侧，反之放左侧；垂直以球为中心并夹在屏内
        val panelWidth = dp(264f)
        val screen = resources.displayMetrics
        val ballRect = ballPair?.second
        val ballCx = if (ballRect != null) ballRect.x + ballRect.width / 2 else screen.widthPixels / 2
        val ballCy = if (ballRect != null) ballRect.y + ballRect.height / 2 else screen.heightPixels / 2
        val gap = dp(12f)
        var px = if (ballCx < screen.widthPixels / 2) ballCx + gap else ballCx - gap - panelWidth
        px = px.coerceIn(dp(8f), (screen.widthPixels - panelWidth - dp(8f)).coerceAtLeast(dp(8f)))
        // 高度未知，先放左上角再测量后修正 y
        panelParams = WindowManager.LayoutParams(
            panelWidth, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = px
            y = (ballCy - dp(180f)).coerceAtLeast(dp(8f))
        }
        runCatching { windowManager.addView(panelHolder!!.root, panelParams) }
        // 测量真实高度后把面板垂直居中于球，并夹在屏幕内
        panelHolder?.root?.post {
            val h = panelHolder?.root?.height ?: return@post
            val p = panelParams ?: return@post
            p.y = (ballCy - h / 2).coerceIn(dp(8f), (screen.heightPixels - h - dp(8f)).coerceAtLeast(dp(8f)))
            runCatching { windowManager.updateViewLayout(panelHolder?.root ?: return@post, p) }
        }
    }

    /** 面板顶部真实状态：控制模式 / 语音状态 / 前台游戏 / ADB 执行链 */
    private fun buildStatusText(): String {
        val container = (application as App).container
        val voiceLabel = when (container.stateMachine.currentState()) {
            VoiceState.MANUAL_SILENT -> "语音已停"
            VoiceState.PAUSED -> "语音暂停"
            VoiceState.RECOG_OFF -> "识别关闭"
            VoiceState.LISTEN, VoiceState.VERIFY -> "监听中"
            VoiceState.CMD_WINDOW -> "指令窗"
            VoiceState.EXECUTE -> "执行中"
            VoiceState.COOLDOWN -> "冷却中"
            VoiceState.IDLE, VoiceState.ERROR, VoiceState.UNLOAD_WAKE -> "待机"
        }
        val gameLabel = if (gameVisible) "王者荣耀" else "非游戏"
        val adbLabel = if (container.adbClient.status.value.phase == com.aixiaohuadb.adb.AdbPhase.CONNECTED) "ADB" else "无障碍"
        return "$voiceLabel · $gameLabel · $adbLabel"
    }

    private fun closePanel() {
        removePanelView()
        panelOpen = false
        reconcile()
    }

    private fun removePanelView() {
        panelHolder?.root?.let { runCatching { windowManager.removeView(it) } }
        panelHolder = null
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    // ---------------- 录制宏 ----------------

    private fun startCapture() {
        if (captureView != null) return
        closePanel()
        recordedPoints.clear()
        lastPointAt = 0L
        captureView = OverlayViews.createCaptureLayer(
            this, 0,
            OverlayViews.CaptureCallbacks(
                onPoint = { x, y -> recordPoint(x, y) },
                onFinish = { finishRecording() },
                onCancel = { stopCapture() },
            ),
        )
        captureParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        )
        runCatching { windowManager.addView(captureView, captureParams) }
    }

    private fun recordPoint(x: Int, y: Int) {
        val now = System.currentTimeMillis()
        val gap = if (recordedPoints.isEmpty()) 0L else (now - lastPointAt).coerceIn(0L, 10_000L)
        lastPointAt = now
        recordedPoints.add(RecordedPoint(x, y, gap))
        captureView?.let { OverlayViews.addMarker(this, it, x, y) }
        captureView?.findViewWithTag<TextView>("capture_hint")?.text =
            "录制中（已标 ${recordedPoints.size} 点）：依次点击要录制的位置，点击不会传给游戏"
    }

    private fun finishRecording() {
        if (recordedPoints.isEmpty()) {
            stopCapture()
            return
        }
        val mapper = (application as App).container.coordinateMapper
        val screen = resources.displayMetrics
        val steps = buildList {
            recordedPoints.forEachIndexed { i, p ->
                if (i > 0 && p.gapMs > 0) add(ExecStep.Wait(p.gapMs))
                val (bx, by) = mapper.mapToBase(p.x, p.y, screen.widthPixels, screen.heightPixels)
                add(ExecStep.Tap(bx, by))
            }
        }
        OverlayStore.stagePendingMacro(steps)
        stopCapture()
        showNamingWindow()
    }

    private fun stopCapture() {
        captureView?.let { runCatching { windowManager.removeView(it) } }
        captureView = null
        captureParams = null
    }

    private fun showNamingWindow() {
        if (namingView != null) return
        val view = OverlayViews.createNamingWindow(
            this, "新宏",
            onConfirm = { name ->
                val steps = OverlayStore.pendingMacro.value?.steps ?: emptyList()
                if (steps.isNotEmpty()) OverlayStore.saveMacro(name, steps)
                else OverlayStore.discardPendingMacro()
                closeNamingWindow()
            },
            onCancel = {
                OverlayStore.discardPendingMacro()
                closeNamingWindow()
            },
        )
        namingParams = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.8f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            0,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.CENTER }
        namingView = view
        // focusable 命名窗在部分 ROM 受限时：宏暂存 pendingMacro，由设置页兜底命名
        val added = runCatching { windowManager.addView(view, namingParams) }.isSuccess
        if (!added) {
            namingView = null
            namingParams = null
        }
    }

    private fun closeNamingWindow() {
        namingView?.let { runCatching { windowManager.removeView(it) } }
        namingView = null
        namingParams = null
    }

    // ---------------- 位置回写 ----------------

    private fun saveBallPosition(params: WindowManager.LayoutParams) {
        val screen = resources.displayMetrics
        val mx = (screen.widthPixels - params.width).coerceAtLeast(1).toFloat()
        val my = (screen.heightPixels - params.height).coerceAtLeast(1).toFloat()
        OverlayStore.updateBallPosition(params.x / mx, params.y / my)
    }

    private fun saveWidgetPosition(widgetId: String, params: WindowManager.LayoutParams) {
        val screen = resources.displayMetrics
        val mx = (screen.widthPixels - params.width).coerceAtLeast(1).toFloat()
        val my = (screen.heightPixels - params.height).coerceAtLeast(1).toFloat()
        OverlayStore.updateWidgetPosition(widgetId, params.x / mx, params.y / my)
    }

    companion object {
        fun start(context: Context) {
            if (!Settings.canDrawOverlays(context)) return
            runCatching { context.startService(Intent(context, OverlayControlService::class.java)) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, OverlayControlService::class.java)) }
        }

        fun isPermissionGranted(context: Context): Boolean = Settings.canDrawOverlays(context)
    }
}
