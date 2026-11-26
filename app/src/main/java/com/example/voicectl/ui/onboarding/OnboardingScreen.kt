package com.example.voicectl.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.voicectl.ui.common.WarningCard
import com.example.voicectl.ui.theme.Bg
import com.example.voicectl.ui.theme.Brand
import com.example.voicectl.ui.theme.BrandSoft
import com.example.voicectl.ui.theme.LgShape
import com.example.voicectl.ui.theme.MdShape
import com.example.voicectl.ui.theme.Muted
import com.example.voicectl.ui.theme.OnBrand
import com.example.voicectl.ui.theme.Panel
import com.example.voicectl.ui.theme.PanelInk
import com.example.voicectl.ui.theme.PanelMuted
import com.example.voicectl.ui.theme.StWindow

@Composable
fun OnboardingScreen(onNext: () -> Unit, onSkip: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().background(Bg)) {
        Box(
            modifier = Modifier.fillMaxWidth().height(260.dp).background(Panel, LgShape),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("VOICE CONTROL · 自用测试版", style = MaterialTheme.typography.labelSmall, color = PanelMuted)
                Spacer(Modifier.height(10.dp))
                Text("小胡，帮我换装", style = MaterialTheme.typography.displayMedium, color = PanelInk)
                Text("王者荣耀保命装备语音切换 · 端侧识别", style = MaterialTheme.typography.bodySmall, color = PanelMuted)
                Spacer(Modifier.height(24.dp))
                Box(
                    modifier = Modifier.size(84.dp).background(BrandSoft, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier.size(60.dp).background(Color.Transparent, CircleShape)
                            .then(Modifier.padding(1.dp)),
                    ) {
                        Text("MIC", color = StWindow, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Column(modifier = Modifier.padding(18.dp)) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MdShape,
                colors = CardDefaults.cardColors(containerColor = Color.White),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("它是怎么工作的？", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    FeatureLine("两级唤醒 + 声纹门控", "「你好小胡 / 小胡小胡」，只响应机主本人")
                    FeatureLine("14 类指令，3 秒窗口", "换名刀 / 复活甲 / 1–6 号位 / 切换位置")
                    FeatureLine("端侧处理，不上传", "语音与声纹模板仅存本机")
                }
            }
            Spacer(Modifier.height(12.dp))
            WarningCard("代操作风险提示：模拟点击可能违反游戏协议导致封号。仅限自有设备与测试账号、学习研究使用，不发布、不牟利，风险自担。")
            Button(
                onClick = onNext,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Brand, contentColor = OnBrand),
                shape = MdShape,
            ) { Text("下一步 · 权限说明", style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onSkip,
                modifier = Modifier.fillMaxWidth().height(46.dp),
                shape = MdShape,
            ) { Text("暂不开启 · 用手动模式", color = Muted, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun FeatureLine(title: String, desc: String) {
    Column(modifier = Modifier.padding(top = 10.dp)) {
        Text(title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        Text(desc, style = MaterialTheme.typography.labelSmall, color = Muted)
    }
}
