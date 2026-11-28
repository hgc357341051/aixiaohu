package com.aixiaohuadb.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.os.IBinder
import android.provider.Settings
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.aixiaohuadb.App
import com.aixiaohuadb.R
import com.aixiaohuadb.adb.AdbClient
import com.aixiaohuadb.adb.AdbPhase
import com.aixiaohuadb.adb.AdbTouchRecorder
import com.aixiaohuadb.domain.macro.MacroAdapter
import com.aixiaohuadb.domain.macro.MacroStep
import com.aixiaohuadb.exec.DisplaySize
import com.aixiaohuadb.exec.ExecStep
import com.aixiaohuadb.machine.VoiceState
import com.aixiaohuadb.service.recorder.AccessibilityRecorder
import com.aixiaohuadb.ui.MainActivity
import com.aixiaohuadb.voice.VoiceAction
import com.aixiaohuadb.voice.VoiceLifecycleState
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
 * 显示规则：全局常驻。悬浮球与所有启用的宏按钮只由「总开关 + 悬浮窗权限 + 服务存活」决定，
 * 不读包名、不读前台三态、不随应用/游戏切换 add/remove。这样玩家一进游戏就能看到并点到按钮，
 * 不会经历"先出现一帧再被前台事件移除"的闪烁。球/按钮均以幂等对齐方式渲染，
 * 目标态未变绝不触碰 WindowManager。常驻服务用低优先级前台通知保活，
 * 避免进入游戏后进程被回收导致 onDestroy 拆掉全部悬浮件。
 * 执行：MACRO → MacroResolver（COMMAND 仅兼容反序列化，不执行）；全局 CAS 执行锁防连点；
 * 反馈用按钮自身闪色（绿成功 / 红失败）替代 Toast。
 */
class OverlayControlService : Service() {
    private lateinit var windowManager: WindowManager
    private var serviceScope: CoroutineScope? = null
    private val executing = AtomicBoolean(false)

    // 视图集合
    private var ballPair: Pair<TextView, WindowManager.LayoutParams>? = null
    /** 上一次渲染的悬浮球样式签名；相同则跳过 applyStyle/updateViewLayout，杜绝高频重绘 */
    private var ballStyleSig: String? = null
    private val buttonViews = mutableMapOf<String, Pair<TextView, WindowManager.LayoutParams>>()
    /** 每个按钮上一次的样式签名 */
    private val buttonStyleSigs = mutableMapOf<String, String>()
    private var panelHolder: OverlayViews.PanelHolder? = null
    private var panelParams: WindowManager.LayoutParams? = null
    /** 每次移除/重建面板自增，阻止旧 View.post 回调更新新面板或已移除的 View */
    private var panelGeneration = 0
    private var accessibilityRecorder: AccessibilityRecorder? = null
    private var adbChipView: View? = null
    private var adbChipParams: WindowManager.LayoutParams? = null
    private var adbRecorder: AdbTouchRecorder? = null
    private var adbRecording = false

    @Volatile
    private var recordError: String = ""
    /** 上次检测到的悬浮窗权限状态：用于检测运行中被撤销后重新授予 */
    @Volatile
    private var overlayPermissionGranted = true
    /** 每次启动递增：迟到的 onUpdate/onError 不得覆盖新一轮录制或命名窗 */
    private var recordingSession = 0
    private var namingView: View? = null
    private var namingParams: WindowManager.LayoutParams? = null
    private var confirmView: View? = null
    private var confirmParams: WindowManager.LayoutParams? = null

    // 状态缓存
    private var widgets: List<OverlayWidget> = emptyList()
    private var ballCfg = BallConfig()
    private var controlMode = ControlMode.DEFAULT
    private var voiceEnabled = false
    private var overlayEnabled = true
    private var panelOpen = false
    private var selectedTargetId: String? = null

    private data class OverlayState(
        val widgets: List<OverlayWidget>,
        val ball: BallConfig,
        val mode: ControlMode,
        val voiceEnabled: Boolean,
        val overlayEnabled: Boolean,
    )

    override fun onCreate() {
        super.onCreate()
        overlayPermissionGranted = Settings.canDrawOverlays(this)
        if (!overlayPermissionGranted) {
            stopSelf()
            return
        }
        // 前台服务保活：进游戏后系统不会把普通后台 Service 直接杀掉并在 onDestroy 里拆掉全部悬浮件。
        // 低优先级常驻通知是"全局悬浮窗稳定存在"的必要代价，用户可从通知一键关闭悬浮窗。
        createOverlayNotificationChannel()
        runCatching { startForeground(OVERLAY_NOTIFICATION_ID, buildOverlayNotification()) }
            .onFailure { Log.w("OverlayControl", "startForeground failed", it) }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        OverlayStore.init(applicationContext)
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        // 唯一渲染入口：只观察"用户配置 + 总开关"，彻底移除包名/前台三态输入。
        // 前台切换不再触发 reconcile，因此不存在"按钮被 add 一帧后立刻 remove"的窗口。
        serviceScope?.launch {
            combine(
                OverlayStore.widgets,
                OverlayStore.ball,
                OverlayStore.controlMode,
                (application as App).container.voiceLifecycle.voiceEnabled,
                OverlayStore.overlayEnabled,
            ) { w, b, m, v, enabled -> OverlayState(w, b, m, v, enabled) }
                .collect { state ->
                    widgets = state.widgets
                    ballCfg = state.ball
                    voiceEnabled = state.voiceEnabled
                    overlayEnabled = state.overlayEnabled
                    val modeChanged = controlMode != state.mode
                    controlMode = state.mode
                    reconcile()
                    if (modeChanged && panelOpen) buildPanel()
                }
        }

        // 只做权限健康检测：系统撤销悬浮窗权限时会移除所有 overlay，
        // 必须同步清掉本地视图缓存，否则重新授予后 updateViewLayout 全失败、悬浮件永远回不来。
        // 1s 一次仅调用 Settings.canDrawOverlays，无 UsageStats/无障碍事件扫描开销。
        serviceScope?.launch {
            while (isActive) {
                val granted = Settings.canDrawOverlays(this@OverlayControlService)
                if (granted != overlayPermissionGranted) {
                    overlayPermissionGranted = granted
                    if (granted) {
                        Log.d("OverlayControl", "overlay permission restored, rebuild")
                        reconcile()
                    } else {
                        Log.w("OverlayControl", "overlay permission revoked, detach views")
                        detachAllOverlayViews()
                    }
                }
                delay(1_000L)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SMART_RECORD -> startRecordingSmart()
            ACTION_START_ADB_RECORD -> startAdbRecording()
            ACTION_REFRESH -> refreshOverlay()
            ACTION_DISABLE -> {
                OverlayStore.setOverlayEnabled(false)
                stopSelf()
                return START_NOT_STICKY
            }
            MacroRecordNotification.ACTION_FINISH_RECORD -> finishAdbRecording()
            MacroRecordNotification.ACTION_CANCEL_RECORD -> cancelAdbRecording()
        }
        // STICKY + 前台通知：进程被系统回收后自动重建服务，悬浮件恢复常驻；
        // 无权限时 onCreate 会 stopSelf，不会无限重启。
        return START_STICKY
    }

    /** 供权限页授权完成后主动触发：重新校验权限并立即对齐悬浮件 */
    private fun refreshOverlay() {
        val granted = Settings.canDrawOverlays(this)
        overlayPermissionGranted = granted
        if (granted) reconcile() else detachAllOverlayViews()
    }

    /**
     * 权限失效时移除所有 overlay 并清空视图缓存，但不碰 widgets/ball 等配置。
     * 权限恢复后 reconcile 会因缓存为空而重新 addView。
     */
    private fun detachAllOverlayViews() {
        // 权限撤销时录制层/胶囊同样会被系统摘掉，必须同步停掉录制状态，
        // 否则权限恢复后表现为"没有胶囊但录制还在隐形继续"。
        stopAllRecording()
        runCatching { ballPair?.first?.let { windowManager.removeView(it) } }
        ballPair = null
        ballStyleSig = null
        buttonViews.values.forEach { (v, _) -> runCatching { windowManager.removeView(v) } }
        buttonViews.clear()
        buttonStyleSigs.clear()
        listOfNotNull(panelHolder?.root, namingView, adbChipView, confirmView).forEach {
            runCatching { windowManager.removeView(it) }
        }
        panelHolder = null
        panelOpen = false
        namingView = null
        adbChipView = null
        confirmView = null
    }

    override fun onDestroy() {
        stopAllRecording()
        serviceScope?.cancel()
        executing.set(false)
        panelGeneration++
        detachAllOverlayViews()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------- 前台保活通知 ----------------

    private fun createOverlayNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(OVERLAY_CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                OVERLAY_CHANNEL_ID,
                "悬浮控制",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "保持全局悬浮球与宏按钮在游戏内外稳定显示"
                setShowBadge(false)
            },
        )
    }

    private fun buildOverlayNotification(): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), flags,
        )
        val disable = PendingIntent.getService(
            this, 1, Intent(this, OverlayControlService::class.java).setAction(ACTION_DISABLE), flags,
        )
        return Notification.Builder(this, OVERLAY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_overlay_controller_silhouette)
            .setContentTitle("小胡 · 悬浮控制")
            .setContentText("全局悬浮球已开启，可随时展开面板")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_delete),
                    "关闭悬浮窗",
                    disable,
                ).build(),
            )
            .build()
    }

    // ---------------- 统一渲染 ----------------

    /**
     * 唯一渲染入口：把当前配置映射到"目标显示集合"，再幂等地对齐实际窗口。
     * 目标集合不再读取前台包名/应用状态；进游戏、切 App、系统弹窗都不会触发 add/remove。
     * 幂等是关键：目标态没变就不碰 WindowManager，避免滑条/重复事件触发无效 updateViewLayout。
     */
    private fun reconcile() {
        // 总开关关闭：清空所有悬浮件
        if (!overlayEnabled) {
            removeBall()
            buttonViews.keys.toList().forEach(::removeButton)
            if (panelOpen) { panelOpen = false; removePanelView() }
            return
        }

        // 悬浮球与所有启用的宏按钮全局常驻：只在配置变化、权限变化、服务重启时对齐一次。
        applyBall(ballCfg.visible)

        val buttonShown = widgets.filter { it.enabled }
        buttonViews.keys.toList().forEach { id ->
            val cfg = buttonShown.firstOrNull { it.id == id }
            if (cfg == null) removeButton(id) else updateButton(cfg)
        }
        buttonShown.forEach { w -> if (!buttonViews.containsKey(w.id)) addButton(w) }
    }

    /** 幂等应用悬浮球：样式/位置未变时完全不触碰 WindowManager */
    private fun applyBall(show: Boolean) {
        if (!show) { removeBall(); return }
        val sig = "ball|$ballCfg|$controlMode|$voiceEnabled"
        if (ballPair == null) {
            addBall()
            ballPair?.let { ballStyleSig = sig }
            return
        }
        ballPair?.let { (view, params) ->
            val styleChanged = ballStyleSig != sig
            if (styleChanged) {
                OverlayViews.applyBallStyle(this, view, params, ballCfg, controlMode, voiceEnabled)
                ballStyleSig = sig
            }
            // 位置：仅当 clamp 后确实变化（如屏幕旋转/尺寸变化）才 update，
            // 拖动经过的 updateViewLayout 由手势自己完成，这里不再重复。
            val (screenW, screenH) = screenSize()
            val (x, y) = OverlayGestures.clampPosition(
                params.x, params.y, params.width, params.height, screenW, screenH,
            )
            if (styleChanged || x != params.x || y != params.y) {
                params.x = x; params.y = y
                runCatching { windowManager.updateViewLayout(view, params) }
            }
        }
    }

    private fun addBall() {
        val view = OverlayViews.createBall(this)
        val params = baseParams()
        OverlayViews.applyBallStyle(this, view, params, ballCfg, controlMode, voiceEnabled)
        val (screenW, screenH) = screenSize()
        params.x = (ballCfg.fx * (screenW - params.width).coerceAtLeast(0)).toInt()
        params.y = (ballCfg.fy * (screenH - params.height).coerceAtLeast(0)).toInt()
        bindBallGestures(view, params)
        // addView 失败不能缓存为"已显示"：否则后续 updateViewLayout 全抛异常被吞，
        // 表现为悬浮球逻辑存在但物理不显示、点了没反应。
        val ok = runCatching { windowManager.addView(view, params) }.isSuccess
        if (ok) {
            ballPair = view to params
            ballStyleSig = "ball|$ballCfg|$controlMode|$voiceEnabled"
        } else {
            scheduleAddRetry { ballPair != null }
        }
    }

    private fun removeBall() {
        ballPair?.let { (v, _) -> runCatching { windowManager.removeView(v) } }
        ballPair = null
        ballStyleSig = null
    }

    private fun addButton(w: OverlayWidget) {
        val view = OverlayViews.createButton(this)
        val params = baseParams()
        OverlayViews.applyButtonStyle(this, view, params, w)
        val (screenW, screenH) = screenSize()
        params.x = (w.fx * (screenW - params.width).coerceAtLeast(0)).toInt()
        params.y = (w.fy * (screenH - params.height).coerceAtLeast(0)).toInt()
        bindButtonGestures(view, params, w.id)
        val ok = runCatching { windowManager.addView(view, params) }.isSuccess
        if (ok) {
            buttonViews[w.id] = view to params
            buttonStyleSigs[w.id] = buttonSig(w)
        } else {
            scheduleAddRetry { buttonViews.containsKey(w.id) }
        }
    }

    /** addView 失败后的有限退避重试：检查目标悬浮件是否已成功挂载，成功则停止 */
    private fun scheduleAddRetry(checkMounted: () -> Boolean) {
        serviceScope?.launch {
            for (delayMs in listOf(300L, 800L, 1500L)) {
                delay(delayMs)
                if (!Settings.canDrawOverlays(this@OverlayControlService)) return@launch
                if (checkMounted()) return@launch
                reconcile()
            }
        }
    }

    /** 按钮外观签名：只有这些字段变化才需要重新 applyStyle + updateViewLayout */
    private fun buttonSig(w: OverlayWidget): String =
        "btn|${w.sizeDp}|${w.alphaPercent}|${w.label}|${w.locked}|${w.enabled}"

    private fun updateButton(w: OverlayWidget) {
        val pair = buttonViews[w.id] ?: return
        val sig = buttonSig(w)
        val styleChanged = buttonStyleSigs[w.id] != sig
        if (styleChanged) {
            OverlayViews.applyButtonStyle(this, pair.first, pair.second, w)
            buttonStyleSigs[w.id] = sig
        }
        val (screenW, screenH) = screenSize()
        val (x, y) = OverlayGestures.clampPosition(
            pair.second.x, pair.second.y, pair.second.width, pair.second.height,
            screenW, screenH,
        )
        if (styleChanged || x != pair.second.x || y != pair.second.y) {
            pair.second.x = x; pair.second.y = y
            runCatching { windowManager.updateViewLayout(pair.first, pair.second) }
        }
    }

    private fun removeButton(id: String) {
        buttonViews.remove(id)?.let { (v, _) -> runCatching { windowManager.removeView(v) } }
        buttonStyleSigs.remove(id)
    }

    private fun baseParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        // NOT_TOUCH_MODAL：悬浮件区域外的触摸直接透传给游戏，避免吞掉技能点击
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
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
                // 双击快捷暂停/恢复语音：生命周期 DISABLED 后一律不响应。
                // 必须走 lifecycle.pause()/resume()，不能只发状态机事件——否则
                // lifecycle._state 停在 RUNNING，面板"恢复语音"调用 resume() 命中 else 空转。
                val lifecycle = (application as App).container.voiceLifecycle
                if (lifecycle.isVoiceActionAllowed(VoiceAction.PAUSE_RESUME_VIA_BALL)) {
                    serviceScope?.launch {
                        if (lifecycle.state.value == VoiceLifecycleState.PAUSED) lifecycle.resume()
                        else lifecycle.pause()
                    }
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
                        val (screenW, screenH) = screenSize()
                        val (cx, cy) = OverlayGestures.clampPosition(
                            params.x, params.y, params.width, params.height,
                            screenW, screenH,
                        )
                        params.x = cx; params.y = cy
                        runCatching { windowManager.updateViewLayout(view, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging && e.actionMasked == MotionEvent.ACTION_UP) {
                        val targetX = OverlayGestures.snapEdgeX(
                            params.x, params.width, screenSize().first,
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
                        val (screenW, screenH) = screenSize()
                        val (cx, cy) = OverlayGestures.clampPosition(
                            params.x, params.y, params.width, params.height,
                            screenW, screenH,
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
                                params.x, params.width, screenSize().first,
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
        // 录制中禁止回放：ADB 录制靠 getevent 被动监听，回放手势会被当作新步骤录进去；
        // 无障碍录制虽被全屏层挡住按钮，这里统一兜底，保证"录制/停止/执行"状态一致。
        if (isRecording()) {
            Toast.makeText(this, "录制中无法执行宏，请先完成或取消录制", Toast.LENGTH_SHORT).show()
            flashResult(w, view, false)
            return
        }
        val container = (application as App).container
        val action = when (w.kind) {
            // 固定指令已删除；旧 COMMAND 控件只反序列化兼容，点击一律拒绝
            WidgetKind.COMMAND -> null
            WidgetKind.MACRO -> OverlayStore.macroByWidgetId(w.id)?.let { MacroResolver.buildCommandAction(it) }
        }
        if (action == null) {
            Toast.makeText(this, "宏不存在，请重新录制", Toast.LENGTH_SHORT).show()
            flashResult(w, view, false)
            return
        }
        if (action.steps.isEmpty()) {
            Toast.makeText(this, "宏为空，请重新录制", Toast.LENGTH_SHORT).show()
            flashResult(w, view, false)
            return
        }
        if (container.stateMachine.currentState() == VoiceState.EXECUTE) {
            Toast.makeText(this, "语音指令正在执行，请稍候", Toast.LENGTH_SHORT).show()
            flashResult(w, view, false)
            return
        }
        // 全局常驻策略：不再按前台包名拒绝执行。按钮在任何界面可见即可点，
        // 误触风险由用户主动点击与执行中取消令牌承担，而不是靠"猜当前是不是游戏"。
        // ADB 链断连且无障碍也被撤回时，执行链会落到只读提示；这里提前给出可操作的引导。
        val adbConnected = container.adbClient.status.value.phase == AdbPhase.CONNECTED
        val a11yAlive = VoiceAccessibilityService.instance != null
        if (!adbConnected && !a11yAlive) {
            Toast.makeText(this, "执行链路未就绪：请连接 ADB 或开启无障碍服务", Toast.LENGTH_LONG).show()
            flashResult(w, view, false)
            return
        }
        // 旧录制器在横屏游戏里可能误用竖屏尺寸，把 x 全夹到 1080；这种宏回放必然看不到效果
        val edgeTaps = action.steps.count { it is ExecStep.Tap && it.x >= MACRO_EDGE_X }
        if (edgeTaps >= 2) {
            Toast.makeText(
                this,
                "检测到 $edgeTaps 步坐标贴在屏幕右边缘，录制时可能方向不对，建议重新录制",
                Toast.LENGTH_LONG,
            ).show()
        }
        // CAS 必须在 serviceScope 内做：scope 已取消时直接放弃，避免锁泄漏到服务销毁后
        val scope = serviceScope ?: return
        scope.launch {
            if (!executing.compareAndSet(false, true)) {
                Toast.makeText(
                    this@OverlayControlService,
                    "上一条宏还在执行，请稍候",
                    Toast.LENGTH_SHORT,
                ).show()
                return@launch
            }
            try {
                // 手动回放走独立执行链：语音关闭后的取消令牌不得影响面板宏按钮。
                // 全局常驻策略下不再按包名/前台状态中断：按钮可见即允许执行，
                // 用户取消仍由 ManualExecutionToken（面板停止/服务销毁）负责。
                val outcome = runCatching { container.executeManual(action) }
                val result = outcome.getOrNull()
                val ok = result?.success == true
                Log.d(
                    "OverlayExec",
                    "widget=${w.id} steps=${action.steps.size} ok=$ok msg=${result?.message ?: outcome.exceptionOrNull()?.message}",
                )
                if (!ok) {
                    Toast.makeText(
                        this@OverlayControlService,
                        "宏执行失败：${result?.message ?: outcome.exceptionOrNull()?.message ?: "未知原因"}",
                        Toast.LENGTH_LONG,
                    ).show()
                }
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
                        serviceScope?.launch { lifecycle.pause() }
                    }
                },
                onResumeVoice = {
                    val lifecycle = (application as App).container.voiceLifecycle
                    if (lifecycle.isVoiceActionAllowed(VoiceAction.PAUSE_RESUME_VIA_BALL)) {
                        serviceScope?.launch { lifecycle.resume() }
                    }
                },
                onStartRecord = { startRecordingSmart() },
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
        val (screenW, screenH) = screenSize()
        val ballRect = ballPair?.second
        val ballCx = if (ballRect != null) ballRect.x + ballRect.width / 2 else screenW / 2
        val ballCy = if (ballRect != null) ballRect.y + ballRect.height / 2 else screenH / 2
        val gap = dp(12f)
        var px = if (ballCx < screenW / 2) ballCx + gap else ballCx - gap - panelWidth
        px = px.coerceIn(dp(8f), (screenW - panelWidth - dp(8f)).coerceAtLeast(dp(8f)))
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
            params.y = (ballCy - h / 2).coerceIn(dp(8f), (screenH - h - dp(8f)).coerceAtLeast(dp(8f)))
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
        val scopeLabel = "全局"
        val adbLabel = if (container.adbClient.status.value.phase == com.aixiaohuadb.adb.AdbPhase.CONNECTED) "ADB" else "无障碍"
        return "$voiceLabel · $scopeLabel · $adbLabel"
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

    /** 悬浮窗定位/夹取统一用当前显示尺寸，避免游戏横屏时仍按竖屏资源计算 */
    private fun screenSize(): Pair<Int, Int> = DisplaySize.current(this)

    // ---------------- 录制宏 ----------------

    /**
     * 智能录制入口（UI 默认走这里）：ADB 已连时优先用 getevent 被动录制——零覆盖层、
     * 零吞触摸，对局体验最好；但 getevent 需读 /dev/input，非 root 设备常不可用，
     * 因此一旦启动失败立即无缝降级到无障碍录制，不让玩家卡在"点了没反应"。
     *
     * 无条件把 ADB 设为默认是错误的：绝大多数玩家的日常设备没有 root/ADB 常连，
     * 那样会让录制直接失效。智能降级兼顾了两种设备。
     */
    /** 当前是否处于任一录制通道中（供入口守卫与执行互斥使用） */
    private fun isRecording(): Boolean = adbRecording || accessibilityRecorder != null

    private fun startRecordingSmart() {
        // 玩家误触保护：录制中再点"录制"不应悄悄清空已录步骤。
        if (isRecording()) {
            Toast.makeText(this, "正在录制中，请先完成或取消", Toast.LENGTH_SHORT).show()
            return
        }
        val client = (application as App).container.adbClient
        if (client.status.value.phase == AdbPhase.CONNECTED) {
            Log.d("OverlayControl", "smart record: ADB connected, try getevent first")
            startAdbRecording(fallbackOnFailure = true)
        } else {
            Log.d("OverlayControl", "smart record: ADB not connected, use accessibility")
            startRecording()
        }
    }

    /**
     * 默认录制：无障碍覆盖层接管触摸，抬手时把同一手势转发给游戏。
     *
     * 不再以 ADB getevent 为主路径：那条路依赖 ADB 连接，且 Type A 只能按帧近似配对触点。
     * 默认路径不关心触摸协议，也不依赖 ADB 连接；ADB 高保真录制作为链路页的显式增强入口。
     */
    /**
     * 统一停止所有录制通道并回到空闲态。任何"开始录制 / 权限撤销 / 服务销毁 / 取消"都走这里，
     * 保证两个通道不会并存，也不会出现"胶囊没了但录制还在跑"的隐形状态。
     */
    private fun stopAllRecording(clearPending: Boolean = true) {
        recordingSession++
        accessibilityRecorder?.let { old ->
            runCatching { old.cancel() }
            runCatching { old.destroy() }
        }
        accessibilityRecorder = null
        runCatching { adbRecorder?.stop() }
        adbRecorder = null
        adbRecording = false
        MacroRecordNotification.hide(this)
        hideAdbChip()
        if (clearPending) OverlayStore.discardPendingMacro()
    }

    private fun startRecording() {
        // 通道互斥：先彻底停掉可能并存的 ADB 录制（含其胶囊/通知），
        // 否则玩家从链路页点 ADB 录制后再点普通录制，会两路同时工作。
        stopAllRecording()
        closePanel()
        val recorder = AccessibilityRecorder(this, object : AccessibilityRecorder.Listener {
            override fun onStep(step: MacroStep) = Unit
            override fun onTick(elapsedMs: Long, stepCount: Int) = Unit
            override fun onFinishRequested() = finishAccessibilityRecording()
            override fun onCancelRequested() = cancelAccessibilityRecording()
            override fun onError(message: String) {
                Toast.makeText(this@OverlayControlService, message, Toast.LENGTH_LONG).show()
            }
        })
        if (!recorder.start()) {
            recorder.destroy()
            return
        }
        accessibilityRecorder = recorder
        OverlayStore.discardPendingMacro()
    }

    private fun finishAccessibilityRecording() {
        val recorder = accessibilityRecorder ?: return
        val steps = recorder.stop()
        recorder.destroy()
        // 录制通道统一收敛（不动 pending，由下面的结果分支决定 stage 还是 discard）
        stopAllRecording(clearPending = false)
        Log.d("OverlayControl", "finishRecording steps=${steps.size}")
        if (steps.isEmpty()) {
            OverlayStore.discardPendingMacro()
            Toast.makeText(this, "没有录到操作，请重试", Toast.LENGTH_SHORT).show()
            return
        }
        OverlayStore.stagePendingMacro(MacroAdapter.toExecSteps(steps))
        showNamingWindow()
    }

    private fun cancelAccessibilityRecording() {
        if (accessibilityRecorder == null && !adbRecording) return
        stopAllRecording()
        Toast.makeText(this, "已取消录制", Toast.LENGTH_SHORT).show()
    }

    /**
     * ADB 增强录制：getevent 被动监听，保留内核时间戳与原生多指。Type A 也能按帧录制，Type B 多指语义更可靠。
     *
     * [fallbackOnFailure] 由智能入口置 true：getevent 读 /dev/input 在非 root 设备常失败，
     * 此时无缝降级无障碍录制，不让玩家卡住；链路页显式选择 ADB 时保持 false，给出明确错误。
     */
    private fun startAdbRecording(fallbackOnFailure: Boolean = false) {
        val client: AdbClient = (application as App).container.adbClient
        if (client.status.value.phase != AdbPhase.CONNECTED) {
            if (fallbackOnFailure) {
                Log.w("OverlayControl", "smart record: ADB disconnected at start, fallback to accessibility")
                startRecording()
            } else {
                Toast.makeText(this, "未连接 ADB，无法使用高保真录制", Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (adbRecording) return
        // 通道互斥：清掉可能残留的无障碍录制器；但不要走完整的 stopAllRecording，
        // 因为它会递增 session 并清 pending，这里只需停掉另一条通道。
        accessibilityRecorder?.let { old ->
            runCatching { old.cancel() }
            runCatching { old.destroy() }
        }
        accessibilityRecorder = null
        closePanel()
        val scope = serviceScope ?: return
        val session = ++recordingSession
        recordError = ""
        val recorder = AdbTouchRecorder(client, scope)
        // 读循环可能在 start() 返回前极早失败：该标志确保"安装 recorder"与"提示"互斥。
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
                // 被新一轮录制取代：不再提示也不降级，交给新会话处理
                if (session != recordingSession) {
                    if (started) runCatching { recorder.stop() }
                    return@withContext
                }
                // 启动失败（未返回成功 / 读循环早退）统一在此处理，降级必须回到主线程 addView
                if (!started || errorSignalled.get()) {
                    if (started) runCatching { recorder.stop() }
                    if (fallbackOnFailure) {
                        Log.w("OverlayControl", "smart record: ADB getevent unavailable (${recordError}), fallback to accessibility")
                        startRecording()
                        Toast.makeText(
                            this@OverlayControlService,
                            "ADB 录制不可用，已自动切换为兼容录制",
                            Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        Toast.makeText(
                            this@OverlayControlService,
                            "ADB 高保真录制不可用：${recordError.ifBlank { "getevent 未返回事件" }}",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    return@withContext
                }
                adbRecorder = recorder
                adbRecording = true
                OverlayStore.discardPendingMacro()
                showAdbChip()
                if (NotificationManagerCompat.from(this@OverlayControlService).areNotificationsEnabled()) {
                    MacroRecordNotification.show(this@OverlayControlService, 0)
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
        // 同步启动失败时 adbRecorder==null，由 startAdbRecording 的 !started 分支负责提示。
        if (!adbRecording || adbRecorder !== recorder) return
        runCatching { adbRecorder?.stop() }
        adbRecorder = null
        adbRecording = false
        MacroRecordNotification.hide(this)
        hideAdbChip()
        Toast.makeText(
            this,
            "ADB 高保真录制中断：${recordError.ifBlank { "getevent 结束" }}",
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun finishAdbRecording() {
        if (!adbRecording) return
        val steps: List<ExecStep> = adbRecorder?.stop().orEmpty()
        stopAllRecording(clearPending = false)
        if (steps.isNotEmpty()) {
            OverlayStore.stagePendingMacro(steps)
            showNamingWindow()
        } else {
            OverlayStore.discardPendingMacro()
            Toast.makeText(this, "没有录到操作，请重试", Toast.LENGTH_SHORT).show()
        }
    }

    private fun cancelAdbRecording() {
        if (!adbRecording && accessibilityRecorder == null) return
        stopAllRecording()
        Toast.makeText(this, "已取消录制", Toast.LENGTH_SHORT).show()
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
            (screenSize().first * 0.86f).toInt(),
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
            (screenSize().first * 0.8f).toInt(),
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
        val (screenW, screenH) = screenSize()
        val mx = (screenW - params.width).coerceAtLeast(1).toFloat()
        val my = (screenH - params.height).coerceAtLeast(1).toFloat()
        OverlayStore.updateBallPosition(params.x / mx, params.y / my)
    }

    private fun saveWidgetPosition(widgetId: String, params: WindowManager.LayoutParams) {
        val (screenW, screenH) = screenSize()
        val mx = (screenW - params.width).coerceAtLeast(1).toFloat()
        val my = (screenH - params.height).coerceAtLeast(1).toFloat()
        OverlayStore.updateWidgetPosition(widgetId, params.x / mx, params.y / my)
    }

    companion object {
        private const val OVERLAY_CHANNEL_ID = "overlay_control_channel"
        private const val OVERLAY_NOTIFICATION_ID = 1003

        /** 宏按钮全局常驻：面板关闭后仍可点击回放（当前为固定行为，无 UI 开关） */
        const val keepButtonsWhenPanelClosed = true

        /** 基准坐标 1080 宽，x>=1079 视为贴右边缘；多步如此通常意味着录制方向错了 */
        private const val MACRO_EDGE_X = 1079

        fun start(context: Context) {
            if (!OverlayStore.overlayEnabled.value || !Settings.canDrawOverlays(context)) return
            startAsForeground(context, Intent(context, OverlayControlService::class.java))
        }

        /** 所有启动路径都走 FGS：前台调用和录音/录制切后台后都不会被系统随手回收 */
        private fun startAsForeground(context: Context, intent: Intent) {
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        /**
         * 默认录制入口（新建宏走这里）：ADB 已连优先零遮挡录制，失败自动降级无障碍。
         * 服务会添加录制层/胶囊，调用方负责把任务切到后台。
         */
        fun startRecording(context: Context) {
            if (!Settings.canDrawOverlays(context)) {
                Toast.makeText(context, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show()
                return
            }
            val intent = Intent(context, OverlayControlService::class.java)
                .setAction(ACTION_START_SMART_RECORD)
            startAsForeground(context, intent)
        }

        /** 从链路页发起 ADB 高保真录制；服务会检查连接状态并显示录制胶囊。 */
        fun startAdbRecording(context: Context) {
            if (!Settings.canDrawOverlays(context)) {
                Toast.makeText(context, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show()
                return
            }
            val intent = Intent(context, OverlayControlService::class.java)
                .setAction(ACTION_START_ADB_RECORD)
            startAsForeground(context, intent)
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, OverlayControlService::class.java)) }
        }

        /** 权限页授权完成后调用：服务在跑则立即重建悬浮件，未跑则拉起 */
        fun refresh(context: Context) {
            if (!OverlayStore.overlayEnabled.value || !Settings.canDrawOverlays(context)) return
            val intent = Intent(context, OverlayControlService::class.java).setAction(ACTION_REFRESH)
            startAsForeground(context, intent)
        }

        fun isPermissionGranted(context: Context): Boolean = Settings.canDrawOverlays(context)

        const val ACTION_START_SMART_RECORD = "com.aixiaohuadb.action.START_SMART_RECORD"
        const val ACTION_START_ADB_RECORD = "com.aixiaohuadb.action.START_ADB_RECORD"
        const val ACTION_REFRESH = "com.aixiaohuadb.action.REFRESH"
        const val ACTION_DISABLE = "com.aixiaohuadb.action.DISABLE_OVERLAY"
    }
}
