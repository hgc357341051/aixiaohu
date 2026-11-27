package com.aixiaohuadb.ui.mapping

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.aixiaohuadb.exec.ExecStep

/** 指令映射页只展示用户宏的步骤与基准坐标，不再维护内置坐标库 */
class MappingViewModel : ViewModel() {
    var tab by androidx.compose.runtime.mutableIntStateOf(0)
        private set

    fun selectTab(index: Int) { tab = index }

    fun previewCode(steps: List<ExecStep>): String {
        val sb = StringBuilder("# 宏步骤 · 基准 1080x2340，运行时按当前分辨率换算\n")
        steps.forEach { step ->
            @Suppress("DEPRECATION")
            when (step) {
                is ExecStep.Tap -> sb.append("Tap(x=${step.x}, y=${step.y}, delayAfterMs=${step.delayAfterMs})\n")
                is ExecStep.Swipe -> sb.append(
                    "Swipe((${step.fromX},${step.fromY})→(${step.toX},${step.toY}), " +
                        "${step.durationMs}ms, delayAfterMs=${step.delayAfterMs})\n",
                )
                is ExecStep.MultiGesture -> sb.append(
                    "MultiGesture(${step.pointers.size}指, ${step.durationMs}ms, " +
                        "delayAfterMs=${step.delayAfterMs})\n",
                )
                is ExecStep.Wait -> sb.append("Wait(ms=${step.ms})  # 旧数据兼容\n")
                is ExecStep.Verify -> sb.append("# verify ${step.key}\n")
            }
        }
        return sb.toString()
    }
}
