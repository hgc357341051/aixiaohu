package com.example.voicectl.ui.home

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.voicectl.machine.UiStateInfo
import com.example.voicectl.ui.theme.LocalReduceMotion
import com.example.voicectl.ui.theme.LgShape
import com.example.voicectl.ui.theme.Panel2
import com.example.voicectl.ui.theme.PanelInk
import com.example.voicectl.ui.theme.PanelMuted
import com.example.voicectl.ui.theme.PillShape
import com.example.voicectl.ui.theme.StIdle
import com.example.voicectl.ui.theme.stateAccents
import kotlin.math.abs
import kotlin.math.sin

/** 7 态共用深色状态卡：呼吸 orb / 脉冲灯 / 7 根波形 / 倒计时条 */
@Composable
fun StateCard(info: UiStateInfo) {
    val reduceMotion = LocalReduceMotion.current
    val accent = stateAccents[info.key] ?: StIdle
    val transition = rememberInfiniteTransition(label = "state-card")
    val breath by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breath",
    )
    val wavePhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(1000), repeatMode = RepeatMode.Restart),
        label = "wave",
    )
    val countdown by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (info.key == "window") 3000 else 30000),
            repeatMode = RepeatMode.Restart,
        ),
        label = "countdown",
    )

    Card(
        modifier = Modifier.fillMaxWidth().height(236.dp),
        shape = LgShape,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF121A2A)),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().height(3.dp).background(accent),
            )
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = PillShape,
                        color = Panel2,
                        border = BorderStroke(1.dp, accent.copy(alpha = 0.25f)),
                    ) {
                        Text(
                            info.chip,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = PanelInk,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    val pulse = if (info.animated && !reduceMotion) {
                        0.6f + 0.4f * abs(sin(wavePhase * 3.14159f))
                    } else 1f
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .graphicsLayer { alpha = pulse }
                            .background(accent, PillShape),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(info.stateLabel, style = MaterialTheme.typography.labelSmall, color = PanelMuted)
                }

                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .graphicsLayer {
                                val scale = if (info.animated && !reduceMotion) breath else 1f
                                scaleX = scale
                                scaleY = scale
                            }
                            .border(BorderStroke(1.dp, accent), PillShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "MIC",
                            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = accent),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        repeat(7) { index ->
                            val height = if (info.animated && !reduceMotion) {
                                (5f + 9f * abs(sin(wavePhase * 6.283f + index * 0.62f))).dp
                            } else 5.dp
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 1.5.dp)
                                    .width(3.dp).height(height)
                                    .background(accent.copy(alpha = 0.85f), PillShape),
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        info.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = PanelInk,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(info.desc, style = MaterialTheme.typography.labelSmall, color = PanelMuted)
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Surface(
                        shape = PillShape,
                        color = Panel2,
                        border = BorderStroke(1.dp, accent.copy(alpha = 0.2f)),
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(info.bottom, style = MaterialTheme.typography.labelSmall, color = PanelInk)
                            if (info.key == "window" || info.key == "paused") {
                                Spacer(Modifier.height(6.dp))
                                Box(
                                    modifier = Modifier
                                        .width(150.dp).height(4.dp)
                                        .background(accent.copy(alpha = 0.18f), PillShape),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(if (reduceMotion) 1f else countdown)
                                            .height(4.dp)
                                            .background(accent, PillShape),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
