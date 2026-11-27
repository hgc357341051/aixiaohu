package com.aixiaohuadb.adb

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AdbAuditEntry(
    val time: String,
    val command: String,
    val source: String,
    val decision: String,
    val ok: Boolean,
    val detail: String,
)

/** raw shell 审计：单行 JSON 追加，超 1000 行或 512KB 轮转；预写成功才允许执行 */
object AdbAuditLog {
    private const val MAX_LINES = 1000
    private const val MAX_BYTES = 512L * 1024L
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

    private fun logFile(context: Context) = File(context.filesDir, "adb_audit.log")

    /** 进程内缓存行数，避免每次 append 都全量 readLines */
    @Volatile
    private var cachedLineCount = -1

    @Synchronized
    private fun lineCount(file: File): Int {
        if (!file.exists()) return 0.also { cachedLineCount = it }
        if (cachedLineCount >= 0) return cachedLineCount
        return runCatching { file.useLines { lines -> lines.count() } }
            .getOrDefault(0).also { cachedLineCount = it }
    }

    /**
     * 执行前预写 pending 记录，写失败必须 fail-closed（调用方拒绝执行命令）。
     */
    @Synchronized
    fun prewrite(context: Context, req: RawShellRequest, verdict: RawShellPolicy.Verdict): Boolean = runCatching {
        val file = logFile(context)
        val entry = AdbAuditEntry(
            time = timeFormat.format(Date()),
            command = req.command,
            source = req.source.name,
            decision = "PENDING_${verdict.decision.name}",
            ok = false,
            detail = "执行前预写：${verdict.reason}",
        )
        appendLine(file, entry.toJsonLine())
        true
    }.getOrDefault(false)

    /** 执行后补写结果；DENY 分支直接调用本方法落一条终态记录 */
    @Synchronized
    fun append(context: Context, req: RawShellRequest, verdict: RawShellPolicy.Verdict, result: CommandResult) {
        runCatching {
            val entry = AdbAuditEntry(
                time = timeFormat.format(Date()),
                command = req.command,
                source = req.source.name,
                decision = verdict.decision.name,
                ok = result.ok,
                detail = result.message.take(160),
            )
            appendLine(logFile(context), entry.toJsonLine())
        }
    }

    private fun appendLine(file: File, line: String) {
        val count = lineCount(file)
        if (file.exists() && (file.length() >= MAX_BYTES || count >= MAX_LINES)) rotate(file)
        file.appendText(line + "\n")
        cachedLineCount = if (cachedLineCount >= 0) cachedLineCount + 1 else cachedLineCount
    }

    /** UI 查看最近 N 条（最多 100） */
    @Synchronized
    fun recent(context: Context, limit: Int = 100): List<AdbAuditEntry> {
        val file = logFile(context)
        if (!file.exists()) return emptyList()
        val lines = runCatching {
            file.readLines().asReversed().take(limit.coerceIn(0, 100))
        }.getOrDefault(emptyList())
        // 同时刷新缓存行数，避免遗漏进程外写入
        cachedLineCount = runCatching { file.useLines { it.count() } }.getOrDefault(lines.size)
        return lines.mapNotNull(::fromJsonLine)
    }

    private fun rotate(file: File) {
        // 只在轮转时读取一次，并按字节预算从尾部截断（日志单行长度不定）
        val all = runCatching { file.readLines() }.getOrDefault(emptyList())
        val kept = mutableListOf<String>()
        var bytes = 0L
        for (line in all.asReversed()) {
            val size = line.toByteArray().size + 1L
            if (kept.size >= MAX_LINES / 2 || bytes + size > MAX_BYTES / 2) break
            kept += line
            bytes += size
        }
        file.writeText(kept.asReversed().joinToString("\n") + "\n")
        cachedLineCount = kept.size
    }

    private fun AdbAuditEntry.toJsonLine(): String = buildString {
        append('{')
        append("\"time\":\"").append(escape(time)).append("\",")
        append("\"command\":\"").append(escape(command)).append("\",")
        append("\"source\":\"").append(escape(source)).append("\",")
        append("\"decision\":\"").append(escape(decision)).append("\",")
        append("\"ok\":").append(ok).append(',')
        append("\"detail\":\"").append(escape(detail)).append("\"")
        append('}')
    }

    private fun fromJsonLine(line: String): AdbAuditEntry? {
        fun field(name: String): String {
            val marker = "\"$name\":\""
            val start = line.indexOf(marker)
            if (start < 0) return ""
            val from = start + marker.length
            val end = line.indexOf('"', from)
            return if (end > from) unescape(line.substring(from, end)) else ""
        }
        return AdbAuditEntry(
            time = field("time"),
            command = field("command"),
            source = field("source"),
            decision = field("decision"),
            ok = line.contains("\"ok\":true"),
            detail = field("detail"),
        )
    }

    private fun escape(raw: String): String = raw
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")

    private fun unescape(raw: String): String = raw
        .replace("\\n", "\n")
        .replace("\\r", "\r")
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")
}
