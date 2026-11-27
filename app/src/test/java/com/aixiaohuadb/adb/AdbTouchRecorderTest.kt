package com.aixiaohuadb.adb

import android.view.Surface
import com.aixiaohuadb.exec.CoordinateMapper
import com.aixiaohuadb.exec.ExecStep
import com.aixiaohuadb.exec.GesturePoint
import com.aixiaohuadb.exec.maxDelayMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbTouchRecorderTest {
    private val screenWidth = 1080
    private val screenHeight = 2340
    private val maxX = 1079
    private val maxY = 2339

    private fun accumulator() = TouchAccumulator(screenWidth, screenHeight, maxX, maxY, CoordinateMapper())

    /** Type A 老设备：没有 ABS_MT_SLOT，累积器按 SYN 帧内的坐标顺序配对触点。 */
    private fun typeAAccumulator() = TouchAccumulator(
        screenWidth, screenHeight, maxX, maxY, CoordinateMapper(),
        Surface.ROTATION_0, "/dev/input/event2", TouchProtocol.A,
    )

    /** 直接喂 `getevent -lt` 的真实文本，顺带覆盖 parseGeteventLine 的解析链路。 */
    private fun feed(acc: TouchAccumulator, output: String) {
        output.lineSequence().forEach { line ->
            parseGeteventLine(line)?.let { acc.accept(it) }
        }
    }

    private fun ev(timeMs: Long, name: String, value: Int, type: String = "EV_ABS") =
        ParsedEvent(timeMs, type, name, value)

    private fun down(acc: TouchAccumulator, timeMs: Long, x: Int, y: Int) {
        acc.accept(ev(timeMs, "ABS_MT_SLOT", 0))
        acc.accept(ev(timeMs, "ABS_MT_TRACKING_ID", 0))
        acc.accept(ev(timeMs + 10, "ABS_MT_POSITION_X", x))
        acc.accept(ev(timeMs + 10, "ABS_MT_POSITION_Y", y))
        acc.accept(ev(timeMs + 20, "SYN_REPORT", 0, "EV_SYN"))
    }

    private fun up(acc: TouchAccumulator, timeMs: Long) {
        acc.accept(ev(timeMs, "ABS_MT_SLOT", 0))
        acc.accept(ev(timeMs, "ABS_MT_TRACKING_ID", -1))
        acc.accept(ev(timeMs + 1, "SYN_REPORT", 0, "EV_SYN"))
    }

    private fun secondFingerDown(
        acc: TouchAccumulator,
        downMs: Long,
        x: Int,
        y: Int,
        trackingId: Int = 1,
    ) {
        acc.accept(ev(downMs, "ABS_MT_SLOT", 1))
        acc.accept(ev(downMs, "ABS_MT_TRACKING_ID", trackingId))
        acc.accept(ev(downMs + 10, "ABS_MT_POSITION_X", x))
        acc.accept(ev(downMs + 10, "ABS_MT_POSITION_Y", y))
        acc.accept(ev(downMs + 20, "SYN_REPORT", 0, "EV_SYN"))
    }

    private fun secondFingerUp(acc: TouchAccumulator, timeMs: Long) {
        acc.accept(ev(timeMs, "ABS_MT_SLOT", 1))
        acc.accept(ev(timeMs, "ABS_MT_TRACKING_ID", -1))
        acc.accept(ev(timeMs + 1, "SYN_REPORT", 0, "EV_SYN"))
    }

    @Test
    fun `parseGeteventDevices 只返回具备 XY 轴的触摸设备`() {
        val output = """
            add device 1: /dev/input/event0
              name:     "VirtualPowerButton"
              events:
                KEY (0001): KEY_POWER
            add device 2: /dev/input/event5
              name:     "goodix_ts"
              events:
                ABS (0003): ABS_MT_SLOT           : value 0, min 0, max 9, resolution 0
                            ABS_MT_TRACKING_ID    : value 0, min 0, max 65535, resolution 0
                            ABS_MT_POSITION_X     : value 0, min 0, max 1079, resolution 0
                            ABS_MT_POSITION_Y     : value 0, min 0, max 2339, resolution 0
                            ABS_MT_PRESSURE       : value 0, min 0, max 255, resolution 0
                            ABS_MT_TOUCH_MAJOR    : value 0, min 0, max 255, resolution 0
        """.trimIndent()

        val devices = parseGeteventDevices(output)

        assertEquals(1, devices.size)
        assertEquals(
            RawTouchDevice(
                path = "/dev/input/event5",
                maxX = 1079,
                maxY = 2339,
                maxSlot = 9,
                hasSlot = true,
                hasTrackingId = true,
                pressureMax = 255,
                touchMajorMax = 255,
            ),
            devices.first(),
        )
        assertTrue(devices.first().supportsMultiTouchCapture)
        assertTrue(parseGeteventDevices("not a getevent dump").isEmpty())
    }

    @Test
    fun `缺少 SLOT 与 TRACKING_ID 的设备被标记为不可多指录制`() {
        val output = """
            add device 1: /dev/input/event2
              name:     "legacy_ts"
              events:
                ABS (0003): ABS_MT_POSITION_X     : value 0, min 0, max 1079, resolution 0
                            ABS_MT_POSITION_Y     : value 0, min 0, max 2339, resolution 0
        """.trimIndent()

        val device = parseGeteventDevices(output).single()

        assertFalse(device.supportsMultiTouchCapture)
        assertFalse(device.hasSlot)
        assertFalse(device.hasTrackingId)
    }

    @Test
    fun `parseGeteventLine 解析时间类型名称与十六进制值`() {
        val parsed = parseGeteventLine("[  1234.567890] EV_ABS       ABS_MT_POSITION_X    00000123")

        assertEquals(ParsedEvent(1_234_567L, "EV_ABS", "ABS_MT_POSITION_X", 0x123), parsed)
        assertEquals(-1, parseGeteventLine("[  1234.567890] EV_ABS       ABS_MT_TRACKING_ID   ffffffff")?.value)
        assertEquals(
            ParsedEvent(1_000_000L, "EV_SYN", "SYN_REPORT", 0),
            parseGeteventLine("[  1000.000000] EV_SYN       SYN_REPORT        00000000"),
        )
        assertEquals(
            "多设备输出带设备路径前缀时仍可解析",
            ParsedEvent(1_234_567L, "EV_ABS", "ABS_MT_POSITION_X", 0x123),
            parseGeteventLine("[  1234.567890] /dev/input/event5: EV_ABS ABS_MT_POSITION_X 00000123"),
        )
        assertNull(parseGeteventLine("   "))
        assertNull(parseGeteventLine("garbage"))
    }

    @Test
    fun `单击生成 Tap 且基准坐标正确`() {
        val acc = accumulator()
        down(acc, 1_000L, 540, 1_170)
        up(acc, 1_100L)

        assertEquals(listOf(ExecStep.Tap(540, 1_170, delayAfterMs = 0L)), acc.steps())
    }

    @Test
    fun `滑动生成 Swipe 且时长被夹取`() {
        val acc = accumulator()
        down(acc, 1_000L, 100, 200)
        acc.accept(ev(1_250L, "ABS_MT_POSITION_X", 900))
        acc.accept(ev(1_250L, "ABS_MT_POSITION_Y", 1_800))
        acc.accept(ev(1_250L, "SYN_REPORT", 0, "EV_SYN"))
        up(acc, 1_250L)

        assertEquals(
            listOf(ExecStep.Swipe(100, 200, 900, 1_800, durationMs = 250, delayAfterMs = 0L)),
            acc.steps(),
        )
    }

    @Test
    fun `新触点按下时回写上一步的 delayAfterMs`() {
        val acc = accumulator()
        down(acc, 1_000L, 100, 100)
        up(acc, 1_100L)
        down(acc, 1_500L, 200, 200)
        up(acc, 1_600L)

        assertEquals(
            listOf(
                ExecStep.Tap(100, 100, delayAfterMs = 400L),
                ExecStep.Tap(200, 200, delayAfterMs = 0L),
            ),
            acc.steps(),
        )
    }

    @Test
    fun `双指重叠生成一个 MultiGesture 并为每根手指保留轨迹`() {
        val acc = accumulator()
        down(acc, 1_000L, 300, 400)
        secondFingerDown(acc, 1_020L, 800, 900)
        secondFingerUp(acc, 1_050L)
        up(acc, 1_100L)

        val step = acc.steps().single() as ExecStep.MultiGesture
        assertEquals(2, step.pointers.size)
        assertEquals(100, step.durationMs)
        assertEquals(0L, step.delayAfterMs)
        assertEquals(
            listOf(GesturePoint(300, 400, 10L), GesturePoint(300, 400, 100L)),
            step.pointers[0].points,
        )
        assertEquals(
            listOf(GesturePoint(800, 900, 30L), GesturePoint(800, 900, 50L)),
            step.pointers[1].points,
        )
    }

    @Test
    fun `双指错位抬起仍属于同一个手势`() {
        val acc = accumulator()
        down(acc, 1_000L, 100, 200)
        secondFingerDown(acc, 1_020L, 800, 900)
        up(acc, 1_200L)
        secondFingerUp(acc, 1_300L)

        val step = acc.steps().single() as ExecStep.MultiGesture
        assertEquals(2, step.pointers.size)
        assertEquals(300, step.durationMs)
        assertEquals(200L, step.pointers[0].endMs)
        assertEquals(300L, step.pointers[1].endMs)
    }

    @Test
    fun `多指手势之后的单指点击写入间隔`() {
        val acc = accumulator()
        down(acc, 1_000L, 300, 400)
        secondFingerDown(acc, 1_020L, 800, 900)
        secondFingerUp(acc, 1_050L)
        up(acc, 1_100L)
        down(acc, 1_500L, 100, 100)
        up(acc, 1_600L)

        val steps = acc.steps()
        assertEquals(2, steps.size)
        assertEquals(400L, steps[0].maxDelayMs)
    }

    @Test
    fun `横屏尺寸下右侧触点不会被夹到基准右边缘`() {
        // 游戏横屏 2340×1080；旧实现误用竖屏 1080×2340，x>1080 全部被夹成 1080
        val acc = TouchAccumulator(2340, 1080, 2339, 1079, CoordinateMapper())
        down(acc, 1_000L, 2_000, 500)
        up(acc, 1_100L)

        val tap = acc.steps().single() as ExecStep.Tap
        assertEquals(923, tap.x)
        assertEquals(1_083, tap.y)
        assertTrue(tap.x < 1079)
    }

    @Test
    fun `ROTATION_90 把面板原生坐标映射到显示坐标`() {
        assertEquals(0 to 540, mapPanelToDisplay(539, 0, maxX, maxY, Surface.ROTATION_90))

        val acc = TouchAccumulator(
            screenWidth, screenHeight, maxX, maxY, CoordinateMapper(), Surface.ROTATION_90,
        )
        down(acc, 1_000L, 539, 0)
        up(acc, 1_100L)

        assertEquals(listOf(ExecStep.Tap(0, 540)), acc.steps())
    }

    @Test
    fun `mapDisplayToPanel 是 mapPanelToDisplay 的逆变换`() {
        val rotations = listOf(
            Surface.ROTATION_0,
            Surface.ROTATION_90,
            Surface.ROTATION_180,
            Surface.ROTATION_270,
        )
        rotations.forEach { rotation ->
            val raw = listOf(0 to 0, 311 to 1_024, maxX to maxY)
            raw.forEach { (x, y) ->
                val (displayX, displayY) = mapPanelToDisplay(x, y, maxX, maxY, rotation)
                assertEquals(
                    "$rotation: $x,$y",
                    x to y,
                    mapDisplayToPanel(displayX, displayY, maxX, maxY, rotation),
                )
            }
        }
    }

    @Test
    fun `XY 先于 TRACKING_ID 时按缓存坐标开始`() {
        val acc = accumulator()
        acc.accept(ev(1_000L, "ABS_MT_SLOT", 0))
        acc.accept(ev(1_005L, "ABS_MT_POSITION_X", 333))
        acc.accept(ev(1_006L, "ABS_MT_POSITION_Y", 777))
        acc.accept(ev(1_010L, "ABS_MT_TRACKING_ID", 0))
        acc.accept(ev(1_020L, "SYN_REPORT", 0, "EV_SYN"))
        up(acc, 1_100L)

        assertEquals(listOf(ExecStep.Tap(333, 777)), acc.steps())
    }

    @Test
    fun `finish 冲刷未抬起的触摸`() {
        val acc = accumulator()
        down(acc, 2_000L, 720, 1_200)
        acc.accept(ev(2_050L, "ABS_MT_POSITION_X", 721))

        val flushed = acc.finish()

        assertEquals(ExecStep.Tap(720, 1_200), flushed)
        assertEquals(listOf(ExecStep.Tap(720, 1_200)), acc.steps())
        assertNull(acc.finish())
        assertFalse(acc.steps().isEmpty())
    }

    // ---------------- Type A 协议 ----------------

    private val typeADump = """
        add device 1: /dev/input/event2
          name:     "legacy_ts"
          events:
            ABS (0003): ABS_MT_POSITION_X     : value 0, min 0, max 1079, resolution 0
                        ABS_MT_POSITION_Y     : value 0, min 0, max 2339, resolution 0
                        ABS_MT_TOUCH_MAJOR    : value 0, min 0, max 31, resolution 0
    """.trimIndent()

    @Test
    fun `缺 SLOT 的设备被识别为 Type A 且优先选择 Type B`() {
        val output = """
            add device 1: /dev/input/event2
              name:     "legacy_ts"
              events:
                ABS (0003): ABS_MT_POSITION_X     : value 0, min 0, max 1079, resolution 0
                            ABS_MT_POSITION_Y     : value 0, min 0, max 2339, resolution 0
            add device 2: /dev/input/event5
              name:     "goodix_ts"
              events:
                ABS (0003): ABS_MT_SLOT           : value 0, min 0, max 9, resolution 0
                            ABS_MT_TRACKING_ID    : value 0, min 0, max 65535, resolution 0
                            ABS_MT_POSITION_X     : value 0, min 0, max 1079, resolution 0
                            ABS_MT_POSITION_Y     : value 0, min 0, max 2339, resolution 0
        """.trimIndent()

        val devices = parseGeteventDevices(output)
        assertEquals(TouchProtocol.A, devices.single { it.path == "/dev/input/event2" }.protocol)
        assertEquals(TouchProtocol.B, devices.single { it.path == "/dev/input/event5" }.protocol)
        // Type A 也能录，但 Type B 的多指语义更可靠，优先选它
        assertEquals("/dev/input/event5", pickTouchDevice(output, devices).path)

        val typeAOnly = parseGeteventDevices(typeADump)
        assertEquals(TouchProtocol.A, pickTouchDevice(typeADump, typeAOnly).protocol)
        assertEquals("/dev/input/event2", pickTouchDevice(typeADump, typeAOnly).path)
    }

    @Test
    fun `Type A 单指点击生成一个 Tap`() {
        val acc = typeAAccumulator()
        feed(
            acc,
            """
            [  7000.100000] EV_ABS       ABS_MT_TRACKING_ID   00000000
            [  7000.100000] EV_ABS       ABS_MT_POSITION_X    0000021c
            [  7000.100000] EV_ABS       ABS_MT_POSITION_Y    00000492
            [  7000.100000] EV_ABS       ABS_MT_TOUCH_MAJOR   00000005
            [  7000.100000] EV_ABS       ABS_MT_PRESSURE      00000020
            [  7000.100000] EV_KEY       BTN_TOUCH            DOWN
            [  7000.100000] EV_SYN       SYN_REPORT           00000000
            [  7000.200000] EV_ABS       ABS_MT_TOUCH_MAJOR   00000000
            [  7000.200000] EV_ABS       ABS_MT_PRESSURE      00000000
            [  7000.200000] EV_KEY       BTN_TOUCH            UP
            [  7000.200000] EV_SYN       SYN_REPORT           00000000
            """.trimIndent(),
        )

        assertEquals(listOf(ExecStep.Tap(540, 1_170, delayAfterMs = 0L)), acc.steps())
    }

    @Test
    fun `Type A 单指滑动生成 Swipe`() {
        val acc = typeAAccumulator()
        feed(
            acc,
            """
            [  7000.100000] EV_ABS       ABS_MT_POSITION_X    00000064
            [  7000.100000] EV_ABS       ABS_MT_POSITION_Y    000000c8
            [  7000.100000] EV_KEY       BTN_TOUCH            DOWN
            [  7000.100000] EV_SYN       SYN_REPORT           00000000
            [  7000.200000] EV_ABS       ABS_MT_POSITION_X    0000012c
            [  7000.200000] EV_ABS       ABS_MT_POSITION_Y    00000258
            [  7000.200000] EV_SYN       SYN_REPORT           00000000
            [  7000.300000] EV_ABS       ABS_MT_POSITION_X    000001f4
            [  7000.300000] EV_ABS       ABS_MT_POSITION_Y    000003e8
            [  7000.300000] EV_SYN       SYN_REPORT           00000000
            [  7000.400000] EV_ABS       ABS_MT_PRESSURE      00000000
            [  7000.400000] EV_KEY       BTN_TOUCH            UP
            [  7000.400000] EV_SYN       SYN_REPORT           00000000
            """.trimIndent(),
        )

        assertEquals(
            listOf(ExecStep.Swipe(100, 200, 500, 1_000, durationMs = 300, delayAfterMs = 0L)),
            acc.steps(),
        )
    }

    @Test
    fun `Type A 一帧两个触点生成多指手势`() {
        val acc = typeAAccumulator()
        feed(
            acc,
            """
            [  7000.100000] EV_ABS       ABS_MT_POSITION_X    0000012c
            [  7000.100000] EV_ABS       ABS_MT_POSITION_Y    00000190
            [  7000.100000] EV_SYN       SYN_MT_REPORT        00000000
            [  7000.100000] EV_ABS       ABS_MT_POSITION_X    00000320
            [  7000.100000] EV_ABS       ABS_MT_POSITION_Y    00000384
            [  7000.100000] EV_SYN       SYN_MT_REPORT        00000000
            [  7000.100000] EV_KEY       BTN_TOUCH            DOWN
            [  7000.100000] EV_SYN       SYN_REPORT           00000000
            [  7000.150000] EV_ABS       ABS_MT_POSITION_X    0000015e
            [  7000.150000] EV_ABS       ABS_MT_POSITION_Y    000001c2
            [  7000.150000] EV_SYN       SYN_MT_REPORT        00000000
            [  7000.150000] EV_ABS       ABS_MT_POSITION_X    00000352
            [  7000.150000] EV_ABS       ABS_MT_POSITION_Y    000003b6
            [  7000.150000] EV_SYN       SYN_MT_REPORT        00000000
            [  7000.150000] EV_SYN       SYN_REPORT           00000000
            [  7000.200000] EV_ABS       ABS_MT_TOUCH_MAJOR   00000000
            [  7000.200000] EV_ABS       ABS_MT_PRESSURE      00000000
            [  7000.200000] EV_KEY       BTN_TOUCH            UP
            [  7000.200000] EV_SYN       SYN_REPORT           00000000
            """.trimIndent(),
        )

        val step = acc.steps().single() as ExecStep.MultiGesture
        assertEquals(2, step.pointers.size)
        assertEquals(100, step.durationMs)
        assertEquals(GesturePoint(300, 400, 0L), step.pointers[0].points.first())
        assertEquals(GesturePoint(350, 450, 50L), step.pointers[0].points[1])
        assertEquals(GesturePoint(800, 900, 0L), step.pointers[1].points.first())
        assertEquals(GesturePoint(850, 950, 50L), step.pointers[1].points[1])
        assertEquals(100L, step.pointers[0].endMs)
        assertEquals(100L, step.pointers[1].endMs)
    }

    @Test
    fun `Type A 双指错位抬起仍收尾为同一个手势`() {
        val acc = typeAAccumulator()
        feed(
            acc,
            """
            [  7000.100000] EV_ABS       ABS_MT_POSITION_X    0000012c
            [  7000.100000] EV_ABS       ABS_MT_POSITION_Y    00000190
            [  7000.100000] EV_ABS       ABS_MT_POSITION_X    00000320
            [  7000.100000] EV_ABS       ABS_MT_POSITION_Y    00000384
            [  7000.100000] EV_KEY       BTN_TOUCH            DOWN
            [  7000.100000] EV_SYN       SYN_REPORT           00000000
            [  7000.200000] EV_ABS       ABS_MT_POSITION_X    00000320
            [  7000.200000] EV_ABS       ABS_MT_POSITION_Y    00000384
            [  7000.200000] EV_SYN       SYN_REPORT           00000000
            [  7000.300000] EV_ABS       ABS_MT_TOUCH_MAJOR   00000000
            [  7000.300000] EV_KEY       BTN_TOUCH            UP
            [  7000.300000] EV_SYN       SYN_REPORT           00000000
            """.trimIndent(),
        )

        val step = acc.steps().single() as ExecStep.MultiGesture
        assertEquals(2, step.pointers.size)
        // 少了一个触点只是抬起，不能在手势中途收尾
        assertEquals(200, step.durationMs)
        assertEquals(100L, step.pointers[1].endMs)
        assertEquals(200L, step.pointers[0].endMs)
    }

    @Test
    fun `Type A 两次手势之间回写 delayAfterMs`() {
        val acc = typeAAccumulator()
        feed(
            acc,
            """
            [  7000.100000] EV_ABS       ABS_MT_POSITION_X    00000064
            [  7000.100000] EV_ABS       ABS_MT_POSITION_Y    00000064
            [  7000.100000] EV_KEY       BTN_TOUCH            DOWN
            [  7000.100000] EV_SYN       SYN_REPORT           00000000
            [  7000.200000] EV_KEY       BTN_TOUCH            UP
            [  7000.200000] EV_SYN       SYN_REPORT           00000000
            [  7000.700000] EV_ABS       ABS_MT_POSITION_X    000000c8
            [  7000.700000] EV_ABS       ABS_MT_POSITION_Y    000000c8
            [  7000.700000] EV_KEY       BTN_TOUCH            DOWN
            [  7000.700000] EV_SYN       SYN_REPORT           00000000
            [  7000.800000] EV_KEY       BTN_TOUCH            UP
            [  7000.800000] EV_SYN       SYN_REPORT           00000000
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                ExecStep.Tap(100, 100, delayAfterMs = 500L),
                ExecStep.Tap(200, 200, delayAfterMs = 0L),
            ),
            acc.steps(),
        )
    }
}
