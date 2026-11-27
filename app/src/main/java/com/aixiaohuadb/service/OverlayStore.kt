package com.aixiaohuadb.service

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aixiaohuadb.exec.ExecStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val Context.overlayStoreDataStore: DataStore<Preferences> by preferencesDataStore(name = "quick_buttons")

/**
 * 悬浮控制中心唯一数据源：widgets（球外全部按钮）+ ball + macros。
 * 内存 StateFlow 即时生效，落盘异步 + 防抖；Service 与设置页共享同一实例。
 * 兼容迁移：旧 QuickButtonStore 的 buttons_json 读入后转 v2 key，旧 key 保留作回滚。
 */
object OverlayStore {
    private val KEY_WIDGETS = stringPreferencesKey("widgets_v2")
    private val KEY_BALL = stringPreferencesKey("ball_v1")
    private val KEY_MACROS = stringPreferencesKey("macros_v1")
    private val KEY_LEGACY = stringPreferencesKey("buttons_json")
    private val KEY_MODE = stringPreferencesKey("control_mode_v1")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()
    private var persistJob: Job? = null

    private val _widgets = MutableStateFlow(defaultWidgets())
    val widgets: StateFlow<List<OverlayWidget>> = _widgets.asStateFlow()

    private val _ball = MutableStateFlow(BallConfig())
    val ball: StateFlow<BallConfig> = _ball.asStateFlow()

    private val _macros = MutableStateFlow(emptyList<MacroConfig>())
    val macros: StateFlow<List<MacroConfig>> = _macros.asStateFlow()

    /** 录制完成待命名宏；命名失败时设置页兜底保存 */
    private val _pendingMacro = MutableStateFlow<MacroConfig?>(null)
    val pendingMacro: StateFlow<MacroConfig?> = _pendingMacro.asStateFlow()

    /** 控制模式：语音识别 / 手动点触；默认语音 */
    private val _controlMode = MutableStateFlow(ControlMode.VOICE)
    val controlMode: StateFlow<ControlMode> = _controlMode.asStateFlow()

    /** 模式变更副作用钩子：由 App 注入 container.applyControlMode，使切换真实启停语音链路 */
    @Volatile
    private var modeApplier: ((ControlMode) -> Unit)? = null

    fun setModeApplier(applier: (ControlMode) -> Unit) {
        modeApplier = applier
    }

    @Volatile
    private var appContext: Context? = null

    /** 幂等初始化 + 旧数据迁移，App.onCreate 调用 */
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        scope.launch {
            val prefs = runCatching { appContext!!.overlayStoreDataStore.data.first() }.getOrNull() ?: return@launch
            val v2 = prefs[KEY_WIDGETS]?.let { OverlayCodec.decodeWidgets(it) }.orEmpty()
            if (v2.isNotEmpty()) {
                _widgets.value = v2
            } else {
                val legacy = prefs[KEY_LEGACY]?.let { OverlayCodec.migrateLegacy(it) }.orEmpty()
                if (legacy.isNotEmpty()) {
                    _widgets.value = legacy
                    persistWidgetsNow(legacy)
                }
            }
            prefs[KEY_BALL]?.let { OverlayCodec.decodeBall(it) }?.let { _ball.value = it }
            prefs[KEY_MACROS]?.let { OverlayCodec.decodeMacros(it) }.orEmpty()
                .takeIf { it.isNotEmpty() }?.let { _macros.value = it }
            prefs[KEY_MODE]?.let { ControlMode.fromKey(it) }?.let { restored ->
                _controlMode.value = restored
                // 恢复手动模式时同步真实停麦；默认语音模式不打断既有启动流程
                if (restored == ControlMode.MANUAL) modeApplier?.invoke(restored)
            }
        }
    }

    // ---------------- 查询 ----------------

    fun isAdded(commandId: Int): Boolean = _widgets.value.any { it.kind == WidgetKind.COMMAND && it.commandId == commandId }

    fun macroByWidgetId(id: String): MacroConfig? =
        OverlayWidget.macroIdOf(id)?.let { macroId -> _macros.value.firstOrNull { it.macroId == macroId } }

    fun nextMacroId(): Long = nextMacroId(_macros.value)

    // ---------------- widgets ----------------

    fun addPreset(commandId: Int, label: String) {
        mutateWidgets { current ->
            if (current.any { it.kind == WidgetKind.COMMAND && it.commandId == commandId }) {
                current
            } else {
                val fy = 0.28f + current.count { it.enabled } * 0.13f
                current + OverlayWidget(
                    id = "cmd:$commandId",
                    kind = WidgetKind.COMMAND,
                    commandId = commandId,
                    label = OverlayCodec.sanitizeLabel(label),
                    fx = 0.92f,
                    fy = fy.coerceAtMost(0.86f),
                )
            }
        }
        ensureServicesRunning()
    }

    fun removeWidget(id: String) {
        mutateWidgets { current -> current.filterNot { it.id == id } }
    }

    fun updateWidgetLive(id: String, sizeDp: Int, alphaPercent: Int) {
        mutateWidgets { current ->
            current.map {
                if (it.id == id) {
                    it.copy(sizeDp = clampSizeDp(sizeDp), alphaPercent = clampAlpha(alphaPercent))
                } else {
                    it
                }
            }
        }
    }

    fun updateWidgetPosition(id: String, fx: Float, fy: Float) {
        mutateWidgets { current ->
            current.map {
                if (it.id == id) it.copy(fx = clampPos(fx), fy = clampPos(fy)) else it
            }
        }
    }

    fun setWidgetLocked(id: String, locked: Boolean) {
        mutateWidgets { current ->
            current.map { if (it.id == id) it.copy(locked = locked) else it }
        }
    }

    // ---------------- ball ----------------

    fun updateBallLive(sizeDp: Int, alphaPercent: Int) {
        _ball.value = _ball.value.copy(sizeDp = clampSizeDp(sizeDp), alphaPercent = clampAlpha(alphaPercent))
        schedulePersist()
    }

    fun updateBallPosition(fx: Float, fy: Float) {
        _ball.value = _ball.value.copy(fx = clampPos(fx), fy = clampPos(fy))
        schedulePersist()
    }

    fun setBallVisible(visible: Boolean) {
        _ball.value = _ball.value.copy(visible = visible)
        schedulePersist()
    }

    fun setBallLocked(locked: Boolean) {
        _ball.value = _ball.value.copy(locked = locked)
        schedulePersist()
    }

    // ---------------- control mode ----------------

    /** 切换控制模式：更新持久化状态 + 触发真实语音链路启停 */
    fun setControlMode(mode: ControlMode) {
        if (_controlMode.value == mode) return
        _controlMode.value = mode
        schedulePersist()
        modeApplier?.invoke(mode)
    }

    // ---------------- macros ----------------

    /** 录制完成保存：分配 id、创建对应按钮（右缘堆叠），返回 macroId */
    fun saveMacro(name: String, steps: List<ExecStep>): Long {
        val macroId = nextMacroId()
        val macro = MacroConfig(
            macroId = macroId,
            name = OverlayCodec.sanitizeLabel(name),
            steps = steps.take(MacroConfig.MAX_STEPS),
            createdAt = System.currentTimeMillis(),
        )
        _macros.value = _macros.value + macro
        mutateWidgets { current ->
            val fy = 0.28f + current.count { it.enabled } * 0.13f
            current + OverlayWidget(
                id = "macro:$macroId",
                kind = WidgetKind.MACRO,
                macroId = macroId,
                label = macro.name,
                fx = 0.92f,
                fy = fy.coerceAtMost(0.86f),
            )
        }
        _pendingMacro.value = null
        schedulePersist()
        ensureServicesRunning()
        return macroId
    }

    fun removeMacro(macroId: Long) {
        _macros.value = _macros.value.filterNot { it.macroId == macroId }
        mutateWidgets { current -> current.filterNot { it.id == "macro:$macroId" } }
    }

    fun renameMacro(macroId: Long, name: String) {
        _macros.value = _macros.value.map {
            if (it.macroId == macroId) it.copy(name = OverlayCodec.sanitizeLabel(name)) else it
        }
        mutateWidgets { current ->
            current.map { if (it.id == "macro:$macroId") it.copy(label = OverlayCodec.sanitizeLabel(name)) else it }
        }
    }

    fun stagePendingMacro(steps: List<ExecStep>) {
        _pendingMacro.value = MacroConfig(
            macroId = nextMacroId(),
            name = "新宏",
            steps = steps.take(MacroConfig.MAX_STEPS),
            createdAt = System.currentTimeMillis(),
        )
    }

    fun discardPendingMacro() {
        _pendingMacro.value = null
    }

    // ---------------- 服务拉起 ----------------

    /** 创建/保存后服务未运行时自动拉起（仅前台调用方触发，满足 Android 12+ 限制） */
    fun ensureServicesRunning() {
        val context = appContext ?: return
        OverlayControlService.start(context)
    }

    // ---------------- 内部 ----------------

    private fun mutateWidgets(op: (List<OverlayWidget>) -> List<OverlayWidget>) {
        _widgets.value = op(_widgets.value)
        schedulePersist()
    }

    /** 滑条高频更新防抖：300ms 内合并为一次落盘 */
    private fun schedulePersist() {
        persistJob?.cancel()
        persistJob = scope.launch {
            delay(300)
            persistAll()
        }
    }

    private fun persistWidgetsNow(list: List<OverlayWidget>) {
        scope.launch {
            writeMutex.withLock {
                runCatching { appContext!!.overlayStoreDataStore.edit { it[KEY_WIDGETS] = OverlayCodec.encodeWidgets(list) } }
            }
        }
    }

    private suspend fun persistAll() {
        val context = appContext ?: return
        val widgetsJson = OverlayCodec.encodeWidgets(_widgets.value)
        val ballJson = OverlayCodec.encodeBall(_ball.value)
        val macrosJson = OverlayCodec.encodeMacros(_macros.value)
        writeMutex.withLock {
            runCatching {
                context.overlayStoreDataStore.edit {
                    it[KEY_WIDGETS] = widgetsJson
                    it[KEY_BALL] = ballJson
                    it[KEY_MACROS] = macrosJson
                    it[KEY_MODE] = _controlMode.value.name
                }
            }
        }
    }

    private fun defaultWidgets(): List<OverlayWidget> = listOf(
        OverlayWidget(id = "cmd:0", kind = WidgetKind.COMMAND, commandId = 0, label = "名刀", fx = 0.92f, fy = 0.28f),
        OverlayWidget(id = "cmd:1", kind = WidgetKind.COMMAND, commandId = 1, label = "复活甲", fx = 0.92f, fy = 0.41f),
        OverlayWidget(id = "cmd:2", kind = WidgetKind.COMMAND, commandId = 2, label = "辉月", fx = 0.92f, fy = 0.54f),
    )
}
