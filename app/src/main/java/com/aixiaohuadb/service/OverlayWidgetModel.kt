package com.aixiaohuadb.service

import com.aixiaohuadb.exec.ExecStep

/** 控件类型：预置指令按钮 / 自定义录制宏按钮 */
enum class WidgetKind { COMMAND, MACRO }

/**
 * 统一悬浮控件：预置指令与自定义宏共用一套配置。
 * id 稳定唯一："cmd:{0..13}" / "macro:{>=1000}"；fx/fy 为归一化位置（0~1）。
 */
data class OverlayWidget(
    val id: String,
    val kind: WidgetKind,
    val commandId: Int = -1,
    val macroId: Long = -1,
    val label: String,
    val sizeDp: Int = DEFAULT_SIZE_DP,
    val alphaPercent: Int = DEFAULT_ALPHA,
    val fx: Float = 0.92f,
    val fy: Float = 0.30f,
    val enabled: Boolean = true,
    val locked: Boolean = false,
) {
    companion object {
        const val MIN_SIZE_DP = 36
        const val MAX_SIZE_DP = 96
        const val MIN_ALPHA = 40
        const val MAX_ALPHA = 100
        const val DEFAULT_SIZE_DP = 56
        const val DEFAULT_ALPHA = 88

        fun commandIdOf(id: String): Int? =
            if (id.startsWith("cmd:")) id.removePrefix("cmd:").toIntOrNull() else null

        fun macroIdOf(id: String): Long? =
            if (id.startsWith("macro:")) id.removePrefix("macro:").toLongOrNull() else null
    }
}

/** 悬浮球配置：语音恢复入口，全局显示（不做游戏门控） */
data class BallConfig(
    val sizeDp: Int = 66,
    val alphaPercent: Int = 85,
    val fx: Float = 0.02f,
    val fy: Float = 0.09f,
    val visible: Boolean = true,
    val locked: Boolean = false,
)

/**
 * 自定义宏：录制得到的步骤序列，Tap 为 1080×2340 基准坐标。
 * macroId 从 1000 起，与预置指令 0~14 隔离；执行由 MacroResolver 直接构造 CommandAction。
 */
data class MacroConfig(
    val macroId: Long,
    val name: String,
    val steps: List<ExecStep>,
    val createdAt: Long = 0L,
) {
    companion object {
        const val MACRO_ID_BASE = 1000L
        const val MAX_STEPS = 24
    }
}

fun clampSizeDp(v: Int): Int = v.coerceIn(OverlayWidget.MIN_SIZE_DP, OverlayWidget.MAX_SIZE_DP)

fun clampAlpha(v: Int): Int = v.coerceIn(OverlayWidget.MIN_ALPHA, OverlayWidget.MAX_ALPHA)

fun clampPos(v: Float): Float = v.coerceIn(0f, 1f)

/** 从现有宏列表推下一个可用 macroId；空表返回 1000 */
fun nextMacroId(macros: List<MacroConfig>): Long =
    (macros.maxOfOrNull { it.macroId } ?: (MacroConfig.MACRO_ID_BASE - 1)) + 1
