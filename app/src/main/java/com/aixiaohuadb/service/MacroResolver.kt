package com.aixiaohuadb.service

import com.aixiaohuadb.exec.CommandAction

/**
 * 宏 → 执行动作：直接构造 CommandAction，不存在固定指令的 fallback，
 * id = 1000 + macroId 与历史预置 0~14 完全隔离。
 */
object MacroResolver {
    fun buildCommandAction(macro: MacroConfig): CommandAction =
        CommandAction(
            commandId = (MacroConfig.MACRO_ID_BASE + macro.macroId).toInt(),
            name = macro.name,
            steps = macro.steps,
        )
}
