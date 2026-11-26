package com.example.voicectl.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.accessibilityservice.GestureDescription

/** 无障碍执行服务：仅在用户主动触发后派发点击手势，不扫描屏幕文字、不注入、不 Hook */
class VoiceAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** 派发一次点击；坐标为屏幕物理像素，由 CoordinateMapper 换算后传入 */
    fun tap(x: Int, y: Int): Boolean {
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
            lineTo(x + 1f, y + 1f)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 60L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = Unit
            override fun onCancelled(gestureDescription: GestureDescription?) = Unit
        }, null)
    }

    companion object {
        @Volatile
        var instance: VoiceAccessibilityService? = null
            private set
    }
}
