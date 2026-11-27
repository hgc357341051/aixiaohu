package com.aixiaohuadb.exec

import com.aixiaohuadb.config.BaseSize
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

    /** 逆映射：录制得到的物理坐标 → 基准坐标（保存宏用） */
    fun mapToBase(x: Int, y: Int, screenWidth: Int, screenHeight: Int): Pair<Int, Int> {
        require(screenWidth > 0 && screenHeight > 0) { "屏幕宽高必须为正数" }
        val baseX = (x.toFloat() * base.width / screenWidth).roundToInt()
            .coerceIn(0, base.width)
        val baseY = (y.toFloat() * base.height / screenHeight).roundToInt()
            .coerceIn(0, base.height)
        return baseX to baseY
    }

    fun baseSize(): BaseSize = base
}
