package com.aixiaohuadb.voice

import com.aixiaohuadb.config.AppConfig
import com.aixiaohuadb.machine.VoiceState
import com.aixiaohuadb.machine.VoiceStateMachine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeSettings(initial: Boolean) : VoiceSettings {
    override val voiceEnabledFlow = MutableStateFlow(initial)
    val writes = mutableListOf<Boolean>()

    override suspend fun setVoiceEnabled(value: Boolean) {
        voiceEnabledFlow.value = value
        writes += value
    }
}

private class FakePipeline : VoicePipeline {
    var startCount = 0
    var stopCount = 0
    var resetCount = 0

    override fun start(scope: kotlinx.coroutines.CoroutineScope) { startCount++ }
    override fun stop() { stopCount++ }
    override fun reset() { resetCount++ }
}

private class FakeAudio : VoiceAudio {
    override var isCapturing: Boolean = false
    var startCount = 0
    var stopCount = 0
    var closeCount = 0
    var disposeCount = 0

    override fun start(): Boolean {
        startCount++
        isCapturing = true
        return true
    }

    override fun stop() {
        stopCount++
        isCapturing = false
    }

    override fun close() { closeCount++ }
    override fun dispose() { disposeCount++ }
}

private class FakeModel : VoiceModelOwner {
    var loadCount = 0
    var unloadCount = 0
    var resetCount = 0

    override fun loadAll(): Result<Unit> {
        loadCount++
        return Result.success(Unit)
    }

    override fun unloadAll() { unloadCount++ }
    override fun reset() { resetCount++ }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class VoiceLifecycleControllerTest {

    @Test
    fun `冷启动残留开启标志也只进 STOPPED 不触碰物理资源`() = runTest {
        val settings = FakeSettings(initial = true)
        val pipeline = FakePipeline()
        val audio = FakeAudio()
        val model = FakeModel()
        val machine = VoiceStateMachine({ AppConfig.DEFAULT }, { "宏" })
        val controller = VoiceLifecycleController(
            context = null,
            settingsStore = settings,
            pipeline = pipeline,
            audioCapture = audio,
            stateMachine = machine,
            modelOwner = model,
            scope = this,
        )

        controller.restorePersistedState()
        runCurrent()

        assertEquals(VoiceLifecycleState.STOPPED, controller.state.value)
        assertTrue(controller.voiceEnabled.value)
        assertEquals(0, pipeline.startCount)
        assertEquals(0, audio.startCount)
        assertEquals(0, model.loadCount)
        assertEquals(VoiceState.IDLE, machine.currentState())
        controller.close()
    }

    @Test
    fun `启用后真实启动 禁用后按序物理释放`() = runTest {
        val settings = FakeSettings(initial = false)
        val pipeline = FakePipeline()
        val audio = FakeAudio()
        val model = FakeModel()
        val machine = VoiceStateMachine({ AppConfig.DEFAULT }, { "宏" })
        val controller = VoiceLifecycleController(
            context = null,
            settingsStore = settings,
            pipeline = pipeline,
            audioCapture = audio,
            stateMachine = machine,
            modelOwner = model,
            scope = this,
        )

        assertTrue(controller.enable(VoiceStartReason.SETTINGS_TOGGLE).isSuccess)
        assertEquals(VoiceLifecycleState.RUNNING, controller.state.value)
        assertEquals(1, model.loadCount)
        assertEquals(1, pipeline.startCount)
        assertEquals(1, audio.startCount)
        assertTrue(controller.isVoiceActionAllowed(VoiceAction.WAKE))

        assertTrue(controller.disable(VoiceStopReason.MODE_LEAVE).isSuccess)
        assertEquals(VoiceLifecycleState.DISABLED, controller.state.value)
        assertEquals(1, pipeline.stopCount)
        assertEquals(1, audio.stopCount)
        assertEquals(1, audio.closeCount)
        assertEquals(1, model.unloadCount)
        assertEquals(listOf(true, false), settings.writes)
        assertEquals(VoiceState.DISABLED, machine.currentState())
        assertFalse(controller.isVoiceActionAllowed(VoiceAction.WAKE))
        assertFalse(controller.isVoiceActionAllowed(VoiceAction.QUICK_TILE))
        controller.close()
    }
}
