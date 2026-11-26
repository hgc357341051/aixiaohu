package com.example.voicectl.inference.tflite

import android.content.res.AssetManager
import com.example.voicectl.inference.api.ActionLabel
import com.example.voicectl.inference.api.CommandClassifier
import com.example.voicectl.inference.api.CommandResult
import com.example.voicectl.inference.api.Delegate
import com.example.voicectl.inference.api.EngineInfo
import com.example.voicectl.inference.api.ObjectLabel

/**
 * 指令模型接入骨架：输出 23 维 = ACTION(3) + OBJECT(14) + CONTROL(5) + REJECT(1)。
 * 训练侧占位顺序见 manifest.json；真实张量顺序确定后只需调整 divide 的区间。
 */
class TfliteCommandClassifier(
    assets: AssetManager,
    spec: ModelSpec,
) : CommandClassifier {
    private val engine = TfliteEngine(assets, "Command", spec)

    override val info: EngineInfo get() = engine.info
    override val isLoaded: Boolean get() = engine.isLoaded

    override fun load(delegate: Delegate): Result<Unit> = engine.load(delegate)
    override fun unload() = engine.unload()
    override fun setDelegate(delegate: Delegate) = engine.setDelegate(delegate)

    override fun classify(features: FloatArray): CommandResult {
        val output = engine.run(features)
        val actionScores = output.copyOfRange(0, minOf(3, output.size))
        val objectStart = 3
        val objectEnd = minOf(objectStart + 14, output.size)
        val objectScores = if (objectStart < objectEnd) output.copyOfRange(objectStart, objectEnd) else FloatArray(14)
        val actionIndex = argMax(actionScores)
        val objectIndex = argMax(objectScores)
        val action = ActionLabel.entries.getOrElse(actionIndex) { ActionLabel.A2_NONE }
        val obj = ObjectLabel.entries.getOrElse(objectIndex) { ObjectLabel.OTHER }
        val rejectScore = output.getOrElse(22) { 0f }
        val actionScore = actionScores.getOrElse(actionIndex) { 0f }
        val objectScore = objectScores.getOrElse(objectIndex) { 0f }
        val commandId = if (rejectScore > actionScore * objectScore) 14 else resolveId(action, obj)
        return CommandResult(action, obj, commandId, actionScore * objectScore)
    }

    private fun resolveId(action: ActionLabel, obj: ObjectLabel): Int = when {
        action == ActionLabel.A0_SWAP && obj.ordinal <= ObjectLabel.E6_XUEMO.ordinal -> obj.ordinal
        action == ActionLabel.A0_SWAP && obj.ordinal in ObjectLabel.P0.ordinal..ObjectLabel.P5.ordinal ->
            7 + (obj.ordinal - ObjectLabel.P0.ordinal)
        action == ActionLabel.A1_SWITCH && obj == ObjectLabel.P6_POSITION -> 13
        else -> 14
    }

    private fun argMax(values: FloatArray): Int {
        if (values.isEmpty()) return 0
        var best = 0
        for (i in 1 until values.size) if (values[i] > values[best]) best = i
        return best
    }
}
