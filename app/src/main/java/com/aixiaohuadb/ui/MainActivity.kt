package com.aixiaohuadb.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aixiaohuadb.App
import com.aixiaohuadb.ui.theme.VoiceCtlTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // edge-to-edge：状态栏/导航栏透明，内容由 Compose 侧 systemBarsPadding 避让。
        // 必须在 super.onCreate 之前，否则窗口已进入非沉浸式状态。
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // GAME OPS 强制深空底 #060A12（见 themes.xml / colors.xml），
        // 因此系统栏图标必须恒为浅色。显式接管而不是依赖 SystemBarStyle 的参数语义，
        // 避免不同 ROM / 系统深浅模式下出现白底深图标或图标不可见。
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        val app = application as App
        setContent {
            VoiceCtlTheme {
                val vm: AppViewModel = viewModel(factory = AppViewModelFactory(app.container))
                AppNavHost(
                    vm = vm,
                    container = app.container,
                    context = this@MainActivity,
                )
            }
        }
    }
}
