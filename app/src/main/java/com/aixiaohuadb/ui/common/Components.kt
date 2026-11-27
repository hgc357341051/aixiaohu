package com.aixiaohuadb.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aixiaohuadb.BuildConfig
import com.aixiaohuadb.ui.theme.BadgeShape
import com.aixiaohuadb.ui.theme.Brand
import com.aixiaohuadb.ui.theme.BrandSoft
import com.aixiaohuadb.ui.theme.BrandStrong
import com.aixiaohuadb.ui.theme.ButtonShape
import com.aixiaohuadb.ui.theme.CardShape
import com.aixiaohuadb.ui.theme.Danger
import com.aixiaohuadb.ui.theme.DangerBg
import com.aixiaohuadb.ui.theme.DangerBd
import com.aixiaohuadb.ui.theme.Info
import com.aixiaohuadb.ui.theme.InfoBg
import com.aixiaohuadb.ui.theme.Line
import com.aixiaohuadb.ui.theme.MdShape
import com.aixiaohuadb.ui.theme.Muted
import com.aixiaohuadb.ui.theme.OnBrand
import com.aixiaohuadb.ui.theme.Ok
import com.aixiaohuadb.ui.theme.OkBg
import com.aixiaohuadb.ui.theme.OkBd
import com.aixiaohuadb.ui.theme.Panel
import com.aixiaohuadb.ui.theme.PanelInk
import com.aixiaohuadb.ui.theme.PanelMuted
import com.aixiaohuadb.ui.theme.PillShape
import com.aixiaohuadb.ui.theme.Spacing
import com.aixiaohuadb.ui.theme.Surface2
import com.aixiaohuadb.ui.theme.Warn
import com.aixiaohuadb.ui.theme.WarnBg
import com.aixiaohuadb.ui.theme.WarnBd

// ---------------------------------------------------------------------------
// 语义色板（供徽标 / 指标卡 / 区块卡统一取色，避免各页散写颜色）
// ---------------------------------------------------------------------------
enum class Tone { BRAND, OK, WARN, DANGER, INFO, NEUTRAL }

private data class ToneColors(val bg: Color, val fg: Color, val border: Color)

@Composable
private fun toneColors(tone: Tone): ToneColors = when (tone) {
    Tone.BRAND -> ToneColors(BrandSoft, BrandStrong, Brand.copy(alpha = 0.35f))
    Tone.OK -> ToneColors(OkBg, Ok, OkBd)
    Tone.WARN -> ToneColors(WarnBg, Warn, WarnBd)
    Tone.DANGER -> ToneColors(DangerBg, Danger, DangerBd)
    Tone.INFO -> ToneColors(InfoBg, Info, Info.copy(alpha = 0.3f))
    Tone.NEUTRAL -> ToneColors(
        MaterialTheme.colorScheme.surfaceVariant,
        Muted,
        MaterialTheme.colorScheme.outlineVariant,
    )
}

// ---------------------------------------------------------------------------
// 页面骨架
// ---------------------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenFrame(
    title: String,
    subtitle: String? = null,
    showBack: Boolean = false,
    onBack: () -> Unit = {},
    activeTab: String = "",
    onTab: (String) -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background),
    ) {
        TopAppBar(
            title = {
                Column {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = Muted,
                        )
                    }
                }
            },
            navigationIcon = {
                if (showBack) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = Brand,
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
            ),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(Spacing.screenPadding),
            content = content,
        )
        TabBar(activeTab = activeTab, onTab = onTab)
    }
}

@Composable
private fun TabBar(activeTab: String, onTab: (String) -> Unit) {
    val tabs = mutableListOf(
        Triple("home", "主页", Icons.Filled.Home),
        Triple("panel", "面板", Icons.Filled.GridView),
        Triple("settings", "设置", Icons.Filled.Settings),
    )
    if (BuildConfig.DEBUG_PAGE_VISIBLE) {
        tabs += Triple("debug", "调试", Icons.Filled.BugReport)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Panel.copy(alpha = 0.96f))
            .padding(vertical = Spacing.sm),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        tabs.forEach { (route, label, icon) ->
            val selected = route == activeTab
            val fg = if (selected) com.aixiaohuadb.ui.theme.StWindow else PanelMuted
            Column(
                modifier = Modifier
                    .clickable { onTab(route) }
                    .padding(horizontal = Spacing.md, vertical = Spacing.xs),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .width(22.dp)
                        .height(3.dp)
                        .background(if (selected) fg else Color.Transparent, PillShape),
                )
                Spacer(Modifier.height(Spacing.xs))
                Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp), tint = fg)
                Spacer(Modifier.height(2.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = fg,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
fun SectionTitle(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.md, bottom = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        if (action != null) {
            QuietButton(text = action, onClick = onAction)
        }
    }
}

@Composable
fun InfoCard(
    contentPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(Spacing.md),
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = Spacing.md),
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}

/** 带图标 / 标题 / 动作的区块卡，统一纵向节奏 */
@Composable
fun SectionCard(
    icon: ImageVector? = null,
    title: String,
    action: String? = null,
    onAction: () -> Unit = {},
    tone: Tone = Tone.BRAND,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tc = toneColors(tone)
    InfoCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Spacing.md, end = Spacing.md, top = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                IconCircle(icon = icon, tint = tc.fg, container = tc.bg, size = 30.dp)
                Spacer(Modifier.width(Spacing.sm))
            }
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (action != null) {
                QuietButton(text = action, onClick = onAction)
            }
        }
        Column(modifier = Modifier.padding(Spacing.md), content = content)
    }
}

@Composable
fun PillChip(
    text: String,
    container: Color = BrandSoft,
    content: Color = BrandStrong,
) {
    Surface(
        shape = BadgeShape,
        color = container,
        border = BorderStroke(1.dp, content.copy(alpha = 0.28f)),
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = content,
        )
    }
}

/** 语义状态徽标：文字 + 可选图标 + 语义配色 */
@Composable
fun StatusBadge(
    text: String,
    tone: Tone = Tone.BRAND,
    icon: ImageVector? = null,
) {
    val tc = toneColors(tone)
    Surface(
        shape = BadgeShape,
        color = tc.bg,
        border = BorderStroke(1.dp, tc.border),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(13.dp), tint = tc.fg)
                Spacer(Modifier.width(Spacing.xs))
            }
            Text(text, style = MaterialTheme.typography.labelSmall, color = tc.fg)
        }
    }
}

@Composable
fun ToggleRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = Muted)
            }
        }
        Spacer(Modifier.width(Spacing.sm))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun Segment(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    Surface(
        shape = MdShape,
        color = Surface2,
        border = BorderStroke(1.dp, Line),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(3.dp)) {
            options.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                Button(
                    onClick = { onSelect(index) },
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selected) Brand else Color.Transparent,
                        contentColor = if (selected) OnBrand else Muted,
                    ),
                    shape = MdShape,
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String = "确认退出",
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = CardShape,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = { Text(text, style = MaterialTheme.typography.bodySmall) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = DangerBg, contentColor = Danger),
                shape = ButtonShape,
            ) { Text(confirmLabel) }
        },
        dismissButton = { QuietButton(text = "取消", onClick = onCancel) },
    )
}

@Composable
fun WarningCard(text: String) {
    Surface(
        shape = CardShape,
        color = WarnBg,
        border = BorderStroke(1.dp, WarnBd),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = Spacing.md),
    ) {
        Row(modifier = Modifier.padding(Spacing.md)) {
            StatusBadge(text = "注意", tone = Tone.WARN)
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = Warn,
            )
        }
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Brand, contentColor = OnBrand),
        shape = ButtonShape,
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp),
    ) { Text(text, style = MaterialTheme.typography.bodySmall) }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp),
        colors = ButtonDefaults.buttonColors(containerColor = BrandSoft, contentColor = BrandStrong),
        shape = ButtonShape,
        border = BorderStroke(1.dp, Line),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(Spacing.xs))
        }
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun DangerButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp),
        colors = ButtonDefaults.buttonColors(containerColor = DangerBg, contentColor = Danger),
        shape = ButtonShape,
        border = BorderStroke(1.dp, DangerBd),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
    ) { Text(text, style = MaterialTheme.typography.bodySmall) }
}

@Composable
fun QuietButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    TextButton(onClick = onClick, enabled = enabled) {
        Text(text, color = Brand, style = MaterialTheme.typography.labelSmall)
    }
}

/** 指标卡：数值 + 说明 + 语义色 */
@Composable
fun MetricCard(
    value: String,
    label: String,
    tone: Tone = Tone.BRAND,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
) {
    val tc = toneColors(tone)
    Surface(
        shape = CardShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(Spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (icon != null) {
                IconCircle(icon = icon, tint = tc.fg, container = tc.bg, size = 34.dp)
                Spacer(Modifier.height(Spacing.sm))
            }
            Text(
                value,
                style = MaterialTheme.typography.displayMedium,
                color = tc.fg,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(label, style = MaterialTheme.typography.labelSmall, color = Muted, textAlign = TextAlign.Center)
        }
    }
}

/** 等宽代码块：深色面板承载命令 / 预览文本 */
@Composable
fun CodeBlock(code: String, title: String? = null) {
    Surface(
        shape = CardShape,
        color = Panel,
        border = BorderStroke(1.dp, PanelMuted.copy(alpha = 0.2f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = Spacing.md),
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.labelSmall, color = PanelMuted)
                Spacer(Modifier.height(Spacing.sm))
            }
            Text(
                code,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
                color = PanelInk,
            )
        }
    }
}

/** 步骤行：序号圆点 + 标题 + 说明 */
@Composable
fun StepRow(number: Int, title: String, desc: String? = null) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.sm)) {
        Surface(shape = CircleShape, color = Brand, modifier = Modifier.size(24.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text(number.toString(), color = OnBrand, style = MaterialTheme.typography.labelSmall)
            }
        }
        Spacer(Modifier.width(Spacing.sm))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodySmall)
            if (desc != null) {
                Text(desc, style = MaterialTheme.typography.labelSmall, color = Muted)
            }
        }
    }
}

/** 图标圆底：列表 / 卡片 / hero 复用 */
@Composable
fun IconCircle(
    icon: ImageVector,
    tint: Color,
    container: Color,
    size: Dp = 40.dp,
) {
    Surface(shape = CircleShape, color = container, modifier = Modifier.size(size)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size((size.value * 0.52f).dp))
        }
    }
}

/** 圆形状态灯，C2/C3 场景复用 */
@Composable
fun StatusDot(color: Color) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(color, CircleShape)
            .border(1.dp, Line, CircleShape),
    )
}
