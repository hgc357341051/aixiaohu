package com.aixiaohuadb.ui.mapping

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.aixiaohuadb.exec.CommandLibrary
import com.aixiaohuadb.exec.ExecStep

class MappingViewModel : ViewModel() {
    var tab by mutableIntStateOf(0)
        private set

    var overrides by mutableStateOf(mapOf<String, Pair<Int, Int>>())
        private set

    val expandedIds = mutableSetOf<Int>()
    var expandedRevision by mutableIntStateOf(0)
        private set

    fun selectTab(index: Int) { tab = index }

    fun toggleExpanded(id: Int) {
        if (!expandedIds.add(id)) expandedIds.remove(id)
        expandedRevision++
    }

    fun coordinateOf(key: String): Pair<Int, Int> =
        overrides[key] ?: CommandLibrary.coordinateByKey[key]?.let { it.x to it.y } ?: (0 to 0)

    fun updateCoordinate(key: String, x: Int, y: Int) {
        overrides = overrides + (key to (x.coerceIn(0, 1080) to y.coerceIn(0, 2340)))
    }

    /** 生成强类型输入命令示意文本；端内不拼接、不执行 shell 命令。 */
    fun previewCode(): String {
        val sb = StringBuilder("# 指令: 换名刀  CMD_HUAN_MINGDAO\n# 基准 1080x2340，运行时按当前分辨率换算\n")
        CommandLibrary.commandActions.first().steps.forEach { step ->
            when (step) {
                is ExecStep.Tap -> {
                    val (x, y) = coordinateOf(step.key)
                    sb.append("Tap(x=$x, y=$y)   # ${step.key}\n")
                }
                is ExecStep.Wait -> sb.append("Wait(ms=${step.ms})   # 等待界面稳定\n")
                is ExecStep.Verify -> sb.append("# verify ${step.key} -> FAIL: STOP, retry <= 1\n")
            }
        }
        return sb.toString()
    }
}
