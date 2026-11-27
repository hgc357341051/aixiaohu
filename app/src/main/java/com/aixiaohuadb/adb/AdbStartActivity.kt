package com.aixiaohuadb.adb

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import com.aixiaohuadb.App

/**
 * 透明 trampoline：从悬浮面板/设置切 ADB 模式时，由可见 Activity 在前台启动
 * ADB 长连接前台服务，规避 Android 12+ 的后台 FGS 启动限制。
 * 失败必须把桥接状态置 ERROR 并给用户可见提示，绝不静默成功。
 */
class AdbStartActivity : Activity() {
    companion object {
        const val EXTRA_SESSION_ID = "adb_start_session"

        fun start(context: Context?, sessionId: Int): Boolean {
            if (context == null) return false
            return runCatching {
                context.startActivity(
                    Intent(context, AdbStartActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(EXTRA_SESSION_ID, sessionId),
                )
                true
            }.getOrDefault(false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sessionId = intent?.getIntExtra(EXTRA_SESSION_ID, -1) ?: -1
        val outcome = AdbBridgeService.start(this, sessionId)
        if (outcome.isFailure) {
            val reason = outcome.exceptionOrNull()?.message ?: "未知错误"
            val message = "ADB 后台连接启动被系统拒绝：$reason。请打开 ADB 页面手动确认连接。"
            runCatching {
                (application as App).container.adbBridgeController
                    .reportForegroundStartFailed(message, sessionId)
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
        finish()
    }
}
