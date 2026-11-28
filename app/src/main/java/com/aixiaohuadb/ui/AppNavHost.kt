package com.aixiaohuadb.ui

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.aixiaohuadb.BuildConfig
import com.aixiaohuadb.di.AppContainer
import com.aixiaohuadb.machine.VoiceState
import com.aixiaohuadb.machine.ui
import com.aixiaohuadb.ui.adb.AdbScreen
import com.aixiaohuadb.ui.adb.AdbViewModel
import com.aixiaohuadb.ui.debug.DebugScreen
import com.aixiaohuadb.ui.debug.DebugViewModel
import com.aixiaohuadb.ui.home.HomeScreen
import com.aixiaohuadb.domain.macro.MacroAdapter
import com.aixiaohuadb.exec.CommandAction
import com.aixiaohuadb.service.OverlayStore
import com.aixiaohuadb.ui.macros.MacroEditorScreen
import com.aixiaohuadb.ui.macros.MacrosScreen
import com.aixiaohuadb.ui.mapping.MappingScreen
import com.aixiaohuadb.ui.mapping.MappingViewModel
import com.aixiaohuadb.ui.onboarding.OnboardingScreen
import com.aixiaohuadb.ui.overlay.OverlayControlScreen
import com.aixiaohuadb.ui.overlay.OverlayControlViewModel
import com.aixiaohuadb.ui.panel.PanelScreen
import com.aixiaohuadb.ui.permissions.PermissionsScreen
import com.aixiaohuadb.ui.settings.SettingsScreen
import com.aixiaohuadb.ui.settings.SettingsViewModel
import com.aixiaohuadb.ui.theme.Bg
import com.aixiaohuadb.ui.theme.Panel
import com.aixiaohuadb.ui.theme.PanelInk
import com.aixiaohuadb.ui.theme.PillShape
import com.aixiaohuadb.ui.voiceprint.VoiceprintScreen
import com.aixiaohuadb.ui.voiceprint.VoiceprintViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 单 Activity 导航图：10 个 destination + home/{state} 参数化承载机态 */
@Composable
fun AppNavHost(
    vm: AppViewModel,
    container: AppContainer,
    context: Context,
) {
    val nav = rememberNavController()
    val uiState by vm.ui.collectAsState()
    var shadeOpen by remember { mutableStateOf(false) }

    // 引导完成状态：持久化后冷启动直达主页，不再每次强制走 onboarding。
    // 用 null 区分「尚未读取完成」，期间显示纯深空底，避免首帧闪现引导页。
    val onboardingDone by container.settingsStore.onboardingDoneFlow.collectAsState(initial = null)
    val persistScope = rememberCoroutineScope()
    val markOnboardingDone: () -> Unit = {
        persistScope.launch { container.settingsStore.setOnboardingDone() }
    }
    if (onboardingDone == null) {
        Box(Modifier.fillMaxSize().background(Bg))
        return
    }
    val startDestination = if (onboardingDone == true) "home/disabled" else "onboarding"

    // 面板 / 映射 / 悬浮设置共用同一 ViewModel，避免 OverlayStore 多实例观察不一致。
    val overlayVm: OverlayControlViewModel = viewModel(
        factory = SimpleVmFactory { OverlayControlViewModel(container) },
    )

    // 动态路由（home/{state}、snapshot_annotation）即使与目标图不一致也不能把 App 带崩：
    // 旧日志里 nav.navigate("home") 曾直接抛 IllegalArgumentException 导致 FATAL。
    val navigateSafe: (String, NavOptionsBuilder.() -> Unit) -> Unit = { route, options ->
        runCatching { nav.navigate(route, options) }
    }

    val startRecordingAndBackground: () -> Unit = {
        com.aixiaohuadb.service.OverlayControlService.startRecording(context)
        // 先让服务真正处理 START_RECORD（添加录制层），再切后台；
        // 立即 moveTaskToBack 会让 startService 落到后台限制里，被静默拒绝。
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            (context as? Activity)?.moveTaskToBack(true)
        }, 600L)
    }

    val navigateTab: (String) -> Unit = navigateTab@{ route ->
        val target = if (route == "home") {
            "home/${uiState.voiceState.ui().key}"
        } else {
            route
        }
        if (nav.currentDestination?.route == target) return@navigateTab
        navigateSafe(target) {
            launchSingleTop = true
            popUpTo(target) { inclusive = true }
        }
    }

    LaunchedEffect(uiState.voiceState) {
        val route = nav.currentDestination?.route
        val target = "home/${uiState.voiceState.ui().key}"
        if (route?.startsWith("home/") == true && route != target) {
            navigateSafe(target) { launchSingleTop = true }
        } else if (
            (uiState.voiceState == VoiceState.MANUAL_SILENT || uiState.voiceState == VoiceState.DISABLED) &&
            route != target
        ) {
            navigateSafe(target) { launchSingleTop = true }
        }
    }

    LaunchedEffect(uiState.toast) {
        if (uiState.toast != null) {
            delay(2200)
            vm.dismissToast()
        }
    }

    Box(modifier = Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
        NavHost(navController = nav, startDestination = startDestination) {
            composable("onboarding") {
                OnboardingScreen(
                    onNext = {
                        markOnboardingDone()
                        nav.navigate("permissions")
                    },
                    onSkip = {
                        markOnboardingDone()
                        nav.navigate("home/disabled") { popUpTo("onboarding") { inclusive = true } }
                    },
                )
            }
            composable("permissions") {
                PermissionsScreen(
                    onBack = { nav.popBackStack() },
                    onGranted = {
                        markOnboardingDone()
                        // 授予权限只启动非语音服务；语音仍需在设置或悬浮面板显式开启。
                        vm.startServices(context)
                        nav.navigate("home/disabled") { popUpTo("permissions") { inclusive = true } }
                    },
                    onManualOnly = {
                        markOnboardingDone()
                        nav.navigate("home/disabled") { popUpTo("permissions") { inclusive = true } }
                    },
                )
            }
            composable("home/{state}") {
                val homeMacros by overlayVm.macros.collectAsState()
                HomeScreen(
                    uiState = uiState,
                    macros = homeMacros,
                    onTab = navigateTab,
                    onNotification = { shadeOpen = true },
                    onOpenWindow = vm::openWindow,
                    onPause = vm::pause,
                    onExit = vm::requestExit,
                    onResume = vm::resume,
                    onRunMacro = vm::runMacro,
                    onEditMacro = { macroId -> nav.navigate("macro_editor/$macroId") },
                    onNewMacro = startRecordingAndBackground,
                    onManageMacros = { nav.navigate("macros") },
                )
            }
            composable("macros") {
                val macros by overlayVm.macros.collectAsState()
                val overlayEnabled by overlayVm.overlayEnabled.collectAsState()
                MacrosScreen(
                    macros = macros,
                    overlayEnabled = overlayEnabled,
                    onToggleOverlay = overlayVm::setOverlayEnabled,
                    onBack = { nav.popBackStack() },
                    onTab = navigateTab,
                    onRun = { macroId ->
                        vm.runMacro(macroId)
                        nav.navigate("home/executing") { launchSingleTop = true }
                    },
                    onEdit = { macroId -> nav.navigate("macro_editor/$macroId") },
                    onDelete = { macroId -> OverlayStore.removeMacro(macroId) },
                    onNew = startRecordingAndBackground,
                )
            }
            composable("macro_editor/{macroId}") { entry ->
                val macros by overlayVm.macros.collectAsState()
                val macroId = entry.arguments?.getString("macroId")?.toLongOrNull()
                val target = macros.firstOrNull { it.macroId == macroId }
                val testScope = rememberCoroutineScope()
                MacroEditorScreen(
                    macro = target,
                    onBack = { nav.popBackStack() },
                    onTab = navigateTab,
                    onSave = { name, steps ->
                        if (macroId != null) {
                            OverlayStore.updateMacro(macroId, name, steps)
                            nav.popBackStack()
                        }
                    },
                    onTestStep = { step ->
                        testScope.launch {
                            container.executeManual(
                                CommandAction(0, "单步测试", MacroAdapter.toExecSteps(listOf(step))),
                            )
                        }
                    },
                    onDelete = { id ->
                        OverlayStore.removeMacro(id)
                        nav.popBackStack()
                    },
                )
            }
            composable("settings") {
                SettingsScreen(
                    vm = viewModel(factory = SimpleVmFactory { SettingsViewModel(container) }),
                    onTab = navigateTab,
                    onVoiceprint = { nav.navigate("voiceprint") },
                    onMapping = { nav.navigate("mapping") },
                    onAdb = { nav.navigate("adb") },
                    onOverlay = { nav.navigate("overlay") },
                    onDebug = { if (BuildConfig.DEBUG_PAGE_VISIBLE) nav.navigate("debug") },
                )
            }

            composable("overlay") {
                OverlayControlScreen(
                    vm = overlayVm,
                    onBack = { nav.popBackStack() },
                    onTab = navigateTab,
                )
            }
            composable("voiceprint") {
                VoiceprintScreen(
                    vm = viewModel(factory = SimpleVmFactory { VoiceprintViewModel(container) }),
                    onBack = { nav.popBackStack() },
                    onTab = navigateTab,
                )
            }
            composable("panel") {
                PanelScreen(
                    vm = overlayVm,
                    onTab = navigateTab,
                    onRunMacro = { macroId ->
                        vm.runMacro(macroId)
                        nav.navigate("home/executing") { launchSingleTop = true }
                    },
                    onManageMacros = { nav.navigate("macros") },
                    onNewMacro = startRecordingAndBackground,
                )
            }
            composable("adb") {
                AdbScreen(
                    vm = viewModel(factory = SimpleVmFactory { AdbViewModel(container, context.applicationContext) }),
                    onBack = { nav.popBackStack() },
                    onTab = navigateTab,
                    onStartAdbRecord = {
                        // 从链路页发起增强录制，随后切回游戏；延迟与服务处理保持一致。
                        com.aixiaohuadb.service.OverlayControlService.startAdbRecording(context)
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            (context as? Activity)?.moveTaskToBack(true)
                        }, 600L)
                    },
                )
            }
            composable("mapping") {
                MappingScreen(
                    vm = viewModel(factory = SimpleVmFactory { MappingViewModel() }),
                    overlayVm = overlayVm,
                    onBack = { nav.popBackStack() },
                    onTab = navigateTab,
                )
            }
            if (BuildConfig.DEBUG_PAGE_VISIBLE) {
                composable("debug") {
                    DebugScreen(
                        vm = viewModel(factory = SimpleVmFactory { DebugViewModel(container) }),
                        appVm = vm,
                        onTab = navigateTab,
                    )
                }
            }
        }

        if (shadeOpen) {
            NotificationShade(
                info = uiState.voiceState.ui(),
                lifecycle = uiState.voiceLifecycle,
                onPause = { shadeOpen = false; vm.pause() },
                onExit = { shadeOpen = false; vm.requestExit() },
                onResume = { shadeOpen = false; vm.resume() },
                onDismiss = { shadeOpen = false },
            )
        }
        if (uiState.showExitConfirm) {
            ExitConfirmDialog(onCancel = vm::cancelExit, onConfirm = vm::confirmExit)
        }
        uiState.toast?.let { text ->
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 48.dp)
                    .background(Panel, PillShape)
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            ) { Text(text, color = PanelInk) }
        }
    }
}

/** 单 ViewModel 工厂：避免引入 Hilt/Koin */
class SimpleVmFactory<V : ViewModel>(private val creator: () -> V) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = creator() as T
}
