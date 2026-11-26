package com.example.voicectl.inference.api

/** GPU → NNAPI → CPU 逐档尝试，任一档构造失败自动降级 */
class DelegateChain(private val preferred: Delegate) {
    fun candidates(): List<Delegate> = when (preferred) {
        Delegate.GPU, Delegate.AUTO -> listOf(Delegate.GPU, Delegate.NNAPI, Delegate.CPU)
        Delegate.NNAPI -> listOf(Delegate.NNAPI, Delegate.CPU)
        Delegate.CPU -> listOf(Delegate.CPU)
    }

    /** 用户手动选择 CPU 时不尝试其他档位 */
    fun isStrict(): Boolean = preferred == Delegate.CPU
}
