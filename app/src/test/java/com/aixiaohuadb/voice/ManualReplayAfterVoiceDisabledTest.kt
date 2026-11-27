package com.aixiaohuadb.voice

import com.aixiaohuadb.adb.AdbInputExecutor
import com.aixiaohuadb.adb.CommandResult
import com.aixiaohuadb.adb.InputCommand
import com.aixiaohuadb.adb.InputCommandExecutor
import com.aixiaohuadb.config.AppConfig
import com.aixiaohuadb.config.BaseSize
import com.aixiaohuadb.exec.CommandAction
import com.aixiaohuadb.exec.CoordinateMapper
import com.aixiaohuadb.exec.ExecutionGuard
import com.aixiaohuadb.exec.ExecStep
import com.aixiaohuadb.exec.ManualExecutionToken
import com.aixiaohuadb.machine.VoiceStateMachine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class RecordingClient : InputCommandExecutor {
    val commands = mutableListOf<InputCommand>()
    var onFirstDispatch: (suspend () -> Unit)? = null

    override suspend fun execute(command: InputCommand): CommandResult {
        commands += command
        if (commands.size == 1) onFirstDispatch?.invoke()
        return CommandResult(true, "ok", attempted = true)
    }
}

private class PlainSettings : VoiceSettings {
    override val voiceEnabledFlow = MutableStateFlow(false)
    override suspend fun setVoiceEnabled(value: Boolean) {
        voiceEnabledFlow.value = value
    }
}

private class PlainPipeline : VoicePipeline {
    override fun start(scope: CoroutineScope) {}
    override fun stop() {}
    override fun reset() {}
}

private class PlainAudio : VoiceAudio {
    override var isCapturing: Boolean = false
    override fun start(): Boolean { isCapturing = true; return true }
    override fun stop() { isCapturing = false }
    override fun close() {}
    override fun dispose() {}
}

private class PlainModel : com.aixiaohuadb.voice.VoiceModelOwner {
    override fun loadAll(): Result<Unit> = Result.success(Unit)
    override fun unloadAll() {}
    override fun reset() {}
}

/** R1：语音执行链与手动执行链必须彻底解耦 */
@OptIn(ExperimentalCoroutinesApi::class)
class ManualReplayAfterVoiceDisabledTest {

    private fun controller(scope: CoroutineScope) = VoiceLifecycleController(
        null, PlainSettings(), PlainPipeline(), PlainAudio(),
        VoiceStateMachine({ AppConfig.DEFAULT }, { "宏" }), PlainModel(), scope,
    )

    private fun action() = CommandAction(
        commandId = 0,
        name = "手动宏",
        steps = listOf(
            ExecStep.Tap(540, 1170),
            ExecStep.Swipe(0, 0, 540, 1170, durationMs = 300),
        ),
    )

    private fun executor(client: InputCommandExecutor, guard: ExecutionGuard) = AdbInputExecutor(
        client = client,
        mapper = CoordinateMapper(BaseSize(1080, 2340)),
        screenSizeProvider = { 1080 to 2340 },
        guard = guard,
    )

    @Test
    fun `语音关闭后手动宏仍真实派发Tap与Swipe`() = runTest {
        val controller = controller(this)
        assertTrue(controller.enable(VoiceStartReason.SETTINGS_TOGGLE).isSuccess)
        assertTrue(controller.disable(VoiceStopReason.SETTINGS_TOGGLE).isSuccess)
        assertTrue(controller.isExecutionCancelled())

        // 语音链的 guard 已取消；手动链每次回放新建独立 token
        val voiceGuard = ExecutionGuard { controller.isExecutionCancelled() }
        val manualToken = ManualExecutionToken()
        assertFalse(manualToken.isCancelled)

        val client = RecordingClient()
        val voiceOutcome = runCatching { executor(client, voiceGuard).execute(action()) }
        assertTrue(voiceOutcome.exceptionOrNull() is CancellationException)
        assertTrue(client.commands.isEmpty())

        val manualResult = executor(client, manualToken.guard).execute(action())

        assertTrue(manualResult.success)
        assertEquals(
            listOf(
                InputCommand.Tap(540, 1170),
                InputCommand.Swipe(0, 0, 540, 1170, 300),
            ),
            client.commands,
        )
        controller.close()
    }

    @Test
    fun `语音禁用仍打断执行中的宏`() = runTest {
        val controller = controller(this)
        assertTrue(controller.enable(VoiceStartReason.SETTINGS_TOGGLE).isSuccess)

        val client = RecordingClient()
        client.onFirstDispatch = { controller.disable(VoiceStopReason.SETTINGS_TOGGLE) }
        val voiceOutcome = runCatching {
            executor(client, ExecutionGuard { controller.isExecutionCancelled() }).execute(action())
        }

        assertTrue(voiceOutcome.exceptionOrNull() is CancellationException)
        assertEquals(1, client.commands.size)
        assertTrue(controller.isExecutionCancelled())
        controller.close()
    }
}
