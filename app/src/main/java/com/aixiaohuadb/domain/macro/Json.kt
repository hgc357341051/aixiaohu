package com.aixiaohuadb.domain.macro

/**
 * 极简 JSON 实现。
 *
 * 不依赖 Android 的 org.json：后者在 JVM 单测里是 stub，无法真正解析；
 * 引入第三方库又会让离线构建失败。这里只覆盖宏文档需要的子集，
 * 并拒绝尾随内容、未闭合字符串等非法输入，避免"解析到一半当成功"。
 */
internal sealed interface JsonValue {
    data class Obj(val fields: Map<String, JsonValue>) : JsonValue {
        operator fun get(key: String): JsonValue? = fields[key]
    }

    data class Arr(val items: List<JsonValue>) : JsonValue {
        val size: Int get() = items.size
        operator fun get(index: Int): JsonValue = items[index]
    }

    data class Str(val value: String) : JsonValue
    data class Num(val value: Double) : JsonValue
    data class Bool(val value: Boolean) : JsonValue
    data object Null : JsonValue
}

internal class JsonParseException(message: String) : Exception(message)

internal object Json {
    fun parse(text: String): JsonValue {
        val parser = Parser(text)
        parser.skipWhitespace()
        val value = parser.readValue()
        parser.skipWhitespace()
        if (!parser.atEnd()) throw JsonParseException("JSON 尾部存在多余内容（位置 ${parser.position}）")
        return value
    }

    fun write(value: JsonValue): String = StringBuilder().also { writeTo(it, value) }.toString()

    private fun writeTo(sb: StringBuilder, value: JsonValue) {
        when (value) {
            is JsonValue.Obj -> {
                sb.append('{')
                var first = true
                value.fields.forEach { (key, item) ->
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, key)
                    sb.append(':')
                    writeTo(sb, item)
                }
                sb.append('}')
            }

            is JsonValue.Arr -> {
                sb.append('[')
                value.items.forEachIndexed { index, item ->
                    if (index > 0) sb.append(',')
                    writeTo(sb, item)
                }
                sb.append(']')
            }

            is JsonValue.Str -> writeString(sb, value.value)
            is JsonValue.Num -> {
                val number = value.value
                if (number.isFinite() && number == number.toLong().toDouble()) {
                    sb.append(number.toLong())
                } else {
                    sb.append(number)
                }
            }

            is JsonValue.Bool -> sb.append(if (value.value) "true" else "false")
            JsonValue.Null -> sb.append("null")
        }
    }

    private fun writeString(sb: StringBuilder, text: String) {
        sb.append('"')
        text.forEach { ch ->
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch < ' ') sb.append("\\u").append(ch.code.toString(16).padStart(4, '0')) else sb.append(ch)
            }
        }
        sb.append('"')
    }

    private class Parser(private val text: String) {
        var position: Int = 0
            private set

        fun atEnd(): Boolean = position >= text.length

        fun skipWhitespace() {
            while (position < text.length && text[position].isWhitespace()) position++
        }

        fun readValue(): JsonValue {
            if (atEnd()) throw JsonParseException("JSON 意外结束")
            return when (val ch = text[position]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> JsonValue.Str(readString())
                't' -> readLiteral("true", JsonValue.Bool(true))
                'f' -> readLiteral("false", JsonValue.Bool(false))
                'n' -> readLiteral("null", JsonValue.Null)
                else -> if (ch == '-' || ch.isDigit()) readNumber() else {
                    throw JsonParseException("位置 $position 出现非法字符 '$ch'")
                }
            }
        }

        private fun readObject(): JsonValue.Obj {
            expect('{')
            val fields = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (peek() == '}') {
                position++
                return JsonValue.Obj(fields)
            }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                fields[key] = readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> position++
                    '}' -> {
                        position++
                        return JsonValue.Obj(fields)
                    }

                    else -> throw JsonParseException("位置 $position 期望 ',' 或 '}'")
                }
            }
        }

        private fun readArray(): JsonValue.Arr {
            expect('[')
            val items = ArrayList<JsonValue>()
            skipWhitespace()
            if (peek() == ']') {
                position++
                return JsonValue.Arr(items)
            }
            while (true) {
                skipWhitespace()
                items += readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> position++
                    ']' -> {
                        position++
                        return JsonValue.Arr(items)
                    }

                    else -> throw JsonParseException("位置 $position 期望 ',' 或 ']'")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw JsonParseException("字符串未闭合")
                when (val ch = text[position++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (atEnd()) throw JsonParseException("转义符后意外结束")
                        when (val escape = text[position++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (position + 4 > text.length) throw JsonParseException("\\u 转义不足 4 位")
                                val hex = text.substring(position, position + 4)
                                val code = hex.toIntOrNull(16)
                                    ?: throw JsonParseException("\\u 转义非法：$hex")
                                sb.append(code.toChar())
                                position += 4
                            }

                            else -> throw JsonParseException("未知转义 \\$escape")
                        }
                    }

                    else -> {
                        if (ch < ' ') throw JsonParseException("字符串中存在未转义控制字符")
                        sb.append(ch)
                    }
                }
            }
        }

        private fun readNumber(): JsonValue.Num {
            val start = position
            if (peek() == '-') position++
            while (!atEnd() && (text[position].isDigit() || text[position] in ".eE+-")) position++
            val raw = text.substring(start, position)
            val value = raw.toDoubleOrNull() ?: throw JsonParseException("数字非法：$raw")
            if (!value.isFinite()) throw JsonParseException("数字越界：$raw")
            return JsonValue.Num(value)
        }

        private fun readLiteral(literal: String, value: JsonValue): JsonValue {
            if (!text.startsWith(literal, position)) {
                throw JsonParseException("位置 $position 期望 $literal")
            }
            position += literal.length
            return value
        }

        private fun expect(ch: Char) {
            if (atEnd() || text[position] != ch) {
                throw JsonParseException("位置 $position 期望 '$ch'")
            }
            position++
        }

        private fun peek(): Char = if (atEnd()) '\u0000' else text[position]
    }
}
