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
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import com.aixiaohuadb.App
import com.aixiaohuadb.adb.AdbClient
import com.aixiaohuadb.adb.AdbPhase
import com.aixiaohuadb.adb.AdbTouchRecorder
import com.aixiaohuadb.exec.ExecStep
import com.aixiaohuadb.inference.api.ControlWord
import com.aixiaohuadb.machine.VoiceEvent
import com.aixiaohuadb.machine.VoiceState
import com.aixiaohuadb.ui.SnapshotCaptureActivity
import com.aixiaohuadb.ui.MainActivity
import com.aixiaohuadb.voice.VoiceAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 统一悬浮控制中心：悬浮球（语音入口）+ 指令按钮 + 自定义宏按钮 + 游戏内调节面板 + 逐点录制备份。
 *
 * 显示规则：球全局显示（语音入口）；按钮仅在王者荣耀前台（或面板打开时预览）显示。
 * 执行：MACRO → MacroResolver（COMMAND 仅兼容反序列化，不执行）；全局 CAS 执行锁防连点；
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
    /** 每次移除/重建面板自增，阻止旧 View.post 回调更新新面板或已移除的 View */
    private var panelGeneration = 0
    private var recordBarView: View? = null
    private var recordBarParams: WindowManager.LayoutParams? = null
    private var adbChipView: View? = null
    private var adbChipParams: WindowManager.LayoutParams? = null
    private var recording = false
    private var adbRecorder: AdbTouchRecorder? = null
    private var adbRecording = false

    @Volatile
    private var recordError: String = ""
    /** 每次启动递增：迟到的 onUpdate/onError 不得覆盖新一轮录制或命名窗 */
    private var recordingSession = 0
    private var namingView: View? = null
    private var namingParams: WindowManager.LayoutParams? = null
    private var confirmView: View? = null
    private var confirmParams: WindowManager.LayoutParams? = null

    // 状态缓存
    private var widgets: List<OverlayWidget> = emptyList()
    private var ballCfg = BallConfig()
    private var gameVisible = false
    private var controlMode = ControlMode.DEFAULT
    private var voiceEnabled = false
    private var panelOpen = false
    private var selectedTargetId: String? = null

    private data class Quad(
        val widgets: List<OverlayWidget>,
        val ball: BallConfig,
        val game: Boolean,
        val mode: ControlMode,
        val voiceEnabled: Boolean,
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
                (application as App).container.voiceLifecycle.voiceEnabled,
            ) { w, b, g, m, v -> Quad(w, b, g, m, v) }.collect { (w, b, g, m, v) ->
                widgets = w
                ballCfg = b
                gameVisible = g
                voiceEnabled = v
                val modeChanged = controlMode != m
                controlMode = m
                reconcile()
                // 模式变化且面板打开时重建面板，实时反映状态/模式徽标
                if (modeChanged && panelOpen) buildPanel()
            }
        }
        // 标注页把步骤写回 pendingMacro 后，底部录制条要立刻更新计数并启用「完成」，
        // 否则用户标注完仍看到 0 步、完成按钮不可点。
        serviceScope?.launch {
            OverlayStore.pendingMacro.collect { macro ->
                recordBarView?.let { bar ->
                    OverlayViews.updateRecordBar(bar, macro?.steps?.size ?: 0, false)
                }
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            MacroRecordNotification.ACTION_FINISH_RECORD -> finishAdbRecording()
            MacroRecordNotification.ACTION_CANCEL_RECORD -> cancelAdbRecording()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        recordingSession++
        runCatching { adbRecorder?.stop() }
        adbRecorder = null
        adbRecording = false
        MacroRecordNotification.hide(this)
        serviceScope?.cancel()
        executing.set(false)
        panelGeneration++
        listOfNotNull(ballPair?.first).forEach { runCatching { windowManager.removeView(it) } }
        buttonViews.values.forEach { (v, _) -> runCatching { windowManager.removeView(v) } }
        buttonViews.clear()
        listOfNotNull(panelHolder?.root, namingView, recordBarView, adbChipView, confirmView).forEach {
            runCatching { windowManager.removeView(it) }
        }
        ballPair = null
        panelHolder = null
        namingView = null
        recordBarView = null
        adbChipView = null
        confirmView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------- 统一渲染 ----------------

    private fun reconcile() {
        // 悬浮球：全局显示
        if (ballCfg.visible && ballPair == null) addBall()
        if (!ballCfg.visible && ballPair != null) removeBall()
        ballPair?.let { (view, params) ->
            OverlayViews.applyBallStyle(this, view, params, ballCfg, controlMode, voiceEnabled)
            val (x, y) = OverlayGestures.clampPosition(
                params.x, params.y, params.width, params.height,
                resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels,
            )
            params.x = x; params.y = y
            runCatching { windowManager.updateViewLayout(view, params) }
        }

        // 按钮常驻：关闭面板、非游戏前台时不消失（参考自动连招工具的快捷键行为）
        val shown = if (keepButtonsWhenPanelClosed) widgets.filter { it.enabled } else
            if (gameVisible || panelOpen) widgets.filter { it.enabled } else emptyList()
        buttonViews.keys.toList().forEach { id ->
            val cfg = shown.firstOrNull { it.id == id }
            if (cfg == null) removeButton(id) else updateButton(cfg)
        }
        shown.forEach { w -> if (!buttonViews.containsKey(w.id)) addButton(w) }
    }

    private fun addBall() {
        val view = OverlayViews.createBall(this)
        val params = baseParams()
        OverlayViews.applyBallStyle(this, view, params, ballCfg, controlMode, voiceEnabled)
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
                // 双击快捷暂停/恢复语音：生命周期 DISABLED 后一律不响应
                val lifecycle = (application as App).container.voiceLifecycle
                if (lifecycle.isVoiceActionAllowed(VoiceAction.PAUSE_RESUME_VIA_BALL)) {
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
            // 固定指令已删除；旧 COMMAND 控件只反序列化兼容，点击一律拒绝
            WidgetKind.COMMAND -> null
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
        // CAS 必须在 serviceScope 内做：scope 已取消时直接放弃，避免锁泄漏到服务销毁后
        val scope = serviceScope ?: return
        scope.launch {
            if (!executing.compareAndSet(false, true)) return@launch
            try {
                // 手动回放走独立执行链：语音关闭后的取消令牌不得影响面板宏按钮
                val outcome = runCatching { container.executeManual(action) }
                val ok = outcome.getOrNull()?.success == true
                container.telemetry.event("exec", "overlay_${if (ok) "ok" else "fail"}_${w.id}")
                flashResult(w, view, ok)
            } finally {
                executing.set(false)
            }
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
        val generation = panelGeneration
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
            controlMode, buildStatusText(), voicePaused, voiceEnabled,
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
                onVoiceBlocked = { showVoiceConfirm() },
                onPauseVoice = {
                    val lifecycle = (application as App).container.voiceLifecycle
                    if (lifecycle.isVoiceActionAllowed(VoiceAction.PAUSE_RESUME_VIA_BALL)) {
                        (application as App).container.stateMachine.send(
                            VoiceEvent.ControlDetected(ControlWord.C3_PAUSE, 1f),
                        )
                    }
                },
                onResumeVoice = {
                    val lifecycle = (application as App).container.voiceLifecycle
                    if (lifecycle.isVoiceActionAllowed(VoiceAction.PAUSE_RESUME_VIA_BALL)) {
                        serviceScope?.launch { lifecycle.resume() }
                    }
                },
                onStartRecord = { startRecording() },
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
        val root = panelHolder?.root ?: return
        val params = panelParams ?: return
        runCatching { windowManager.addView(root, params) }
        // 测量真实高度后把面板垂直居中于球，并夹在屏幕内。
        // 捕获 root/params/generation：快速切换模式会重建面板，旧回调不得操作新 View。
        root.post {
            if (generation != panelGeneration || panelHolder?.root !== root) return@post
            val h = root.height
            if (h <= 0) return@post
            params.y = (ballCy - h / 2).coerceIn(dp(8f), (screen.heightPixels - h - dp(8f)).coerceAtLeast(dp(8f)))
            runCatching { windowManager.updateViewLayout(root, params) }
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
            VoiceState.DISABLED -> "语音已关闭"
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
        panelGeneration++
        panelHolder?.root?.let { runCatching { windowManager.removeView(it) } }
        panelHolder = null
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    // ---------------- 录制宏 ----------------

    /**
     * 首选 ADB 被动监听：不添加任何可触摸覆盖层，游戏收到原始点击，我们只读内核事件。
     * ADB 不可用或 getevent 失败时回落到底部录制条 + 截图标注方案，并明确告知原因。
     */
    private fun startRecording() {
        val client: AdbClient = (application as App).container.adbClient
        if (client.status.value.phase != AdbPhase.CONNECTED) {
            Toast.makeText(
                this,
                "未连接 ADB：无法读取真实触摸，改为手动截屏标注坐标（不是录制）",
                Toast.LENGTH_LONG,
            ).show()
            startSnapshotCapture()
            return
        }
        if (adbRecording) return
        closePanel()
        val scope = serviceScope ?: return
        val session = ++recordingSession
        recordError = ""
        val recorder = AdbTouchRecorder(client, scope)
        // 读循环可能在 start() 返回前极早失败（getevent 权限不足的典型场景）：
        // 该标志确保"安装 recorder"与"回落"互斥，避免把已死的 recorder 装进录制态。
        val errorSignalled = AtomicBoolean(false)
        scope.launch(Dispatchers.IO) {
            val started = try {
                recorder.start(
                    onUpdate = { steps -> scope.launch { if (session == recordingSession) onRecordUpdate(recorder, steps) } },
                    onError = { message ->
                        recordError = message
                        errorSignalled.set(true)
                        scope.launch { if (session == recordingSession) onRecordError(recorder) }
                    },
                )
            } catch (t: Throwable) {
                recordError = t.message.orEmpty()
                false
            }
            withContext(Dispatchers.Main.immediate) {
                if (!started || session != recordingSession) {
                    if (started) runCatching { recorder.stop() }
                    return@withContext
                }
                if (errorSignalled.get()) {
                    // 早失败时 onError 的 Main 块因 recorder 未安装而 no-op，这里负责收尾
                    runCatching { recorder.stop() }
                    return@withContext
                }
                adbRecorder = recorder
                adbRecording = true
                OverlayStore.discardPendingMacro()
                showAdbChip()
                // 通知是可选入口：被用户关闭时屏幕胶囊仍能完成/取消
                if (NotificationManagerCompat.from(this@OverlayControlService).areNotificationsEnabled()) {
                    MacroRecordNotification.show(this@OverlayControlService, 0)
                }
            }
            if (!started || errorSignalled.get()) {
                withContext(Dispatchers.Main.immediate) {
                    val reason = recordError.ifBlank { "getevent 不可用" }
                    Toast.makeText(
                        this@OverlayControlService,
                        "被动录制不可用：$reason；改为手动截屏标注",
                        Toast.LENGTH_LONG,
                    ).show()
                    startSnapshotCapture()
                }
            }
        }
    }

    private fun onRecordUpdate(recorder: AdbTouchRecorder, steps: List<ExecStep>) {
        if (!adbRecording || adbRecorder !== recorder || steps.isEmpty()) return
        OverlayStore.stagePendingMacro(steps)
        adbChipView?.let { OverlayViews.updateAdbRecordChip(it, steps.size) }
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            MacroRecordNotification.show(this, steps.size)
        }
    }

    private fun onRecordError(recorder: AdbTouchRecorder) {
        // A synchronous start failure has adbRecorder==null; the !started branch above owns fallback then.
        if (!adbRecording || adbRecorder !== recorder) return
        runCatching { adbRecorder?.stop() }
        adbRecorder = null
        adbRecording = false
        MacroRecordNotification.hide(this)
        hideAdbChip()
        Toast.makeText(
            this,
            "被动录制中断：${recordError.ifBlank { "getevent 结束" }}；改为手动截屏标注",
            Toast.LENGTH_LONG,
        ).show()
        startSnapshotCapture()
    }

    private fun finishAdbRecording() {
        MacroRecordNotification.hide(this)
        hideAdbChip()
        recordingSession++
        if (!adbRecording) return
        val steps: List<ExecStep> = adbRecorder?.stop().orEmpty()
        adbRecording = false
        adbRecorder = null
        if (steps.isNotEmpty()) {
            OverlayStore.stagePendingMacro(steps)
            showNamingWindow()
        } else {
            OverlayStore.discardPendingMacro()
        }
    }

    private fun cancelAdbRecording() {
        MacroRecordNotification.hide(this)
        hideAdbChip()
        recordingSession++
        if (!adbRecording) return
        adbRecorder?.stop()
        adbRecording = false
        adbRecorder = null
        OverlayStore.discardPendingMacro()
    }

    private fun showAdbChip() {
        if (adbChipView != null) return
        val chip = OverlayViews.createAdbRecordChip(this, OverlayViews.AdbRecordChipCallbacks(
            onFinish = { finishAdbRecording() },
            onCancel = { cancelAdbRecording() },
        ))
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            dp(44f),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(44f)
        }
        runCatching { windowManager.addView(chip, params) }
        adbChipView = chip
        adbChipParams = params
    }

    private fun hideAdbChip() {
        adbChipView?.let { runCatching { windowManager.removeView(it) } }
        adbChipView = null
        adbChipParams = null
    }

    /**
     * 回落方案：游戏内只保留底部 64dp 录制条；顶部 25% 零覆盖，录制条以外区域全部透传给游戏。
     * 所有坐标点一律来自 MediaProjection 单帧截图 → 应用内 SnapshotAnnotationScreen 标注，
     * 不存在任何覆盖游戏区域、消费触摸的拾取层。
     */
    private fun startSnapshotCapture() {
        if (recordBarView != null) return
        closePanel()
        recording = true
        refreshPendingCount()
        val screen = resources.displayMetrics
        val bar = OverlayViews.createRecordBar(this, OverlayViews.RecordBarCallbacks(
            onToggleRecord = { SnapshotCaptureActivity.start(this) },
            onUndo = {
                OverlayStore.discardPendingMacro()
                refreshPendingCount()
            },
            onFinish = {
                if (OverlayStore.pendingMacro.value?.steps?.isNotEmpty() == true) {
                    stopCapture()
                    showNamingWindow()
                } else {
                    SnapshotCaptureActivity.start(this)
                }
            },
            onCancel = { cancelRecording() },
            onCollapsedChange = { collapsed ->
                recordBarParams?.let { params ->
                    params.width = if (collapsed) {
                        dp(148f)
                    } else {
                        minOf(dp(380f), screen.widthPixels - dp(24f)).coerceAtLeast(dp(200f))
                    }
                    params.height = dp(64f)
                    recordBarView?.let { view ->
                        runCatching { windowManager.updateViewLayout(view, params) }
                    }
                }
            },
        ))
        val width = minOf(dp(380f), screen.widthPixels - dp(24f)).coerceAtLeast(dp(200f))
        recordBarParams = WindowManager.LayoutParams(
            width, dp(64f),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // gravity=BOTTOM 时正的 y 会把条推出屏幕，必须用负偏移/底边距
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = -dp(20f)
        }
        runCatching { windowManager.addView(bar, recordBarParams) }
        recordBarView = bar
        refreshPendingCount()
    }

    /** 标注页保存后回到游戏时，计数以 pendingMacro 为准 */
    private fun refreshPendingCount() {
        val count = OverlayStore.pendingMacro.value?.steps?.size ?: 0
        recordBarView?.let { OverlayViews.updateRecordBar(it, count, false) }
    }

    private fun cancelRecording() {
        recording = false
        OverlayStore.discardPendingMacro()
        stopCapture()
    }

    private fun stopCapture() {
        recording = false
        recordBarView?.let { runCatching { windowManager.removeView(it) } }
        recordBarView = null
        recordBarParams = null
    }

    /** 面板切语音模式：未开启时弹确认，enable 成功才真正切 mode */
    private fun showVoiceConfirm() {
        if (confirmView != null) return
        val view = OverlayViews.createVoiceConfirmWindow(
            this,
            onConfirm = {
                closeVoiceConfirm()
                serviceScope?.launch {
                    val result = (application as App).container.voiceLifecycle.enable(
                        com.aixiaohuadb.voice.VoiceStartReason.OVERLAY_SWITCH,
                    )
                    if (result.isSuccess) OverlayStore.setControlMode(ControlMode.VOICE)
                }
            },
            onCancel = { closeVoiceConfirm() },
        )
        confirmParams = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.86f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            0,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.CENTER }
        confirmView = view
        runCatching { windowManager.addView(view, confirmParams) }
    }

    private fun closeVoiceConfirm() {
        confirmView?.let { runCatching { windowManager.removeView(it) } }
        confirmView = null
        confirmParams = null
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
        /** 宏按钮在游戏中常驻：面板关闭后仍可点击回放（当前为固定行为，无 UI 开关） */
        const val keepButtonsWhenPanelClosed = true

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
