package com.aixiaohuadb.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aixiaohuadb.ui.theme.Brand
import com.aixiaohuadb.ui.theme.BrandSoft
import com.aixiaohuadb.ui.theme.BrandTint
import com.aixiaohuadb.ui.theme.ButtonShape
import com.aixiaohuadb.ui.theme.CardShape
import com.aixiaohuadb.ui.theme.Danger
import com.aixiaohuadb.ui.theme.Ink
import com.aixiaohuadb.ui.theme.Line
import com.aixiaohuadb.ui.theme.LineStrong
import com.aixiaohuadb.ui.theme.Muted
import com.aixiaohuadb.ui.theme.OnBrand
import com.aixiaohuadb.ui.theme.Panel
import com.aixiaohuadb.ui.theme.PanelFaint
import com.aixiaohuadb.ui.theme.SmShape
import com.aixiaohuadb.ui.theme.Spacing
import com.aixiaohuadb.ui.theme.Surface2
import com.aixiaohuadb.ui.theme.VioletBg

/**
 * GAME OPS 任务台 / 工作台共用组件。
 * 与通用 [InfoCard]/[SectionCard] 不同，这一组直接承载设计稿里的战术语义：
 * 链路状态、任务卡、录制入口、执行链与安全区。
 */

@Composable
fun OpsSectionLabel(title: String, trailing: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.md, bottom = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
            color = Muted,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.weight(1f))
        if (trailing != null) {
            Text(trailing, style = MaterialTheme.typography.labelSmall, color = PanelFaint)
        }
    }
}

@Composable
fun OpsStatusTile(
    label: String,
    value: String,
    icon: ImageVector,
    tint: Color = Brand,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = CardShape,
        color = Surface2,
        border = BorderStroke(1.dp, Line),
        modifier = modifier,
    ) {
        Column(Modifier.padding(Spacing.sm)) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp),
                color = PanelFaint,
            )
            Spacer(Modifier.height(Spacing.xs))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(13.dp), tint = tint)
                Spacer(Modifier.width(Spacing.xs))
                Text(
                    value,
                    style = MaterialTheme.typography.labelSmall,
                    color = Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun OpsHeroCard(
    title: String,
    description: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
    secondaryLabel: String? = null,
    onSecondary: () -> Unit = {},
    badge: String? = null,
) {
    Surface(
        shape = CardShape,
        color = Color.Transparent,
        border = BorderStroke(1.dp, Brand.copy(alpha = 0.38f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier.background(
                Brush.linearGradient(listOf(BrandSoft, VioletBg)),
            ),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 28.dp, y = (-32).dp)
                    .size(124.dp)
                    .border(1.dp, Brand.copy(alpha = 0.16f), CircleShape),
            )
            Column(Modifier.padding(Spacing.md)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        color = Ink,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.weight(1f))
                    if (badge != null) StatusBadge(text = badge, tone = Tone.BRAND)
                }
                Spacer(Modifier.height(Spacing.xs))
                Text(description, style = MaterialTheme.typography.labelSmall, color = Muted)
                Spacer(Modifier.height(Spacing.md))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Button(
                        onClick = onPrimary,
                        shape = ButtonShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Brand,
                            contentColor = OnBrand,
                        ),
                        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
                    ) {
                        Text(primaryLabel, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                    if (secondaryLabel != null) {
                        OutlinedButton(
                            onClick = onSecondary,
                            shape = ButtonShape,
                            border = BorderStroke(1.dp, LineStrong),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink),
                        ) {
                            Text(secondaryLabel, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun OpsMacroCard(
    name: String,
    summary: String,
    footerLeft: String,
    footerRight: String,
    onRun: () -> Unit,
    modifier: Modifier = Modifier,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    active: Boolean = false,
) {
    Surface(
        shape = CardShape,
        color = Surface2,
        border = BorderStroke(
            1.dp,
            if (active) Brand.copy(alpha = 0.5f) else Line,
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column {
            Column(Modifier.padding(Spacing.sm)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        name,
                        style = MaterialTheme.typography.bodySmall,
                        color = Ink,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onRun, modifier = Modifier.size(30.dp)) {
                        Surface(
                            shape = SmShape,
                            color = BrandSoft,
                            border = BorderStroke(1.dp, Brand.copy(alpha = 0.3f)),
                            modifier = Modifier.size(28.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Filled.PlayArrow,
                                    contentDescription = "运行宏",
                                    tint = Brand,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(Spacing.sm))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        footerLeft,
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.6.sp),
                        color = PanelFaint,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        footerRight,
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.6.sp),
                        color = PanelFaint,
                    )
                }
                if (onEdit != null || onDelete != null) {
                    Spacer(Modifier.height(Spacing.xs))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (onEdit != null) {
                            TextButton(
                                onClick = onEdit,
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.height(24.dp),
                            ) {
                                Text("编辑", color = Brand, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        if (onDelete != null) {
                            IconButton(onClick = onDelete, modifier = Modifier.size(26.dp)) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "删除宏",
                                    tint = Danger,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                    }
                }
            }
            if (active) {
                Box(Modifier.fillMaxWidth().height(2.dp).background(Brand))
            }
        }
    }
}

@Composable
fun OpsLinkChain() {
    Surface(shape = CardShape, color = Panel, border = BorderStroke(1.dp, Line), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.md)) {
            Text(
                "EXECUTION CHAIN",
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
                color = PanelFaint,
            )
            Spacer(Modifier.height(Spacing.sm))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("ADB", style = MaterialTheme.typography.bodySmall, color = Ink, fontWeight = FontWeight.Bold)
                Text(" → ", style = MaterialTheme.typography.bodySmall, color = Brand)
                Text("无障碍", style = MaterialTheme.typography.bodySmall, color = Ink, fontWeight = FontWeight.Bold)
                Text(" → ", style = MaterialTheme.typography.bodySmall, color = Brand)
                Text("只读回执", style = MaterialTheme.typography.bodySmall, color = Ink, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(Spacing.xs))
            Text("仅前一级未派发时降级，失败原因会显示，不静默跳过。", style = MaterialTheme.typography.labelSmall, color = PanelFaint)
        }
    }
}

@Composable
fun OpsMatrixCard(
    icon: ImageVector,
    title: String,
    status: String,
    description: String,
    primary: Boolean = false,
) {
    val accent = when {
        primary -> Brand
        status == "OFF" -> Muted
        else -> com.aixiaohuadb.ui.theme.Violet
    }
    Surface(
        shape = CardShape,
        color = if (primary) BrandTint else Surface2,
        border = BorderStroke(1.dp, if (primary) Brand.copy(alpha = 0.45f) else Line),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    title,
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    status,
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp),
                    color = accent,
                )
            }
            Spacer(Modifier.height(Spacing.sm))
            Text(description, style = MaterialTheme.typography.labelSmall, color = Muted)
        }
    }
}

@Composable
fun OpsSafeZone() {
    Surface(shape = CardShape, color = Panel, border = BorderStroke(1.dp, Line), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.md)) {
            Text(
                "安全区 / SAFE ZONE",
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
                color = Muted,
            )
            Spacer(Modifier.height(Spacing.sm))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .border(
                        1.dp,
                        Line,
                        CardShape,
                    )
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                com.aixiaohuadb.ui.theme.Warn.copy(alpha = 0.06f),
                                Brand.copy(alpha = 0.04f),
                            ),
                        ),
                        CardShape,
                    ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .background(com.aixiaohuadb.ui.theme.DangerBg, CardShape)
                        .padding(horizontal = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                ) {
                    Text("72dp KEEP-OUT", style = MaterialTheme.typography.labelSmall, color = Danger)
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = Spacing.sm)
                        .size(width = 22.dp, height = 46.dp)
                        .border(1.dp, com.aixiaohuadb.ui.theme.Violet.copy(alpha = 0.6f), SmShape),
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 14.dp, bottom = 14.dp)
                        .size(30.dp)
                        .background(BrandSoft, CircleShape)
                        .border(1.dp, Brand, CircleShape),
                )
            }
            Spacer(Modifier.height(Spacing.sm))
            Text(
                "顶部保留给状态栏/刘海与录制胶囊；右侧窄带放宏快捷；悬浮球固定在右下，不覆盖主要技能区。",
                style = MaterialTheme.typography.labelSmall,
                color = PanelFaint,
            )
        }
    }
}

@Composable
fun OpsClickableNote(text: String, trailing: String = "说明", onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = Muted, modifier = Modifier.weight(1f))
        Text(trailing, style = MaterialTheme.typography.labelSmall, color = Brand)
    }
}
