package com.aixiaohuadb.ui.home

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aixiaohuadb.machine.UiStateInfo
import com.aixiaohuadb.ui.theme.LocalReduceMotion
import com.aixiaohuadb.ui.theme.LocalStateCardColors
import com.aixiaohuadb.ui.theme.LgShape
import com.aixiaohuadb.ui.theme.Panel
import com.aixiaohuadb.ui.theme.Panel2
import com.aixiaohuadb.ui.theme.PanelInk
import com.aixiaohuadb.ui.theme.PanelMuted
import com.aixiaohuadb.ui.theme.PillShape
import com.aixiaohuadb.ui.theme.Spacing
import com.aixiaohuadb.ui.theme.StateCardColors
import com.aixiaohuadb.ui.theme.StIdle
import com.aixiaohuadb.ui.theme.stateAccents
import kotlin.math.abs
import kotlin.math.sin

/**
 * 7 态共用深色状态卡：呼吸光环 + 麦克风图标 + 7 根波形 + 静态语义底栏。
 * 强调色由当前 state key 决定并通过 [LocalStateCardColors] 下发，杜绝固定 StIdle。
 * 注意：UiState 没有「剩余时间」字段，底栏只显示语义文案，不再用无限循环伪造倒计时。
 */
@Composable
fun StateCard(info: UiStateInfo) {
    val reduceMotion = LocalReduceMotion.current
    val accent = stateAccents[info.key] ?: StIdle

    CompositionLocalProvider(LocalStateCardColors provides StateCardColors(accent = accent)) {
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

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(236.dp),
            shape = LgShape,
            color = Panel,
            border = BorderStroke(1.dp, accent.copy(alpha = 0.28f)),
            shadowElevation = 8.dp,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(accent),
                )
                Column(modifier = Modifier.fillMaxSize().padding(Spacing.lg)) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = PillShape,
                            color = Panel2,
                            border = BorderStroke(1.dp, accent.copy(alpha = 0.3f)),
                        ) {
                            Text(
                                info.chip,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = accent,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        val pulse = if (info.animated && !reduceMotion) {
                            0.55f + 0.45f * abs(sin(wavePhase * 3.14159f))
                        } else 1f
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .graphicsLayer { alpha = pulse }
                                .background(accent, PillShape),
                        )
                        Spacer(Modifier.width(Spacing.sm))
                        Text(info.stateLabel, style = MaterialTheme.typography.labelSmall, color = PanelMuted)
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        // 双层呼吸光环 + 麦克风图标
                        Box(contentAlignment = Alignment.Center) {
                            Box(
                                modifier = Modifier
                                    .size(84.dp)
                                    .graphicsLayer {
                                        val scale = if (info.animated && !reduceMotion) breath else 1f
                                        scaleX = scale
                                        scaleY = scale
                                        alpha = 0.12f
                                    }
                                    .background(accent, PillShape),
                            )
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .graphicsLayer {
                                        val scale = if (info.animated && !reduceMotion) breath else 1f
                                        scaleX = scale
                                        scaleY = scale
                                    }
                                    .border(BorderStroke(1.5.dp, accent), PillShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                val micIcon = if (info.key == "silent" || info.key == "recogoff") {
                                    Icons.Filled.MicOff
                                } else {
                                    Icons.Filled.Mic
                                }
                                Icon(micIcon, contentDescription = null, tint = accent, modifier = Modifier.size(26.dp))
                            }
                        }
                        Spacer(Modifier.height(Spacing.sm))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            repeat(7) { index ->
                                val barHeight = if (info.animated && !reduceMotion) {
                                    (5f + 9f * abs(sin(wavePhase * 6.283f + index * 0.62f))).dp
                                } else 5.dp
                                Box(
                                    modifier = Modifier
                                        .padding(horizontal = 1.5.dp)
                                        .width(3.dp)
                                        .height(barHeight)
                                        .background(accent.copy(alpha = 0.85f), PillShape),
                                )
                            }
                        }
                        Spacer(Modifier.height(Spacing.sm))
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
                            border = BorderStroke(1.dp, accent.copy(alpha = 0.22f)),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Filled.GraphicEq,
                                    contentDescription = null,
                                    tint = accent,
                                    modifier = Modifier.size(13.dp),
                                )
                                Spacer(Modifier.width(Spacing.xs))
                                Text(info.bottom, style = MaterialTheme.typography.labelSmall, color = PanelInk)
                            }
                        }
                    }
                }
            }
        }
    }
}
