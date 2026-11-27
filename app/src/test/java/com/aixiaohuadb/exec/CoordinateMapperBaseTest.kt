package com.aixiaohuadb.exec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoordinateMapperBaseTest {

    private val mapper = CoordinateMapper()

    @Test
    fun `mapToBase inverts map on same screen`() {
        val screenW = 1080
        val screenH = 2340
        val physical = mapper.map(500, 1000, screenW, screenH)
        val back = mapper.mapToBase(physical.first, physical.second, screenW, screenH)
        assertEquals(500, back.first)
        assertEquals(1000, back.second)
    }

    @Test
    fun `mapToBase scales non base screens`() {
        // 720×1600 屏：物理点按比例折算回 1080×2340 基准
        val (bx, by) = mapper.mapToBase(360, 800, 720, 1600)
        assertEquals(540, bx)
        assertEquals(1170, by)
    }

    @Test
    fun `mapToBase clamps out of range`() {
        val (bx, by) = mapper.mapToBase(-100, 9999, 1080, 2340)
        assertTrue(bx >= 0)
        assertTrue(by <= 2340)
    }
}
