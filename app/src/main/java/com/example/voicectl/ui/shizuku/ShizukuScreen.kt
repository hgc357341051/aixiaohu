package com.example.voicectl.ui.shizuku

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PowerOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.voicectl.BuildConfig
import com.example.voicectl.shizuku.ActivationGuide
import com.example.voicectl.shizuku.CommandValidator
import com.example.voicectl.shizuku.GuideAction
import com.example.voicectl.shizuku.GuideStep
import com.example.voicectl.shizuku.InputCommand
import com.example.voicectl.shizuku.ShizukuPhase
import com.example.voicectl.shizuku.ShizukuStatus
import com.example.voicectl.ui.common.CodeBlock
import com.example.voicectl.ui.common.QuietButton
import com.example.voicectl.ui.common.ScreenFrame
import com.example.voicectl.ui.common.SectionCard
import com.example.voicectl.ui.common.SectionTitle
import com.example.voicectl.ui.common.StatusBadge
import com.example.voicectl.ui.common.Tone
import com.example.voicectl.ui.common.WarningCard
import com.example.voicectl.ui.theme.Brand
import com.example.voicectl.ui.theme.ButtonShape
import com.example.voicectl.ui.theme.Muted
import com.example.voicectl.ui.theme.Ok
import com.example.voicectl.ui.theme.OkBg
import com.example.voicectl.ui.theme.OnBrand
import com.example.voicectl.ui.theme.Spacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class PhaseMeta(
    val title: String,
    val description: String,
    val buttonLabel: String,
    val icon: ImageVector,
    val tone: Tone,
)

private enum class StepState { DONE, CURRENT, LOCKED }

@Composable
fun ShizukuScreen(
    vm: ShizukuViewModel,
    onBack: () -> Unit,
    onTab: (String) -> Unit,
) {
    val ui by vm.ui.collectAsState()
    val status = ui.status
    val env = status.env
    val meta = phaseMeta(status.phase)

    // onResume 主动重查：官方无授权撤销监听器，这是六态不失真的重要入口之一
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.onResume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    ScreenFrame(
        title = "Shizuku 无线调试",
        subtitle = "备用执行链路 · 强类型输入",
        showBack = true,
        onBack = onBack,
        onTab = onTab,
    ) {
        SectionCard(icon = meta.icon, title = meta.title, tone = meta.tone) {
            Text(meta.description, style = MaterialTheme.typography.bodySmall)
            if (status.reason != null) {
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    "现状：${status.reason}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                )
            }
            Spacer(Modifier.height(Spacing.md))
            Button(
                onClick = { primaryAction(vm, status, ui.guide) },
                modifier = Modifier.fillMaxWidth().height(46.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Brand, contentColor = OnBrand),
                shape = ButtonShape,
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
            ) { Text(meta.buttonLabel, style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(Spacing.xs))
            Text(
                "最近检测：${formatTime(status.checkedAtMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
            )
        }

        if (ui.message != null) {
            StatusBadge(text = ui.message!!, tone = Tone.INFO)
            Spacer(Modifier.height(Spacing.sm))
        }

        SectionCard(icon = Icons.AutoMirrored.Filled.ListAlt, title = "激活步骤") {
            val currentIndex = currentStepIndex(status.phase, ui.guide)
            ui.guide.steps.forEachIndexed { index, step ->
                val state = when {
                    index < currentIndex || status.phase == ShizukuPhase.AUTHORIZED -> StepState.DONE
                    index == currentIndex -> StepState.CURRENT
                    else -> StepState.LOCKED
                }
                GuideStepRow(
                    number = index + 1,
                    step = step,
                    state = state,
                    onClick = { step.action?.let(vm::handleGuideAction) },
                )
            }
        }

        SectionCard(icon = Icons.Filled.Memory, title = "真机环境快照") {
            InfoLine("SDK_INT", env.sdkInt.toString())
            InfoLine("Shizuku 已安装", env.shizukuInstalled.toString())
            InfoLine("Binder 存活", env.binderAlive.toString())
            InfoLine("Shizuku API", env.shizukuApiVersion?.toString() ?: "—")
            InfoLine("UID", env.uid?.toString() ?: "—")
            InfoLine("特权身份", env.privilege.name)
            InfoLine("SELinux", env.selinuxContext ?: "—")
            InfoLine("屏幕", "${env.screenWidth}×${env.screenHeight}")
        }

        if (BuildConfig.DEBUG_PAGE_VISIBLE) {
            SectionTitle("自测（真实输入命令）")
            SectionCard(icon = Icons.Filled.BugReport, title = "输入桥自测") {
                WarningCard("以下操作会向当前屏幕注入真实输入事件，仅用于本机验证；结果显示真实命令、退出码与耗时。")
                Row(modifier = Modifier.fillMaxWidth()) {
                    QuietButton(text = "探测 input", onClick = { vm.probe() })
                    QuietButton(text = "点击中心", onClick = {
                        vm.selfTest(InputCommand.Tap(env.screenWidth / 2, env.screenHeight / 2))
                    })
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    QuietButton(text = "滑动", onClick = {
                        vm.selfTest(
                            InputCommand.Swipe(
                                fromX = env.screenWidth / 2,
                                fromY = env.screenHeight * 3 / 4,
                                toX = env.screenWidth / 2,
                                toY = env.screenHeight / 4,
                                durationMs = 300,
                            ),
                        )
                    })
                    QuietButton(text = "HOME 键", onClick = {
                        vm.selfTest(InputCommand.KeyEvent(CommandValidator.KEYCODE_HOME))
                    })
                }
                val probe = ui.probeResult
                if (probe != null) {
                    CodeBlock(
                        title = "probe 结果",
                        code = "exit=${probe.exitCode ?: "—"} · ok=${probe.ok} · ${probe.elapsedMs}ms\n${probe.message}",
                    )
                }
                val selfTest = ui.selfTestResult
                if (selfTest != null) {
                    CodeBlock(
                        title = "命令执行结果",
                        code = "${ui.selfTestCommand ?: "—"}\n" +
                            "exit=${selfTest.exitCode ?: "—"} · ok=${selfTest.ok} · ${selfTest.elapsedMs}ms\n" +
                            selfTest.message,
                    )
                }
            }
        }

        WarningCard(
            "安全边界 · 仅发系统输入事件：无字符串命令入口；AIDL 只有 int 参数；" +
                "shell 进程仅调用 /system/bin/input 的固定子命令；" +
                "坐标、时长与 keyCode 双重校验；不读 /data/data、不 Hook、不向应用内存注入代码。",
        )
        Spacer(Modifier.height(Spacing.sm))
    }
}

@Composable
private fun GuideStepRow(
    number: Int,
    step: GuideStep,
    state: StepState,
    onClick: () -> Unit,
) {
    val background = when (state) {
        StepState.DONE -> OkBg
        StepState.CURRENT -> Brand
        StepState.LOCKED -> MaterialTheme.colorScheme.surfaceVariant
    }
    val foreground = when (state) {
        StepState.DONE -> Ok
        StepState.CURRENT -> OnBrand
        StepState.LOCKED -> Muted
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = step.action != null, onClick = onClick)
            .padding(vertical = Spacing.sm)
            .alpha(if (state == StepState.LOCKED) 0.5f else 1f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(shape = CircleShape, color = background, modifier = Modifier.size(24.dp)) {
            Box(contentAlignment = Alignment.Center) {
                if (state == StepState.DONE) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = "已完成",
                        tint = foreground,
                        modifier = Modifier.size(14.dp),
                    )
                } else {
                    Text(number.toString(), color = foreground, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Spacer(Modifier.width(Spacing.sm))
        Column(Modifier.weight(1f)) {
            Text(step.title, style = MaterialTheme.typography.bodySmall)
            Text(step.detail, style = MaterialTheme.typography.labelSmall, color = Muted)
        }
        if (state == StepState.CURRENT && step.action != null) {
            Text("点此前往", color = Brand, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Muted, modifier = Modifier.width(96.dp))
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

private fun phaseMeta(phase: ShizukuPhase): PhaseMeta = when (phase) {
    ShizukuPhase.NOT_INSTALLED -> PhaseMeta(
        "未安装 Shizuku",
        "需要先安装 Shizuku App，本应用不内置任何提权组件。",
        "打开官方下载页",
        Icons.Filled.Download,
        Tone.WARN,
    )
    ShizukuPhase.NOT_RUNNING -> PhaseMeta(
        "Shizuku 服务未运行",
        "设备上的 Shizuku 服务端尚未启动，请按下方步骤启动后再检测。",
        "重新检测",
        Icons.Filled.PowerOff,
        Tone.WARN,
    )
    ShizukuPhase.UNAUTHORIZED -> PhaseMeta(
        "等待授权",
        "服务端已运行，但本应用尚未获得 Shizuku 授权。",
        "请求授权",
        Icons.Filled.Lock,
        Tone.INFO,
    )
    ShizukuPhase.AUTHORIZED -> PhaseMeta(
        "已授权 · 可执行",
        "执行链路就绪；每次执行前都会重查 binder 与授权。",
        "重新检测",
        Icons.Filled.CheckCircle,
        Tone.OK,
    )
    ShizukuPhase.AUTHORIZATION_LOST -> PhaseMeta(
        "授权已失效",
        "服务端仍在运行，但本应用的授权已被撤销，需要重新授权。",
        "重新授权",
        Icons.Filled.Error,
        Tone.DANGER,
    )
    ShizukuPhase.UNSUPPORTED -> PhaseMeta(
        "版本不支持",
        "当前 Shizuku 版本过低（需 API ≥ 10），请升级 Shizuku App 后重试。",
        "打开官方下载页",
        Icons.Filled.Error,
        Tone.DANGER,
    )
}

private fun primaryAction(vm: ShizukuViewModel, status: ShizukuStatus, guide: ActivationGuide) {
    when (status.phase) {
        ShizukuPhase.NOT_INSTALLED,
        ShizukuPhase.UNSUPPORTED,
        -> guide.steps.firstOrNull { it.action is GuideAction.OpenDownload }
            ?.action?.let(vm::handleGuideAction) ?: vm.recheck()
        ShizukuPhase.UNAUTHORIZED,
        ShizukuPhase.AUTHORIZATION_LOST,
        -> vm.requestPermission()
        else -> vm.recheck()
    }
}

private fun currentStepIndex(phase: ShizukuPhase, guide: ActivationGuide): Int {
    if (phase == ShizukuPhase.AUTHORIZED) return guide.steps.size
    val matches: (GuideAction?) -> Boolean = when (phase) {
        ShizukuPhase.NOT_INSTALLED, ShizukuPhase.UNSUPPORTED -> { action ->
            action is GuideAction.OpenDownload
        }
        ShizukuPhase.NOT_RUNNING -> { action ->
            action is GuideAction.OpenWirelessDebugging || action is GuideAction.OpenShizukuApp
        }
        ShizukuPhase.UNAUTHORIZED, ShizukuPhase.AUTHORIZATION_LOST -> { action ->
            action is GuideAction.Recheck
        }
        ShizukuPhase.AUTHORIZED -> { _ -> false }
    }
    val found = guide.steps.indexOfFirst { matches(it.action) }
    return if (found >= 0) found else 0
}

private fun formatTime(epochMs: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(epochMs))
