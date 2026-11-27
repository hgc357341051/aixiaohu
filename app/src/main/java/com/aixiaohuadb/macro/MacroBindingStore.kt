package com.aixiaohuadb.macro

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aixiaohuadb.config.LabelBook
import com.aixiaohuadb.inference.api.CommandResult
import com.aixiaohuadb.service.MacroConfig
import com.aixiaohuadb.service.OverlayStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val Context.macroBindingDataStore: DataStore<Preferences> by preferencesDataStore(name = "macro_bindings")

/** 语音指令槽位 0..13 → 用户宏绑定；14 是推理层拒识槽，不可绑定也永不执行 */
data class VoiceMacroBinding(
    val slot: Int,
    val macroId: Long,
    val phrase: String,
    val enabled: Boolean = true,
) {
    companion object {
        const val MIN_SLOT = 0
        /** 与 LabelBook.isReject(14) 保持一致：14 保留为拒识槽 */
        const val MAX_SLOT = 13
        fun isBindable(slot: Int): Boolean = slot in MIN_SLOT..MAX_SLOT && !LabelBook.isReject(slot)
    }
}

/** 纯 Kotlin 编解码，便于单测；格式 "slot,macroId,phrase,enabled"，记录间 ';' */
object MacroBindingCodec {
    fun sanitizePhrase(raw: String): String = raw
        .replace(';', ' ')
        .replace(',', ' ')
        .replace('\n', ' ')
        .trim()
        .ifEmpty { "宏指令" }

    /** 拒识槽等非可绑定槽位直接丢弃，不能 coerce 成 13 造成“串槽” */
    fun encode(list: List<VoiceMacroBinding>): String = list
        .filter { VoiceMacroBinding.isBindable(it.slot) }
        .joinToString(";") { b ->
            listOf(
                b.slot.toString(),
            b.macroId.toString(),
            sanitizePhrase(b.phrase),
            if (b.enabled) "1" else "0",
        ).joinToString(",")
    }

    fun decode(raw: String): List<VoiceMacroBinding> = raw.split(";").mapNotNull { rec ->
        val p = rec.split(",")
        if (p.size != 4) return@mapNotNull null
        runCatching {
            val slot = p[0].toInt()
            VoiceMacroBinding(
                slot = slot,
                macroId = p[1].toLong(),
                phrase = p[2],
                enabled = p[3] == "1",
            ).takeIf { VoiceMacroBinding.isBindable(it.slot) }
        }.getOrNull()
    }
}

/**
 * slot → macro 绑定唯一数据源。commandId 退化为 slot，不再指向任何内置固定指令。
 */
class MacroBindingStore(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val key = stringPreferencesKey("bindings_v1")

    val bindings: StateFlow<List<VoiceMacroBinding>> =
        appContext.macroBindingDataStore.data
            .map { prefs -> prefs[key]?.let(MacroBindingCodec::decode).orEmpty() }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    suspend fun bind(slot: Int, macroId: Long, phrase: String) {
        require(VoiceMacroBinding.isBindable(slot)) { "槽位需在 0..13，14 为拒识槽不可绑定" }
        appContext.macroBindingDataStore.edit { prefs ->
            val current = prefs[key]?.let(MacroBindingCodec::decode).orEmpty()
                .filterNot { it.slot == slot }
            prefs[key] = MacroBindingCodec.encode(
                current + VoiceMacroBinding(slot, macroId, MacroBindingCodec.sanitizePhrase(phrase)),
            )
        }
    }

    suspend fun unbind(slot: Int) {
        appContext.macroBindingDataStore.edit { prefs ->
            prefs[key] = MacroBindingCodec.encode(
                prefs[key]?.let(MacroBindingCodec::decode).orEmpty().filterNot { it.slot == slot },
            )
        }
    }

    suspend fun macroIdFor(slot: Int): Long? = bindingForSlot(slot)?.macroId

    suspend fun phraseFor(slot: Int): String? = bindingForSlot(slot)?.phrase

    private suspend fun bindingForSlot(slot: Int): VoiceMacroBinding? =
        appContext.macroBindingDataStore.data.first()
            .let { it[key]?.let(MacroBindingCodec::decode).orEmpty() }
            .firstOrNull { it.slot == slot && it.enabled }

    /** 同步查询当前内存绑定，供识别帧热路径使用 */
    fun bindingFor(command: CommandResult): VoiceMacroBinding? =
        if (!VoiceMacroBinding.isBindable(command.commandId)) null
        else bindings.value.firstOrNull { it.slot == command.commandId && it.enabled }

    fun resolve(command: CommandResult): MacroConfig? = resolve(command, bindings.value, OverlayStore.macros.value)

    companion object {
        /** 纯函数解析：拒识槽、binding 不存在、已禁用或宏已删除一律不产生 CommandDetected */
        fun resolve(
            command: CommandResult,
            bindings: List<VoiceMacroBinding>,
            macros: List<MacroConfig>,
        ): MacroConfig? {
            if (!VoiceMacroBinding.isBindable(command.commandId)) return null
            val binding = bindings.firstOrNull { it.slot == command.commandId && it.enabled } ?: return null
            return macros.firstOrNull { it.macroId == binding.macroId }
        }
    }
}

/** ViewModel 侧只依赖这个只读接口 */
fun interface MacroBindingResolver {
    fun phraseFor(slot: Int): String?
}

/** 避免 ViewModel 持有 Context：用内存绑定表构造只读解析器 */
fun bindingResolver(bindings: List<VoiceMacroBinding>): MacroBindingResolver =
    MacroBindingResolver { slot -> bindings.firstOrNull { it.slot == slot && it.enabled }?.phrase }
