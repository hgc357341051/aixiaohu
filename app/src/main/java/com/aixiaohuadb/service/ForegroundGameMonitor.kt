package com.aixiaohuadb.service

import android.content.Context
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import com.aixiaohuadb.util.PermissionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 前台状态单一真源。悬浮层显示/隐藏只认这个状态，杜绝"多个字段不同步"导致的时显时隐。
 *
 * 两个信号通道（无障碍事件直达 / 500ms 轮询兜底）都必须经 [update] 写入同一状态，
 * 不再各写各的字段。resolve 时对 null 包名保持上一态，避免压在自家 App 上。
 */
sealed interface ForegroundScope {
    data object Game : ForegroundScope
    data object Self : ForegroundScope
    data object Other : ForegroundScope
}

object ForegroundGameMonitor {
    /** 王者荣耀：正式服 / 体验服 / 国际服 */
    private val BUILTIN_PACKAGES = setOf(
        "com.tencent.tmgp.sgame",
        "com.tencent.tmgp.sgamece",
        "com.tencent.tmgp.sgameglobal",
    )

    /** 内置包名的人类可读展示（设置页说明用） */
    val BUILTIN_PACKAGE_DISPLAY: List<String> = listOf(
        "com.tencent.tmgp.sgame（正式服）",
        "com.tencent.tmgp.sgamece（体验服）",
        "com.tencent.tmgp.sgameglobal（国际服）",
    )

    /** 用户手动登记的游戏包名（渠道服 / 其它游戏），运行时可变 */
    private val extraPackages = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun registerGamePackage(pkg: String) { if (pkg.isNotBlank()) extraPackages.add(pkg.trim()) }
    fun unregisterGamePackage(pkg: String) { extraPackages.remove(pkg.trim()) }
    fun registeredGamePackages(): Set<String> = extraPackages.toSet()

    /** 冷启动从持久化整体恢复；旧内存态清空，以磁盘为准 */
    fun replaceRegisteredPackages(packages: Set<String>) {
        extraPackages.clear()
        packages.forEach { if (it.isNotBlank()) extraPackages.add(it.trim()) }
    }

    fun isTargetPackage(pkg: String?): Boolean =
        pkg != null && (pkg in BUILTIN_PACKAGES || pkg in extraPackages)

    private var selfPackageName: String = ""

    /** 初始化自家包名（App.onCreate 或服务启动时调用一次） */
    fun initSelfPackage(pkg: String) { selfPackageName = pkg }

    // ---- 单一真源 ----
    private val _scope = MutableStateFlow<ForegroundScope>(ForegroundScope.Other)
    val scope: StateFlow<ForegroundScope> = _scope.asStateFlow()

    /** 当前前台包名（供日志/状态展示，读取用；写入统一走 update） */
    @Volatile
    var lastForegroundPackage: String? = null
        private set

    /** 由 resolve 结果写入唯一真源；值相同则忽略，天然去重 */
    private fun update(pkg: String?) {
        if (pkg == null) return
        lastForegroundPackage = pkg
        val next = when {
            isTargetPackage(pkg) -> ForegroundScope.Game
            pkg == selfPackageName -> ForegroundScope.Self
            else -> ForegroundScope.Other
        }
        if (_scope.value != next) _scope.value = next
        val inGame = next == ForegroundScope.Game
        if (_gameForeground.value != inGame) _gameForeground.value = inGame
    }

    /** 无障碍窗口事件直达 */
    fun notifyForegroundPackage(pkg: String?) = update(pkg)

    /** 轮询兜底：把检测到的包名写回同一真源（关键修复：过去轮询不写包名） */
    fun publishForegroundPackage(pkg: String?) = update(pkg)

    // ---- 兼容旧调用：读真源派生 ----
    /** 游戏是否前台（供状态徽标 / 执行判定） */
    val isGameForegroundNow: Boolean
        get() = _scope.value == ForegroundScope.Game

    private val _gameForeground = MutableStateFlow(false)
    val gameForeground: StateFlow<Boolean> = _gameForeground.asStateFlow()

    /** 保留旧 API，语义改为读真源 */
    fun isGameForeground(context: Context): Boolean {
        if (lastForegroundPackage != null) return _scope.value == ForegroundScope.Game
        return isGameForegroundByUsageStats(context)
    }

    /** 保留旧 API：轮询拿到的是包名，写回单一真源 */
    fun publish(inGame: Boolean) {
        // 兼容占位：真正的写回由 publishForegroundPackage(pkg) 完成。
    }

    fun hasUsageAccess(context: Context): Boolean = PermissionState.usageAccessGranted(context)

    fun hasForegroundTracking(context: Context): Boolean =
        PermissionState.accessibilityEnabled(context) || hasUsageAccess(context)

    private fun isGameForegroundByUsageStats(context: Context): Boolean {
        if (!hasUsageAccess(context)) return false
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return false
        val now = System.currentTimeMillis()
        val events = runCatching { usm.queryEvents(now - 24 * 3600_000L, now) }.getOrNull()
            ?: return false
        var lastResumed: String? = null
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) lastResumed = event.packageName
        }
        return lastResumed != null && (lastResumed in BUILTIN_PACKAGES || lastResumed in extraPackages)
    }
}
