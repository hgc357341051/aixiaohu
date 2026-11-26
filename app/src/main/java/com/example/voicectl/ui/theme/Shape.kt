package com.example.voicectl.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val XsShape = RoundedCornerShape(6.dp)
val SmShape = RoundedCornerShape(9.dp)
val MdShape = RoundedCornerShape(12.dp)
val LgShape = RoundedCornerShape(16.dp)
val PillShape = RoundedCornerShape(999.dp)

val AppShapes = Shapes(
    extraSmall = XsShape,
    small = SmShape,
    medium = MdShape,
    large = LgShape,
)
