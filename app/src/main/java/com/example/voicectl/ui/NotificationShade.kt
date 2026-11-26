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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.voicectl.machine.UiStateInfo
import com.example.voicectl.ui.theme.Brand
import com.example.voicectl.ui.theme.Danger
import com.example.voicectl.ui.theme.DangerBg
import com.example.voicectl.ui.theme.LgShape
import com.example.voicectl.ui.theme.MdShape
import com.example.voicectl.ui.theme.Muted
import com.example.voicectl.ui.theme.Panel

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
        modifier = Modifier.fillMaxSize().background(Color(0xE0FFFFFF)).clickable { onDismiss() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(start = 14.dp, end = 14.dp, top = 44.dp)
                .clickable(enabled = false) {},
        ) {
            NotificationCard(info, onPause, onExit, onResume)
            Spacer(Modifier.height(8.dp))
            NotificationHint("退出唤醒后，点悬浮球 / 通知栏可手动恢复")
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("收起通知栏") }
            Text(
                "下拉通知栏 · 常驻操作入口",
                color = Muted,
                modifier = Modifier.padding(top = 12.dp).fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun NotificationCard(info: UiStateInfo, onPause: () -> Unit, onExit: () -> Unit, onResume: () -> Unit) {
    androidx.compose.material3.Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MdShape,
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Row(modifier = Modifier.padding(12.dp)) {
            Box(
                modifier = Modifier.size(34.dp).background(Brand, MdShape),
                contentAlignment = Alignment.Center,
            ) { Text("MIC", color = Color.White) }
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text("小胡 · 语音控制", style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                Text("前台服务运行中", style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = Muted)
                Text(info.notif, style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = Brand)
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            Button(
                onClick = onPause,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = com.example.voicectl.ui.theme.WarnBg,
                    contentColor = com.example.voicectl.ui.theme.Warn,
                ),
            ) { Text("暂停") }
            Spacer(Modifier.width(7.dp))
            Button(
                onClick = onExit,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = DangerBg, contentColor = Danger),
            ) { Text("退出") }
            Spacer(Modifier.width(7.dp))
            Button(onClick = onResume, modifier = Modifier.weight(1f)) { Text("恢复") }
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun NotificationHint(text: String) {
    androidx.compose.material3.Surface(shape = LgShape, color = Panel) {
        Text(text, color = Color(0xFFA9B8CA), style = androidx.compose.material3.MaterialTheme.typography.labelSmall, modifier = Modifier.padding(12.dp))
    }
}
