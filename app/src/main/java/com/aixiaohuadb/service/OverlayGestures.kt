package com.aixiaohuadb.service

import android.animation.ValueAnimator
import android.view.View
import kotlin.math.roundToInt

/** 悬浮控件手势的纯逻辑部分：可单测的吸附/夹取 + 拖动视觉反馈与贴边动画 */
internal object OverlayGestures {

    /** 水平吸边：控件中心在左半屏吸左缘，否则吸右缘 */
    fun snapEdgeX(x: Int, width: Int, screenWidth: Int): Int {
        val maxX = (screenWidth - width).coerceAtLeast(0)
        return if (x + width / 2 < screenWidth / 2) 0 else maxX
    }

    /** 位置夹取到屏幕内 */
    fun clampPosition(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        screenWidth: Int,
        screenHeight: Int,
    ): Pair<Int, Int> {
        val maxX = (screenWidth - width).coerceAtLeast(0)
        val maxY = (screenHeight - height).coerceAtLeast(0)
        return x.coerceIn(0, maxX) to y.coerceIn(0, maxY)
    }

    /** 拖起视觉反馈：放大 1.1 + 提亮 */
    fun lift(view: View) {
        view.scaleX = 1.1f
        view.scaleY = 1.1f
        view.alpha = (view.alpha + 0.1f).coerceAtMost(1f)
    }

    /** 松手：缩放还原 + 200ms 贴边动画；mover 负责实际 updateViewLayout */
    fun settle(view: View, fromX: Int, targetX: Int, mover: (Int) -> Unit, onEnd: () -> Unit) {
        view.scaleX = 1f
        view.scaleY = 1f
        ValueAnimator.ofInt(fromX, targetX).apply {
            duration = 200
            addUpdateListener { animator -> mover(animator.animatedValue as Int) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    onEnd()
                }
            })
            start()
        }
    }

    /** 位移是否超过判定阈值（dp → px） */
    fun exceededSlop(dx: Float, dy: Float, slopPx: Float): Boolean =
        dx * dx + dy * dy > slopPx * slopPx

    fun dp(context: android.content.Context, value: Float): Int =
        (value * context.resources.displayMetrics.density).roundToInt()
}
