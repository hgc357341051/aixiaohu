package com.aixiaohuadb.ui.onboarding

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aixiaohuadb.ui.common.IconCircle
import com.aixiaohuadb.ui.common.PrimaryButton
import com.aixiaohuadb.ui.common.SecondaryButton
import com.aixiaohuadb.ui.common.WarningCard
import com.aixiaohuadb.ui.theme.Bg
import com.aixiaohuadb.ui.theme.BrandSoft
import com.aixiaohuadb.ui.theme.LgShape
import com.aixiaohuadb.ui.theme.Muted
import com.aixiaohuadb.ui.theme.Panel
import com.aixiaohuadb.ui.theme.PanelInk
import com.aixiaohuadb.ui.theme.PanelMuted
import com.aixiaohuadb.ui.theme.Spacing
import com.aixiaohuadb.ui.theme.StWindow

@Composable
fun OnboardingScreen(onNext: () -> Unit, onSkip: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .verticalScroll(rememberScrollState()),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .padding(Spacing.lg)
                .background(Panel, LgShape),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "VOICE CONTROL · 自用测试版",
                    style = MaterialTheme.typography.labelSmall,
                    color = PanelMuted,
                )
                Spacer(Modifier.height(Spacing.sm))
                Text("小胡，帮我换装", style = MaterialTheme.typography.displayMedium, color = PanelInk)
                Text(
                    "王者荣耀保命装备语音切换 · 端侧识别",
                    style = MaterialTheme.typography.bodySmall,
                    color = PanelMuted,
                )
                Spacer(Modifier.height(Spacing.xl))
                // 装饰性 hero：外圈辉光 + 内圈波纹 + 麦克风图标
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .size(104.dp)
                            .background(StWindow.copy(alpha = 0.10f), CircleShape),
                    )
                    Box(
                        modifier = Modifier
                            .size(84.dp)
                            .background(StWindow.copy(alpha = 0.16f), CircleShape),
                    )
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .background(BrandSoft, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Mic,
                            contentDescription = null,
                            tint = com.aixiaohuadb.ui.theme.Brand,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                }
            }
        }
        Column(modifier = Modifier.padding(Spacing.lg)) {
            Surface(
                shape = com.aixiaohuadb.ui.theme.CardShape,
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(Spacing.lg)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconCircle(
                            icon = Icons.Filled.GraphicEq,
                            tint = com.aixiaohuadb.ui.theme.Brand,
                            container = BrandSoft,
                            size = 32.dp,
                        )
                        Spacer(Modifier.width(Spacing.sm))
                        Text(
                            "它是怎么工作的？",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    FeatureLine(
                        icon = Icons.Filled.Security,
                        title = "两级唤醒 + 声纹门控",
                        desc = "「你好小胡 / 小胡小胡」，只响应机主本人",
                    )
                    FeatureLine(
                        icon = Icons.Filled.Timer,
                        title = "14 类指令，3 秒窗口",
                        desc = "换名刀 / 复活甲 / 1–6 号位 / 切换位置",
                    )
                    FeatureLine(
                        icon = Icons.Filled.PhoneAndroid,
                        title = "端侧处理，不上传",
                        desc = "语音与声纹模板仅存本机",
                    )
                }
            }
            Spacer(Modifier.height(Spacing.md))
            WarningCard("代操作风险提示：模拟点击可能违反游戏协议导致封号。仅限自有设备与测试账号、学习研究使用，不发布、不牟利，风险自担。")
            PrimaryButton(text = "下一步 · 权限说明", onClick = onNext)
            Spacer(Modifier.height(Spacing.sm))
            SecondaryButton(text = "暂不开启 · 用手动模式", onClick = onSkip)
        }
    }
}

@Composable
private fun FeatureLine(icon: ImageVector, title: String, desc: String) {
    Row(
        modifier = Modifier.padding(top = Spacing.md),
        verticalAlignment = Alignment.Top,
    ) {
        IconCircle(
            icon = icon,
            tint = com.aixiaohuadb.ui.theme.Brand,
            container = BrandSoft,
            size = 30.dp,
        )
        Spacer(Modifier.width(Spacing.sm))
        Column {
            Text(title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            Text(desc, style = MaterialTheme.typography.labelSmall, color = Muted)
        }
    }
}
