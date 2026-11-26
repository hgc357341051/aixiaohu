package com.example.voicectl.exec

import com.example.voicectl.config.BaseSize
import org.junit.Assert.assertEquals
import org.junit.Test

class CoordinateMapperTest {
    private val mapper = CoordinateMapper(BaseSize(1080, 2340))

    @Test
    fun `基准分辨率保持原坐标`() {
        assertEquals(760 to 1750, mapper.map(760, 1750, 1080, 2340))
        assertEquals(900 to 2100, mapper.map(900, 2100, 1080, 2340))
    }

    @Test
    fun `两倍分辨率等比放大`() {
        assertEquals(1800 to 4200, mapper.map(900, 2100, 2160, 4680))
        assertEquals(1520 to 3500, mapper.map(760, 1750, 2160, 4680))
    }

    @Test
    fun `小屏四舍五入并限制在屏幕范围内`() {
        assertEquals(352 to 748, mapper.map(760, 1750, 500, 1000))
        assertEquals(719 to 1599, mapper.map(1080, 2340, 720, 1600))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `非法屏幕尺寸抛出异常`() {
        mapper.map(100, 100, 0, 100)
    }
}
