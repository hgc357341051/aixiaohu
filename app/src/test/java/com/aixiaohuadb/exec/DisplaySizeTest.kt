package com.aixiaohuadb.exec

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Test

class DisplaySizeTest {
    @Test
    fun `横屏旋转时竖屏尺寸必须互换`() {
        assertEquals(2340 to 1080, DisplaySize.oriented(1080, 2340, Surface.ROTATION_90))
        assertEquals(2340 to 1080, DisplaySize.oriented(1080, 2340, Surface.ROTATION_270))
    }

    @Test
    fun `已经是横屏尺寸时保持不变`() {
        assertEquals(2340 to 1080, DisplaySize.oriented(2340, 1080, Surface.ROTATION_90))
        assertEquals(2340 to 1080, DisplaySize.oriented(2340, 1080, Surface.ROTATION_270))
    }

    @Test
    fun `竖屏旋转不交换宽高`() {
        assertEquals(1080 to 2340, DisplaySize.oriented(1080, 2340, Surface.ROTATION_0))
        assertEquals(1080 to 2340, DisplaySize.oriented(1080, 2340, Surface.ROTATION_180))
    }

    @Test
    fun `非法尺寸返回安全值`() {
        assertEquals(1 to 1, DisplaySize.oriented(0, 2340, Surface.ROTATION_90))
    }
}
