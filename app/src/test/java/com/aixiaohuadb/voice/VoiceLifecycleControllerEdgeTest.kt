package com.aixiaohuadb.voice

import com.aixiaohuadb.config.AppConfig
import com.aixiaohuadb.machine.VoiceState
import com.aixiaohuadb.machine.VoiceStateMachine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class EdgeSettings(initial: Boolean = false) : VoiceSettings {
    override val voiceEnabledFlow = MutableStateFlow(initial)
    val writes = mutableListOf<Boolean>()

    override suspend fun setVoiceEnabled(value: Boolean) {
        voiceEnabledFlow.value = value
        writes += value
    }
}

private class EdgePipeline : VoicePipeline {
    var startCount = 0
    var stopCount = 0

    override fun start(scope: CoroutineScope) { startCount++ }
    override fun stop() { stopCount++ }
    override fun reset() {}
}

private class EdgeAudio(var startResult: Boolean = true) : VoiceAudio {
    override var isCapturing: Boolean = false
    var startCount = 0
    var stopCount = 0
    var closeCount = 0

    override fun start(): Boolean {
        startCount++
        isCapturing = startResult
        return startResult
    }
    override fun stop() { stopCount++; isCapturing = false }
    override fun close() { closeCount++ }
    override fun dispose() {}
}

private class EdgeModel(private val loadResult: Result<Unit> = Result.success(Unit)) : VoiceModelOwner {
    var loadCount = 0
    var unloadCount = 0

    override fun loadAll(): Result<Unit> { loadCount++; return loadResult }
    override fun unloadAll() { unloadCount++ }
    override fun reset() {}
}

/** S2/S3 + 补充 P0：失败回滚、采集返回值、epoch、执行令牌的 JVM 回归 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceLifecycleControllerEdgeTest {

    @Test
    fun `模型加载失败时回滚且不落盘 true`() = runTest {
        val settings = EdgeSettings()
        val pipeline = EdgePipeline()
        val audio = EdgeAudio()
        val model = EdgeModel(Result.failure(IllegalStateException("模型损坏")))
        val machine = VoiceStateMachine({ AppConfig.DEFAULT }, { "宏" })
        val controller = VoiceLifecycleController(
            null, settings, pipeline, audio, machine, model, this,
        )

        val result = controller.enable(VoiceStartReason.SETTINGS_TOGGLE)

        assertTrue(result.isFailure)
        assertEquals(VoiceLifecycleState.DISABLED, controller.state.value)
        assertFalse(controller.voiceEnabled.value)
        assertEquals(listOf(false), settings.writes)
        assertEquals(1, model.loadCount)
        assertEquals(1, model.unloadCount)
        assertEquals(0, pipeline.startCount)
        assertEquals(0, audio.startCount)
        assertEquals(VoiceState.DISABLED, machine.currentState())
        controller.close()
    }

    @Test
    fun `麦克风启动失败时回滚且不留常驻资源`() = runTest {
        val settings = EdgeSettings()
        val pipeline = EdgePipeline()
        val audio = EdgeAudio(startResult = false)
        val model = EdgeModel()
        val machine = VoiceStateMachine({ AppConfig.DEFAULT }, { "宏" })
        val controller = VoiceLifecycleController(
            null, settings, pipeline, audio, machine, model, this,
        )

        val result = controller.enable(VoiceStartReason.SETTINGS_TOGGLE)

        assertTrue(result.isFailure)
        assertEquals(VoiceLifecycleState.DISABLED, controller.state.value)
        assertFalse(controller.voiceEnabled.value)
        assertEquals(listOf(false), settings.writes)
        assertEquals(1, model.loadCount)
        assertEquals(1, model.unloadCount)
        assertEquals(1, pipeline.startCount)
        assertEquals(1, pipeline.stopCount)
        assertEquals(1, audio.startCount)
        assertEquals(1, audio.stopCount)
        assertEquals(1, audio.closeCount)
        assertEquals(VoiceState.DISABLED, machine.currentState())
        controller.close()
    }

    @Test
    fun `采集成功后才落盘 true 且 disable 幂等`() = runTest {
        val settings = EdgeSettings()
        val pipeline = EdgePipeline()
        val audio = EdgeAudio()
        val model = EdgeModel()
        val controller = VoiceLifecycleController(
            null, settings, pipeline, audio, VoiceStateMachine(), model, this,
        )

        assertTrue(controller.enable(VoiceStartReason.SETTINGS_TOGGLE).isSuccess)
        assertEquals(listOf(true), settings.writes)
        assertFalse(controller.isExecutionCancelled())

        assertTrue(controller.disable(VoiceStopReason.SETTINGS_TOGGLE).isSuccess)
        assertTrue(controller.disable(VoiceStopReason.SETTINGS_TOGGLE).isSuccess)
        assertEquals(listOf(true, false), settings.writes)
        assertEquals(1, pipeline.stopCount)
        assertEquals(1, model.unloadCount)
        assertTrue(controller.isExecutionCancelled())
        controller.close()
    }

    @Test
    fun `DISABLED 吸收所有语音入口`() = runTest {
        val controller = VoiceLifecycleController(
            null, EdgeSettings(), EdgePipeline(), EdgeAudio(),
            VoiceStateMachine(), EdgeModel(), this,
        )
        voiceActions().forEach { action ->
            assertFalse("$action 不应被允许", controller.isVoiceActionAllowed(action))
        }
        controller.close()
    }

    private fun voiceActions() = listOf(
        VoiceAction.WAKE,
        VoiceAction.RECOGNIZE,
        VoiceAction.PAUSE_RESUME_VIA_BALL,
        VoiceAction.VOICE_NOTIFICATION_ACTION,
        VoiceAction.QUICK_TILE,
        VoiceAction.FOREGROUND_START,
    )

    @Test
    fun `排队期间 disable 使陈旧 enable 失效`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val settings = EdgeSettings()
        val model = EdgeModel()
        val controller = VoiceLifecycleController(
            null, settings, EdgePipeline(), EdgeAudio(),
            VoiceStateMachine(), model, this,
            onBeforeEnable = { entered.complete(Unit); gate.await() },
        )

        val pending = async { controller.enable(VoiceStartReason.SETTINGS_TOGGLE) }
        entered.await()
        // enable 已捕获 epoch 但在抢锁前挂起；此时用户关闭语音
        assertTrue(controller.disable(VoiceStopReason.SETTINGS_TOGGLE).isSuccess)
        gate.complete(Unit)

        val result = pending.await()
        assertTrue(result.isFailure)
        assertEquals(VoiceLifecycleState.DISABLED, controller.state.value)
        assertEquals(0, model.loadCount)
        assertTrue(settings.writes.isEmpty())
        controller.close()
    }

    @Test
    fun `并发 enable 只加载一次模型`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        var calls = 0
        val model = EdgeModel()
        val pipeline = EdgePipeline()
        val controller = VoiceLifecycleController(
            null, EdgeSettings(), pipeline, EdgeAudio(),
            VoiceStateMachine(), model, this,
            onBeforeEnable = {
                calls++
                if (calls == 1) { entered.complete(Unit); gate.await() }
            },
        )

        val first = async { controller.enable(VoiceStartReason.SETTINGS_TOGGLE) }
        entered.await()
        assertTrue(controller.enable(VoiceStartReason.OVERLAY_SWITCH).isSuccess)
        gate.complete(Unit)

        assertTrue(first.await().isSuccess)
        assertEquals(VoiceLifecycleState.RUNNING, controller.state.value)
        assertEquals(1, model.loadCount)
        assertEquals(1, pipeline.startCount)
        controller.close()
    }

    @Test
    fun `迟到的持久化首帧不覆盖已开启状态`() = runTest {
        val lateFlow = MutableSharedFlow<Boolean>()
        val settings = object : VoiceSettings {
            override val voiceEnabledFlow = lateFlow
            val writes = mutableListOf<Boolean>()
            override suspend fun setVoiceEnabled(value: Boolean) { writes += value }
        }
        val controller = VoiceLifecycleController(
            null, settings, EdgePipeline(), EdgeAudio(),
            VoiceStateMachine(), EdgeModel(), this,
        )

        controller.restorePersistedState()
        runCurrent()
        assertEquals(VoiceLifecycleState.DISABLED, controller.state.value)
        assertTrue(controller.enable(VoiceStartReason.SETTINGS_TOGGLE).isSuccess)

        // DataStore 首帧晚于 enable 返回：即使携带 true 也不得把 RUNNING 拉回 STOPPED
        lateFlow.emit(true)
        runCurrent()
        assertEquals(VoiceLifecycleState.RUNNING, controller.state.value)
        assertTrue(controller.voiceEnabled.value)
        controller.close()
    }

    @Test
    fun `迟到的持久化首帧不覆盖已关闭状态`() = runTest {
        val lateFlow = MutableSharedFlow<Boolean>()
        val settings = object : VoiceSettings {
            override val voiceEnabledFlow = lateFlow
            override suspend fun setVoiceEnabled(value: Boolean) {}
        }
        val controller = VoiceLifecycleController(
            null, settings, EdgePipeline(), EdgeAudio(),
            VoiceStateMachine(), EdgeModel(), this,
        )

        controller.restorePersistedState()
        runCurrent()
        assertTrue(controller.disable(VoiceStopReason.SETTINGS_TOGGLE).isSuccess)

        lateFlow.emit(true)
        runCurrent()
        assertEquals(VoiceLifecycleState.DISABLED, controller.state.value)
        assertFalse(controller.voiceEnabled.value)
        controller.close()
    }

    @Test
    fun `PAUSED恢复采集失败时收敛到DISABLED`() = runTest {
        val audio = EdgeAudio(startResult = true)
        val machine = VoiceStateMachine({ AppConfig.DEFAULT }, { "宏" })
        val controller = VoiceLifecycleController(
            null, EdgeSettings(), EdgePipeline(), audio,
            machine, EdgeModel(), this,
        )

        assertTrue(controller.enable(VoiceStartReason.SETTINGS_TOGGLE).isSuccess)
        controller.pause()
        assertEquals(VoiceLifecycleState.PAUSED, controller.state.value)
        assertTrue(controller.isVoiceActionAllowed(VoiceAction.FOREGROUND_START))

        audio.startResult = false
        controller.resume()

        assertEquals(VoiceLifecycleState.DISABLED, controller.state.value)
        assertFalse(controller.voiceEnabled.value)
        assertEquals(VoiceState.DISABLED, machine.currentState())
        voiceActions().forEach { action ->
            assertFalse("$action 不应在失败后被放行", controller.isVoiceActionAllowed(action))
        }
        controller.close()
    }
}
