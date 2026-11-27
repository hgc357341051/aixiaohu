package com.aixiaohuadb.domain

import com.aixiaohuadb.exec.CommandLibrary
import com.aixiaohuadb.exec.ExecutorChain
import com.aixiaohuadb.exec.ExecResult

/** 把指令 ID 解析为步骤序列后交给执行链；面板与语音路径共用 */
class ExecuteCommandUseCase(private val executor: ExecutorChain) {
    suspend fun execute(commandId: Int): ExecResult =
        executor.execute(CommandLibrary.actionFor(commandId))
}
