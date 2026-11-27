package com.aixiaohuadb.adb

import java.util.Locale

/**
 * raw shell 风险拦截。
 *
 * 用户已选择“允许任意 shell cmd、风险自负”，这里不是白名单，而是**常见不可逆命令拦截（可能不全）**：
 * 先做 token 级解析（引号、反斜杠、换行、; && || | 与 > 重定向），再按 token 序列匹配危险动词+参数，
 * 修掉大小写/无空格/粘连与子串误杀。所有非拦截命令一律 ALLOW_WITH_CONFIRM，必须显式确认。
 */
object RawShellPolicy {
    enum class Decision { ALLOW, ALLOW_WITH_CONFIRM, DENY }

    data class Verdict(val decision: Decision, val reason: String = "")

    private sealed class Tok {
        data class Word(val text: String) : Tok()
        data class Op(val text: String) : Tok()
    }

    private val SEPARATORS = setOf(";", "&&", "||", "|", "&", "\n")

    private val DANGEROUS_VERBS = setOf(
        "reboot", "shutdown", "halt", "poweroff", "wipe", "setenforce", "su",
        "mkfs", "mkfs.ext4", "mkfs.vfat", "fastboot", "recovery", "format",
        "destroy", "master_clear", "--wipe_data",
    )
    private val PM_DANGEROUS = setOf("uninstall", "clear", "reset-permissions", "disable", "enable")
    private val REDIRECT_DANGEROUS = setOf("/proc/sysrq-trigger")
    private val REDIRECT_PREFIXES = listOf("/dev/block/", "/sys/kernel", "/sys/class", "/system/", "/data/")

    /** 引号/反斜杠/换行/分号等全部在 token 化阶段归一，避免各种粘连绕过 */
    private fun tokenize(command: String): List<Tok> {
        val out = mutableListOf<Tok>()
        val word = StringBuilder()
        var quote: Char? = null
        var escaped = false

        fun flush() {
            if (word.isNotEmpty()) {
                out += Tok.Word(word.toString().lowercase(Locale.ROOT))
                word.setLength(0)
            }
        }

        var i = 0
        while (i < command.length) {
            val c = command[i]
            when {
                escaped -> { word.append(c); escaped = false }
                c == '\\' -> escaped = true
                // 进程替换 <(...) 与命令替换 $(...) 在引号内同样生效，必须在 token 阶段识别
                c == '$' && i + 1 < command.length && command[i + 1] == '(' -> {
                    flush()
                    out += Tok.Op("\$(")
                    i++
                }
                c == '<' && i + 1 < command.length && command[i + 1] == '(' -> {
                    flush()
                    out += Tok.Op("<(")
                    i++
                }
                quote != null -> {
                    if (c == quote) quote = null else word.append(c)
                }
                c == '\'' || c == '"' -> quote = c
                c.isWhitespace() -> flush()
                c == ';' || c == '&' || c == '|' || c == '>' || c == '<' -> {
                    flush()
                    val op = when (c) {
                        '>' -> if (i + 1 < command.length && command[i + 1] == '>') { i++; ">>" } else ">"
                        '&' -> if (i + 1 < command.length && command[i + 1] == '&') { i++; "&&" } else "&"
                        '|' -> if (i + 1 < command.length && command[i + 1] == '|') { i++; "||" } else "|"
                        else -> c.toString()
                    }
                    out += Tok.Op(op)
                }
                else -> word.append(c)
            }
            i++
        }
        flush()
        return out
    }

    private fun isRecursiveFlag(word: String): Boolean {
        if (!word.startsWith("-")) return false
        if (word.startsWith("--")) return word == "--recursive"
        return word.length > 1 && word.substring(1).any { it == 'r' || it == 'r'.uppercaseChar() }
    }

    private fun isDangerousRmTarget(word: String): Boolean {
        if (word.startsWith("-")) return false
        return word == "/" || word == "//" || word == "/*" || word == "/." ||
            word == "~" || word == "\$home" ||
            listOf("/system", "/data", "/sdcard", "/storage/emulated").any {
                word == it || word.startsWith("$it/")
            }
    }

    /** 把 token 序列按命令分隔符切开，管道右侧也是独立命令 */
    private fun segments(tokens: List<Tok>): List<List<Tok>> {
        val result = mutableListOf<List<Tok>>()
        var current = mutableListOf<Tok>()
        tokens.forEach { tok ->
            if (tok is Tok.Op && tok.text in SEPARATORS) {
                if (current.isNotEmpty()) result += current
                current = mutableListOf()
            } else {
                current += tok
            }
        }
        if (current.isNotEmpty()) result += current
        return result
    }

    private fun isDangerous(segment: List<Tok>): String? {
        val words = segment.filterIsInstance<Tok.Word>().map { it.text }
        val verb = words.firstOrNull() ?: return null

        // rm 递归 + 危险根路径：覆盖 -fr / -r -f / rm -rf\n/ 等变形
        if (verb == "rm" && words.any(::isRecursiveFlag) && words.any(::isDangerousRmTarget)) {
            return "递归删除根目录/系统目录"
        }
        // "rm -rf/" 这类 flag 与根路径粘连的变形
        if (verb == "rm" && words.any {
                it.startsWith("-") && it.endsWith("/") && isRecursiveFlag(it.dropLast(1))
            }
        ) return "递归删除根目录（粘连写法）"
        if (verb in DANGEROUS_VERBS) return "危险命令：$verb"
        if (verb == "dd" && words.any { it.startsWith("if=") }) return "dd 原始写设备"
        if (verb == "pm" && words.getOrNull(1) in PM_DANGEROUS) return "pm ${words[1]} 不可逆"
        if (words.any {
                it.contains("master_clear") || it.contains("_wipe_data") ||
                    it.contains("sysrq-trigger")
            }
        ) return "恢复出厂/内核触发命令"

        // echo b > /proc/sysrq-trigger 一类重定向写系统节点
        val ops = segment.filterIsInstance<Tok.Op>()
        if (ops.any { it.text == ">" || it.text == ">>" }) {
            val badTarget = words.any { w ->
                w in REDIRECT_DANGEROUS || REDIRECT_PREFIXES.any(w::startsWith)
            }
            if (badTarget) return "重定向写系统/块设备"
        }
        return null
    }

    fun decide(req: RawShellRequest): Verdict {
        val command = req.command.trim()
        if (command.isBlank()) return Verdict(Decision.DENY, "命令为空")
        val tokens = tokenize(command)
        if (tokens.isEmpty()) return Verdict(Decision.DENY, "命令为空")
        if (tokens.any { it is Tok.Op && (it.text == "\$(" || it.text == "<(") }) {
            return Verdict(Decision.DENY, "进程替换/命令替换不保证安全")
        }
        segments(tokens).forEach { segment ->
            val reason = isDangerous(segment)
            if (reason != null) return Verdict(Decision.DENY, "常见不可逆命令拦截：$reason")
        }
        // DEBUG 来源不再无条件 ALLOW：所有来源都必须显式确认
        return Verdict(Decision.ALLOW_WITH_CONFIRM, "需用户确认；拦截规则可能不全，风险自负")
    }

    fun isAllowed(req: RawShellRequest): Boolean = decide(req).decision != Decision.DENY
}
