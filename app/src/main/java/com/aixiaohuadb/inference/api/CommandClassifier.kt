package com.aixiaohuadb.inference.api

enum class ActionLabel { A0_SWAP, A1_SWITCH, A2_NONE }

/** 顺序固定：E0–E6 装备、P0–P5 号位、P6 泛化位置、OTHER 拒识 */
enum class ObjectLabel {
    E0_MINGDAO, E1_FUHUO, E2_HUIYUE, E3_XIXUE_SHU, E4_XIXUE_DAO,
    E5_BINGJIA, E6_XUEMO, P0, P1, P2, P3, P4, P5, P6_POSITION, OTHER,
}

data class CommandResult(
    val action: ActionLabel,
    val obj: ObjectLabel,
    val commandId: Int,
    val score: Float,
)

/** ACTION × OBJECT 双头指令分类；控制头优先于本接口调用 */
interface CommandClassifier : InferenceEngine {
    fun classify(features: FloatArray): CommandResult
}
