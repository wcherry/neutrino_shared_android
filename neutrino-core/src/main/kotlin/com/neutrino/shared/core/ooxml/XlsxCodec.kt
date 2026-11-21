package com.neutrino.shared.core.ooxml

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal

/**
 * The `.xlsx` a Neutrino spreadsheet is stored as, read into and written from the sheets editor's
 * `{"sheets":[…]}` JSON model — a port of the web's `web/apps/web/src/lib/ooxml/xlsx/{read,write,
 * mapping}.ts`, kept line for line so the two agree (`scripts/crosscheck-web-xlsx.sh` runs the
 * web's own code against this, both ways).
 *
 * The JSON model is the boundary on purpose, as on iOS: the app decodes the parts it models and
 * carries the rest — charts, conditional formats, CF variables, anything a later web release adds —
 * through untouched. Every construct is written as the SpreadsheetML element that means it; the few
 * OOXML cannot say go in `customXml/item1.xml` (see the web's `mapping.ts`).
 */
object XlsxCodec {
    const val MIME_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    const val FILE_EXTENSION = "xlsx"

    /** The part a pre-OOXML save left the editor's model in. Read, never written. */
    const val LEGACY_MODEL_PART = "neutrino/model.json"

    const val NS_MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    const val NS_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    const val EXTRAS_PART = "customXml/item1.xml"
    const val EXTRAS_PROPS_PART = "customXml/itemProps1.xml"
    const val EXTRAS_NAMESPACE = "https://neutrino.app/ns/sheet-extras"
    private const val XML_DECL = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"

    /** The web's `TABLE_STYLES[0].id`: what a table read without an extras entry is styled as. */
    const val DEFAULT_TABLE_STYLE = "blank"

    private val json = Json { ignoreUnknownKeys = true }

    // ── Entry points ────────────────────────────────────────────────────────────────────────────

    /** `.xlsx` bytes as the model. A trustworthy legacy model part wins, as on the web. */
    fun decode(bytes: ByteArray): JsonObject = legacyModel(bytes) ?: XlsxReader(ZipArchive(bytes)).read()

    /** The model as `.xlsx` bytes. `keep` is the package this replaces; parts nothing here understands are carried. */
    fun encode(file: JsonObject, keep: ByteArray? = null): ByteArray = XlsxWriter.write(file, keep)

    fun isWorkbook(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte() && bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()

    /** One tab, named the way the web names it. */
    val emptyModel: JsonObject
        get() = JsonObject(mapOf("sheets" to JsonArray(listOf(JsonObject(mapOf("name" to JsonPrimitive("Sheet 1"), "cells" to JsonObject(emptyMap())))))))

    fun emptyWorkbook(): ByteArray = encode(emptyModel)

    fun withExtension(name: String): String = if (hasExtension(name)) name else "$name.$FILE_EXTENSION"
    fun strippingExtension(name: String): String = if (hasExtension(name)) name.dropLast(FILE_EXTENSION.length + 1) else name
    private fun hasExtension(name: String) = name.lowercase().endsWith(".$FILE_EXTENSION")

    // ── The legacy model part ───────────────────────────────────────────────────────────────────

    /**
     * The model a pre-OOXML save stored, or null when there is none to trust: another tool's
     * package, another editor's model, or parts changed since it was written. Digest-guarded exactly
     * as `readNeutrinoModel` in the web's `ooxmlContainer.ts`.
     */
    fun legacyModel(bytes: ByteArray): JsonObject? {
        if (!ZipArchive.looksLikeArchive(bytes)) return null
        val zip = runCatching { ZipArchive(bytes) }.getOrNull() ?: return null
        val raw = zip.text(LEGACY_MODEL_PART) ?: return null
        val envelope = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
        if (envelope["version"]?.jsonPrimitive?.intOrNull != 1) return null
        if ((envelope["app"] as? JsonPrimitive)?.content != "sheets") return null
        val model = (envelope["model"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        if ((envelope["digest"] as? JsonPrimitive)?.content != digest(zip)) return null
        return runCatching { json.parseToJsonElement(model) as? JsonObject }.getOrNull()
    }

    /** FNV-1a over every part but the model, name included, sorted — byte for byte the web's `digestParts`. */
    internal fun digest(zip: ZipArchive): String {
        var hash = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
        val prime = 0x100000001b3L
        for (name in zip.names.filter { it != LEGACY_MODEL_PART }.sorted()) {
            for (b in name.toByteArray(Charsets.UTF_8)) hash = (hash xor (b.toLong() and 0xff)) * prime
            for (b in zip.data(name) ?: ByteArray(0)) hash = (hash xor (b.toLong() and 0xff)) * prime
        }
        return java.lang.Long.toUnsignedString(hash, 16).padStart(16, '0')
    }

    // ── Shared vocabulary (the web's mapping.ts) ────────────────────────────────────────────────

    private val cellIdRegex = Regex("^([A-Z]+)(\\d+)$")

    fun columnToNumber(letters: String): Int = letters.fold(0) { n, c -> n * 26 + (c.code - 64) }

    fun numberToColumn(number: Int): String {
        var n = number
        val out = StringBuilder()
        while (n > 0) {
            n--
            out.insert(0, ('A' + n % 26))
            n /= 26
        }
        return out.toString()
    }

    /** (column, row), both 1-based, or null when `id` is not a cell id. */
    fun parseCellId(id: String): Pair<Int, Int>? {
        val m = cellIdRegex.matchEntire(id) ?: return null
        val row = m.groupValues[2].toLongOrNull() ?: return null
        return columnToNumber(m.groupValues[1]) to row.toInt()
    }

    fun pxToCharWidth(px: Double) = (px - 5) / 7
    fun charWidthToPx(chars: Double) = jsRound(chars * 7 + 5)
    fun pxToPoints(px: Double) = px * 0.75
    fun pointsToPx(pt: Double) = jsRound(pt / 0.75)

    private val hexColor = Regex("^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$")
    private val rgbColor = Regex("^rgba?\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*(?:,[^)]*)?\\)$", RegexOption.IGNORE_CASE)

    /** A CSS colour as `#rrggbb`, or null. */
    fun normalizeColor(value: String?): String? {
        if (value.isNullOrEmpty()) return null
        val raw = value.trim()
        hexColor.matchEntire(raw)?.let { m ->
            val body = m.groupValues[1]
            val full = if (body.length == 3) body.map { "$it$it" }.joinToString("") else body
            return "#${full.lowercase()}"
        }
        rgbColor.matchEntire(raw)?.let { m ->
            fun part(s: String) = minOf(255, s.toInt()).toString(16).padStart(2, '0')
            return "#${part(m.groupValues[1])}${part(m.groupValues[2])}${part(m.groupValues[3])}".lowercase()
        }
        return null
    }

    fun toArgb(value: String?): String? = normalizeColor(value)?.let { "FF${it.drop(1).uppercase()}" }

    fun fromArgb(value: String?): String? {
        if (value.isNullOrEmpty()) return null
        val hex = if (value.length == 8) value.drop(2) else value
        return if (Regex("^[0-9a-fA-F]{6}$").matches(hex)) "#${hex.lowercase()}" else null
    }

    /** The format code for a structured `numberFormat` + `decimalPlaces`, or a `customFormat`. */
    fun formatCodeFor(style: JsonObject): String? {
        style.str("customFormat")?.takeIf { it.isNotEmpty() }?.let { return it }
        val kind = style.str("numberFormat")?.takeIf { it.isNotEmpty() } ?: return null
        val decimals = style.int("decimalPlaces") ?: if (kind == "currency") 2 else 0
        val frac = if (decimals > 0) "." + "0".repeat(decimals) else ""
        return when (kind) {
            "number" -> "#,##0$frac"
            "currency" -> "$#,##0$frac"
            "percent" -> "0$frac%"
            "date" -> "m/d/yyyy"
            "time" -> "h:mm:ss AM/PM"
            "datetime" -> "m/d/yyyy h:mm:ss AM/PM"
            else -> null
        }
    }

    /** A format code back as `numberFormat` + `decimalPlaces` when it is exactly one of ours. */
    fun structuredFormat(code: String): Map<String, JsonElement>? {
        when (code) {
            "m/d/yyyy" -> return mapOf("numberFormat" to JsonPrimitive("date"))
            "h:mm:ss AM/PM" -> return mapOf("numberFormat" to JsonPrimitive("time"))
            "m/d/yyyy h:mm:ss AM/PM" -> return mapOf("numberFormat" to JsonPrimitive("datetime"))
        }
        fun decimals(frac: String) = if (frac.isEmpty()) 0 else frac.length - 1
        Regex("^#,##0(\\.0+)?$").matchEntire(code)?.let { return mapOf("numberFormat" to JsonPrimitive("number"), "decimalPlaces" to JsonPrimitive(decimals(it.groupValues[1]))) }
        Regex("^\\$#,##0(\\.0+)?$").matchEntire(code)?.let { return mapOf("numberFormat" to JsonPrimitive("currency"), "decimalPlaces" to JsonPrimitive(decimals(it.groupValues[1]))) }
        Regex("^0(\\.0+)?%$").matchEntire(code)?.let { return mapOf("numberFormat" to JsonPrimitive("percent"), "decimalPlaces" to JsonPrimitive(decimals(it.groupValues[1]))) }
        return null
    }

    val BUILTIN_FORMATS = linkedMapOf(0 to "General", 1 to "0", 2 to "0.00", 3 to "#,##0", 4 to "#,##0.00", 9 to "0%", 10 to "0.00%", 14 to "m/d/yyyy", 22 to "m/d/yy h:mm")
    const val FIRST_CUSTOM_FORMAT_ID = 164

    val H_ALIGN = mapOf("left" to "left", "center" to "center", "right" to "right")
    val V_ALIGN = mapOf("top" to "top", "middle" to "center", "bottom" to "bottom")
    val V_ALIGN_BACK = mapOf("top" to "top", "center" to "middle", "bottom" to "bottom")
    val BORDER_SIDES = listOf("borderLeft" to "left", "borderRight" to "right", "borderTop" to "top", "borderBottom" to "bottom")

    fun borderWeight(name: String?): String? = name?.takeIf { it == "thin" || it == "medium" || it == "thick" }

    val CF_OPERATOR = mapOf("greaterThan" to "greaterThan", "lessThan" to "lessThan", "equalTo" to "equal", "notEqualTo" to "notEqual", "between" to "between")
    val CF_OPERATOR_BACK = mapOf("greaterThan" to "greaterThan", "lessThan" to "lessThan", "equal" to "equalTo", "notEqual" to "notEqualTo", "between" to "between")
    val CF_TIME_PERIOD = mapOf("dateIsToday" to "today", "dateIsTomorrow" to "tomorrow", "dateIsNextWeek" to "nextWeek", "dateIsThisMonth" to "thisMonth")
    val CF_TIME_PERIOD_BACK = CF_TIME_PERIOD.entries.associate { (k, v) -> v to k }
    val CF_ICON_SET = mapOf("trafficLights" to "3TrafficLights1", "arrows" to "3Arrows", "ratings" to "3Stars")
    val CF_ICON_SET_BACK = CF_ICON_SET.entries.associate { (k, v) -> v to k }
    val CF_THEME_COLORS = mapOf(
        "success" to ("#d7f5dd" to "#0b6b2e"), "warning" to ("#fff4cc" to "#7a5300"),
        "danger" to ("#ffdcdc" to "#8a1414"), "info" to ("#dbe9ff" to "#123a7a"),
    )
    val CF_HEAT_MAP_PRESETS = mapOf(
        "financial" to listOf("#f8696b", "#ffeb84", "#63be7b"),
        "performance" to listOf("#5a8ac6", "#fcfcff", "#f8696b"),
        "temperature" to listOf("#4a7ebb", "#ffffff", "#e0574a"),
    )
    private val NATIVE_CF_KINDS = setOf("singleColor", "colorScale", "dataBar", "iconSet", "duplicates", "uniques", "topBottom", "average", "formula")

    fun needsCfExtra(kind: String, condition: String?): Boolean =
        kind !in NATIVE_CF_KINDS || (kind == "singleColor" && condition == "dateIsPastDue")

    /** The web's `xmlEscape`: & < > " only. */
    fun xmlEscape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** The web's `xmlSafe`: the control characters XML 1.0 cannot carry at all. */
    fun xmlSafe(value: String) = value.filter { c -> !(c.code in 0x00..0x08 || c.code == 0x0B || c.code == 0x0C || c.code in 0x0E..0x1F) }

    // ── JavaScript arithmetic, so numbers print as the web prints them ──────────────────────────

    /** `Math.round`: halves toward +∞. */
    fun jsRound(x: Double): Double = kotlin.math.floor(x + 0.5)

    /** A number as JavaScript's `String(n)` writes it. */
    fun jsNumber(d: Double): String {
        if (d.isNaN()) return "NaN"
        if (d.isInfinite()) return if (d > 0) "Infinity" else "-Infinity"
        if (d == 0.0) return "0"
        val abs = kotlin.math.abs(d)
        if (abs >= 1e21 || abs < 1e-6) {
            val s = d.toString().replace("E", "e")
            val (mantissa, exponent) = s.split("e").let { it[0] to it.getOrElse(1) { "0" } }
            val m = mantissa.removeSuffix(".0")
            val e = exponent.toInt()
            return "${m}e${if (e >= 0) "+" else ""}$e"
        }
        return BigDecimal(d.toString()).stripTrailingZeros().toPlainString()
    }

    /** `parseFloat`: the longest leading number, or null. */
    fun jsParseFloat(s: String): Double? = Regex("^\\s*[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?").find(s)?.value?.trim()?.toDoubleOrNull()

    /** `Number(s)` for an attribute value: blank is null, and so is anything non-finite. */
    fun jsNumberOf(s: String?): Double? {
        if (s == null || s.isBlank()) return null
        return s.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    /** A JSON number the way JSON.stringify writes it: integral ones without a fraction. */
    fun jsonNumber(d: Double): JsonPrimitive =
        if (d == kotlin.math.floor(d) && kotlin.math.abs(d) < 9.007199254740992E15) JsonPrimitive(d.toLong()) else JsonPrimitive(d)

    // ── JSON access ─────────────────────────────────────────────────────────────────────────────

    internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }
    internal fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
    internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
    internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
    internal val JsonElement?.isNullish: Boolean get() = this == null || this is JsonNull
}
