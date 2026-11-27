package com.aixiaohuadb.service

import android.app.AppOpsManager
import android.content.Context
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.os.Process
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 前台游戏检测：悬浮指令按钮只在王者荣耀前台时显示，避免其他场景误触。
 *
 * 检测优先级：
 * 1. 无障碍服务实时推送（TYPE_WINDOW_STATE_CHANGED → notifyForegroundPackage，事件直达）；
 * 2. 兜底：500ms 轮询 isGameForeground（无障碍跟踪 / UsageStatsManager 最近 ACTIVITY_RESUMED）。
 * 双通道汇入同一 StateFlow，服务只 collect 一个流。
 */
object ForegroundGameMonitor {
    /** 王者荣耀：正式服 / 体验服 */
    val TARGET_PACKAGES: Set<String> = setOf(
        "com.tencent.tmgp.sgame",
        "com.tencent.tmgp.sgamece",
    )

    /** 由 VoiceAccessibilityService 在窗口切换事件中回写 */
    @Volatile
    var lastForegroundPackage: String? = null
        private set

    private val _gameForeground = MutableStateFlow(false)
    val gameForeground: StateFlow<Boolean> = _gameForeground.asStateFlow()

    /** 每次窗口切换都发一个值（包括切回自家 App），悬浮层据此立即隐藏/恢复， */
    private val _foregroundPackage = MutableStateFlow<String?>(null)
    val foregroundPackage: StateFlow<String?> = _foregroundPackage.asStateFlow()

    /** 无障碍事件直达：更新跟踪包名并发布前台状态 */
    fun notifyForegroundPackage(pkg: String?) {
        lastForegroundPackage = pkg
        _foregroundPackage.value = pkg
        if (pkg != null) publish(pkg in TARGET_PACKAGES)
    }

    /** 供轮询兜底写回；值相同则忽略，天然去重 */
    fun publish(inGame: Boolean) {
        if (_gameForeground.value != inGame) _gameForeground.value = inGame
    }

    fun isGameForeground(context: Context): Boolean {
        val tracked = lastForegroundPackage
        if (VoiceAccessibilityService.instance != null && tracked != null) {
            return tracked in TARGET_PACKAGES
        }
        return isGameForegroundByUsageStats(context)
    }

    fun hasUsageAccess(context: Context): Boolean = runCatching {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
        appOps?.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        ) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    /** 前台检测能力是否就绪（无障碍实时跟踪或使用情况访问二选一即可） */
    fun hasForegroundTracking(context: Context): Boolean =
        VoiceAccessibilityService.instance != null || hasUsageAccess(context)

    private fun isGameForegroundByUsageStats(context: Context): Boolean {
        if (!hasUsageAccess(context)) return false
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return false
        val now = System.currentTimeMillis()
        val events = runCatching {
            usm.queryEvents(now - 24 * 3600_000L, now)
        }.getOrNull() ?: return false
        var lastResumed: String? = null
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                lastResumed = event.packageName
            }
        }
        return lastResumed != null && lastResumed in TARGET_PACKAGES
    }
}
