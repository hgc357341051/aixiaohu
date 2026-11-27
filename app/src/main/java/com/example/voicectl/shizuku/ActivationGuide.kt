package com.example.voicectl.shizuku

/** 两种激活策略共用同一个引导模型；步骤是否可点由 GuideAction 决定。 */
sealed interface ActivationGuide {
    val steps: List<GuideStep>

    data class WirelessDebug(override val steps: List<GuideStep>) : ActivationGuide
    data class InstallShizuku(override val steps: List<GuideStep>) : ActivationGuide
}

data class GuideStep(
    val title: String,
    val detail: String,
    val action: GuideAction? = null,
)

sealed interface GuideAction {
    data object OpenDeveloperOptions : GuideAction
    data object OpenWirelessDebugging : GuideAction
    data object OpenShizukuApp : GuideAction
    data object OpenDownload : GuideAction
    data object Recheck : GuideAction
}
