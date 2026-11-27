package com.aixiaohuadb.ui.macro

import com.aixiaohuadb.config.BaseSize
import com.aixiaohuadb.exec.GesturePointer

/** 标注页的一步：坐标仍为 1080×2340 基准，间隔挂在步上 */
sealed class RecordStepUi {
    data class TapUi(
        val x: Int,
        val y: Int,
        val delayAfterMs: Long = 0L,
    ) : RecordStepUi()

    data class SwipeUi(
        val fromX: Int,
        val fromY: Int,
        val toX: Int,
        val toY: Int,
        val durationMs: Int = 300,
        val delayAfterMs: Long = 0L,
    ) : RecordStepUi()

    /** 被动录制得到的多指手势；标注页只读展示，保存时原样落盘。 */
    data class MultiGestureUi(
        val pointers: List<GesturePointer>,
        val durationMs: Int,
        val delayAfterMs: Long = 0L,
    ) : RecordStepUi()
}

/** 录制草稿：单帧截图 + 可编辑步骤；保存时转为 MacroConfig */
data class MacroDraft(
    val macroId: Long = 0L,
    val name: String = "新宏",
    val steps: List<RecordStepUi> = emptyList(),
    val snapshotUri: String? = null,
    val coordBase: BaseSize = BaseSize(1080, 2340),
    val updatedAt: Long = System.currentTimeMillis(),
)
