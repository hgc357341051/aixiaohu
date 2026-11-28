package com.aixiaohuadb.ui.theme

import androidx.compose.ui.graphics.Color

// GAME OPS 视觉 token：深空底 + 霓虹 Mint/Violet + 切角面板。
// 保留旧语义名，避免无意义的全量引用改名；值已整体替换为设计稿色板。
val Brand = Color(0xFF3DF5C8)
val BrandStrong = Color(0xFF17C9A3)
val BrandSoft = Color(0xFF12332F)
val BrandTint = Color(0xFF0C1F1E)
val OnBrand = Color(0xFF04130F)

val Bg = Color(0xFF060A12)
val Surface = Color(0xFF0E1626)
val Surface2 = Color(0xFF121E32)
val Ink = Color(0xFFEAF3FF)
val Ink2 = Color(0xFFB7C8DE)
val Muted = Color(0xFF8CA2BE)
val Line = Color(0xFF223552)
val LineStrong = Color(0xFF334A6B)

val Ok = Color(0xFF3DF5C8)
val OkBg = Color(0xFF10322C)
val OkBd = Color(0xFF1E6E5E)

val Warn = Color(0xFFFFB547)
val WarnBg = Color(0xFF332711)
val WarnBd = Color(0xFF6B4E1C)

val Danger = Color(0xFFFF4D6D)
val DangerBg = Color(0xFF35121C)
val DangerBd = Color(0xFF7A2A3D)

val Info = Color(0xFF4DA3FF)
val InfoBg = Color(0xFF102640)

val Violet = Color(0xFF8B6CFF)
val VioletBg = Color(0xFF201A3D)
val VioletBd = Color(0xFF4B3F8C)

val Panel = Color(0xFF0E1626)
val Panel2 = Color(0xFF121E32)
val PanelInk = Color(0xFFEAF3FF)
val PanelMuted = Color(0xFF8CA2BE)
val PanelFaint = Color(0xFF60768F)

// 状态灯：与旧 7 态 key 保持一致，颜色对齐设计稿。
val StIdle = Color(0xFFA9B8CA)
val StListening = Color(0xFF3DF5C8)
val StWindow = Color(0xFF8B6CFF)
val StExecuting = Color(0xFF3DF5C8)
val StPaused = Color(0xFFFFB547)
val StRecogOff = Color(0xFF60768F)
val StSilent = Color(0xFF8CA2BE)

/** 深色状态卡专用配色，M3 无对应槽位，由 CompositionLocal 提供 */
data class StateCardColors(
    val accent: Color,
    val background: Color = Panel,
    val surface: Color = Panel2,
    val onBackground: Color = PanelInk,
    val muted: Color = PanelMuted,
    val line: Color = PanelFaint,
)

val stateAccents: Map<String, Color> = mapOf(
    "idle" to StIdle,
    "listening" to StListening,
    "window" to StWindow,
    "executing" to StExecuting,
    "paused" to StPaused,
    "recogoff" to StRecogOff,
    "silent" to StSilent,
)
