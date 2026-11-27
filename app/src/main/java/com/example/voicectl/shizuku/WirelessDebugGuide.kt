package com.example.voicectl.shizuku

/** Android 14+：无线调试只是 Shizuku App 的启动方式，不是第二套客户端协议。 */
object WirelessDebugGuide {
    fun create(): ActivationGuide = ActivationGuide.WirelessDebug(
        listOf(
            GuideStep(
                title = "安装 Shizuku",
                detail = "从官方渠道安装 Shizuku（moe.shizuku.privileged.api），不要使用第三方改包。",
                action = GuideAction.OpenDownload,
            ),
            GuideStep(
                title = "打开开发者选项",
                detail = "设置 → 关于手机 → 连续点按「版本号」7 次，直到提示已进入开发者模式。",
                action = GuideAction.OpenDeveloperOptions,
            ),
            GuideStep(
                title = "开启无线调试",
                detail = "开发者选项 → 无线调试 → 打开开关。配对码只用于 Shizuku App 内部，本应用不需要也不保存配对码。",
                action = GuideAction.OpenWirelessDebugging,
            ),
            GuideStep(
                title = "在 Shizuku 中启动服务",
                detail = "打开 Shizuku → 选择「通过无线调试启动」→ 按系统提示完成启动；如提示缺少 Companion，按 Shizuku 页内指引处理。",
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
