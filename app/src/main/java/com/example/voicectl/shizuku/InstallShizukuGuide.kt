package com.example.voicectl.shizuku

/** Android 10–13：系统无「无线调试启动 Shizuku」入口，引导安装并按 Shizuku 页面提示启动服务。 */
object InstallShizukuGuide {
    fun create(): ActivationGuide = ActivationGuide.InstallShizuku(
        listOf(
            GuideStep(
                title = "安装 Shizuku",
                detail = "从官方渠道安装 Shizuku（moe.shizuku.privileged.api），不要使用第三方改包。",
                action = GuideAction.OpenDownload,
            ),
            GuideStep(
                title = "启动 Shizuku 服务",
                detail = "已 root 设备直接在 Shizuku 中点「启动」；未 root 设备按其页面提示用电脑 adb 授权一次，之后开机可自启。",
                action = GuideAction.OpenShizukuApp,
            ),
            GuideStep(
                title = "回到本页完成授权",
                detail = "点击「重新检测」，随后在 Shizuku 的授权弹窗中允许本应用。",
                action = GuideAction.Recheck,
            ),
        ),
    )
}
