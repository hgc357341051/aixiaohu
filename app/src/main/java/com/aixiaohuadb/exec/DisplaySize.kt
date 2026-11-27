package com.aixiaohuadb.exec

import android.content.Context
import android.graphics.Point
import android.os.Build
import android.view.Surface
import android.view.WindowManager

/**
 * 当前显示尺寸（已按旋转取宽高）。
 *
 * 不能用 `context.resources.displayMetrics` 作为唯一来源：应用在后台时，
 * 进程资源可能仍停留在竖屏配置，而游戏已经把屏幕转成横屏。录制时若拿竖屏
 * 1080×2340 去换算横屏 2340×1080 的触点，x 会被夹到 1080，录出来的宏全部落在右边缘。
 */
object DisplaySize {
    fun current(context: Context): Pair<Int, Int> {
        val fallback = context.resources.displayMetrics.let { it.widthPixels to it.heightPixels }
        val windowManager = context.getSystemService(WindowManager::class.java) ?: return fallback
        val size = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = windowManager.currentWindowMetrics.bounds
                bounds.width() to bounds.height()
            } else {
                val point = Point()
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealSize(point)
                point.x to point.y
            }
        }.getOrNull() ?: return fallback
        return if (size.first > 0 && size.second > 0) size else fallback
    }

    /** 纯函数兜底：旋转 90/270 时宽高必须互换；单测覆盖。 */
    fun oriented(width: Int, height: Int, rotation: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 1 to 1
        val landscape = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
        return if (landscape && width < height) height to width else width to height
    }
}
