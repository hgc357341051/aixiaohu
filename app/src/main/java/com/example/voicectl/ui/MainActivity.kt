package com.example.voicectl.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.voicectl.App
import com.example.voicectl.ui.theme.VoiceCtlTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as App
        setContent {
            VoiceCtlTheme {
                val vm: AppViewModel = viewModel(factory = AppViewModelFactory(app.container))
                AppNavHost(vm = vm, container = app.container, context = this@MainActivity)
            }
        }
    }
}
