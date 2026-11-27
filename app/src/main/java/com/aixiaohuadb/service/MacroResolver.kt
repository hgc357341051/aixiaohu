package com.aixiaohuadb.service

import com.aixiaohuadb.exec.CommandAction

/**
 * 宏 → 执行动作：不经过 CommandLibrary.actionFor（其 fallback 会把未知 id 映射为"未识别"），
 * 直接构造 CommandAction，id = 1000 + macroId 与预置 0~14 完全隔离。
 */
object MacroResolver {
    fun buildCommandAction(macro: MacroConfig): CommandAction =
        CommandAction(
            commandId = (MacroConfig.MACRO_ID_BASE + macro.macroId).toInt(),
            name = macro.name,
            steps = macro.steps,
        )
}
