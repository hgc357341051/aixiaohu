package com.aixiaohuadb.machine

import com.aixiaohuadb.config.AppConfig
import com.aixiaohuadb.inference.api.ActionLabel
import com.aixiaohuadb.inference.api.CommandResult
import com.aixiaohuadb.inference.api.ControlWord
import com.aixiaohuadb.inference.api.ObjectLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceStateMachineTest {

    private val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() {
        collectorScope.cancel()
    }

    private fun testConfig(): AppConfig = AppConfig.DEFAULT.copy(
        decode = AppConfig.DEFAULT.decode.copy(cmdWindowS = 60f, cooldownS = 60f),
    )

    private fun collectEffects(sm: VoiceStateMachine, sink: MutableList<SideEffect>) {
        collectorScope.launch(Dispatchers.Unconfined) { sm.effects.collect { sink += it } }
    }

    @Test
    fun `主流程 唤醒 到 执行 到 冷却 到 待机`() = runTest {
        val sm = VoiceStateMachine(testConfig())
        sm.send(VoiceEvent.Start)
        assertEquals(VoiceState.LISTEN, sm.currentState())
        sm.send(VoiceEvent.CoarseHit)
        assertEquals(VoiceState.VERIFY, sm.currentState())
        sm.send(VoiceEvent.WakeVerified(0.9f))
        assertEquals(VoiceState.CMD_WINDOW, sm.currentState())
        val result = CommandResult(ActionLabel.A0_SWAP, ObjectLabel.E0_MINGDAO, 0, 0.95f)
        sm.send(VoiceEvent.CommandDetected(result, 0.9f))
        assertEquals(VoiceState.EXECUTE, sm.currentState())
        sm.send(VoiceEvent.ExecSucceeded)
        assertEquals(VoiceState.COOLDOWN, sm.currentState())
        sm.send(VoiceEvent.CooldownElapsed)
        assertEquals(VoiceState.IDLE, sm.currentState())
        sm.close()
    }

    @Test
    fun `C1 首次二次确认 真退出后仅认手动恢复`() = runTest {
        val sm = VoiceStateMachine(testConfig())
        val effects = mutableListOf<SideEffect>()
        collectEffects(sm, effects)
        sm.send(VoiceEvent.Start)
        sm.send(VoiceEvent.WakeVerified(0.9f))
        sm.send(VoiceEvent.ControlDetected(ControlWord.C1_EXIT_WAKE, 0.95f))

        assertEquals(VoiceState.CMD_WINDOW, sm.currentState())
        assertTrue(effects.any { it is SideEffect.RequestConfirmExit })
        assertTrue(effects.none { it is SideEffect.UnloadModels })

        sm.send(VoiceEvent.ExitConfirmed)
        assertEquals(VoiceState.MANUAL_SILENT, sm.currentState())
        assertTrue(effects.any { it is SideEffect.UnloadModels })
        assertTrue(effects.any { it is SideEffect.StopCapture })

        effects.clear()
        sm.send(VoiceEvent.WakeVerified(1f))
        sm.send(VoiceEvent.ControlDetected(ControlWord.C4_RESUME, 1f))
        sm.send(VoiceEvent.CoarseHit)
        assertEquals(VoiceState.MANUAL_SILENT, sm.currentState())
        assertTrue(effects.isEmpty())

        sm.send(VoiceEvent.ManualResume)
        assertEquals(VoiceState.LISTEN, sm.currentState())
        assertTrue(effects.any { it is SideEffect.LoadModels })
        assertTrue(effects.any { it is SideEffect.StartCapture })
        sm.close()
    }

    @Test
    fun `C1 关闭二次确认时直接进入静默态`() = runTest {
        val config = testConfig().copy(
            controlWords = testConfig().controlWords.copy(c1ConfirmOnFirst = false),
        )
        val sm = VoiceStateMachine(config)
        val effects = mutableListOf<SideEffect>()
        collectEffects(sm, effects)
        sm.send(VoiceEvent.Start)
        sm.send(VoiceEvent.WakeVerified(1f))
        sm.send(VoiceEvent.ControlDetected(ControlWord.C1_EXIT_WAKE, 1f))
        assertEquals(VoiceState.MANUAL_SILENT, sm.currentState())
        assertTrue(effects.any { it is SideEffect.UnloadModels })
        sm.close()
    }

    @Test
    fun `C2 退出识别保留唤醒 唤醒后提示识别关闭`() = runTest {
        val sm = VoiceStateMachine(testConfig())
        val effects = mutableListOf<SideEffect>()
        collectEffects(sm, effects)
        sm.send(VoiceEvent.Start)
        sm.send(VoiceEvent.WakeVerified(1f))
        sm.send(VoiceEvent.ControlDetected(ControlWord.C2_EXIT_RECOG, 0.9f))
        assertEquals(VoiceState.RECOG_OFF, sm.currentState())
        effects.clear()
        sm.send(VoiceEvent.WakeVerified(0.9f))
        assertEquals(VoiceState.CMD_WINDOW, sm.currentState())
        assertTrue(effects.any { it is SideEffect.Feedback && it.text.contains("识别已关闭") })
        sm.close()
    }

    @Test
    fun `C3 暂停可超时或 C4 恢复`() = runTest {
        val sm = VoiceStateMachine(testConfig())
        sm.send(VoiceEvent.Start)
        sm.send(VoiceEvent.WakeVerified(1f))
        sm.send(VoiceEvent.ControlDetected(ControlWord.C3_PAUSE, 0.9f))
        assertEquals(VoiceState.PAUSED, sm.currentState())
        sm.send(VoiceEvent.PauseTimeout)
        assertEquals(VoiceState.LISTEN, sm.currentState())

        sm.send(VoiceEvent.WakeVerified(1f))
        sm.send(VoiceEvent.ControlDetected(ControlWord.C3_PAUSE, 0.9f))
        sm.send(VoiceEvent.ControlDetected(ControlWord.C4_RESUME, 0.9f))
        assertEquals(VoiceState.LISTEN, sm.currentState())
        sm.close()
    }

    @Test
    fun `连续三次异常真退出`() = runTest {
        val sm = VoiceStateMachine(testConfig())
        val effects = mutableListOf<SideEffect>()
        collectEffects(sm, effects)
        sm.send(VoiceEvent.Start)
        repeat(3) { sm.send(VoiceEvent.Error(IllegalStateException("test"))) }
        assertEquals(VoiceState.MANUAL_SILENT, sm.currentState())
        assertTrue(effects.any { it is SideEffect.UnloadModels })
        assertTrue(effects.any { it is SideEffect.StopCapture })
        sm.close()
    }

    @Test
    fun `声纹拒绝回监听态且游戏离前台回待机`() = runTest {
        val sm = VoiceStateMachine(testConfig())
        sm.send(VoiceEvent.Start)
        sm.send(VoiceEvent.CoarseHit)
        sm.send(VoiceEvent.WakeRejected)
        assertEquals(VoiceState.LISTEN, sm.currentState())
        sm.send(VoiceEvent.ExitGame)
        assertEquals(VoiceState.IDLE, sm.currentState())
        sm.close()
    }
}
