package com.aixiaohuadb.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.aixiaohuadb.BuildConfig
import com.aixiaohuadb.di.AppContainer
import com.aixiaohuadb.machine.VoiceState
import com.aixiaohuadb.machine.ui
import com.aixiaohuadb.ui.debug.DebugScreen
import com.aixiaohuadb.ui.debug.DebugViewModel
import com.aixiaohuadb.ui.home.HomeScreen
import com.aixiaohuadb.ui.mapping.MappingScreen
import com.aixiaohuadb.ui.mapping.MappingViewModel
import com.aixiaohuadb.ui.onboarding.OnboardingScreen
import com.aixiaohuadb.ui.overlay.OverlayControlScreen
import com.aixiaohuadb.ui.overlay.OverlayControlViewModel
import com.aixiaohuadb.ui.panel.PanelScreen
import com.aixiaohuadb.ui.permissions.PermissionsScreen
import com.aixiaohuadb.ui.settings.SettingsScreen
import com.aixiaohuadb.ui.settings.SettingsViewModel
import com.aixiaohuadb.ui.adb.AdbScreen
import com.aixiaohuadb.ui.adb.AdbViewModel
import com.aixiaohuadb.ui.theme.Panel
import com.aixiaohuadb.ui.theme.PanelInk
import com.aixiaohuadb.ui.theme.PillShape
import com.aixiaohuadb.ui.voiceprint.VoiceprintScreen
import com.aixiaohuadb.ui.voiceprint.VoiceprintViewModel
import kotlinx.coroutines.delay

/** 单 Activity 导航图：9 个 destination + home/{state} 参数化承载 7 态 */
@Composable
fun AppNavHost(vm: AppViewModel, container: AppContainer, context: Context) {
    val nav = rememberNavController()
    val uiState by vm.ui.collectAsState()
    var shadeOpen by remember { mutableStateOf(false) }

    val navigateTab: (String) -> Unit = navigateTab@{ route ->
        val target = if (route == "home") {
            "home/${uiState.voiceState.ui().key}"
        } else {
            route
        }
        if (nav.currentDestination?.route == target) return@navigateTab
        nav.navigate(target) {
            launchSingleTop = true
            popUpTo(target) { inclusive = true }
        }
    }

    LaunchedEffect(uiState.voiceState) {
        val route = nav.currentDestination?.route
        val target = "home/${uiState.voiceState.ui().key}"
        if (route?.startsWith("home/") == true && route != target) {
            nav.navigate(target) { launchSingleTop = true }
        } else if (uiState.voiceState == VoiceState.MANUAL_SILENT && route != target) {
            nav.navigate(target) { launchSingleTop = true }
        }
    }

    LaunchedEffect(uiState.toast) {
        if (uiState.toast != null) {
            delay(2200)
            vm.dismissToast()
        }
    }

    Box(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
        NavHost(navController = nav, startDestination = "onboarding") {
            composable("onboarding") {
                OnboardingScreen(
                    onNext = { nav.navigate("permissions") },
                    onSkip = {
                        nav.navigate("home/silent") { popUpTo("onboarding") { inclusive = true } }
                    },
                )
            }
            composable("permissions") {
                PermissionsScreen(
                    onBack = { nav.popBackStack() },
                    onGranted = {
                        vm.startVoice()
                        vm.startServices(context)
                        nav.navigate("home/idle") { popUpTo("permissions") { inclusive = true } }
                    },
                    onManualOnly = {
                        nav.navigate("home/silent") { popUpTo("permissions") { inclusive = true } }
                    },
                )
            }
            composable("home/{state}") {
                HomeScreen(
                    uiState = uiState,
                    onTab = navigateTab,
                    onNotification = { shadeOpen = true },
                    onOpenWindow = vm::openWindow,
                    onPause = vm::pause,
                    onExit = vm::requestExit,
                    onResume = vm::resume,
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
                    vm = viewModel(factory = SimpleVmFactory { OverlayControlViewModel() }),
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
                    onTab = navigateTab,
                    onRunCommand = { id ->
                        vm.runCommand(id)
                        nav.navigate("home/executing") { launchSingleTop = true }
                    },
                )
            }
            composable("adb") {
                AdbScreen(
                    vm = viewModel(factory = SimpleVmFactory { AdbViewModel(container, context) }),
                    onBack = { nav.popBackStack() },
                    onTab = navigateTab,
                )
            }
            composable("mapping") {
                MappingScreen(
                    vm = viewModel(factory = SimpleVmFactory { MappingViewModel() }),
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
