package com.aixiaohuadb.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aixiaohuadb.App
import com.aixiaohuadb.ui.theme.VoiceCtlTheme

class MainActivity : ComponentActivity() {
    private var snapshotUri by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // targetSdk 35 在 Android 15+ 强制 edge-to-edge：显式接管并把图标设为浅色，
        // 由 AppNavHost 的 systemBarsPadding() 统一给内容让出状态栏 / 导航栏安全区。
        // 必须在 super.onCreate 之前调用，否则窗口已进入 edge-to-edge 状态。
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.BLACK),
        )
        super.onCreate(savedInstanceState)
        snapshotUri = annotationUri(intent)
        val app = application as App
        setContent {
            VoiceCtlTheme {
                val vm: AppViewModel = viewModel(factory = AppViewModelFactory(app.container))
                AppNavHost(
                    vm = vm,
                    container = app.container,
                    context = this@MainActivity,
                    snapshotUri = snapshotUri,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        annotationUri(intent)?.let { snapshotUri = it }
    }

    private fun annotationUri(intent: Intent?): String? = intent
        ?.takeIf { it.action == SnapshotCaptureActivity.ACTION_ANNOTATE }
        ?.getStringExtra(SnapshotCaptureActivity.EXTRA_SNAPSHOT_URI)
        ?.takeIf { it.isNotBlank() }
}
