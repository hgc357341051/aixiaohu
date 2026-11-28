package com.aixiaohuadb.domain.macro

import com.aixiaohuadb.exec.ExecStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MacroCodecTest {

    private fun doc(vararg steps: MacroStep) = MacroDocument(
        id = "m1",
        name = "测试宏",
        createdAt = 1L,
        updatedAt = 2L,
        screen = ScreenSpec(1080, 2340, 0),
        steps = steps.toList(),
    )

    @Test
    fun `编码后再解码保持步骤内容`() {
        val original = doc(
            MacroStep.Tap(x = 0.25f, y = 0.75f, delayBeforeMs = 120L),
            MacroStep.Swipe(
                points = listOf(NormPoint(0.1f, 0.2f), NormPoint(0.6f, 0.8f)),
                durationMs = 400,
            ),
            MacroStep.LongPress(x = 0.5f, y = 0.5f, durationMs = 700),
            MacroStep.Wait(waitMs = 1500L),
        )

        val restored = MacroCodec.decode(MacroCodec.encode(original))

        assertEquals(original.name, restored.name)
        assertEquals(original.screen, restored.screen)
        assertEquals(original.steps.size, restored.steps.size)
        val tap = restored.steps[0] as MacroStep.Tap
        assertEquals(0.25f, tap.x, 0.0001f)
        assertEquals(0.75f, tap.y, 0.0001f)
        assertEquals(120L, tap.delayBeforeMs)
        val swipe = restored.steps[1] as MacroStep.Swipe
        assertEquals(2, swipe.points.size)
        assertEquals(0.6f, swipe.end.x, 0.0001f)
        assertEquals(400, swipe.durationMs)
        assertEquals(1500L, (restored.steps[3] as MacroStep.Wait).waitMs)
    }

    @Test
    fun `版本高于当前支持时拒绝解析`() {
        val text = """{"schemaVersion":99,"id":"a","name":"x","createdAt":0,"updatedAt":0,
            |"screen":{"w":1080,"h":2340,"r":0},"steps":[]}""".trimMargin()
        val error = runCatching { MacroCodec.decode(text) }.exceptionOrNull()
        assertTrue(error is MacroCodecException)
        assertTrue(error!!.message!!.contains("版本"))
    }

    @Test
    fun `未知步骤类型抛出而不是跳过`() {
        val text = """{"schemaVersion":1,"id":"a","name":"x","createdAt":0,"updatedAt":0,
            |"screen":{"w":1080,"h":2340,"r":0},
            |"steps":[{"t":"teleport","id":"s1","delay":0}]}""".trimMargin()
        val error = runCatching { MacroCodec.decode(text) }.exceptionOrNull()
        assertTrue(error is MacroCodecException)
        assertTrue(error!!.message!!.contains("未知"))
    }

    @Test
    fun `坏 JSON 不会被当作空宏`() {
        val error = runCatching { MacroCodec.decode("{not json") }.exceptionOrNull()
        assertTrue(error is MacroCodecException)
    }

    @Test
    fun `坐标越界会被夹到 0 到 1`() {
        val text = """{"schemaVersion":1,"id":"a","name":"x","createdAt":0,"updatedAt":0,
            |"screen":{"w":1080,"h":2340,"r":0},
            |"steps":[{"t":"tap","id":"s1","delay":0,"x":3.5,"y":-2,"dur":60}]}""".trimMargin()
        val step = MacroCodec.decode(text).steps.single() as MacroStep.Tap
        assertEquals(1f, step.x, 0.0001f)
        assertEquals(0f, step.y, 0.0001f)
    }

    @Test
    fun `列表编码解码保持顺序`() {
        val a = doc(MacroStep.Tap(x = 0.1f, y = 0.1f))
        val b = doc(MacroStep.Wait(waitMs = 300L)).copy(id = "m2", name = "第二个")
        val restored = MacroCodec.decodeMany(MacroCodec.encodeMany(listOf(a, b)))
        assertEquals(listOf("m1", "m2"), restored.map { it.id })
    }

    @Test
    fun `归一化坐标转换为 1080x2340 基准并夹紧`() {
        val steps = MacroAdapter.toExecSteps(
            doc(
                MacroStep.Tap(x = 0.5f, y = 0.5f),
                MacroStep.Swipe(points = listOf(NormPoint(1f, 0f), NormPoint(0f, 1f)), durationMs = 200),
                MacroStep.LongPress(x = 1f, y = 1f, durationMs = 800),
            ),
        )
        val tap = steps[0] as ExecStep.Tap
        assertEquals(540, tap.x)
        assertEquals(1170, tap.y)
        val swipe = steps[1] as ExecStep.Swipe
        assertEquals(1080 - 1, swipe.fromX)
        assertEquals(0, swipe.fromY)
        val longPress = steps[2] as ExecStep.Swipe
        assertEquals(800, longPress.durationMs)
        assertTrue(longPress.toX <= 1080 - 1)
    }

    @Test
    fun `旧 ExecStep 能转入新文档`() {
        val steps = listOf(
            ExecStep.Tap(540, 1170, delayAfterMs = 100L),
            ExecStep.Swipe(0, 0, 1080, 2340, durationMs = 300),
            ExecStep.Wait(200L),
            ExecStep.Verify("k"),
        )
        val macros = MacroAdapter.fromExecSteps(steps)
        assertEquals(3, macros.size)
        assertEquals(0.5f, (macros[0] as MacroStep.Tap).x, 0.01f)
        assertEquals(2, (macros[1] as MacroStep.Swipe).points.size)
    }

    @Test
    fun `一次点击只产生一步且首步准备延迟被丢弃`() {
        // 回归：曾把首步 delayBeforeMs 前置成独立 Wait，导致"点一次记录两步"。
        val steps = MacroAdapter.toExecSteps(
            listOf(
                MacroStep.Tap(x = 0.1f, y = 0.1f, delayBeforeMs = 500L),
                MacroStep.Tap(x = 0.2f, y = 0.2f, delayBeforeMs = 300L),
            ),
        )
        assertEquals("两次点击应恰好两步，没有额外 Wait", 2, steps.size)
        assertEquals(0L, (steps[0] as ExecStep.Tap).delayAfterMs)
        assertEquals(300L, (steps[1] as ExecStep.Tap).delayAfterMs)
    }

    @Test
    fun `玩家显式插入的 Wait 步骤仍被保留`() {
        val steps = MacroAdapter.toExecSteps(
            listOf(
                MacroStep.Wait(waitMs = 700L),
                MacroStep.Tap(x = 0.1f, y = 0.1f),
            ),
        )
        assertEquals(2, steps.size)
        assertEquals(700L, (steps[0] as ExecStep.Wait).ms)
        assertTrue(steps[1] is ExecStep.Tap)
    }

    @Test
    fun `估算时长包含等待与手势`() {
        val value = doc(
            MacroStep.Tap(x = 0.1f, y = 0.1f, durationMs = 60),
            MacroStep.Wait(waitMs = 500L),
        ).estimatedDurationMs
        assertEquals(560L, value)
    }
}
