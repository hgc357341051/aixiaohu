package com.aixiaohuadb.service

/**
 * 控制模式：决定指令由谁触发。
 *
 * VOICE         语音识别模式：需先在设置中显式开启语音，才允许启动麦克风/模型/常驻通知。
 * ACCESSIBILITY 无障碍模式（默认）：完全不拾音，A11y 手势为主执行链路，停 ADB 后台服务。
 * ADB           命令模式：不拾音，保留 A11y（面板显隐依赖其事件），启动 ADB 前台长连接。
 */
enum class ControlMode {
    VOICE,
    ACCESSIBILITY,
    ADB,
    ;

    val label: String
        get() = when (this) {
            VOICE -> "语音识别"
            ACCESSIBILITY -> "无障碍模式"
            ADB -> "ADB 命令模式"
        }

    val description: String
        get() = when (this) {
            VOICE -> "需开启语音开关；唤醒后下达指令，A11y 主执行 + ADB 可选备用"
            ACCESSIBILITY -> "不拾音，点击悬浮宏按钮由无障碍手势执行"
            ADB -> "不拾音，保留 A11y 事件，ADB 后台长连接随时下发命令"
        }

    companion object {
        /** 默认手动、非语音。val 而非 const：Kotlin 不允许 const 引用枚举实例。 */
        val DEFAULT: ControlMode = ACCESSIBILITY

        /**
         * DataStore 字符串还原。旧 MANUAL/manual 与非法值一律回退 ACCESSIBILITY，
         * 绝不回退到 VOICE（冷启动不得意外拾音）。
         */
        fun fromKey(key: String?): ControlMode = when (key) {
            "VOICE" -> VOICE
            "ADB" -> ADB
            "ACCESSIBILITY", "MANUAL", "manual", null -> ACCESSIBILITY
            else -> ACCESSIBILITY
        }
    }
}
