package com.example.voicectl.machine

/** 原型 states 表：机态 → chip/title/desc/bottom/notif 文案（逐字对应） */
data class UiStateInfo(
    val key: String,
    val chip: String,
    val stateLabel: String,
    val title: String,
    val desc: String,
    val bottom: String,
    val mode: String,
    val modeDesc: String,
    val notif: String,
    val accentHex: String,
    val animated: Boolean,
)

fun VoiceState.ui(): UiStateInfo = when (this) {
    VoiceState.IDLE, VoiceState.COOLDOWN -> UiStateInfo(
        "idle", "VOICE", "待机", "随时待命", "说「你好小胡」或「小胡小胡」唤醒我",
        "唤醒词 · 声纹校验已就绪", "语音模式", "唤醒监听已开启 · 仅对局中生效",
        "● 监听中 · 说「你好小胡」唤醒", "#A9B8CA", false,
    )
    VoiceState.LISTEN, VoiceState.VERIFY -> UiStateInfo(
        "listening", "LISTEN", "监听中", "正在聆听…", "L1 粗检 → L2 精检 → 声纹 1:1 校验",
        "只响应机主本人 · 他人语音将被拒绝", "语音模式", "常驻采集 + AEC / NS 前端处理",
        "● 正在聆听 · 声纹校验中", "#22D3EE", true,
    )
    VoiceState.CMD_WINDOW -> UiStateInfo(
        "window", "CMD 3s", "指令窗", "请说指令", "「换名刀」「换 1 号位」「切换位置」或控制词",
        "倒计时 3s", "语音模式", "3s 指令窗 · VAD 定位 · 控制头优先",
        "● 指令窗开启 · 请说指令", "#5EEAD4", true,
    )
    VoiceState.EXECUTE -> UiStateInfo(
        "executing", "EXEC", "执行中", "换名刀", "ACTION=换 · OBJECT=名刀 · 声纹复核通过",
        "点击序列执行中 · 冷却 1.5s", "语音模式", "状态门控通过 · 无障碍/ADB 执行",
        "● 执行中 · 换名刀", "#34D399", true,
    )
    VoiceState.PAUSED -> UiStateInfo(
        "paused", "PAUSED", "已暂停", "识别已暂停", "C3 暂停识别 · 默认 30s 后自动恢复",
        "剩余 30s · 自动恢复", "语音模式", "唤醒仍在 · 可说「继续识别」恢复",
        "● 已暂停 · 点通知恢复", "#FBBF24", false,
    )
    VoiceState.RECOG_OFF -> UiStateInfo(
        "recogoff", "RECOG OFF", "识别关闭", "识别已关闭", "C2 退出识别 · 唤醒监听仍保留",
        "唤醒后说「恢复语音」(C4) 继续", "语音模式", "仅关闭指令识别与执行，唤醒仍开启",
        "● 识别已关闭 · 唤醒仍开启", "#7C90A8", false,
    )
    VoiceState.MANUAL_SILENT, VoiceState.ERROR, VoiceState.UNLOAD_WAKE -> UiStateInfo(
        "silent", "SILENT", "已退出", "语音已退出", "唤醒模型已真实卸载 · 无常驻拾音",
        "点悬浮球 / 通知栏手动恢复", "无语音", "仅保留手动面板 · 麦克风不参与",
        "● 语音已退出 · 点击恢复", "#94A3B8", false,
    )
}
