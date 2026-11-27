package com.aixiaohuadb.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// 圆角 token，逐字对应 CSS --r-xs..--r-pill
val XsShape = RoundedCornerShape(6.dp)
val SmShape = RoundedCornerShape(9.dp)
val MdShape = RoundedCornerShape(12.dp)
val LgShape = RoundedCornerShape(16.dp)
val PillShape = RoundedCornerShape(999.dp)

// 语义别名：页面/组件优先使用语义名，避免直接挑数字形状
val CardShape = MdShape
val FieldShape = SmShape
val ButtonShape = MdShape
val PanelShape = LgShape
val BadgeShape = PillShape
val DialogShape = LgShape

val AppShapes = Shapes(
    extraSmall = XsShape,
    small = SmShape,
    medium = MdShape,
    large = LgShape,
)
