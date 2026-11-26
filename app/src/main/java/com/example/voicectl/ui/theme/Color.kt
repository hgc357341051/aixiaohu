package com.example.voicectl.ui.theme

import androidx.compose.ui.graphics.Color

// CSS token → Compose Color，逐字对应视觉样式规范
val Brand = Color(0xFF0E7490)
val BrandStrong = Color(0xFF0B5F73)
val BrandSoft = Color(0xFFE6F4F6)
val BrandTint = Color(0xFFF1F9FA)
val OnBrand = Color(0xFFFFFFFF)
val Bg = Color(0xFFF6F7F9)
val Surface = Color(0xFFFFFFFF)
val Surface2 = Color(0xFFF9FAFC)
val Ink = Color(0xFF1F2733)
val Ink2 = Color(0xFF4A5568)
val Muted = Color(0xFF5B6879)
val Line = Color(0xFFE3E8EF)
val LineStrong = Color(0xFFD3DBE6)
val Ok = Color(0xFF1B6E34)
val OkBg = Color(0xFFEAF6EE)
val OkBd = Color(0xFFBFE3CB)
val Warn = Color(0xFF8A5A00)
val WarnBg = Color(0xFFFFF7E8)
val WarnBd = Color(0xFFF3DCA8)
val Danger = Color(0xFFA03A3A)
val DangerBg = Color(0xFFFDEDED)
val DangerBd = Color(0xFFF3C7C7)
val Info = Color(0xFF1C4B8C)
val InfoBg = Color(0xFFEEF5FF)
val Panel = Color(0xFF121A2A)
val Panel2 = Color(0xFF1A2536)
val PanelInk = Color(0xFFE6EDF5)
val PanelMuted = Color(0xFFA9B8CA)
val PanelFaint = Color(0xFF7C90A8)

// 7 态状态灯
val StIdle = Color(0xFFA9B8CA)
val StListening = Color(0xFF22D3EE)
val StWindow = Color(0xFF5EEAD4)
val StExecuting = Color(0xFF34D399)
val StPaused = Color(0xFFFBBF24)
val StRecogOff = Color(0xFF7C90A8)
val StSilent = Color(0xFF94A3B8)

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
