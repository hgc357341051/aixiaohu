package com.aixiaohuadb.ui.theme

import android.provider.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * 由主题向深色状态卡下发当前状态的强调色。
 * 默认参数保证既有 VoiceCtlTheme { } 调用点无需改动；
 * StateCard 也会按自身 state key 再覆盖一次，确保任何调用路径下颜色都与真实状态一致。
 */
val LocalStateCardColors = compositionLocalOf { StateCardColors(StIdle) }
val LocalReduceMotion = compositionLocalOf { false }

/** 应用强制使用 GAME OPS 深色主题；不再跟随系统浅色，避免两套视觉并存。 */
private val OpsColors = darkColorScheme(
    primary = Brand,
    onPrimary = OnBrand,
    primaryContainer = BrandSoft,
    onPrimaryContainer = Brand,
    secondary = Violet,
    onSecondary = OnBrand,
    secondaryContainer = VioletBg,
    onSecondaryContainer = Violet,
    error = Danger,
    onError = OnBrand,
    errorContainer = DangerBg,
    onErrorContainer = Danger,
    background = Bg,
    onBackground = Ink,
    surface = Surface,
    onSurface = Ink,
    surfaceVariant = Surface2,
    onSurfaceVariant = Muted,
    outline = LineStrong,
    outlineVariant = Line,
)

@Composable
fun VoiceCtlTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val reduceMotion = runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }.getOrDefault(false)

    CompositionLocalProvider(
        LocalStateCardColors provides StateCardColors(accent = StIdle),
        LocalReduceMotion provides reduceMotion,
    ) {
        MaterialTheme(
            colorScheme = OpsColors,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
