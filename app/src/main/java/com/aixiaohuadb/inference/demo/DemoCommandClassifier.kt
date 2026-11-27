package com.aixiaohuadb.inference.demo

import com.aixiaohuadb.inference.api.ActionLabel
import com.aixiaohuadb.inference.api.CommandClassifier
import com.aixiaohuadb.inference.api.CommandResult
import com.aixiaohuadb.inference.api.Delegate
import com.aixiaohuadb.inference.api.EngineInfo
import com.aixiaohuadb.inference.api.ObjectLabel

/** 演示级指令分类器：默认拒识；forcedCommandId 可指定 0–13 任一指令 */
class DemoCommandClassifier : CommandClassifier {
    private var currentInfo = EngineInfo("DemoCommand", "1.0.0-demo", Delegate.CPU)
    private var loaded = false
    var forcedCommandId: Int? = null
    var forcedScore: Float? = null

    override val info: EngineInfo get() = currentInfo
    override val isLoaded: Boolean get() = loaded

    override fun load(delegate: Delegate): Result<Unit> {
        loaded = true
        currentInfo = currentInfo.copy(
            activeDelegate = if (delegate == Delegate.AUTO) Delegate.CPU else delegate,
        )
        return Result.success(Unit)
    }

    override fun unload() {
        loaded = false
        forcedCommandId = null
        forcedScore = null
    }

    override fun setDelegate(delegate: Delegate) {
        currentInfo = currentInfo.copy(
            activeDelegate = if (delegate == Delegate.AUTO) Delegate.CPU else delegate,
        )
    }

    override fun classify(features: FloatArray): CommandResult {
        val id = forcedCommandId ?: 14
        val score = forcedScore ?: if (id == 14) {
            DemoSupport.pseudoScore(features, 303, 0.02f, 0.32f)
        } else {
            DemoSupport.pseudoScore(features, 304, 0.82f, 0.97f)
        }
        val action = when (id) {
            13 -> ActionLabel.A1_SWITCH
            14 -> ActionLabel.A2_NONE
            else -> ActionLabel.A0_SWAP
        }
        val obj = when (id) {
            13 -> ObjectLabel.P6_POSITION
            14 -> ObjectLabel.OTHER
            in 0..6 -> ObjectLabel.entries[id]
            in 7..12 -> ObjectLabel.entries[ObjectLabel.P0.ordinal + id - 7]
            else -> ObjectLabel.OTHER
        }
        return CommandResult(action, obj, id, score)
    }
}
