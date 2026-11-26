package com.example.voicectl.config

import com.example.voicectl.inference.api.ActionLabel
import com.example.voicectl.inference.api.ControlWord
import com.example.voicectl.inference.api.ObjectLabel

/** §1 词表与标签：6 唤醒标签 + 15 指令标签 + ACTION×OBJECT + C0–C4 控制头 */
object LabelBook {
    val wakeLabels: List<String> = listOf(
        "WK_WAKE_NIHAO_XIAOHU",
        "WK_WAKE_XIAOHU_XIAOHU",
        "WK_NEG_SILENCE",
        "WK_NEG_GAME_AUDIO",
        "WK_NEG_HUMAN_SPEECH",
        "WK_NEG_HARD_NEAR",
    )

    val wakeVariants: Map<String, List<String>> = mapOf(
        "WK_WAKE_NIHAO_XIAOHU" to listOf("你好小胡", "李好小胡"),
        "WK_WAKE_XIAOHU_XIAOHU" to listOf("小胡小胡"),
    )

    val commandLabels: Map<Int, String> = mapOf(
        0 to "换名刀",
        1 to "换复活甲",
        2 to "换辉月",
        3 to "换吸血书",
        4 to "换吸血刀",
        5 to "换冰甲",
        6 to "换血魔",
        7 to "换1号位",
        8 to "换2号位",
        9 to "换3号位",
        10 to "换4号位",
        11 to "换5号位",
        12 to "换6号位",
        13 to "切换位置",
        14 to "CMD_OTHER_UNKNOWN",
    )

    val actionLabels: List<String> = listOf("A0_SWAP", "A1_SWITCH", "A2_NONE")
    val objectLabels: List<String> = listOf(
        "E0_MINGDAO", "E1_FUHUO", "E2_HUIYUE", "E3_XIXUE_SHU", "E4_XIXUE_DAO",
        "E5_BINGJIA", "E6_XUEMO", "P0", "P1", "P2", "P3", "P4", "P5",
        "P6_POSITION", "OTHER",
    )

    val controlLabels: Map<ControlWord, String> = mapOf(
        ControlWord.C0_NONE to "CTRL_NONE",
        ControlWord.C1_EXIT_WAKE to "CTRL_EXIT_WAKE",
        ControlWord.C2_EXIT_RECOG to "CTRL_EXIT_RECOG",
        ControlWord.C3_PAUSE to "CTRL_PAUSE",
        ControlWord.C4_RESUME to "CTRL_RESUME",
    )

    /** 控制词标准说法，供调试页与说明页展示 */
    val controlPhrases: Map<ControlWord, List<String>> = mapOf(
        ControlWord.C0_NONE to emptyList(),
        ControlWord.C1_EXIT_WAKE to listOf("退出唤醒", "关闭唤醒", "退出语音控制", "别唤醒了"),
        ControlWord.C2_EXIT_RECOG to listOf("退出识别", "停止识别", "不听了"),
        ControlWord.C3_PAUSE to listOf("暂停语音", "暂停识别", "停一下"),
        ControlWord.C4_RESUME to listOf("恢复语音", "继续识别", "恢复识别"),
    )

    /** ACTION × OBJECT → 指令 ID；无效组合返回 14（拒识） */
    fun commandIdOf(action: ActionLabel, obj: ObjectLabel): Int = when {
        action == ActionLabel.A0_SWAP && obj.ordinal <= ObjectLabel.E6_XUEMO.ordinal -> obj.ordinal
        action == ActionLabel.A0_SWAP && obj.ordinal in ObjectLabel.P0.ordinal..ObjectLabel.P5.ordinal ->
            7 + (obj.ordinal - ObjectLabel.P0.ordinal)
        action == ActionLabel.A1_SWITCH && obj == ObjectLabel.P6_POSITION -> 13
        else -> 14
    }

    fun labelOf(commandId: Int): String = commandLabels[commandId] ?: "CMD_OTHER_UNKNOWN"

    fun isReject(commandId: Int): Boolean = commandId == 14 || commandId !in commandLabels

    fun controlLabelOf(word: ControlWord): String = controlLabels[word] ?: "CTRL_NONE"
}
