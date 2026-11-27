package com.aixiaohuadb.service

/**
 * 控制模式：决定指令由谁触发。
 *
 * VOICE  语音识别模式：唤醒词 + 声纹 + 指令识别，语音链路常驻。
 * MANUAL 手动点触模式：停止拾音并卸载模型（无常驻拾音），仅靠悬浮指令/宏按钮点击执行。
 */
enum class ControlMode {
    VOICE,
    MANUAL,
    ;

    val label: String
        get() = when (this) {
            VOICE -> "语音识别"
            MANUAL -> "手动点触"
        }

    val description: String
        get() = when (this) {
            VOICE -> "说「你好小胡」唤醒后下达指令"
            MANUAL -> "不拾音，点击悬浮指令按钮执行"
        }

    companion object {
        /** DataStore 字符串还原，非法值回退默认语音模式 */
        fun fromKey(key: String?): ControlMode = entries.firstOrNull { it.name == key } ?: VOICE
    }
}
