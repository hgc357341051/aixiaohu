package com.example.voicectl.exec

import com.example.voicectl.config.BaseSize
import kotlin.math.roundToInt

/** 基准坐标换算：x = x0 × W / 1080，y = y0 × H / 2340 */
class CoordinateMapper(private val base: BaseSize = BaseSize(1080, 2340)) {
    fun map(x: Int, y: Int, screenWidth: Int, screenHeight: Int): Pair<Int, Int> {
        require(screenWidth > 0 && screenHeight > 0) { "屏幕宽高必须为正数" }
        val mappedX = (x.toFloat() * screenWidth / base.width).roundToInt()
            .coerceIn(0, screenWidth - 1)
        val mappedY = (y.toFloat() * screenHeight / base.height).roundToInt()
            .coerceIn(0, screenHeight - 1)
        return mappedX to mappedY
    }

    fun baseSize(): BaseSize = base
}
