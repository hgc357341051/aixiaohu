package com.example.voicectl.shizuku

import kotlinx.coroutines.flow.StateFlow

interface ShizukuClient {
    val status: StateFlow<ShizukuStatus>

    /** 注册三类 Shizuku 监听并做首次重算；可重复调用。 */
    fun start()

    /** 反注册监听，停止状态推送。 */
    fun stop()

    /** 反注册监听并释放本客户端持有的协程；保留最近一次 status 快照，不影响正在执行的命令。 */
    fun close()

    /** 主动重查 binder 与 checkSelfPermission —— 发现授权被撤的唯一可靠手段。 */
    fun refresh()

    fun requestPermission()

    /** 按 SDK_INT 选择激活引导策略（34+ 无线调试，29–33 安装引导）。 */
    fun activationGuide(): ActivationGuide

    /** 当前真机环境快照，供调试页展示，不做任何网络上报。 */
    fun debugInfo(): ShizukuDebugInfo
}
