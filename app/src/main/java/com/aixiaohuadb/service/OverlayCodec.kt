@file:Suppress("DEPRECATION")
package com.aixiaohuadb.service

import com.aixiaohuadb.adb.CommandValidator
import com.aixiaohuadb.exec.ExecStep
import com.aixiaohuadb.exec.GesturePointer
import com.aixiaohuadb.exec.clampDelayMs
import com.aixiaohuadb.exec.clampMultiTouchDurationMs
import java.util.Base64

/**
 * 统一悬浮配置编解码（纯 Kotlin，可单测）。
 * 记录分隔 ';'，字段分隔 '|'，label/steps 分隔 ':'；label 写入前 sanitize。
 * 宏 steps 段 v2：相邻步骤用 '~' 分隔，t{x},{y},{delay} / s{fx},{fy},{tx},{ty},{dur},{delay}；
 * v3 新增 g{base64url(payload)} 表示并发多指手势，内容与分隔字符全部 base64 后不再冲突。
 * w{ms} 与 v1 两字段 Tap 仅兼容读取。Verify 不持久化。
 * 兼容迁移：旧 QuickButtonCodec 格式 "id,size,alpha,fx,fy,on:label" → OverlayWidget(COMMAND)。
 */
object OverlayCodec {
    private const val SEP_ITEM = ";"
    private const val SEP_FIELD = "|"
    private const val SEP_LABEL = ":"
    private const val SEP_STEP = "~"
    private const val POS_SCALE = 10000
    private const val MAX_WAIT_MS = 60_000L

    // ---------------- label ----------------

    fun sanitizeLabel(raw: String): String = raw
        .replace(SEP_ITEM, "·")
        .replace(SEP_FIELD, "·")
        .replace(SEP_LABEL, "：")
        .replace(SEP_STEP, "·")
        .replace("\n", " ")
        .trim()
        .ifEmpty { "按钮" }

    // ---------------- widgets v2 ----------------

    fun encodeWidgets(list: List<OverlayWidget>): String = list.joinToString(SEP_ITEM) { w ->
        val kind = when (w.kind) {
            WidgetKind.COMMAND -> "C${w.commandId}"
            WidgetKind.MACRO -> "M${w.macroId}"
        }
        listOf(
            kind,
            clampSizeDp(w.sizeDp).toString(),
            clampAlpha(w.alphaPercent).toString(),
            (clampPos(w.fx) * POS_SCALE).toInt().toString(),
            (clampPos(w.fy) * POS_SCALE).toInt().toString(),
            if (w.enabled) "1" else "0",
            if (w.locked) "1" else "0",
        ).joinToString(SEP_FIELD) + SEP_LABEL + sanitizeLabel(w.label)
    }

    fun decodeWidgets(raw: String): List<OverlayWidget> = raw.split(SEP_ITEM).mapNotNull { rec ->
        val idx = rec.indexOf(SEP_LABEL)
        if (idx <= 0) return@mapNotNull null
        val head = rec.substring(0, idx).split(SEP_FIELD)
        if (head.size != 7) return@mapNotNull null
        runCatching {
            val kindToken = head[0]
            val kind = if (kindToken.startsWith("C")) WidgetKind.COMMAND else WidgetKind.MACRO
            val numeric = kindToken.substring(1)
            OverlayWidget(
                id = if (kind == WidgetKind.COMMAND) "cmd:$numeric" else "macro:$numeric",
                kind = kind,
                commandId = if (kind == WidgetKind.COMMAND) numeric.toInt() else -1,
                macroId = if (kind == WidgetKind.MACRO) numeric.toLong() else -1,
                label = rec.substring(idx + 1).ifEmpty { "按钮" },
                sizeDp = clampSizeDp(head[1].toInt()),
                alphaPercent = clampAlpha(head[2].toInt()),
                fx = clampPos(head[3].toInt() / POS_SCALE.toFloat()),
                fy = clampPos(head[4].toInt() / POS_SCALE.toFloat()),
                enabled = head[5] == "1",
                locked = head[6] == "1",
            )
        }.getOrNull()
    }

    // ---------------- ball v1 ----------------

    fun encodeBall(c: BallConfig): String = listOf(
        clampSizeDp(c.sizeDp).toString(),
        clampAlpha(c.alphaPercent).toString(),
        (clampPos(c.fx) * POS_SCALE).toInt().toString(),
        (clampPos(c.fy) * POS_SCALE).toInt().toString(),
        if (c.visible) "1" else "0",
        if (c.locked) "1" else "0",
    ).joinToString(SEP_FIELD)

    fun decodeBall(raw: String): BallConfig? {
        val head = raw.split(SEP_FIELD)
        if (head.size != 6) return null
        return runCatching {
            BallConfig(
                sizeDp = clampSizeDp(head[0].toInt()),
                alphaPercent = clampAlpha(head[1].toInt()),
                fx = clampPos(head[2].toInt() / POS_SCALE.toFloat()),
                fy = clampPos(head[3].toInt() / POS_SCALE.toFloat()),
                visible = head[4] == "1",
                locked = head[5] == "1",
            )
        }.getOrNull()
    }

    // ---------------- macros v2 ----------------
    //
    // 步格式：t{x},{y},{delay} / s{fx},{fy},{tx},{ty},{dur},{delay} / w{ms}（旧 Wait）
    // v1 的 "x,y" 两字段 Tap 仍可解码；Verify 不持久化。

    fun encodeMacros(list: List<MacroConfig>): String = list.joinToString(SEP_ITEM) { m ->
        val steps = m.steps.joinToString(SEP_STEP) { s ->
            when (s) {
                is ExecStep.Tap -> "t${s.x},${s.y},${clampDelay(s.delayAfterMs)}"
                is ExecStep.Swipe -> "s${s.fromX},${s.fromY},${s.toX},${s.toY}," +
                    "${clampDuration(s.durationMs)},${clampDelay(s.delayAfterMs)}"
                is ExecStep.MultiGesture -> "g${encodeMultiGesture(s)}"
                is ExecStep.Wait -> "w${clampDelay(s.ms)}"
                is ExecStep.Verify -> ""
            }
        }.trim(SEP_STEP.single())
        "${m.macroId}$SEP_FIELD${sanitizeLabel(m.name)}$SEP_LABEL$steps"
    }

    fun decodeMacros(raw: String): List<MacroConfig> = raw.split(SEP_ITEM).mapNotNull { rec ->
        val idx = rec.indexOf(SEP_LABEL)
        if (idx <= 0) return@mapNotNull null
        val head = rec.substring(0, idx).split(SEP_FIELD)
        if (head.size != 2) return@mapNotNull null
        runCatching {
            MacroConfig(
                macroId = head[0].toLong(),
                name = head[1].ifEmpty { "宏" },
                steps = parseSteps(rec.substring(idx + 1)),
            )
        }.getOrNull()
    }

    private fun clampDelay(v: Long): Long = v.clampDelayMs()
    private fun clampDuration(v: Int): Int = CommandValidator.clampSwipeDurationMs(v)

    private fun b64Encode(raw: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(Charsets.UTF_8))

    private fun b64Decode(raw: String): String =
        String(Base64.getUrlDecoder().decode(raw), Charsets.UTF_8)

    private fun encodeMultiGesture(step: ExecStep.MultiGesture): String {
        val duration = step.durationMs.clampMultiTouchDurationMs()
        val pointerPart = step.pointers.joinToString(";") { pointer ->
            pointer.points.joinToString("#") { point ->
                val t = point.timeMs.coerceIn(0L, duration.toLong())
                "${point.x},${point.y},$t"
            }
        }
        val header = listOf(
            duration.toString(),
            clampDelay(step.delayAfterMs).toString(),
            step.rotation.toString(),
            step.panelMaxX.coerceAtLeast(0).toString(),
            step.panelMaxY.coerceAtLeast(0).toString(),
            b64Encode(step.devicePath),
        ).joinToString(",")
        return b64Encode("$header|$pointerPart")
    }

    private fun decodeMultiGesture(payload: String): ExecStep.MultiGesture? = runCatching {
        val decoded = b64Decode(payload)
        val parts = decoded.split('|', limit = 2)
        val header = parts.first().split(',')
        require(header.size == 6) { "bad header" }
        val duration = header[0].toInt().clampMultiTouchDurationMs()
        val delay = header[1].toLongOrNull()?.let(::clampDelay) ?: 0L
        val rotation = header[2].toIntOrNull() ?: 0
        val maxX = header[3].toIntOrNull()?.coerceAtLeast(0) ?: 0
        val maxY = header[4].toIntOrNull()?.coerceAtLeast(0) ?: 0
        val devicePath = b64Decode(header[5])
        val pointers = parts.getOrNull(1).orEmpty()
            .split(';')
            .mapNotNull { pointerToken ->
                val points = pointerToken.split('#').mapNotNull { pointToken ->
                    val values = pointToken.split(',')
                    if (values.size != 3) return@mapNotNull null
                    com.aixiaohuadb.exec.GesturePoint(
                        x = (values[0].toIntOrNull() ?: return@mapNotNull null)
                            .coerceIn(0, CommandValidator.BASE_WIDTH),
                        y = (values[1].toIntOrNull() ?: return@mapNotNull null)
                            .coerceIn(0, CommandValidator.BASE_HEIGHT),
                        timeMs = (values[2].toLongOrNull() ?: return@mapNotNull null)
                            .coerceIn(0L, duration.toLong()),
                    )
                }
                if (points.isEmpty()) null else GesturePointer(points)
            }
        require(pointers.size in 2..CommandValidator.MAX_POINTERS) { "bad pointer count" }
        ExecStep.MultiGesture(
            pointers = pointers,
            durationMs = duration,
            delayAfterMs = delay,
            devicePath = devicePath,
            panelMaxX = maxX,
            panelMaxY = maxY,
            rotation = rotation,
        )
    }.getOrNull()

    private fun parseSteps(segment: String): List<ExecStep> = segment.split(SEP_STEP).mapNotNull { token ->
        runCatching {
            when {
                token.isEmpty() -> null
                token.startsWith("g") -> decodeMultiGesture(token.substring(1))
                token.startsWith("w") -> {
                    ExecStep.Wait(token.substring(1).toLongOrNull()?.let(::clampDelay) ?: return@runCatching null)
                }
                token.startsWith("t") -> {
                    val p = token.substring(1).split(",")
                    if (p.size != 3) return@runCatching null
                    ExecStep.Tap(
                        x = (p[0].toIntOrNull() ?: return@runCatching null).coerceIn(0, CommandValidator.BASE_WIDTH),
                        y = (p[1].toIntOrNull() ?: return@runCatching null).coerceIn(0, CommandValidator.BASE_HEIGHT),
                        delayAfterMs = p[2].toLongOrNull()?.let(::clampDelay) ?: 0L,
                    )
                }
                token.startsWith("s") -> {
                    val p = token.substring(1).split(",")
                    if (p.size != 6) return@runCatching null
                    ExecStep.Swipe(
                        fromX = (p[0].toIntOrNull() ?: return@runCatching null).coerceIn(0, CommandValidator.BASE_WIDTH),
                        fromY = (p[1].toIntOrNull() ?: return@runCatching null).coerceIn(0, CommandValidator.BASE_HEIGHT),
                        toX = (p[2].toIntOrNull() ?: return@runCatching null).coerceIn(0, CommandValidator.BASE_WIDTH),
                        toY = (p[3].toIntOrNull() ?: return@runCatching null).coerceIn(0, CommandValidator.BASE_HEIGHT),
                        durationMs = (p[4].toIntOrNull() ?: return@runCatching null).let(::clampDuration),
                        delayAfterMs = p[5].toLongOrNull()?.let(::clampDelay) ?: 0L,
                    )
                }
                else -> {
                    // macros_v1 兼容："x,y" 两字段 Tap，无延迟
                    val p = token.split(",")
                    if (p.size != 2) return@runCatching null
                    ExecStep.Tap(
                        x = (p[0].toIntOrNull() ?: return@runCatching null).coerceIn(0, CommandValidator.BASE_WIDTH),
                        y = (p[1].toIntOrNull() ?: return@runCatching null).coerceIn(0, CommandValidator.BASE_HEIGHT),
                    )
                }
            }
        }.getOrNull()
    }

    // ---------------- legacy 迁移 ----------------

    /** 旧 QuickButtonCodec 格式："id,size,alpha,fx,fy,on:label"，id 即预置 commandId */
    fun migrateLegacy(raw: String): List<OverlayWidget> = raw.split(SEP_ITEM).mapNotNull { rec ->
        val idx = rec.indexOf(SEP_LABEL)
        if (idx <= 0) return@mapNotNull null
        val head = rec.substring(0, idx).split(",")
        if (head.size != 6) return@mapNotNull null
        runCatching {
            val commandId = head[0].toInt()
            OverlayWidget(
                id = "cmd:$commandId",
                kind = WidgetKind.COMMAND,
                commandId = commandId,
                label = rec.substring(idx + 1).ifEmpty { "按钮" },
                sizeDp = clampSizeDp(head[1].toInt()),
                alphaPercent = clampAlpha(head[2].toInt()),
                fx = clampPos(head[3].toInt() / POS_SCALE.toFloat()),
                fy = clampPos(head[4].toInt() / POS_SCALE.toFloat()),
                enabled = head[5] == "1",
            )
        }.getOrNull()
    }
}
