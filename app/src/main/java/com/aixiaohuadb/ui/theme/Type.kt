package com.aixiaohuadb.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** GAME OPS 字体：标题更紧、数字更重、正文保持可读。 */
val AppTypography = Typography(
    displayMedium = TextStyle(
        fontSize = 26.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
    ),
    headlineMedium = TextStyle(
        fontSize = 19.sp,
        lineHeight = 25.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.5.sp,
    ),
    titleMedium = TextStyle(
        fontSize = 17.sp,
        lineHeight = 23.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.3.sp,
    ),
    titleSmall = TextStyle(
        fontSize = 14.sp,
        lineHeight = 19.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(
        fontSize = 12.sp,
        lineHeight = 17.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.3.sp,
    ),
    labelMedium = TextStyle(
        fontSize = 11.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.2.sp,
    ),
    labelSmall = TextStyle(
        fontSize = 10.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.4.sp,
    ),
)
