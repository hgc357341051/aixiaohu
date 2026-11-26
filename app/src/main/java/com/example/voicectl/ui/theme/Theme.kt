package com.example.voicectl.ui.theme

import android.provider.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalContext

val LocalStateCardColors = compositionLocalOf { StateCardColors(StIdle) }
val LocalReduceMotion = compositionLocalOf { false }

@Composable
fun VoiceCtlTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val reduceMotion = runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }.getOrDefault(false)

    val colors = lightColorScheme(
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

    CompositionLocalProvider(
        LocalStateCardColors provides StateCardColors(StIdle),
        LocalReduceMotion provides reduceMotion,
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
