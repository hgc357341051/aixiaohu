package com.example.voicectl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PowerOff
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.voicectl.machine.UiStateInfo
import com.example.voicectl.ui.common.IconCircle
import com.example.voicectl.ui.common.StatusBadge
import com.example.voicectl.ui.common.Tone
import com.example.voicectl.ui.theme.Brand
import com.example.voicectl.ui.theme.BrandSoft
import com.example.voicectl.ui.theme.ButtonShape
import com.example.voicectl.ui.theme.Danger
import com.example.voicectl.ui.theme.DangerBg
import com.example.voicectl.ui.theme.LgShape
import com.example.voicectl.ui.theme.Muted
import com.example.voicectl.ui.theme.Panel
import com.example.voicectl.ui.theme.PanelInk
import com.example.voicectl.ui.theme.PanelMuted
import com.example.voicectl.ui.theme.Spacing
import com.example.voicectl.ui.theme.Warn
import com.example.voicectl.ui.theme.WarnBg

/** 通知栏覆盖层：原型 shade，提供暂停 / 退出 / 恢复入口 */
@Composable
fun NotificationShade(
    info: UiStateInfo,
    onPause: () -> Unit,
    onExit: () -> Unit,
    onResume: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Panel.copy(alpha = 0.72f))
            .clickable { onDismiss() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(start = Spacing.lg, end = Spacing.lg, top = 44.dp)
                .clickable(enabled = false) {},
        ) {
            NotificationCard(info, onPause, onExit, onResume)
            Spacer(Modifier.height(Spacing.sm))
            NotificationHint("退出唤醒后，点悬浮球 / 通知栏可手动恢复")
            Spacer(Modifier.height(Spacing.md))
            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("收起通知栏") }
            Text(
                "下拉通知栏 · 常驻操作入口",
                color = PanelMuted,
                modifier = Modifier
                    .padding(top = Spacing.md)
                    .fillMaxWidth(),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun NotificationCard(info: UiStateInfo, onPause: () -> Unit, onExit: () -> Unit, onResume: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = LgShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
    ) {
        Row(modifier = Modifier.padding(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
            IconCircle(icon = Icons.Filled.Mic, tint = Brand, container = BrandSoft, size = 42.dp)
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Text("小胡 · 语音控制", style = MaterialTheme.typography.bodySmall)
                Text("前台服务运行中", style = MaterialTheme.typography.labelSmall, color = Muted)
                Spacer(Modifier.height(Spacing.xs))
                StatusBadge(text = info.notif, tone = Tone.BRAND)
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md)) {
            ShadeAction(
                text = "暂停",
                icon = Icons.Filled.PauseCircle,
                container = WarnBg,
                content = Warn,
                modifier = Modifier.weight(1f),
                onClick = onPause,
            )
            Spacer(Modifier.width(Spacing.sm))
            ShadeAction(
                text = "退出",
                icon = Icons.Filled.PowerOff,
                container = DangerBg,
                content = Danger,
                modifier = Modifier.weight(1f),
                onClick = onExit,
            )
            Spacer(Modifier.width(Spacing.sm))
            ShadeAction(
                text = "恢复",
                icon = Icons.Filled.Restore,
                container = BrandSoft,
                content = Brand,
                modifier = Modifier.weight(1f),
                onClick = onResume,
            )
        }
        Spacer(Modifier.height(Spacing.md))
    }
}

@Composable
private fun ShadeAction(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
        shape = ButtonShape,
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(Spacing.xs))
        Text(text, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun NotificationHint(text: String) {
    Surface(shape = LgShape, color = Panel) {
        Row(modifier = Modifier.padding(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Mic,
                contentDescription = null,
                tint = PanelMuted,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(text, color = PanelInk, style = MaterialTheme.typography.labelSmall)
        }
    }
}
