package com.aixiaohuadb.domain.macro

/** 编解码失败：调用方必须显式处理，不允许静默丢弃用户宏。 */
class MacroCodecException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 宏文档 JSON 编解码。
 *
 * 约定：
 * - 解析失败抛 [MacroCodecException]，绝不返回空文档（旧实现解析失败会覆盖原文件）。
 * - 未知字段忽略；版本号高于 [MacroDocument.VERSION] 直接拒绝，避免降级写坏数据。
 * - 字段名短，便于单文件存储与人工排查。
 */
object MacroCodec {

    fun encode(doc: MacroDocument): String = Json.write(JsonValue.Obj(linkedMapOf(
        "schemaVersion" to JsonValue.Num(doc.schemaVersion.toDouble()),
        "id" to JsonValue.Str(doc.id),
        "name" to JsonValue.Str(doc.name),
        "createdAt" to JsonValue.Num(doc.createdAt.toDouble()),
        "updatedAt" to JsonValue.Num(doc.updatedAt.toDouble()),
        "screen" to JsonValue.Obj(linkedMapOf(
            "w" to JsonValue.Num(doc.screen.width.toDouble()),
            "h" to JsonValue.Num(doc.screen.height.toDouble()),
            "r" to JsonValue.Num(doc.screen.rotation.toDouble()),
        )),
        "steps" to JsonValue.Arr(doc.steps.map { encodeStep(it) }),
    )))

    fun decode(text: String): MacroDocument {
        val root = parseObject(text, "宏")
        val version = root.int("schemaVersion", 0)
        if (version <= 0) throw MacroCodecException("宏缺少 schemaVersion")
        if (version > MacroDocument.VERSION) {
            throw MacroCodecException("宏版本 $version 高于当前支持的 ${MacroDocument.VERSION}，请升级应用")
        }

        val id = root.string("id").takeIf { it.isNotBlank() } ?: throw MacroCodecException("宏缺少 id")
        val name = root.string("name").ifBlank { "未命名宏" }
        val screenObj = root["screen"] as? JsonValue.Obj ?: throw MacroCodecException("宏缺少 screen")
        val screen = ScreenSpec(
            width = screenObj.int("w", 0),
            height = screenObj.int("h", 0),
            rotation = screenObj.int("r", 0),
        )
        if (screen.width <= 0 || screen.height <= 0) {
            throw MacroCodecException("宏的屏幕尺寸无效")
        }

        val stepArray = root["steps"] as? JsonValue.Arr ?: throw MacroCodecException("宏缺少 steps")
        val steps = ArrayList<MacroStep>(stepArray.size)
        for (index in 0 until stepArray.size) {
            val item = stepArray[index] as? JsonValue.Obj
                ?: throw MacroCodecException("第 ${index + 1} 步不是对象")
            steps += decodeStep(item, index)
        }

        return MacroDocument(
            schemaVersion = version,
            id = id,
            name = name,
            createdAt = root.long("createdAt", 0L),
            updatedAt = root.long("updatedAt", 0L),
            screen = screen,
            steps = steps,
        )
    }

    fun encodeMany(docs: List<MacroDocument>): String = Json.write(
        JsonValue.Arr(docs.map { Json.parse(encode(it)) }),
    )

    fun decodeMany(text: String): List<MacroDocument> {
        val array = Json.parse(text) as? JsonValue.Arr
            ?: throw MacroCodecException("宏列表不是数组")
        val result = ArrayList<MacroDocument>(array.size)
        for (index in 0 until array.size) {
            val item = array[index] as? JsonValue.Obj
                ?: throw MacroCodecException("第 ${index + 1} 个宏不是对象")
            result += decode(Json.write(item))
        }
        return result
    }

    private fun encodeStep(step: MacroStep): JsonValue = when (step) {
        is MacroStep.Tap -> JsonValue.Obj(linkedMapOf(
            "t" to JsonValue.Str(TYPE_TAP),
            "id" to JsonValue.Str(step.id),
            "delay" to JsonValue.Num(step.delayBeforeMs.toDouble()),
            "x" to JsonValue.Num(step.x.toDouble()),
            "y" to JsonValue.Num(step.y.toDouble()),
            "dur" to JsonValue.Num(step.durationMs.toDouble()),
        ))

        is MacroStep.Swipe -> JsonValue.Obj(linkedMapOf(
            "t" to JsonValue.Str(TYPE_SWIPE),
            "id" to JsonValue.Str(step.id),
            "delay" to JsonValue.Num(step.delayBeforeMs.toDouble()),
            "pts" to JsonValue.Arr(step.points.map {
                JsonValue.Arr(listOf(JsonValue.Num(it.x.toDouble()), JsonValue.Num(it.y.toDouble())))
            }),
            "dur" to JsonValue.Num(step.durationMs.toDouble()),
        ))

        is MacroStep.LongPress -> JsonValue.Obj(linkedMapOf(
            "t" to JsonValue.Str(TYPE_LONG_PRESS),
            "id" to JsonValue.Str(step.id),
            "delay" to JsonValue.Num(step.delayBeforeMs.toDouble()),
            "x" to JsonValue.Num(step.x.toDouble()),
            "y" to JsonValue.Num(step.y.toDouble()),
            "dur" to JsonValue.Num(step.durationMs.toDouble()),
        ))

        is MacroStep.Wait -> JsonValue.Obj(linkedMapOf(
            "t" to JsonValue.Str(TYPE_WAIT),
            "id" to JsonValue.Str(step.id),
            "delay" to JsonValue.Num(step.delayBeforeMs.toDouble()),
            "ms" to JsonValue.Num(step.waitMs.toDouble()),
        ))
    }

    private fun decodeStep(json: JsonValue.Obj, index: Int): MacroStep {
        val type = json.string("t")
        val id = json.string("id").ifBlank { MacroStep.newId() }
        val delay = json.long("delay", 0L).coerceIn(0L, 60_000L)

        fun unit(key: String): Float {
            val value = (json[key] as? JsonValue.Num)?.value
                ?: throw MacroCodecException("第 ${index + 1} 步缺少坐标 $key")
            return value.toFloat().coerceIn(0f, 1f)
        }

        fun duration(): Int = json.int("dur", MacroStep.DEFAULT_TAP_MS)
            .coerceIn(MacroStep.MIN_DURATION_MS, MacroStep.MAX_DURATION_MS)

        return when (type) {
            TYPE_TAP -> MacroStep.Tap(id, delay, unit("x"), unit("y"), duration())

            TYPE_LONG_PRESS -> MacroStep.LongPress(id, delay, unit("x"), unit("y"), duration())

            TYPE_WAIT -> MacroStep.Wait(
                id = id,
                delayBeforeMs = delay,
                waitMs = json.long("ms", 500L).coerceIn(0L, 60_000L),
            )

            TYPE_SWIPE -> {
                val pointsJson = json["pts"] as? JsonValue.Arr
                    ?: throw MacroCodecException("第 ${index + 1} 步缺少轨迹点")
                if (pointsJson.size < 2) {
                    throw MacroCodecException("第 ${index + 1} 步轨迹点少于 2 个")
                }
                val points = ArrayList<NormPoint>(pointsJson.size)
                for (i in 0 until pointsJson.size) {
                    val pair = pointsJson[i] as? JsonValue.Arr
                    if (pair == null || pair.size < 2 ||
                        pair[0] !is JsonValue.Num || pair[1] !is JsonValue.Num
                    ) {
                        throw MacroCodecException("第 ${index + 1} 步第 ${i + 1} 个轨迹点无效")
                    }
                    points += NormPoint.of(
                        (pair[0] as JsonValue.Num).value.toFloat(),
                        (pair[1] as JsonValue.Num).value.toFloat(),
                    )
                }
                MacroStep.Swipe(
                    id = id,
                    delayBeforeMs = delay,
                    points = points,
                    durationMs = json.int("dur", MacroStep.DEFAULT_SWIPE_MS)
                        .coerceIn(MacroStep.MIN_DURATION_MS, MacroStep.MAX_DURATION_MS),
                )
            }

            else -> throw MacroCodecException("第 ${index + 1} 步类型未知：$type")
        }
    }

    private fun parseObject(text: String, what: String): JsonValue.Obj {
        val value = runCatching { Json.parse(text) }
            .getOrElse { throw MacroCodecException("$what JSON 无法解析：${it.message}", it) }
        return value as? JsonValue.Obj ?: throw MacroCodecException("$what JSON 不是对象")
    }

    private fun JsonValue.Obj.string(key: String): String =
        (this[key] as? JsonValue.Str)?.value ?: ""

    private fun JsonValue.Obj.int(key: String, fallback: Int): Int {
        val number = (this[key] as? JsonValue.Num)?.value ?: return fallback
        if (!number.isFinite()) return fallback
        return number.toInt()
    }

    private fun JsonValue.Obj.long(key: String, fallback: Long): Long {
        val number = (this[key] as? JsonValue.Num)?.value ?: return fallback
        if (!number.isFinite()) return fallback
        return number.toLong()
    }

    private const val TYPE_TAP = "tap"
    private const val TYPE_SWIPE = "swipe"
    private const val TYPE_LONG_PRESS = "long"
    private const val TYPE_WAIT = "wait"
}
