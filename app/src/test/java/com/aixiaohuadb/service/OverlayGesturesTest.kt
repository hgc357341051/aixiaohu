package com.aixiaohuadb.service

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayGesturesTest {

    @Test
    fun `snap left when center in left half`() {
        assertEquals(0, OverlayGestures.snapEdgeX(x = 100, width = 100, screenWidth = 1080))
        assertEquals(0, OverlayGestures.snapEdgeX(x = 480, width = 100, screenWidth = 1080))
    }

    @Test
    fun `snap right when center in right half`() {
        assertEquals(980, OverlayGestures.snapEdgeX(x = 541, width = 100, screenWidth = 1080))
        assertEquals(980, OverlayGestures.snapEdgeX(x = 800, width = 100, screenWidth = 1080))
    }

    @Test
    fun `clamp keeps view inside screen`() {
        val (x, y) = OverlayGestures.clampPosition(-50, 5000, 100, 100, 1080, 2340)
        assertEquals(0, x)
        assertEquals(2240, y)
        val (x2, y2) = OverlayGestures.clampPosition(500, 500, 100, 100, 1080, 2340)
        assertEquals(500, x2)
        assertEquals(500, y2)
    }

    @Test
    fun `slop detection`() {
        assertEquals(true, OverlayGestures.exceededSlop(10f, 0f, 8f))
        assertEquals(false, OverlayGestures.exceededSlop(3f, 4f, 6f))
    }
}
