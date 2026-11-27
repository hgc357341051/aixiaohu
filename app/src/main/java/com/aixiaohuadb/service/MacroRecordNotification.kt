package com.aixiaohuadb.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.aixiaohuadb.R

/** 被动 ADB 录制期间唯一的控制入口：通知栏显示步数并提供完成/取消。 */
object MacroRecordNotification {
    const val ACTION_FINISH_RECORD = "com.aixiaohuadb.action.FINISH_RECORD"
    const val ACTION_CANCEL_RECORD = "com.aixiaohuadb.action.CANCEL_RECORD"
    private const val CHANNEL_ID = "macro_record_channel"
    private const val NOTIFICATION_ID = 2003

    fun show(context: Context, stepCount: Int) {
        runCatching {
            val manager = NotificationManagerCompat.from(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "宏录制",
                    NotificationManager.IMPORTANCE_LOW,
                )
                manager.createNotificationChannel(channel)
            }
            val finish = pendingIntentFor(context, ACTION_FINISH_RECORD, 20031)
            val cancel = pendingIntentFor(context, ACTION_CANCEL_RECORD, 20032)
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_overlay_controller_silhouette)
                .setContentTitle("宏录制中 · $stepCount 步")
                .setContentText("ADB 被动监听，不拦截游戏点击")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(NotificationCompat.Action(R.drawable.ic_overlay_controller_silhouette, "完成", finish))
                .addAction(NotificationCompat.Action(R.drawable.ic_overlay_controller_silhouette, "取消", cancel))
                .build()
            manager.notify(NOTIFICATION_ID, notification)
        }
    }

    fun update(context: Context, stepCount: Int) = show(context, stepCount)

    fun hide(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }

    private fun pendingIntentFor(context: Context, action: String, requestCode: Int) =
        PendingIntent.getService(
            context,
            requestCode,
            Intent(action, null, context, OverlayControlService::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
