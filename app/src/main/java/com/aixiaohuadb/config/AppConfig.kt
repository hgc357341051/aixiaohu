package com.aixiaohuadb.config

import com.aixiaohuadb.inference.api.Delegate

/** 运行模式：语音唤醒 / 手动开窗 / 完全无语音直选 */
enum class WakeMode { VOICE, MANUAL, MANUAL_SILENT }

/** 退出唤醒后的恢复策略：C1 必须为仅手动恢复 */
enum class Recovery { MANUAL_ONLY, AUTO }

/** 声纹门控模式：硬门控（非本人直接拒） */
enum class GateMode { AND_HARD, SOFT }

/** 执行链路类型 */
enum class ExecKind { ACCESSIBILITY, ADB, READ_ONLY }

/** 灵敏度档位，映射为各判决阈值偏移 */
enum class Sensitivity { CONSERVATIVE, STANDARD, SENSITIVE }

/** 基准分辨率尺寸（避免 UI 层与配置层耦合） */
data class BaseSize(val width: Int, val height: Int)

data class FeatureConfig(
    val sampleRate: Int = 16000,
    val winMs: Int = 25,
    val hopMs: Int = 10,
    val nFft: Int = 400,
    val nMels: Int = 40,
    val wakeWindowMs: Int = 1000,
    val wakeStrideMs: Int = 100,
    val cmdWindowMs: Int = 3000,
    val cmvn: String = "utterance",
)

data class DecodeConfig(
    val tauCoarse: Float = 0.60f,
    val tauFine: Float = 0.90f,
    val debounceFrames: Int = 3,
    val cmdWindowS: Float = 3f,
    val tauCmd: Float = 0.80f,
    val tauCtrl: Float = 0.85f,
    val silenceTimeoutS: Float = 2f,
    val cooldownS: Float = 1.5f,
    val pauseDefaultS: Int = 30,
    val tauSpkWake: Float = 0.70f,
    val tauSpkCmd: Float = 0.65f,
)

data class ControlWordConfig(
    val c1ConfirmOnFirst: Boolean = true,
    val c1UnloadWakeModel: Boolean = true,
    val c1Recovery: Recovery = Recovery.MANUAL_ONLY,
    val c2KeepWake: Boolean = true,
    val c2PromptOnWake: Boolean = true,
    val c3TimeoutS: Int = 30,
    val c3AutoResume: Boolean = true,
    val c4Enabled: Boolean = true,
)

data class WakeupConfig(
    val enabled: Boolean = true,
    val mode: WakeMode = WakeMode.VOICE,
    val persist: Boolean = true,
    val backupOnMiss: Boolean = true,
    val missPromptThreshold: Int = 3,
    val unloadWakeModelWhenOff: Boolean = true,
)

data class SpeakerConfig(
    val enabled: Boolean = true,
    val minSamples: Int = 600,
    val gateMode: GateMode = GateMode.AND_HARD,
    val tempAllowOnReject: Int = 3,
    val fallbackToNonspk: Boolean = true,
    val emaAlpha: Float = 0.05f,
    val freezeAfterRejects: Int = 3,
)

data class ExecConfig(
    val primary: ExecKind = ExecKind.ACCESSIBILITY,
    val fallback: ExecKind = ExecKind.ADB,
    val coordBase: BaseSize = BaseSize(1080, 2340),
    val heartbeatS: Int = 5,
    val reconnectMax: Int = 3,
    val retryMax: Int = 1,
)

/** §14 统一配置的运行时子集；开关由 DataStore 持久化后覆盖默认值 */
data class AppConfig(
    val feature: FeatureConfig = FeatureConfig(),
    val decode: DecodeConfig = DecodeConfig(),
    val controlWords: ControlWordConfig = ControlWordConfig(),
    val voiceWakeup: WakeupConfig = WakeupConfig(),
    val exec: ExecConfig = ExecConfig(),
    val speaker: SpeakerConfig = SpeakerConfig(),
    val sensitivity: Sensitivity = Sensitivity.STANDARD,
    val preferredDelegate: Delegate = Delegate.AUTO,
    val useDemoModels: Boolean = true,
    val feedbackTts: Boolean = true,
    val feedbackVibration: Boolean = true,
    val feedbackOverlay: Boolean = true,
    val tempAllow: Boolean = false,
) {
    companion object {
        val DEFAULT: AppConfig = AppConfig()
    }

    /** 灵敏度对阈值的偏移：档位越高阈值越低（更容易触发） */
    val thresholdOffset: Float
        get() = when (sensitivity) {
            Sensitivity.CONSERVATIVE -> 0.05f
            Sensitivity.STANDARD -> 0f
            Sensitivity.SENSITIVE -> -0.05f
        }
}
