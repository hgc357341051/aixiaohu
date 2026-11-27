package com.aixiaohuadb.adb

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 内嵌 ADB 客户端：由本应用直接实现 adb 协议（配对 / 连接 / shell），
 * 不依赖任何外部辅助应用，也不依赖 PC。底层使用 Kadb，证书与私钥持久化在应用私有
 * 目录，因此配对一次后跨进程重启仍然有效。
 *
 * 连接与 shell 都是阻塞 socket 操作，统一调度到 [Dispatchers.IO]；
 * Kadb 自身的 close() 内部已加锁，连接对象的替换用 [ioMutex] 串行化。
 */
class AdbClient(
    private val context: Context,
    private val screenSizeProvider: () -> Pair<Int, Int> = {
        val m = context.resources.displayMetrics
        m.widthPixels to m.heightPixels
    },
) : InputCommandExecutor {

    private val ioMutex = Mutex()
    @Volatile
    private var kadb: Kadb? = null
    @Volatile
    private var connectedEndpoint: AdbEndpoint? = null
    @Volatile
    private var pairedEndpoint: AdbEndpoint? = null

    private val _status = MutableStateFlow(
        AdbStatus(phase = AdbPhase.DISCONNECTED, env = snapshotEnvironment()),
    )
    val status: StateFlow<AdbStatus> = _status.asStateFlow()

    init {
        val certFile = File(context.filesDir, "adb_host_cert.der")
        val keyFile = File(context.filesDir, "adb_host_key.der")
        runCatching {
            if (certFile.exists() && keyFile.exists()) {
                KadbCert.set(certFile.readBytes(), keyFile.readBytes())
            } else {
                val (cert, key) = KadbCert.get()
                certFile.writeBytes(cert)
                keyFile.writeBytes(key)
            }
        }
        val env = snapshotEnvironment()
        _status.value = AdbStatus(
            phase = if (env.supportsWirelessPairing) AdbPhase.DISCONNECTED else AdbPhase.UNSUPPORTED,
            env = env,
        )
    }

    /**
     * 重新读取真机环境，连接态一律以真实 shell 探活为准。
     * 探活失败时立即清理陈旧连接对象，避免“页面显示已连接、自检却说未连接”。
     */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        val env = snapshotEnvironment()
        val connected = isConnected()
        if (!connected && kadb != null) ioMutex.withLock { clearConnectionLocked() }
        val phase = when {
            !env.supportsWirelessPairing -> AdbPhase.UNSUPPORTED
            connected -> AdbPhase.CONNECTED
            pairedEndpoint != null -> AdbPhase.PAIRED
            else -> AdbPhase.DISCONNECTED
        }
        _status.value = AdbStatus(
            phase = phase,
            env = env,
            host = if (connected) connectedEndpoint?.host else null,
            port = if (connected) connectedEndpoint?.port else null,
            pairedHost = pairedEndpoint?.host,
            pairedPort = pairedEndpoint?.port,
            reason = null,
        )
    }

    /** 无线调试配对：pairingCode 由用户从系统开发者选项读取后输入。 */
    suspend fun pair(host: String, port: Int, pairingCode: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val outcome = runCatching {
                require(host.isNotBlank() && port in 1..65535) { "配对地址或端口无效" }
                require(pairingCode.length == 6 && pairingCode.all { it.isDigit() }) { "配对码需为 6 位数字" }
                Kadb.pair(host = host.trim(), port = port, pairingCode = pairingCode.trim())
                pairedEndpoint = AdbEndpoint(host.trim(), port)
                publish(
                    phase = AdbPhase.PAIRED,
                    reason = "配对成功，请在无线调试主页查看连接端口（与配对端口不同）",
                )
            }
            outcome.onFailure { t ->
                val friendly = AdbErrors.pairing(t)
                publish(AdbPhase.ERROR, friendly)
            }
            // Result 也返回友好消息，UI 的 message 卡片不再显示 BoringSSL 原文
            outcome.fold(
                onSuccess = { Result.success(Unit) },
                onFailure = { t -> Result.failure(IllegalStateException(AdbErrors.pairing(t), t)) },
            )
        }

    /**
     * 连接已授权的设备端点。任何一步失败都会彻底清理连接对象与端点，
     * 使状态不可能停留在“显示已连接但 shell 不可用”。
     */
    suspend fun connect(host: String, port: Int): Result<Unit> = withContext(Dispatchers.IO) {
        val host = host.trim()
        val outcome = runCatching {
            require(host.isNotBlank() && port in 1..65535) { "连接地址或端口无效" }
            ioMutex.withLock {
                clearConnectionLocked()
                val candidate = Kadb.create(host = host, port = port)
                val probe = candidate.shell("echo __adb_ok__")
                require(probe.exitCode == 0 && probe.output.trim() == "__adb_ok__") {
                    "shell 验证失败（exit=${probe.exitCode}）"
                }
                kadb = candidate
                connectedEndpoint = AdbEndpoint(host, port)
            }
            publish(AdbPhase.CONNECTED, "已连接，shell 可用", host, port)
        }
        outcome.onFailure { t ->
            ioMutex.withLock { clearConnectionLocked() }
            publish(AdbPhase.ERROR, AdbErrors.connection(t, host, port))
        }
        outcome.fold(
            onSuccess = { Result.success(Unit) },
            onFailure = { t -> Result.failure(IllegalStateException(AdbErrors.connection(t, host, port), t)) },
        )
    }

    /** 断开并释放连接。Kadb.close() 线程安全，无需等待 ioMutex。 */
    fun disconnect() {
        kadb?.close()
        kadb = null
        connectedEndpoint = null
        publish(AdbPhase.DISCONNECTED, "已断开连接")
    }

    /** 轻量探活：不抛异常，且必须 shell echo 成功才算连通。 */
    fun isConnected(): Boolean = kadb?.let(::probeAlive) ?: false

    /**
     * 执行强类型输入命令。命令动词由固定模板决定，参数均为已校验整数，
     * 不存在任意命令注入入口。
     */
    override suspend fun execute(command: InputCommand): CommandResult = withContext(Dispatchers.IO) {
        val (sw, sh) = screenSizeProvider()
        when (val validation = CommandValidator.validate(command, sw, sh)) {
            is CommandValidator.ValidationResult.Invalid ->
                CommandResult(false, "命令校验失败：${validation.reason}")

            CommandValidator.ValidationResult.Valid -> {
                val result = ioMutex.withLock {
                    val client = kadb
                    if (client == null || !probeAlive(client)) {
                        CommandResult(false, "ADB 未连接，请先在连接页完成配对与连接")
                    } else {
                        val shellCmd = when (command) {
                            is InputCommand.Tap -> "input tap ${command.x} ${command.y}"
                            is InputCommand.Swipe ->
                                "input swipe ${command.fromX} ${command.fromY} ${command.toX} ${command.toY} ${command.durationMs}"
                            is InputCommand.KeyEvent -> "input keyevent ${command.keyCode}"
                        }
                        runCatching {
                            val response = client.shell(shellCmd)
                            if (response.exitCode == 0) {
                                CommandResult(true, "执行成功", attempted = true)
                            } else {
                                CommandResult(false, "执行失败：${response.allOutput.take(120)}", attempted = true)
                            }
                        }.getOrElse { t ->
                            CommandResult(false, "执行异常：${t.message ?: "未知错误"}", attempted = false)
                        }
                    }
                }
                // 探活失败说明链路已断，同步修正 UI 状态，避免与自检结果矛盾。
                if (!result.ok && result.message.startsWith("ADB 未连接")) {
                    ioMutex.withLock { clearConnectionLocked() }
                    publish(AdbPhase.DISCONNECTED, "链路已断开，请重新连接")
                }
                result
            }
        }
    }

    /** 自测：发送一次无害的 HOME 按键验证链路。 */
    suspend fun probe(): CommandResult {
        val result = execute(InputCommand.KeyEvent(3))
        if (!result.ok) refresh()
        return result
    }

    fun close() {
        disconnect()
    }

    /** 调用方需持有 ioMutex（connect 的失败清理路径）。 */
    private fun clearConnectionLocked() {
        kadb?.close()
        kadb = null
        connectedEndpoint = null
    }

    private fun probeAlive(client: Kadb): Boolean = runCatching {
        client.connectionCheck() &&
            client.shell("echo __adb_ok__").let { it.exitCode == 0 && it.output.trim() == "__adb_ok__" }
    }.getOrDefault(false)

    private fun publish(phase: AdbPhase, reason: String?, host: String? = null, port: Int? = null) {
        _status.value = AdbStatus(
            phase = phase,
            env = snapshotEnvironment(),
            host = host,
            port = port,
            pairedHost = pairedEndpoint?.host,
            pairedPort = pairedEndpoint?.port,
            reason = reason,
        )
    }

    private fun snapshotEnvironment(): AdbEnvironment {
        val enabled = runCatching {
            Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0)
        }.getOrDefault(0)
        val metrics = context.resources.displayMetrics
        return AdbEnvironment(
            sdkInt = Build.VERSION.SDK_INT,
            wirelessDebuggingEnabled = enabled,
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
        )
    }
}
