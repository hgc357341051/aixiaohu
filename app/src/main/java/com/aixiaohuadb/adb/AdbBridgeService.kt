package com.aixiaohuadb.adb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.aixiaohuadb.App
import com.aixiaohuadb.R
import com.aixiaohuadb.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** ADB 长连接前台宿主：只负责进程存活与 5s 心跳，命令全部走 AdbClient 队列 */
class AdbBridgeService : Service() {
    companion object {
        const val CHANNEL_ID = "adb_bridge_channel"
        const val NOTIFICATION_ID = 2002

        /** 必须由可见 Activity 调用；失败原因返回给调用方，禁止静默吞掉 */
        fun start(context: Context, sessionId: Int): Result<Unit> = runCatching {
            val intent = Intent(context, AdbBridgeService::class.java)
                .putExtra(AdbStartActivity.EXTRA_SESSION_ID, sessionId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private var sessionId: Int = -1
    private var scope: CoroutineScope? = null
    private var foregroundStarted = false
    private val controller get() = (application as App).container.adbBridgeController

    override fun onCreate() {
        super.onCreate()
        // stop 后残留的 start 请求或系统复活一律不得重新点亮前台通知/心跳。
        // Service 没有 intent 属性，session 级守卫放到 onStartCommand。
        if (!controller.isActive()) {
            stopSelf()
            return
        }
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val sid = intent?.getIntExtra(AdbStartActivity.EXTRA_SESSION_ID, -1) ?: -1
        if (sid < 0 || !controller.isActive(sid)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (sessionId != sid || !foregroundStarted) startSession(sid)
        return START_NOT_STICKY
    }

    /** FGS 在 onStartCommand 阶段启动，满足 5s 约束；只有真正 startForeground 成功才回调确认 */
    private fun startSession(sid: Int) {
        scope?.cancel()
        scope = null
        sessionId = sid
        if (!foregroundStarted) {
            val foreground = runCatching { startForeground(NOTIFICATION_ID, buildNotification()) }
            if (foreground.isFailure) {
                val reason = foreground.exceptionOrNull()?.message ?: "未知错误"
                controller.reportForegroundStartFailed("ADB 前台服务启动失败：$reason", sid)
                stopSelf()
                return
            }
            foregroundStarted = true
        }
        controller.reportForegroundStarted(sid)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope?.launch {
            while (isActive) {
                controller.heartbeatTick(sid)
                delay(controller.heartbeatIntervalMs())
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        foregroundStarted = false
        getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        controller.onServiceDestroyed(sessionId)
        super.onDestroy()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "ADB 长连接", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun buildNotification(): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_overlay_controller_silhouette)
            .setContentTitle("ADB 命令模式")
            .setContentText("长连接保持中 · 可随时下发命令")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }
}
