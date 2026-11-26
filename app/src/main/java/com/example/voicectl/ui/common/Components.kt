package com.example.voicectl.ui.common

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.voicectl.BuildConfig
import com.example.voicectl.ui.theme.Brand
import com.example.voicectl.ui.theme.BrandSoft
import com.example.voicectl.ui.theme.BrandStrong
import com.example.voicectl.ui.theme.Danger
import com.example.voicectl.ui.theme.Line
import com.example.voicectl.ui.theme.MdShape
import com.example.voicectl.ui.theme.Muted
import com.example.voicectl.ui.theme.OnBrand
import com.example.voicectl.ui.theme.PillShape
import com.example.voicectl.ui.theme.Surface2
import com.example.voicectl.ui.theme.Warn
import com.example.voicectl.ui.theme.WarnBg

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
    Column(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = {
                Column {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (subtitle != null) {
                        Text(subtitle, style = MaterialTheme.typography.labelSmall, color = Muted)
                    }
                }
            },
            navigationIcon = {
                if (showBack) {
                    TextButton(onClick = onBack) {
                        Text("返回", color = Brand, style = MaterialTheme.typography.labelMedium)
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.White,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
            ),
        )
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(14.dp),
            content = content,
        )
        TabBar(activeTab = activeTab, onTab = onTab)
    }
}

@Composable
private fun TabBar(activeTab: String, onTab: (String) -> Unit) {
    val tabs = mutableListOf("home" to "主页", "panel" to "面板", "settings" to "设置")
    if (BuildConfig.DEBUG_PAGE_VISIBLE) tabs += "debug" to "调试"
    Row(
        modifier = Modifier.fillMaxWidth().background(Color.White).padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        tabs.forEach { (route, label) ->
            val selected = route == activeTab
            Column(
                modifier = Modifier
                    .clickable { onTab(route) }
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .width(22.dp).height(3.dp)
                        .background(if (selected) Brand else Color.Transparent),
                )
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) Brand else Muted,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
fun SectionTitle(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        if (action != null) {
            TextButton(onClick = onAction) { Text(action, color = Brand, style = MaterialTheme.typography.labelSmall) }
        }
    }
}

@Composable
fun InfoCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        shape = MdShape,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        content = content,
    )
}

@Composable
fun PillChip(
    text: String,
    container: Color = BrandSoft,
    content: Color = BrandStrong,
) {
    Surface(
        shape = PillShape,
        color = container,
        border = androidx.compose.foundation.BorderStroke(1.dp, Line.copy(alpha = 0.6f)),
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = content,
        )
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
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = Muted)
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun Segment(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().background(Surface2).padding(3.dp),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Button(
                onClick = { onSelect(index) },
                modifier = Modifier.weight(1f).height(36.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selected) Color.White else Surface2,
                    contentColor = if (selected) Brand else Muted,
                ),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
            ) {
                Text(label, style = MaterialTheme.typography.labelSmall)
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
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = { Text(text, style = MaterialTheme.typography.bodySmall) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel, color = Danger) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("取消", color = Brand) } },
    )
}

@Composable
fun WarningCard(text: String) {
    Surface(
        shape = MdShape,
        color = WarnBg,
        border = androidx.compose.foundation.BorderStroke(1.dp, Warn),
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
    ) {
        Text(text, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.labelSmall, color = Warn)
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Brand, contentColor = OnBrand),
        shape = MdShape,
    ) { Text(text, style = MaterialTheme.typography.bodySmall) }
}

/** 圆形状态灯，C2/C3 场景复用 */
@Composable
fun StatusDot(color: Color) {
    Box(
        modifier = Modifier
            .width(8.dp).height(8.dp)
            .background(color, CircleShape)
            .border(1.dp, Line, CircleShape),
    )
}
