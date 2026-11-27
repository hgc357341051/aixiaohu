package com.aixiaohuadb.monitor

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 分模式埋点：只记录事件名与维度，不记录音频、声纹向量或用户标识 */
class Telemetry {
    private val _recent = MutableStateFlow<List<String>>(emptyList())
    val recent: StateFlow<List<String>> = _recent

    fun event(dim: String, name: String, mode: String = "unknown") {
        val line = "[${System.currentTimeMillis()}] $dim/$name mode=$mode"
        Log.d("VoiceCtlTelemetry", line)
        _recent.value = (_recent.value + line).takeLast(60)
    }
}
