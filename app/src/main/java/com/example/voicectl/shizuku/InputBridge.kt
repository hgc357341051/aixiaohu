package com.example.voicectl.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.os.SystemClock
import com.example.voicectl.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku

/** 真实执行结果：exitCode 为 shell 进程返回的真实值，异常时为 null；elapsedMs 为真实耗时。 */
data class CommandResult(
    val ok: Boolean,
    val exitCode: Int?,
    val elapsedMs: Long,
    val message: String,
)

interface InputBridge : AutoCloseable {
    suspend fun execute(command: InputCommand): CommandResult
    suspend fun probe(): CommandResult
}

/**
 * App 进程通过 bindUserService 绑定运行在 shell 身份进程的本 App Binder 服务。
 * 所有执行前都重查授权；SecurityException 会触发客户端重新判定六态。
 */
class InputBridgeImpl(
    context: Context,
    private val client: ShizukuClient,
) : InputBridge {
    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, ShizukuInputService::class.java.name),
    ).daemon(false)
        .processNameSuffix("shizuku")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)
        .tag("input-bridge")

    @Volatile
    private var service: IShizukuInputService? = null

    @Volatile
    private var pendingBind: CompletableDeferred<Boolean>? = null

    private val bindMutex = Mutex()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val usable = binder != null && runCatching { binder.pingBinder() }.getOrDefault(false)
            if (usable) service = IShizukuInputService.Stub.asInterface(binder)
            pendingBind?.complete(usable)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            pendingBind?.complete(false)
        }
    }

    override suspend fun execute(command: InputCommand): CommandResult {
        val startMs = SystemClock.elapsedRealtime()
        fun done(exitCode: Int?, message: String) = CommandResult(
            ok = exitCode == 0,
            exitCode = exitCode,
            elapsedMs = SystemClock.elapsedRealtime() - startMs,
            message = message,
        )

        // 门禁 1：每条命令执行前重查 binder + 授权
        client.refresh()
        val state = client.status.value
        if (state.phase != ShizukuPhase.AUTHORIZED) {
            return done(null, "Shizuku 不可用（${state.phase}）：${state.reason ?: "尚未授权"}")
        }

        // 门禁 2：App 进程边界校验
        val validated = try {
            CommandValidator.validate(command, state.env.screenWidth, state.env.screenHeight)
        } catch (e: IllegalArgumentException) {
            return done(null, "命令校验失败：${e.message}")
        }

        if (!ensureBound()) {
            val now = client.status.value
            return done(
                null,
                "UserService 绑定失败（${now.phase}）：${now.reason ?: "请确认 Shizuku 已启动并已授权"}",
            )
        }
        val bound = service ?: return done(null, "UserService 未连接")

        // 门禁 3：绑定完成后、真正发命令前再查一次
        client.refresh()
        if (client.status.value.phase != ShizukuPhase.AUTHORIZED) {
            return done(null, "执行前授权已失效，已中止本次输入")
        }

        return withContext(Dispatchers.IO) {
            try {
                val exitCode = when (validated) {
                    is InputCommand.Tap -> bound.tap(validated.x, validated.y)
                    is InputCommand.Swipe -> bound.swipe(
                        validated.fromX,
                        validated.fromY,
                        validated.toX,
                        validated.toY,
                        validated.durationMs,
                    )
                    is InputCommand.KeyEvent -> bound.keyEvent(validated.keyCode)
                }
                done(exitCode, "${describeCommand(validated)} · exit=$exitCode")
            } catch (e: SecurityException) {
                // bindUserService / AIDL 调用被拒 → 主动重算，曾授权则落到 AUTHORIZATION_LOST
                client.refresh()
                done(null, "执行被拒绝（SecurityException），授权可能已撤销")
            } catch (e: DeadObjectException) {
                client.refresh()
                done(null, "UserService 已断开（DeadObjectException）")
            } catch (e: RemoteException) {
                done(null, "远端调用失败：${e.message}")
            } catch (e: RuntimeException) {
                done(null, "执行失败：${e.message}")
            }
        }
    }

    override suspend fun probe(): CommandResult {
        val startMs = SystemClock.elapsedRealtime()
        fun done(exitCode: Int?, message: String) = CommandResult(
            ok = exitCode == 0,
            exitCode = exitCode,
            elapsedMs = SystemClock.elapsedRealtime() - startMs,
            message = message,
        )

        client.refresh()
        if (client.status.value.phase != ShizukuPhase.AUTHORIZED) {
            return done(null, "无法自测：Shizuku 尚未授权")
        }
        if (!ensureBound()) return done(null, "无法自测：UserService 绑定失败")
        val bound = service ?: return done(null, "无法自测：UserService 未连接")

        client.refresh()
        if (client.status.value.phase != ShizukuPhase.AUTHORIZED) {
            return done(null, "自测前授权已失效")
        }

        return withContext(Dispatchers.IO) {
            try {
                val exitCode = bound.probe()
                done(exitCode, "probe /system/bin/input · exit=$exitCode")
            } catch (e: SecurityException) {
                client.refresh()
                done(null, "自测被拒绝：SecurityException")
            } catch (e: DeadObjectException) {
                client.refresh()
                done(null, "自测失败：UserService 已断开")
            } catch (e: RemoteException) {
                done(null, "自测失败：${e.message}")
            } catch (e: RuntimeException) {
                done(null, "自测失败：${e.message}")
            }
        }
    }

    override fun close() {
        pendingBind?.complete(false)
        pendingBind = null
        service = null
        mainHandler.post {
            runCatching { Shizuku.unbindUserService(userServiceArgs, connection, true) }
        }
    }

    private suspend fun ensureBound(): Boolean = bindMutex.withLock {
        val current = service
        if (current != null && safePing(current)) return@withLock true

        // SecurityException 在这里被捕获后触发 refresh：曾授权 → AUTHORIZATION_LOST
        client.refresh()
        if (client.status.value.phase != ShizukuPhase.AUTHORIZED) return@withLock false

        val deferred = CompletableDeferred<Boolean>()
        pendingBind = deferred
        val submitted = withContext(Dispatchers.Main) {
            runCatching { Shizuku.bindUserService(userServiceArgs, connection) }
                .onFailure { client.refresh() }
                .isSuccess
        }
        if (!submitted) {
            pendingBind = null
            return@withLock false
        }

        val bound = try {
            withTimeout(BIND_TIMEOUT_MS) { deferred.await() }
        } catch (_: TimeoutCancellationException) {
            false
        } finally {
            pendingBind = null
        }
        bound && service != null
    }

    private fun safePing(bound: IShizukuInputService): Boolean =
        runCatching { bound.asBinder().pingBinder() }.getOrDefault(false)

    private companion object {
        const val BIND_TIMEOUT_MS = 5_000L
    }
}

/** 调试区展示的真实命令文本，严格与 shell 进程实际 exec 的参数一致。 */
internal fun describeCommand(command: InputCommand): String = when (command) {
    is InputCommand.Tap -> "/system/bin/input tap ${command.x} ${command.y}"
    is InputCommand.Swipe -> "/system/bin/input swipe ${command.fromX} ${command.fromY} " +
        "${command.toX} ${command.toY} ${command.durationMs}"
    is InputCommand.KeyEvent -> "/system/bin/input keyevent ${command.keyCode}"
}
