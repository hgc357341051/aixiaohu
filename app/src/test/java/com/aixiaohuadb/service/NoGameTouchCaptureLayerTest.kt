package com.aixiaohuadb.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 录制链路的架构契约（P0 重构后）。
 *
 * 与旧契约的区别：旧版要求"只能有底部条 + 截屏标注"，那套交互已被删除。
 * 新版录制靠无障碍覆盖层接管触摸并转发，因此本测试不再禁止"全屏覆盖层"，
 * 而是守住三条边界：
 * 1. 不得再出现截图标注冒充录制的入口；
 * 2. 录制覆盖层只能由 AccessibilityRecorder 创建；
 * 3. 转发必须切换 FLAG_NOT_TOUCHABLE，否则注入的手势会被自己的录制层吃掉。
 */
class NoGameTouchCaptureLayerTest {

    private fun moduleRoot(): File? {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            if (File(dir, "src/main/java/com/aixiaohuadb/service/OverlayControlService.kt").isFile) return dir
            dir = dir.parentFile ?: return null
        }
        return null
    }

    private fun read(relative: String): String {
        val root = moduleRoot()
        assertTrue("未定位到 app 模块目录：${System.getProperty("user.dir")}", root != null)
        return File(root!!, relative).readText()
    }

    @Test
    fun `截图标注录制链路已移除`() {
        val service = read("src/main/java/com/aixiaohuadb/service/OverlayControlService.kt")
        val views = read("src/main/java/com/aixiaohuadb/service/OverlayViews.kt")

        listOf(
            "SnapshotCaptureActivity",
            "startSnapshotCapture",
            "refreshPendingCount",
            "createRecordBar",
            "updateRecordBar",
            "RecordBarCallbacks",
        ).forEach { symbol ->
            assertFalse("OverlayControlService 不应再出现 $symbol", service.contains(symbol))
        }

        listOf("createRecordBar", "updateRecordBar", "截屏标注", "已标 ").forEach { symbol ->
            assertFalse("OverlayViews 不应再出现 $symbol", views.contains(symbol))
        }
    }

    @Test
    fun `录制覆盖层只能由 AccessibilityRecorder 创建`() {
        val service = read("src/main/java/com/aixiaohuadb/service/OverlayControlService.kt")
        assertTrue("录制入口应使用 AccessibilityRecorder", service.contains("AccessibilityRecorder"))
        assertFalse(
            "服务不得自己创建全屏录制层",
            service.contains("WindowManager.LayoutParams.MATCH_PARENT,\n            WindowManager.LayoutParams.MATCH_PARENT"),
        )
    }

    @Test
    fun `录制转发必须临时关闭触摸以免吃掉注入手势`() {
        val recorder = read("src/main/java/com/aixiaohuadb/service/recorder/AccessibilityRecorder.kt")
        assertTrue("录制层必须是无障碍覆盖层", recorder.contains("TYPE_ACCESSIBILITY_OVERLAY"))
        assertTrue("转发期间必须切 NOT_TOUCHABLE", recorder.contains("FLAG_NOT_TOUCHABLE"))
    }
}
