package com.example.voicectl.shizuku

/** 引导策略工厂：唯一的分支依据是 SDK_INT，客户端始终只有一套 Shizuku Binder 协议。 */
object ActivationGuides {
    const val WIRELESS_DEBUG_MIN_SDK = 34

    fun forSdk(sdkInt: Int): ActivationGuide =
        if (sdkInt >= WIRELESS_DEBUG_MIN_SDK) WirelessDebugGuide.create() else InstallShizukuGuide.create()
}
