package com.aixiaohuadb.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.aixiaohuadb.config.AppConfig
import com.aixiaohuadb.voice.VoiceAudio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 16kHz 单声道采集 + 帧共享流；无常驻拾音时调用 stop() 彻底释放 AudioRecord */
class AudioCapture(
    private val context: Context,
    private val config: AppConfig,
) : VoiceAudio {
    private val sampleRate = config.feature.sampleRate
    private val frameSize = 400 // 25ms
    private var record: AudioRecord? = null
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _frames = MutableSharedFlow<FloatArray>(extraBufferCapacity = 8)
    val frames: SharedFlow<FloatArray> = _frames

    @Volatile
    override var isCapturing: Boolean = false
        private set

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    override fun start(): Boolean {
        if (isCapturing || !hasPermission()) return false
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return false
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, frameSize * 4),
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return false
        }
        record = recorder
        try {
            recorder.startRecording()
        } catch (e: IllegalStateException) {
            recorder.release()
            record = null
            return false
        }
        isCapturing = true
        job = scope.launch {
            val shorts = ShortArray(frameSize)
            while (isActive && isCapturing) {
                val read = recorder.read(shorts, 0, frameSize)
                if (read == frameSize) {
                    val frame = FloatArray(frameSize) { shorts[it] / 32768f }
                    _frames.tryEmit(frame)
                }
            }
        }
        return true
    }

    override fun stop() {
        isCapturing = false
        job?.cancel()
        job = null
        try {
            if (record?.state == AudioRecord.RECORDSTATE_RECORDING) record?.stop()
        } catch (e: IllegalStateException) {
            // 系统已停止，忽略
        }
        record?.release()
        record = null
    }

    /**
     * 释放麦克风与 AudioRecord，但保留采集协程 scope，使语音再次 enable 时能重新 start()。
     * 进程级最终销毁请用 [dispose]。
     */
    override fun close() {
        stop()
    }

    override fun dispose() {
        close()
        scope.cancel()
    }
}
