package com.aixiaohuadb.service

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
import com.aixiaohuadb.inference.api.ControlWord
import com.aixiaohuadb.machine.VoiceEvent
import com.aixiaohuadb.machine.ui
import com.aixiaohuadb.ui.MainActivity
import com.aixiaohuadb.voice.VoiceAction

/** 常驻通知：仅语音生命周期 RUNNING/PAUSED 时允许存在 */
class VoiceForegroundService : Service() {
    companion object {
        const val CHANNEL_ID = "voice_ctl_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_PAUSE = "com.aixiaohuadb.action.PAUSE"
        const val ACTION_EXIT = "com.aixiaohuadb.action.EXIT"
        const val ACTION_RESUME = "com.aixiaohuadb.action.RESUME"

        @Volatile
        var runningInstance: VoiceForegroundService? = null
            private set

        fun start(context: Context) {
            val intent = Intent(context, VoiceForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** 物理停止常驻通知；onDestroy 中还会 cancel 一次兜底 */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, VoiceForegroundService::class.java)) }
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        }
    }

    private val container get() = (application as App).container

    override fun onCreate() {
        super.onCreate()
        // sticky / 后台复活 / disable 后残留的 start 请求一律不进入 startForeground
        if (!container.voiceLifecycle.isVoiceActionAllowed(VoiceAction.FOREGROUND_START)) {
            stopSelf()
            return
        }
        runningInstance = this
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // intent==null 是 START_STICKY 重启路径；无论哪种来源都要过生命周期守卫
        if (intent == null ||
            !container.voiceLifecycle.isVoiceActionAllowed(VoiceAction.VOICE_NOTIFICATION_ACTION)
        ) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_PAUSE -> container.stateMachine.send(
                VoiceEvent.ControlDetected(ControlWord.C3_PAUSE, 1f),
            )
            ACTION_EXIT -> container.stateMachine.send(
                VoiceEvent.ControlDetected(ControlWord.C1_EXIT_WAKE, 1f),
            )
            ACTION_RESUME -> container.stateMachine.send(VoiceEvent.ManualResume)
            else -> Unit
        }
        if (container.voiceLifecycle.isVoiceActionAllowed(VoiceAction.FOREGROUND_START)) {
            startForeground(NOTIFICATION_ID, buildNotification())
        } else {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (runningInstance === this) runningInstance = null
        // 兜底：即使 stopService 被系统拦截，通知也不再残留
        getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** 状态变化时由 AppViewModel 调用刷新通知文案 */
    fun refresh() {
        if (!container.voiceLifecycle.isVoiceActionAllowed(VoiceAction.FOREGROUND_START)) {
            stopSelf()
            return
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.notification_channel_description) }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val info = container.stateMachine.currentState().ui()
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), flags,
        )
        val pause = PendingIntent.getService(this, 1, actionIntent(ACTION_PAUSE), flags)
        val exit = PendingIntent.getService(this, 2, actionIntent(ACTION_EXIT), flags)
        val resume = PendingIntent.getService(this, 3, actionIntent(ACTION_RESUME), flags)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_overlay_controller_silhouette)
            .setContentTitle("小胡 · 语音控制")
            .setContentText(info.notif)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_pause, "暂停", pause).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_delete, "退出", exit).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_play, "恢复", resume).build())
            .build()
    }

    private fun actionIntent(action: String): Intent =
        Intent(this, VoiceForegroundService::class.java).setAction(action)
}
