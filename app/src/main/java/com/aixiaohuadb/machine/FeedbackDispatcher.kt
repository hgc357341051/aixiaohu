package com.aixiaohuadb.machine

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import com.aixiaohuadb.config.AppConfig
import java.util.Locale

/**
 * 执行反馈的真实消费端：把设置页三个反馈开关（TTS / 震动 / 悬浮提示）落地。
 *
 * 设计要点：
 * 1. 开关与文案都是纯输入，震动/TTS 全部降级保护，失败绝不阻断执行主流程。
 * 2. TTS 懒初始化（只有开关打开且有文案时才创建），用完不常驻占用，进程内复用单例。
 * 3. [FeedbackType.INFO] 只走悬浮提示，不震动不朗读，避免每个提示都打扰用户。
 */
class FeedbackDispatcher(
    private val context: Context,
    private val configProvider: () -> AppConfig,
) {
    private var tts: TextToSpeech? = null

    private val vibrator: Vibrator? by lazy {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        }.getOrNull()
    }

    /**
     * [onOverlayHint] 由调用方提供，负责把文本显示成不遮挡游戏的浮层提示。
     * 是否调用由 feedbackOverlay 开关决定。
     */
    fun dispatch(type: FeedbackType, text: String, onOverlayHint: (String) -> Unit) {
        val config = configProvider()
        when (type) {
            FeedbackType.SUCCESS, FeedbackType.ERROR, FeedbackType.WARN -> {
                if (config.feedbackVibration) vibrate(type)
                if (config.feedbackTts) speak(text)
                if (config.feedbackOverlay) onOverlayHint(text)
            }
            FeedbackType.INFO -> {
                if (config.feedbackOverlay) onOverlayHint(text)
            }
        }
    }

    private fun vibrate(type: FeedbackType) {
        val v = vibrator ?: return
        runCatching {
            // minSdk 29 必然支持 VibrationEffect（API 26+）
            val effect = when (type) {
                FeedbackType.SUCCESS -> VibrationEffect.createOneShot(60L, 120)
                FeedbackType.ERROR -> VibrationEffect.createWaveform(longArrayOf(0L, 90L, 60L, 90L), -1)
                FeedbackType.WARN -> VibrationEffect.createOneShot(120L, 160)
                else -> VibrationEffect.createOneShot(60L, 120)
            }
            v.vibrate(effect)
        }
    }

    private fun speak(text: String) {
        if (text.isBlank()) return
        val engine = ensureTts() ?: return
        runCatching {
            @Suppress("DEPRECATION")
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "feedback")
        }
    }

    private fun ensureTts(): TextToSpeech? {
        tts?.let { return it }
        val created = runCatching {
            TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    runCatching { tts?.setLanguage(Locale.CHINA) }
                }
            }
        }.getOrNull() ?: return null
        tts = created
        return created
    }

    /** 进程销毁时释放 TTS，避免引擎残留占用 */
    fun shutdown() {
        runCatching { tts?.stop(); tts?.shutdown() }
        tts = null
    }
}
