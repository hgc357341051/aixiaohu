package com.aixiaohuadb.ui.theme

import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * 由主题向深色状态卡下发当前状态的强调色。
 * 默认参数保证既有 VoiceCtlTheme { } 调用点无需改动；
 * StateCard 也会按自身 state key 再覆盖一次，确保任何调用路径下颜色都与真实状态一致。
 */
val LocalStateCardColors = compositionLocalOf { StateCardColors(StIdle) }
val LocalReduceMotion = compositionLocalOf { false }

private val LightColors = lightColorScheme(
    primary = Brand,
    onPrimary = OnBrand,
    primaryContainer = BrandSoft,
    onPrimaryContainer = BrandStrong,
    secondary = Warn,
    secondaryContainer = WarnBg,
    onSecondaryContainer = Warn,
    error = Danger,
    errorContainer = DangerBg,
    onErrorContainer = Danger,
    background = Bg,
    onBackground = Ink,
    surface = Surface,
    onSurface = Ink,
    surfaceVariant = Surface2,
    onSurfaceVariant = Ink2,
    outline = LineStrong,
    outlineVariant = Line,
)

/** 深色配色：状态面板底色 + 状态灯强调色，保证夜间/深色下对比度 */
private val DarkColors = darkColorScheme(
    primary = StListening,
    onPrimary = Panel,
    primaryContainer = BrandStrong,
    onPrimaryContainer = PanelInk,
    secondary = StWindow,
    onSecondary = Panel,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0D1420),
    onBackground = PanelInk,
    surface = Panel2,
    onSurface = PanelInk,
    surfaceVariant = Panel,
    onSurfaceVariant = PanelMuted,
    outline = PanelFaint,
    outlineVariant = Color(0xFF2A3A50),
)

@Composable
fun VoiceCtlTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val darkTheme = isSystemInDarkTheme()
    val reduceMotion = runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }.getOrDefault(false)

    CompositionLocalProvider(
        // 主题提供中性默认色；真实状态色由 StateCard 按 state key 再覆盖下发
        LocalStateCardColors provides StateCardColors(accent = StIdle),
        LocalReduceMotion provides reduceMotion,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
