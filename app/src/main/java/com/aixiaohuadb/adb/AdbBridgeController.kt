package com.aixiaohuadb.adb

import android.content.Context
import com.aixiaohuadb.service.ControlMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

enum class AdbBridgePhase { OFF, CONNECTING, CONNECTED, RECONNECTING, ERROR }

data class AdbBridgeState(
    val phase: AdbBridgePhase = AdbBridgePhase.OFF,
    val endpoint: String? = null,
    val heartbeatOk: Boolean = false,
    val lastHeartbeatAt: Long = 0L,
    val retryCount: Int = 0,
    val queued: Int = 0,
    val rawShellEnabled: Boolean = true,
    /** 用户可见的失败原因（FGS 启动被拒、重连超限等），不可静默吞掉 */
    val message: String? = null,
)

sealed class AdbEvent {
    data class CommandResultEvent(val result: com.aixiaohuadb.adb.CommandResult) : AdbEvent()
    data object Reconnected : AdbEvent()
    data class Error(val message: String) : AdbEvent()
}

/**
 * ADB 前台长连接编排：模式切换启停、心跳、退避重连、队列回执状态。
 * 不直接持有 socket，全部委托 [AdbTransport]；不写 UI，只暴露状态流。
 *
 * 前台服务启动必须由可见 Activity trampoline 触发（Android 12+ 后台 FGS 限制），
 * 失败时置 ERROR 并写入 [AdbBridgeState.message]，绝不静默成功。
 */
class AdbBridgeController(
    private val context: Context?,
    private val client: AdbTransport,
    private val scope: CoroutineScope,
    private val heartbeatSecondsProvider: () -> Int = { 5 },
    private val reconnectMaxProvider: () -> Int = { 3 },
    /** 测试 seam：默认走可见 trampoline；回调需回传 sessionId 以便丢弃过期回调 */
    private val foregroundStarter: (Context?, Int) -> Boolean = { ctx, sessionId ->
        AdbStartActivity.start(ctx, sessionId)
    },
    /** 前台启动确认超时；<=0 关闭。测试可设为 Long.MAX_VALUE 避免误触发 */
    private val foregroundConfirmTimeoutMs: Long = 3_000L,
    /** 测试 seam：阻塞探活所在 dispatcher，JVM 单测传入 testDispatcher 保证可确定性 */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _state = MutableStateFlow(AdbBridgeState())
    val state: StateFlow<AdbBridgeState> = _state.asStateFlow()

    @Volatile
    private var heartbeatFailures = 0
    @Volatile
    private var reconnecting = false
    @Volatile
    private var active = false
    private val session = AtomicInteger(0)
    @Volatile
    private var backoffJob: Job? = null

    /** 保护 start/stop/FGS 回调的全部状态变更，消除非原子 active/session 竞态 */
    private val lifecycleLock = ReentrantLock()
    @Volatile
    private var foregroundConfirmed = false
    private var foregroundTimeoutJob: Job? = null

    /** ACCESSIBILITY 停服务断链；ADB 起前台并复用配对端点；VOICE 保持已有长连接作备用 */
    fun onModeChanged(mode: ControlMode) {
        when (mode) {
            ControlMode.ACCESSIBILITY -> stop()
            ControlMode.ADB -> start()
            ControlMode.VOICE -> Unit
        }
    }

    fun start() {
        lifecycleLock.withLock {
            if (active) return@withLock
            active = true
            val currentSession = session.incrementAndGet()
            heartbeatFailures = 0
            backoffJob?.cancel()
            backoffJob = null
            reconnecting = false
            foregroundConfirmed = false
            foregroundTimeoutJob?.cancel()
            foregroundTimeoutJob = null
            _state.value = AdbBridgeState(phase = AdbBridgePhase.CONNECTING)
            // 后台 context.startForegroundService 在 Android 12+ 会被拒，改走可见 trampoline。
            // starter 的同步返回值只代表“启动界面已拉起”，真正的 FGS 确认由 reportForegroundStarted 回调。
            val started = foregroundStarter(context, currentSession)
            if (!started) {
                reportForegroundStartFailed("无法拉起 ADB 启动界面，请在 ADB 页手动确认连接", currentSession)
                return@withLock
            }
            if (foregroundConfirmTimeoutMs > 0) {
                foregroundTimeoutJob = scope.launch {
                    delay(foregroundConfirmTimeoutMs)
                    if (active && currentSession == session.get() && !foregroundConfirmed) {
                        reportForegroundStartFailed(
                            "ADB 前台服务启动超时，请打开 ADB 页手动确认连接", currentSession,
                        )
                    }
                }
            }
            scope.launch { connectLast(currentSession) }
        }
    }

    /** 供前台服务 onCreate 守卫：stop/stale 回调后不得再持有前台 */
    fun isActive(expectedSession: Int? = null): Boolean =
        active && (expectedSession == null || expectedSession == session.get())

    /** FGS 真正 startForeground 成功后由 Service 回调；只确认，不清空其它状态 */
    fun reportForegroundStarted(sessionId: Int) {
        lifecycleLock.withLock {
            if (sessionId != session.get() || !active) return@withLock
            foregroundConfirmed = true
            foregroundTimeoutJob?.cancel()
            foregroundTimeoutJob = null
        }
    }

    /** 由 AdbStartActivity/Service 在前台调用；session 不匹配的过期回调一律忽略，失败必须可见 */
    fun reportForegroundStartFailed(message: String, sessionId: Int) {
        lifecycleLock.withLock {
            if (sessionId != session.get()) return@withLock
            foregroundTimeoutJob?.cancel()
            foregroundTimeoutJob = null
            foregroundConfirmed = false
            session.incrementAndGet()
            active = false
            backoffJob?.cancel()
            backoffJob = null
            reconnecting = false
            context?.let { ctx ->
                runCatching { ctx.stopService(android.content.Intent(ctx, AdbBridgeService::class.java)) }
            }
            client.cancelAll("ADB 前台服务启动失败")
            _state.value = AdbBridgeState(phase = AdbBridgePhase.ERROR, message = message)
        }
    }

    fun stop() {
        lifecycleLock.withLock {
            if (!active && _state.value.phase == AdbBridgePhase.OFF) return@withLock
            foregroundTimeoutJob?.cancel()
            foregroundTimeoutJob = null
            foregroundConfirmed = false
            session.incrementAndGet()
            active = false
            backoffJob?.cancel()
            backoffJob = null
            reconnecting = false
            context?.let { ctx ->
                runCatching { ctx.stopService(android.content.Intent(ctx, AdbBridgeService::class.java)) }
            }
            client.cancelAll("ADB 模式已退出")
            client.disconnect()
            heartbeatFailures = 0
            _state.value = AdbBridgeState(phase = AdbBridgePhase.OFF)
        }
    }

    /** Service 被系统终止：只在仍属于当前 session 时收敛，过期 onDestroy 一律忽略 */
    fun onServiceDestroyed(sessionId: Int) {
        lifecycleLock.withLock {
            if (sessionId != session.get() || !active) return@withLock
            foregroundTimeoutJob?.cancel()
            foregroundTimeoutJob = null
            foregroundConfirmed = false
            session.incrementAndGet()
            active = false
            backoffJob?.cancel()
            backoffJob = null
            reconnecting = false
            client.cancelAll("ADB 前台服务被系统终止")
            client.disconnect()
            heartbeatFailures = 0
            _state.value = AdbBridgeState(
                phase = AdbBridgePhase.ERROR,
                message = "ADB 前台服务被系统终止",
            )
        }
    }

    suspend fun connectLast(sessionId: Int = session.get()): Boolean {
        if (!active || sessionId != session.get()) return false
        val endpoint = client.pairedEndpoint()
        if (endpoint == null) {
            if (sessionId == session.get()) {
                _state.value = _state.value.copy(
                    phase = AdbBridgePhase.ERROR,
                    endpoint = null,
                    message = "尚无已配对端点，请先在 ADB 页完成配对",
                )
            }
            return false
        }
        if (sessionId != session.get()) return false
        _state.value = _state.value.copy(
            phase = AdbBridgePhase.CONNECTING,
            endpoint = "${endpoint.host}:${endpoint.port}",
        )
        val ok = client.reconnect().isSuccess
        if (sessionId != session.get()) return false
        _state.value = _state.value.copy(
            phase = if (ok) AdbBridgePhase.CONNECTED else AdbBridgePhase.ERROR,
            heartbeatOk = ok,
            lastHeartbeatAt = if (ok) System.currentTimeMillis() else 0L,
            message = if (ok) null else "重连失败，请在 ADB 页检查配对状态",
        )
        return ok
    }

    /** 服务定时调用；连续 2 次失败触发 1→2→4→8→16→30s 退避重连，超过上限转 ERROR */
    fun heartbeatTick(currentSession: Int = session.get()) = scope.launch heartbeat@{
        if (!active || currentSession != session.get()) return@heartbeat
        if (_state.value.phase != AdbBridgePhase.CONNECTED || reconnecting) return@heartbeat
        // 探活是阻塞 IO；挂起期间用户可能已切模式，返回后必须二次校验 session
        val ok = withContext(ioDispatcher) { client.heartbeat() }
        if (!active || currentSession != session.get()) return@heartbeat
        if (ok) {
            heartbeatFailures = 0
            _state.update { cur ->
                if (!active || currentSession != session.get()) cur
                else cur.copy(
                    heartbeatOk = true,
                    lastHeartbeatAt = System.currentTimeMillis(),
                    queued = client.queuedCount,
                    message = null,
                )
            }
        } else {
            heartbeatFailures++
            _state.update { cur ->
                if (!active || currentSession != session.get()) cur
                else cur.copy(heartbeatOk = false)
            }
            if (heartbeatFailures >= 2) {
                heartbeatFailures = 0
                if (active && currentSession == session.get()) reconnectWithBackoff(currentSession)
            }
        }
    }

    private fun reconnectWithBackoff(currentSession: Int) {
        if (reconnecting || !active) return
        reconnecting = true
        // holder 在协程启动后回填自身 Job；finally 用它判断是否是“当前”退避任务，
        // 防止 stop→start 后旧 job 清掉新 session 的 reconnecting/backoffJob。
        val self = arrayOfNulls<Job>(1)
        self[0] = scope.launch { runBackoff(currentSession, self[0]) }
        backoffJob = self[0]
    }

    private suspend fun runBackoff(currentSession: Int, self: Job?) {
        val backoffs = longArrayOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L)
        try {
            val max = reconnectMaxProvider().coerceIn(1, 10)
            var attempt = 0
            while (attempt < max && active && currentSession == session.get()) {
                _state.value = _state.value.copy(
                    phase = AdbBridgePhase.RECONNECTING,
                    retryCount = attempt + 1,
                )
                delay(backoffs[attempt.coerceAtMost(backoffs.lastIndex)])
                if (!active || currentSession != session.get()) return
                if (client.reconnect().isSuccess) {
                    val endpoint = client.pairedEndpoint()
                    _state.value = _state.value.copy(
                        phase = AdbBridgePhase.CONNECTED,
                        endpoint = endpoint?.let { "${it.host}:${it.port}" },
                        heartbeatOk = true,
                        lastHeartbeatAt = System.currentTimeMillis(),
                        retryCount = 0,
                        message = null,
                    )
                    return
                }
                attempt++
            }
            if (active && currentSession == session.get()) {
                _state.value = _state.value.copy(
                    phase = AdbBridgePhase.ERROR,
                    message = "重连超过 $max 次仍失败，请在 ADB 页手动重连",
                )
            }
        } finally {
            // 只有仍属于当前 session 且仍是当前退避任务时，才允许清理共享状态
            if (backoffJob === self && currentSession == session.get()) {
                reconnecting = false
                backoffJob = null
            }
        }
    }

    suspend fun submit(command: InputCommand): CommandResult {
        val result = client.execute(command)
        _state.value = _state.value.copy(queued = client.queuedCount)
        return result
    }

    suspend fun submitRaw(req: RawShellRequest): CommandResult {
        val result = client.executeRaw(req)
        _state.value = _state.value.copy(queued = client.queuedCount)
        return result
    }

    fun heartbeatIntervalMs(): Long = heartbeatSecondsProvider().toLong().coerceIn(1L, 60L) * 1_000L
}
