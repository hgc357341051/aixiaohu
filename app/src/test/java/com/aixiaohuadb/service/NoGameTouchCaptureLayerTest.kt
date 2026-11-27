package com.aixiaohuadb.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** S1：录制链路只能有底部条 + 截屏标注，不得残留任何消费游戏触摸的全屏捕获/落点层 */
class NoGameTouchCaptureLayerTest {

    private fun moduleRoot(): File? {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            if (File(dir, "src/main/java/com/aixiaohuadb/service/OverlayViews.kt").isFile) return dir
            dir = dir.parentFile ?: return null
        }
        return null
    }

    @Test
    fun `录制相关源码不含消费游戏触摸的捕获层`() {
        val root = moduleRoot()
        assertTrue("未定位到 app 模块目录：${System.getProperty("user.dir")}", root != null)

        val forbidden = listOf(
            "createCaptureLayer",
            "captureView",
            "captureParams",
            "recordPoint",
            "showPointLayer",
            "hidePointLayer",
            "CaptureCallbacks",
            "recordedPoints",
        )
        val files = listOf(
            "src/main/java/com/aixiaohuadb/service/OverlayViews.kt",
            "src/main/java/com/aixiaohuadb/service/OverlayControlService.kt",
        )
        files.forEach { relative ->
            val source = File(root!!, relative).readText()
            forbidden.forEach { symbol ->
                assertFalse("$relative 不应再出现 $symbol", source.contains(symbol))
            }
        }

        // 正向断言：底部条仍然在，且入口语义是“截屏标注”而非游戏区落点采集
        val views = File(root, files[0]).readText()
        assertTrue(views.contains("截屏标注"))
        assertTrue(views.contains("已标 "))
        val service = File(root, files[1]).readText()
        assertTrue(service.contains("dp(64f)"))
        assertTrue(service.contains("Gravity.BOTTOM"))
    }
}
