package com.example.voicectl.ui.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 与视觉样式规范 CSS --s1..--s8 一一对应的间距 token。
 * 页面一律使用这里的 token，禁止散写裸 padding 数字。
 */
object Spacing {
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp

    /** ScreenFrame 滚动内容统一留白 */
    val screenPadding = PaddingValues(horizontal = lg, vertical = md)
}
