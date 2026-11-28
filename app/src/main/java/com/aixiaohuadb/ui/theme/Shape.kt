package com.aixiaohuadb.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** GAME OPS 切角：三圆角 + 一个 4dp 小角，形成装备面板感。 */
private fun ops(radius: Dp): RoundedCornerShape =
    RoundedCornerShape(radius, radius, 4.dp, radius)

val XsShape = ops(6.dp)
val SmShape = ops(9.dp)
val MdShape = ops(12.dp)
val LgShape = ops(16.dp)
val PillShape = RoundedCornerShape(999.dp)

// 语义别名
val CardShape = MdShape
val FieldShape = SmShape
val ButtonShape = SmShape
val PanelShape = LgShape
val BadgeShape = PillShape
val DialogShape = LgShape

val AppShapes = Shapes(
    extraSmall = XsShape,
    small = SmShape,
    medium = MdShape,
    large = LgShape,
)
