package com.aixiaohuadb.adb

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.Surface
import android.view.WindowManager
import com.aixiaohuadb.exec.DisplaySize
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.shell.AdbShellStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** 仅允许从 getevent 探测结果注入真实 event 节点路径，杜绝拼接任意路径。 */
private val DEVICE_PATH = Regex("^/dev/input/event\\d+$")

/**
 * 内嵌 ADB 客户端：由本应用直接实现 adb 协议（配对 / 连接 / shell），
 * 不依赖任何外部辅助应用，也不依赖 PC。底层使用 Kadb，证书与私钥持久化在应用私有
 * 目录，因此配对一次后跨进程重启仍然有效。
 *
 * 连接与 shell 都是阻塞 socket 操作，统一调度到 [Dispatchers.IO]；
 * shell 写串行化用 [ioMutex]，连接对象的替换/关闭用 [connectionLock] 串行化，
 * 两者分开避免 shell 执行期间长期持有连接锁。
 */
class AdbClient(
    private val context: Context,
    private val screenSizeProvider: () -> Pair<Int, Int> = { DisplaySize.current(context) },
) : InputCommandExecutor, AdbTransport {

    private val ioMutex = Mutex()
    private val connectionLock = ReentrantLock()
    private val connectionEpoch = AtomicInteger(0)
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

    /** 免 root 多指注入：首次使用时启动 app_process 服务，之后复用同一条 shell */
    private val injectSession by lazy {
        AdbInjectSession(
            apkPathProvider = { context.packageCodePath },
            transportFactory = { command ->
                openShellStream(command)?.let(::KadbInjectTransport)
            },
        )
    }

    /** 长连接上的唯一下发通道：强类型与 raw shell 都串行入队 */
    private val queueScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = AdbCommandQueue(
        executeTyped = { executeTyped(it) },
        executeRaw = { executeRawInternal(it) },
    )
    override val queuedCount: Int get() = queue.queuedCount
    override fun heartbeat(): Boolean = runCatching {
        connectionLock.withLock { kadb }?.connectionCheck() == true
    }.getOrDefault(false)

    init {
        queue.start(queueScope)
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
        if (!connected) connectionLock.withLock { clearConnectionLocked() }
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
        // disconnect/clear 会递增 epoch；探针阶段被抢跑时不得再安装连接对象。
        val epochAtStart = connectionEpoch.get()
        var candidate: Kadb? = null
        var displaced: Kadb? = null
        var installed = false
        var cancelled = false
        val outcome = runCatching {
            require(host.isNotBlank() && port in 1..65535) { "连接地址或端口无效" }
            ioMutex.withLock {
                val created = Kadb.create(host = host, port = port)
                candidate = created
                val probe = created.shell("echo __adb_ok__")
                require(probe.exitCode == 0 && probe.output.trim() == "__adb_ok__") {
                    "shell 验证失败（exit=${probe.exitCode}）"
                }
                if (connectionEpoch.get() != epochAtStart) {
                    cancelled = true
                    throw IllegalStateException("连接已被取消")
                }
                connectionLock.withLock {
                    if (connectionEpoch.get() != epochAtStart) {
                        cancelled = true
                        throw IllegalStateException("连接已被取消")
                    }
                    displaced = kadb
                    kadb = created
                    connectedEndpoint = AdbEndpoint(host, port)
                    publish(AdbPhase.CONNECTED, "已连接，shell 可用", host, port)
                    installed = true
                }
            }
        }
        runCatching { if (!installed) candidate?.close() }
        runCatching { displaced?.close() }
        if (outcome.isFailure && !cancelled && !installed) {
            connectionLock.withLock { clearConnectionLocked() }
            publish(AdbPhase.ERROR, AdbErrors.connection(outcome.exceptionOrNull() ?: IllegalStateException("连接失败"), host, port))
        }
        if (cancelled) {
            Result.failure(IllegalStateException("连接已被取消"))
        } else {
            outcome.fold(
                onSuccess = { Result.success(Unit) },
                onFailure = { t -> Result.failure(IllegalStateException(AdbErrors.connection(t, host, port), t)) },
            )
        }
    }

    /** 断开并释放连接：替换/关闭与状态发布都在 connectionLock 内，杜绝双重 close 与状态倒退 */
    override fun disconnect() = connectionLock.withLock {
        clearConnectionLocked()
        publish(AdbPhase.DISCONNECTED, "已断开连接")
    }

    /** 轻量探活：不抛异常，且必须 shell echo 成功才算连通。 */
    fun isConnected(): Boolean = connectionLock.withLock { kadb }?.let(::probeAlive) ?: false

    /** 当前显示方向下的真实像素尺寸；getevent 面板坐标需配合 [displayRotation] 换算。 */
    fun screenSizePx(): Pair<Int, Int> = screenSizeProvider()

    /** 当前显示旋转，供 getevent 面板原生坐标映射到显示坐标。 */
    @Suppress("DEPRECATION")
    fun displayRotation(): Int = runCatching {
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
    }.getOrDefault(Surface.ROTATION_0)

    /** 只在锁内取连接快照，shell I/O 不持有 connectionLock。 */
    fun readShellOutput(command: String): String? {
        val client = connectionLock.withLock { kadb } ?: return null
        return runCatching { client.openShell(command).use { it.readAll().output } }.getOrNull()
    }

    /** 返回长连接上的 shell 流，由调用方负责 use/close。 */
    fun openShellStream(command: String): AdbShellStream? {
        val client = connectionLock.withLock { kadb } ?: return null
        return runCatching { client.openShell(command) }.getOrNull()
    }

    /** 重新探测当前触摸设备；宏回放不信任录制时保存的设备路径。 */
    internal fun discoverTouchDevice(): RawTouchDevice? {
        val listing = readShellOutput("getevent -pl") ?: return null
        val devices = parseGeteventDevices(listing)
        return pickTouchDevice(listing, devices)
    }

    /**
     * 强类型命令入口：不再每次 execute 做 connectionCheck + shell echo 双重探活，
     * 探活统一由 AdbBridgeService 心跳负责；这里只做轻量连接对象检查。
     */
    override suspend fun execute(command: InputCommand): CommandResult =
        queue.submit(AdbCommand.Typed(command))

    /** raw shell 入口：策略判定 + 审计 + 超时，全部在同一队列内串行 */
    override suspend fun executeRaw(req: RawShellRequest): CommandResult = queue.submit(AdbCommand.Raw(req))

    private suspend fun executeTyped(command: InputCommand): CommandResult = withContext(Dispatchers.IO) {
        val (sw, sh) = screenSizeProvider()
        when (val validation = CommandValidator.validate(command, sw, sh)) {
            is CommandValidator.ValidationResult.Invalid ->
                CommandResult(false, "命令校验失败：${validation.reason}")

            CommandValidator.ValidationResult.Valid -> {
                val result = ioMutex.withLock {
                    // 只在锁内取连接对象快照；shell 执行期间不得持有 connectionLock，
                    // 否则模式切换的 replace/close 会被长命令阻塞。
                    val client = connectionLock.withLock { kadb }
                    if (client == null) {
                        CommandResult(false, "ADB 未连接，请先在连接页完成配对与连接")
                    } else {
                        runCatching {
                            if (command is InputCommand.MultiTouch) {
                                executeMultiTouchLocked(client, command)
                            } else {
                                val shellCmd = when (command) {
                                    is InputCommand.Tap -> "input tap ${command.x} ${command.y}"
                                    is InputCommand.Swipe ->
                                        "input swipe ${command.fromX} ${command.fromY} ${command.toX} ${command.toY} ${command.durationMs}"
                                    is InputCommand.KeyEvent -> "input keyevent ${command.keyCode}"
                                    is InputCommand.MultiTouch -> error("unreachable")
                                }
                                val response = client.shell(shellCmd)
                                if (response.exitCode == 0) {
                                    CommandResult(true, "执行成功", attempted = true)
                                } else {
                                    // 设备拒绝（非零 exit）：点击没有落地，保持 attempted=false 允许降级
                                    CommandResult(false, "执行失败：${response.allOutput.take(120)}", attempted = false)
                                }
                            }
                        }.getOrElse { t ->
                            // 超时/IO 异常时链路可能已写入，保守置 true，禁止重放造成双点击
                            CommandResult(false, "执行异常：${t.message ?: "未知错误"}", attempted = true)
                        }
                    }
                }
                if (!result.ok && result.message.startsWith("ADB 未连接")) {
                    connectionLock.withLock { clearConnectionLocked() }
                    publish(AdbPhase.DISCONNECTED, "链路已断开，请重新连接")
                }
                result
            }
        }
    }

    /**
     * 多指注入按成熟项目的能力梯度执行：
     *
     * 1. 首选 app_process + IInputManager.injectInputEvent（scrcpy 同款）：
     *    不写 /dev/input、不需要 root，坐标直接是当前显示坐标。
     * 2. 注入服务不可用（OEM 禁用 app_process、APK 不可读）才回退 sendevent；
     *    sendevent 需要 shell/root 能写触摸设备，失败保持 attempted=false 允许无障碍回退。
     * 3. 注入服务已经发出帧后失败，则 attempted=true，禁止再回退造成重复触摸。
     */
    private suspend fun executeMultiTouchLocked(
        client: Kadb,
        command: InputCommand.MultiTouch,
    ): CommandResult {
        var injectUnavailable = ""
        when (val injected = injectSession.inject(command.pointers, command.durationMs)) {
            is AdbInjectSession.InjectResult.Success ->
                return CommandResult(true, "多指执行完成（注入服务）", attempted = true)
            is AdbInjectSession.InjectResult.Failed ->
                return CommandResult(false, "多指注入失败：${injected.reason}", attempted = true)
            is AdbInjectSession.InjectResult.Unavailable -> injectUnavailable = injected.reason
        }

        val device = discoverTouchDevice()
            ?: return CommandResult(
                false,
                "未找到可注入的触摸设备（注入服务：${injectUnavailable.ifBlank { "不可用" }}）",
                attempted = false,
            )
        if (!DEVICE_PATH.matches(device.path)) {
            return CommandResult(false, "触摸设备路径非法：${device.path}", attempted = false)
        }
        if (!device.supportsMultiTouchCapture) {
            return CommandResult(
                false,
                "触摸设备使用 Type A 旧协议（缺少 ABS_MT_SLOT/TRACKING_ID），sendevent 无法注入多指；单指仍可用 input tap/swipe，多指请走无障碍",
                attempted = false,
            )
        }
        if (command.pointers.size > device.maxSlot + 1) {
            return CommandResult(
                false,
                "触摸设备最多支持 ${device.maxSlot + 1} 指，宏录制了 ${command.pointers.size} 指",
                attempted = false,
            )
        }

        val probe = client.shell("sendevent ${device.path} 0 0 0")
        if (probe.exitCode != 0) {
            return CommandResult(
                false,
                "当前 ADB shell 无法直接写触摸设备（/dev/input），多指注入不可用；" +
                    "注入服务：${injectUnavailable.ifBlank { "不可用" }}；" +
                    "请改用无障碍模式或 root 设备。${probe.allOutput.take(120)}",
                attempted = false,
            )
        }

        val rotation = displayRotation()
        val panelPointers = command.pointers.map { pointer ->
            InputCommand.MotionPointer(
                pointer.points.map { point ->
                    val (panelX, panelY) = mapDisplayToPanel(
                        point.x, point.y, device.maxX, device.maxY, rotation,
                    )
                    InputCommand.MotionPoint(
                        panelX.coerceIn(0, device.maxX),
                        panelY.coerceIn(0, device.maxY),
                        point.timeMs,
                    )
                },
            )
        }
        val script = runCatching {
            AdbMultiTouchScript.build(device, panelPointers, command.durationMs)
        }.getOrElse { error ->
            return CommandResult(
                false,
                "多指注入参数非法：${error.message ?: "未知错误"}",
                attempted = false,
            )
        }
        if (script.isBlank()) {
            return CommandResult(false, "多指轨迹为空，未派发", attempted = false)
        }

        val response = withTimeout(command.durationMs.toLong() + 15_000L) { client.shell(script) }
        return if (response.exitCode == 0) {
            CommandResult(true, "多指执行完成", attempted = true)
        } else {
            CommandResult(false, "多指注入失败：${response.allOutput.take(120)}", attempted = true)
        }
    }

    private suspend fun executeRawInternal(req: RawShellRequest): CommandResult {
        val verdict = RawShellPolicy.decide(req)
        if (verdict.decision == RawShellPolicy.Decision.DENY) {
            val denied = CommandResult(false, verdict.reason)
            AdbAuditLog.append(context, req, verdict, denied)
            return denied
        }
        // 审计预写失败必须 fail-closed：没有可审计记录就不执行任何 raw 命令
        if (!AdbAuditLog.prewrite(context, req, verdict)) {
            return CommandResult(false, "审计日志写入失败，已拒绝执行")
        }
        var timedOut = false
        val result = runCatching {
            ioMutex.withLock {
                val client = connectionLock.withLock { kadb }
                    ?: return@withLock CommandResult(false, "ADB 未连接")
                withTimeout(req.timeoutMs.coerceIn(1_000L, 30_000L)) {
                    val response = client.shell(req.command.trim())
                    if (response.exitCode == 0) {
                        CommandResult(true, response.output.take(400), attempted = true)
                    } else {
                        CommandResult(false, "exit=${response.exitCode} ${response.allOutput.take(200)}", attempted = true)
                    }
                }
            }
        }.getOrElse { t ->
            timedOut = t is kotlinx.coroutines.TimeoutCancellationException
            CommandResult(false, if (timedOut) "shell 超时" else "执行异常：${t.message ?: "未知错误"}")
        }
        AdbAuditLog.append(context, req, verdict, result)
        // 超时后连接状态不可信：关闭旧 socket 并尝试重建
        if (timedOut) {
            disconnect()
            reconnect()
        }
        return result
    }

    /** 复用例外保存的 pairedEndpoint + 持久证书重连 */
    override suspend fun reconnect(): Result<Unit> {
        val endpoint = pairedEndpoint ?: return Result.failure(IllegalStateException("尚无已配对端点"))
        return connect(endpoint.host, endpoint.port)
    }

    override fun pairedEndpoint(): AdbEndpoint? = pairedEndpoint
    override fun cancelAll(reason: String) = queue.cancelAll(reason)

    /** 自测：发送一次无害的 HOME 按键验证链路。 */
    suspend fun probe(): CommandResult {
        val result = execute(InputCommand.KeyEvent(3))
        if (!result.ok) refresh()
        return result
    }

    fun close() {
        disconnect()
        queue.close()
        queueScope.cancel()
    }

    /** 调用方需持有 connectionLock（connect/refresh/execute/disconnect 的失败清理路径）。 */
    private fun clearConnectionLocked() {
        connectionEpoch.incrementAndGet()
        runCatching { kadb?.close() }
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
