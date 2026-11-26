package com.example.voicectl.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.example.voicectl.App
import com.example.voicectl.inference.api.ControlWord
import com.example.voicectl.machine.VoiceEvent
import com.example.voicectl.machine.VoiceState

/** 悬浮球：C1 退出后唯一语音恢复入口之一；无悬浮窗权限时自动不可用 */
class FloatingBallService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var ball: View
    private var layoutParams: WindowManager.LayoutParams? = null

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        ball = TextView(this).apply {
            text = "小胡"
            textSize = 13f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#0E7490"))
            gravity = Gravity.CENTER
            alpha = 0.85f
        }
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val machine = (application as App).container.stateMachine
                when (machine.currentState()) {
                    VoiceState.MANUAL_SILENT, VoiceState.RECOG_OFF, VoiceState.ERROR,
                    VoiceState.COOLDOWN, VoiceState.PAUSED, VoiceState.UNLOAD_WAKE
                    -> machine.send(VoiceEvent.ManualResume)
                    VoiceState.IDLE -> {
                        machine.send(VoiceEvent.Start)
                        machine.send(VoiceEvent.WakeVerified(1f))
                    }
                    VoiceState.LISTEN, VoiceState.VERIFY -> machine.send(VoiceEvent.WakeVerified(1f))
                    VoiceState.CMD_WINDOW, VoiceState.EXECUTE -> Unit
                }
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                (application as App).container.stateMachine.send(VoiceEvent.ManualResume)
                return true
            }
            override fun onLongPress(e: MotionEvent) {
                (application as App).container.stateMachine.send(
                    VoiceEvent.ControlDetected(ControlWord.C3_PAUSE, 1f),
                )
            }
        })
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        ball.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            val params = layoutParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    runCatching { windowManager.updateViewLayout(ball, params) }
                    true
                }
                else -> true
            }
        }
        val type = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }
        layoutParams = WindowManager.LayoutParams(
            132, 132, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 220
        }
        runCatching { windowManager.addView(ball, layoutParams) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        if (::ball.isInitialized && ::windowManager.isInitialized) {
            runCatching { windowManager.removeView(ball) }
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(context: Context) {
            if (!Settings.canDrawOverlays(context)) return
            runCatching { context.startService(Intent(context, FloatingBallService::class.java)) }
        }
    }
}
