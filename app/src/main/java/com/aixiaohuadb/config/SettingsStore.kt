package com.aixiaohuadb.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aixiaohuadb.inference.api.Delegate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "voice_settings")

/** Preferences DataStore 持久化所有开关与阈值，配置跟随用户而非模型版本 */
class SettingsStore(private val context: Context) {

    private object Keys {
        val WAKE_ENABLED = booleanPreferencesKey("wake_enabled")
        val WAKE_MODE = stringPreferencesKey("wake_mode")
        val SENSITIVITY = stringPreferencesKey("sensitivity")
        val DELEGATE = stringPreferencesKey("delegate")
        val SPEAKER_ENABLED = booleanPreferencesKey("speaker_enabled")
        val EXIT_CONFIRM = booleanPreferencesKey("exit_confirm")
        val FEEDBACK_TTS = booleanPreferencesKey("feedback_tts")
        val FEEDBACK_VIBRATION = booleanPreferencesKey("feedback_vibration")
        val FEEDBACK_OVERLAY = booleanPreferencesKey("feedback_overlay")
        val TEMP_ALLOW = booleanPreferencesKey("temp_allow")
        val TAU_COARSE = floatPreferencesKey("tau_coarse")
        val TAU_FINE = floatPreferencesKey("tau_fine")
        val TAU_CMD = floatPreferencesKey("tau_cmd")
    }

    val configFlow: Flow<AppConfig> = context.settingsDataStore.data.map { p ->
        val defaults = AppConfig.DEFAULT
        defaults.copy(
            voiceWakeup = defaults.voiceWakeup.copy(
                enabled = p[Keys.WAKE_ENABLED] ?: defaults.voiceWakeup.enabled,
                mode = runCatching { WakeMode.valueOf(p[Keys.WAKE_MODE] ?: defaults.voiceWakeup.mode.name) }
                    .getOrDefault(defaults.voiceWakeup.mode),
            ),
            sensitivity = runCatching { Sensitivity.valueOf(p[Keys.SENSITIVITY] ?: defaults.sensitivity.name) }
                .getOrDefault(defaults.sensitivity),
            preferredDelegate = runCatching { Delegate.valueOf(p[Keys.DELEGATE] ?: defaults.preferredDelegate.name) }
                .getOrDefault(defaults.preferredDelegate),
            speaker = defaults.speaker.copy(
                enabled = p[Keys.SPEAKER_ENABLED] ?: defaults.speaker.enabled,
            ),
            controlWords = defaults.controlWords.copy(
                c1ConfirmOnFirst = p[Keys.EXIT_CONFIRM] ?: defaults.controlWords.c1ConfirmOnFirst,
            ),
            feedbackTts = p[Keys.FEEDBACK_TTS] ?: defaults.feedbackTts,
            feedbackVibration = p[Keys.FEEDBACK_VIBRATION] ?: defaults.feedbackVibration,
            feedbackOverlay = p[Keys.FEEDBACK_OVERLAY] ?: defaults.feedbackOverlay,
            tempAllow = p[Keys.TEMP_ALLOW] ?: defaults.tempAllow,
            decode = defaults.decode.copy(
                tauCoarse = p[Keys.TAU_COARSE] ?: defaults.decode.tauCoarse,
                tauFine = p[Keys.TAU_FINE] ?: defaults.decode.tauFine,
                tauCmd = p[Keys.TAU_CMD] ?: defaults.decode.tauCmd,
            ),
        )
    }

    private suspend fun put(key: Preferences.Key<Boolean>, value: Boolean) {
        context.settingsDataStore.edit { it[key] = value }
    }

    private suspend fun put(key: Preferences.Key<String>, value: String) {
        context.settingsDataStore.edit { it[key] = value }
    }

    private suspend fun put(key: Preferences.Key<Float>, value: Float) {
        context.settingsDataStore.edit { it[key] = value }
    }

    suspend fun setWakeEnabled(value: Boolean) = put(Keys.WAKE_ENABLED, value)
    suspend fun setWakeMode(value: WakeMode) = put(Keys.WAKE_MODE, value.name)
    suspend fun setSensitivity(value: Sensitivity) = put(Keys.SENSITIVITY, value.name)
    suspend fun setDelegate(value: Delegate) = put(Keys.DELEGATE, value.name)
    suspend fun setSpeakerEnabled(value: Boolean) = put(Keys.SPEAKER_ENABLED, value)
    suspend fun setExitConfirm(value: Boolean) = put(Keys.EXIT_CONFIRM, value)
    suspend fun setFeedbackTts(value: Boolean) = put(Keys.FEEDBACK_TTS, value)
    suspend fun setFeedbackVibration(value: Boolean) = put(Keys.FEEDBACK_VIBRATION, value)
    suspend fun setFeedbackOverlay(value: Boolean) = put(Keys.FEEDBACK_OVERLAY, value)
    suspend fun setTempAllow(value: Boolean) = put(Keys.TEMP_ALLOW, value)
    suspend fun setTauCoarse(value: Float) = put(Keys.TAU_COARSE, value)
    suspend fun setTauFine(value: Float) = put(Keys.TAU_FINE, value)
    suspend fun setTauCmd(value: Float) = put(Keys.TAU_CMD, value)
}
