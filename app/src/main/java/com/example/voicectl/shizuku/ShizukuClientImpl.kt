package com.example.voicectl.shizuku

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.example.voicectl.config.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

/**
 * 官方 Shizuku API 的 App 进程封装：注册三类监听、主动重算六态、采集真机环境快照。
 *
 * 官方没有「授权被撤销」监听器，因此 binder 收到、页面 onResume、每条命令执行前、
 * 每次自测前都调用 [refresh]，用 checkSelfPermission 重查作为唯一可靠依据。
 *
 * Shizuku 是可选备用链路：监听注册、状态重算、持久化写入全部隔离异常，
 * 任何失败都只落到状态 reason，不允许冒泡到 Application.onCreate。
 */
class ShizukuClientImpl(
    context: Context,
    private val settingsStore: SettingsStore,
) : ShizukuClient {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _status = MutableStateFlow(
        ShizukuStatus(
            phase = ShizukuPhase.NOT_INSTALLED,
            env = snapshotEnvironment(),
            reason = null,
            checkedAtMs = System.currentTimeMillis(),
        ),
    )
    override val status: StateFlow<ShizukuStatus> = _status.asStateFlow()

    /**
     * 曾获得过授权。内存置位是快速路径；持久化值由 configFlow 异步补齐，
     * 用于冷启动时区分「从未授权 UNAUTHORIZED」与「曾授权后被撤 AUTHORIZATION_LOST」。
     */
    @Volatile
    private var lastAuthorized = false

    /** 三类监听全部注册失败时置 false，保证备用链路不会被误判为可用。 */
    @Volatile
    private var listenersHealthy = true

    @Volatile
    private var started = false

    @Volatile
    private var binderReceivedRegistered = false

    @Volatile
    private var binderDeadRegistered = false

    @Volatile
    private var permissionResultRegistered = false

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { onBinderReceived() }
    private val binderDeadListener = Shizuku.OnBinderDeadListener { onBinderDead() }
    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { code, result ->
        onPermissionResult(code, result)
    }

    init {
        // DataStore 为异步读取，构造期发起收集；值到达后补一次重算，不阻塞主线程。
        clientScope.launch {
            runCatching {
                settingsStore.configFlow.collect { config ->
                    if (config.everShizukuAuthorized && !lastAuthorized) {
                        lastAuthorized = true
                        refresh(null)
                    }
                }
            }
        }
    }

    override fun start() {
        if (started) return
        binderReceivedRegistered = runCatching {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener, mainHandler)
            true
        }.getOrDefault(false)
        binderDeadRegistered = runCatching {
            Shizuku.addBinderDeadListener(binderDeadListener, mainHandler)
            true
        }.getOrDefault(false)
        permissionResultRegistered = runCatching {
            Shizuku.addRequestPermissionResultListener(permissionResultListener, mainHandler)
            true
        }.getOrDefault(false)

        listenersHealthy = binderReceivedRegistered || binderDeadRegistered || permissionResultRegistered
        started = listenersHealthy
        refresh(if (listenersHealthy) null else "Shizuku 监听注册失败，备用执行链路不可用")
    }

    override fun stop() {
        if (binderReceivedRegistered) {
            runCatching { Shizuku.removeBinderReceivedListener(binderReceivedListener) }
            binderReceivedRegistered = false
        }
        if (binderDeadRegistered) {
            runCatching { Shizuku.removeBinderDeadListener(binderDeadListener) }
            binderDeadRegistered = false
        }
        if (permissionResultRegistered) {
            runCatching { Shizuku.removeRequestPermissionResultListener(permissionResultListener) }
            permissionResultRegistered = false
        }
        started = false
    }

    /** 释放内部 configFlow 收集器与持久化协程；不触碰 _status，也不干预 InputBridge 的执行。 */
    override fun close() {
        stop()
        clientScope.cancel()
    }

    override fun refresh() {
        refresh(null)
    }

    /** 任何 Shizuku 调用异常都被吞掉，只影响本链路状态，绝不影响语音主链路。 */
    private fun refresh(reason: String?) = runCatching {
        val env = snapshotEnvironment()
        val granted = if (env.binderAlive && listenersHealthy) {
            runCatching {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
        } else {
            false
        }
        val unsupported = if (env.shizukuInstalled) {
            runCatching { Shizuku.isPreV11() || Shizuku.getVersion() < MIN_API_VERSION }
                .getOrDefault(false)
        } else {
            false
        }

        val next = ShizukuStatusResolver.resolve(
            env = env,
            current = _status.value.phase,
            // 当前态为 AUTHORIZED 也算「曾授权」，覆盖执行前重查发现授权被撤的场景
            lastAuthorized = lastAuthorized || _status.value.phase == ShizukuPhase.AUTHORIZED,
            permissionGranted = granted,
            unsupported = unsupported,
            reason = reason,
        )
        if (next.phase == ShizukuPhase.AUTHORIZED) markAuthorized()
        _status.value = next
    }

    /** 异步持久化，绝不阻塞 binder 回调或主链路。 */
    private fun markAuthorized() {
        if (lastAuthorized) return
        lastAuthorized = true
        clientScope.launch {
            runCatching { settingsStore.setShizukuEverAuthorized(true) }
        }
    }

    override fun requestPermission() {
        refresh(null)
        if (!_status.value.env.binderAlive) {
            refresh("Shizuku 服务未运行，无法发起授权")
            return
        }
        runCatching { Shizuku.requestPermission(REQUEST_CODE) }
            .onFailure { refresh("授权请求失败：${it.message ?: "未知错误"}") }
    }

    override fun activationGuide(): ActivationGuide = ActivationGuides.forSdk(Build.VERSION.SDK_INT)

    override fun debugInfo(): ShizukuDebugInfo = ShizukuDebugInfo.from(_status.value)

    private fun onBinderReceived() = refresh(null)

    private fun onBinderDead() = refresh("Shizuku 服务已断开，等待服务端重新启动")

    private fun onPermissionResult(requestCode: Int, grantResult: Int) {
        if (requestCode != REQUEST_CODE) return
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            refresh(null)
        } else {
            refresh("授权被拒绝，请在 Shizuku 中为本应用开启权限")
        }
    }

    private fun snapshotEnvironment(): ShizukuEnvironment {
        val installed = runCatching {
            appContext.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        }.getOrDefault(false)

        // checkSelfPermission/getUid/getVersion/getSELinuxContext 在 binder 不存活时会抛异常，
        // 因此先 pingBinder，再对每次调用单独 runCatching。
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val apiVersion = if (alive) runCatching { Shizuku.getVersion() }.getOrNull() else null
        val uid = if (alive) runCatching { Shizuku.getUid() }.getOrNull() else null
        val selinux = if (alive) runCatching { Shizuku.getSELinuxContext() }.getOrNull() else null
        val privilege = when (uid) {
            UID_ROOT -> Privilege.ROOT
            UID_SHELL -> Privilege.SHELL
            else -> Privilege.UNKNOWN
        }

        val metrics = Resources.getSystem().displayMetrics
        return ShizukuEnvironment(
            sdkInt = Build.VERSION.SDK_INT,
            shizukuInstalled = installed,
            binderAlive = alive,
            shizukuApiVersion = apiVersion,
            uid = uid,
            selinuxContext = selinux,
            privilege = privilege,
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
        )
    }

    private companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        const val REQUEST_CODE = 1001
        const val MIN_API_VERSION = 10
        const val UID_ROOT = 0
        const val UID_SHELL = 2000
    }
}
