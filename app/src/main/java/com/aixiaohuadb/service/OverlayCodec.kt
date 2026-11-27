package com.aixiaohuadb.service

import com.aixiaohuadb.adb.CommandValidator
import com.aixiaohuadb.exec.ExecStep

/**
 * 统一悬浮配置编解码（纯 Kotlin，可单测）。
 * 记录分隔 ';'，字段分隔 '|'，label/steps 分隔 ':'；label 写入前 sanitize。
 * 宏 steps 段：相邻步骤用 '~' 分隔，Tap 为 "x,y"，Wait 为 "w{ms}"，Verify 不持久化。
 * 兼容迁移：旧 QuickButtonCodec 格式 "id,size,alpha,fx,fy,on:label" → OverlayWidget(COMMAND)。
 */
object OverlayCodec {
    private const val SEP_ITEM = ";"
    private const val SEP_FIELD = "|"
    private const val SEP_LABEL = ":"
    private const val SEP_STEP = "~"
    private const val POS_SCALE = 10000
    private const val MAX_WAIT_MS = 10_000L

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

    // ---------------- macros v1：记录 "macroId|name:steps" ----------------

    fun encodeMacros(list: List<MacroConfig>): String = list.joinToString(SEP_ITEM) { m ->
        val steps = m.steps.joinToString(SEP_STEP) { s ->
            when (s) {
                is ExecStep.Tap -> "${s.x},${s.y}"
                is ExecStep.Wait -> "w${s.ms}"
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

    private fun parseSteps(segment: String): List<ExecStep> = segment.split(SEP_STEP).mapNotNull { token ->
        when {
            token.isEmpty() -> null
            token.startsWith("w") -> token.substring(1).toLongOrNull()
                ?.coerceIn(0L, MAX_WAIT_MS)
                ?.let { ExecStep.Wait(it) }
            else -> {
                val parts = token.split(",")
                if (parts.size != 2) return@mapNotNull null
                val x = parts[0].toIntOrNull() ?: return@mapNotNull null
                val y = parts[1].toIntOrNull() ?: return@mapNotNull null
                ExecStep.Tap(
                    x.coerceIn(0, CommandValidator.BASE_WIDTH),
                    y.coerceIn(0, CommandValidator.BASE_HEIGHT),
                )
            }
        }
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
