package com.example.voicectl.adb

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

    /** 重新读取真机环境并重算状态；连接态以实际 socket 探活为准。 */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        val env = snapshotEnvironment()
        val connected = isConnected()
        val phase = when {
            !env.supportsWirelessPairing -> AdbPhase.UNSUPPORTED
            connected -> AdbPhase.CONNECTED
            else -> when (_status.value.phase) {
                AdbPhase.PAIRED, AdbPhase.CONNECTED -> AdbPhase.PAIRED
                AdbPhase.ERROR -> AdbPhase.ERROR
                else -> AdbPhase.DISCONNECTED
            }
        }
        _status.value = AdbStatus(
            phase = phase,
            env = env,
            host = connectedEndpoint?.host,
            port = connectedEndpoint?.port,
            reason = if (phase == AdbPhase.ERROR) _status.value.reason else null,
        )
    }

    /** 无线调试配对：pairingCode 由用户从系统开发者选项读取后输入。 */
    suspend fun pair(host: String, port: Int, pairingCode: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(host.isNotBlank() && port in 1..65535) { "配对地址或端口无效" }
                require(pairingCode.length == 6 && pairingCode.all { it.isDigit() }) { "配对码需为 6 位数字" }
                Kadb.pair(host = host.trim(), port = port, pairingCode = pairingCode.trim())
                publish(AdbPhase.PAIRED, "配对成功，请在无线调试主页查看连接端口", host = host.trim(), port = port)
            }.onFailure { t ->
                publish(AdbPhase.ERROR, "配对失败：${t.message ?: "未知错误"}")
            }
        }

    /** 连接已授权的设备端点，并用一次 echo 验证 shell 可用。 */
    suspend fun connect(host: String, port: Int): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(host.isNotBlank() && port in 1..65535) { "连接地址或端口无效" }
            ioMutex.withLock {
                kadb?.close()
                val candidate = Kadb.create(host = host.trim(), port = port)
                val probe = candidate.shell("echo __adb_ok__")
                require(probe.exitCode == 0 && probe.output.trim() == "__adb_ok__") {
                    "shell 验证失败（exit=${probe.exitCode}）"
                }
                kadb = candidate
                connectedEndpoint = AdbEndpoint(host.trim(), port)
            }
            publish(AdbPhase.CONNECTED, "已连接，shell 可用", host = host.trim(), port = port)
        }.onFailure { t ->
            publish(AdbPhase.ERROR, "连接失败：${t.message ?: "未知错误"}")
        }
    }

    /** 断开并释放连接。Kadb.close() 线程安全，无需等待 ioMutex。 */
    fun disconnect() {
        kadb?.close()
        kadb = null
        connectedEndpoint = null
        publish(AdbPhase.DISCONNECTED, "已断开连接")
    }

    /** 轻量探活：不抛异常。 */
    fun isConnected(): Boolean = runCatching {
        kadb?.let { it.connectionCheck() && it.shell("echo ok").exitCode == 0 } == true
    }.getOrDefault(false)

    /**
     * 执行强类型输入命令。命令动词由固定模板决定，参数均为已校验整数，
     * 不存在任意命令注入入口。
     */
    override suspend fun execute(command: InputCommand): CommandResult = withContext(Dispatchers.IO) {
        val (sw, sh) = screenSizeProvider()
        when (val validation = CommandValidator.validate(command, sw, sh)) {
            is CommandValidator.ValidationResult.Invalid ->
                CommandResult(false, "命令校验失败：${validation.reason}")
            CommandValidator.ValidationResult.Valid -> ioMutex.withLock {
                val client = kadb
                if (client == null || !client.connectionCheck()) {
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
        }
    }

    /** 自测：发送一次无害的 HOME 按键验证链路。 */
    suspend fun probe(): CommandResult = execute(InputCommand.KeyEvent(3))

    fun close() {
        disconnect()
    }

    private fun publish(phase: AdbPhase, reason: String?, host: String? = null, port: Int? = null) {
        _status.value = AdbStatus(
            phase = phase,
            env = snapshotEnvironment(),
            host = host ?: connectedEndpoint?.host,
            port = port ?: connectedEndpoint?.port,
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
