package com.example.voicectl.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class FeatureWindowTest {
    @Test
    fun `snapshot pads zeros when frames are insufficient`() {
        val window = FeatureWindow(targetSize = 400, nMels = 40)
        window.push(FloatArray(40) { it + 1f })
        window.push(FloatArray(40) { it + 41f })

        val snapshot = window.snapshot()
        assertArrayEquals(FloatArray(400) { index ->
            if (index < 320) 0f else index - 320f + 1f
        }, snapshot, 0.0001f)
    }

    @Test
    fun `snapshot keeps newest values when frames exceed target`() {
        val window = FeatureWindow(targetSize = 10, nMels = 4)
        window.push(floatArrayOf(1f, 2f, 3f, 4f))
        window.push(floatArrayOf(5f, 6f, 7f, 8f))
        window.push(floatArrayOf(9f, 10f, 11f, 12f))

        assertArrayEquals(
            floatArrayOf(3f, 4f, 5f, 6f, 7f, 8f, 9f, 10f, 11f, 12f),
            window.snapshot(),
            0.0001f,
        )
    }

    @Test
    fun `reset clears history`() {
        val window = FeatureWindow(targetSize = 8, nMels = 4)
        window.push(floatArrayOf(1f, 2f, 3f, 4f))
        window.reset()

        assertArrayEquals(FloatArray(8), window.snapshot(), 0.0001f)
    }
}
