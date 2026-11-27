package com.aixiaohuadb.domain

import com.aixiaohuadb.exec.CommandAction
import com.aixiaohuadb.exec.ExecutorChain
import com.aixiaohuadb.exec.ExecResult

/** 执行入口只接受已经解析好的用户宏动作，不再认识任何内置固定指令 */
class ExecuteCommandUseCase(private val executor: ExecutorChain) {
    suspend fun execute(action: CommandAction): ExecResult = executor.execute(action)
}
