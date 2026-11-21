package com.neutrino.shared.core.ooxml

import com.neutrino.shared.core.ooxml.XlsxCodec.CF_ICON_SET_BACK
import com.neutrino.shared.core.ooxml.XlsxCodec.CF_OPERATOR_BACK
import com.neutrino.shared.core.ooxml.XlsxCodec.CF_TIME_PERIOD_BACK
import com.neutrino.shared.core.ooxml.XlsxCodec.EXTRAS_PART
import com.neutrino.shared.core.ooxml.XlsxCodec.H_ALIGN
import com.neutrino.shared.core.ooxml.XlsxCodec.V_ALIGN_BACK
import com.neutrino.shared.core.ooxml.XlsxCodec.borderWeight
import com.neutrino.shared.core.ooxml.XlsxCodec.charWidthToPx
import com.neutrino.shared.core.ooxml.XlsxCodec.fromArgb
import com.neutrino.shared.core.ooxml.XlsxCodec.jsNumber
import com.neutrino.shared.core.ooxml.XlsxCodec.jsNumberOf
import com.neutrino.shared.core.ooxml.XlsxCodec.jsonNumber
import com.neutrino.shared.core.ooxml.XlsxCodec.numberToColumn
import com.neutrino.shared.core.ooxml.XlsxCodec.parseCellId
import com.neutrino.shared.core.ooxml.XlsxCodec.pointsToPx
import com.neutrino.shared.core.ooxml.XlsxCodec.structuredFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * An `.xlsx` → the sheets editor's model: the web's `read.ts`, construct for construct. Anything not
 * recognised is left behind rather than guessed at, and a cell always keeps its value. Shared
 * formulas read as their own text where the master carries it (cells elsewhere in the group keep
 * their cached value); rich text in a shared string collapses to its text — both gaps the web has.
 */
internal class XlsxReader(private val zip: ZipArchive) {

    private fun xml(path: String): XmlElement? = zip.text(path)?.let { runCatching { XmlElement.parse(it) }.getOrNull() }

    private fun kids(el: XmlElement?, name: String): List<XmlElement> = el?.elements(name).orEmpty()
    private fun kid(el: XmlElement?, name: String): XmlElement? = el?.element(name)
    /** Every descendant, the element itself included — `getElementsByTagName('*')` on a document. */
    private fun all(root: XmlElement?, name: String): List<XmlElement> =
        if (root == null) emptyList() else (if (root.localName == name) listOf(root) else emptyList()) + root.descendants(name)
    private fun attr(el: XmlElement?, name: String): String? = el?.attribute(name)
    private fun num(value: String?): Double? = jsNumberOf(value)

    // ── Package ─────────────────────────────────────────────────────────────────────────────────

    private fun rels(partPath: String): Map<String, String> {
        val dir = if ('/' in partPath) partPath.substringBeforeLast('/') else ""
        val relPath = "${if (dir.isNotEmpty()) "$dir/" else ""}_rels/${partPath.substringAfterLast('/')}.rels"
        val doc = xml(relPath) ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (rel in all(doc, "Relationship")) {
            val id = attr(rel, "Id")
            val target = attr(rel, "Target")
            if (!id.isNullOrEmpty() && !target.isNullOrEmpty()) out[id] = resolve(target, dir)
        }
        return out
    }

    private fun resolve(target: String, fromDir: String): String {
        if (target.startsWith("/")) return target.drop(1)
        val stack = ArrayList<String>()
        for (part in (if (fromDir.isNotEmpty()) "$fromDir/$target" else target).split("/")) {
            when (part) {
                ".", "" -> Unit
                ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                else -> stack += part
            }
        }
        return stack.joinToString("/")
    }

    // ── Styles ──────────────────────────────────────────────────────────────────────────────────

    private class Styles(val cells: List<JsonObject?>, val differentials: List<JsonObject?>)

    private fun styles(): Styles {
        val root = xml("xl/styles.xml") ?: return Styles(emptyList(), emptyList())
        val formats = HashMap<Int, String>()
        XlsxCodec.BUILTIN_FORMATS.forEach { (id, code) -> formats[id] = code }
        for (fmt in all(root, "numFmt")) {
            val id = num(attr(fmt, "numFmtId"))
            val code = attr(fmt, "formatCode")
            if (id != null && code != null) formats[id.toInt()] = code
        }
        val fonts = kids(kid(root, "fonts"), "font").map(::fontStyle)
        val fills = kids(kid(root, "fills"), "fill").map(::fillColor)
        val borders = kids(kid(root, "borders"), "border").map(::borderStyle)

        val cells = kids(kid(root, "cellXfs"), "xf").map { xf ->
            val style = LinkedHashMap<String, JsonElement>()
            fonts.getOrNull(num(attr(xf, "fontId"))?.toInt() ?: 0)?.let { style.putAll(it) }
            borders.getOrNull(num(attr(xf, "borderId"))?.toInt() ?: 0)?.let { style.putAll(it) }
            fills.getOrNull(num(attr(xf, "fillId"))?.toInt() ?: 0)?.let { style["backgroundColor"] = JsonPrimitive(it) }
            val formatId = num(attr(xf, "numFmtId"))
            val code = formatId?.let { formats[it.toInt()] }
            if (code != null && code != "General") {
                val structured = structuredFormat(code)
                if (structured != null) style.putAll(structured) else style["customFormat"] = JsonPrimitive(code)
            }
            kid(xf, "alignment")?.let { alignment ->
                attr(alignment, "horizontal")?.let { h -> H_ALIGN[h]?.let { style["textAlign"] = JsonPrimitive(it) } }
                attr(alignment, "vertical")?.let { v -> V_ALIGN_BACK[v]?.let { style["verticalAlign"] = JsonPrimitive(it) } }
                if (attr(alignment, "wrapText") == "1") style["wrapMode"] = JsonPrimitive("wrap")
            }
            if (style.isEmpty()) null else JsonObject(style)
        }

        val differentials = kids(kid(root, "dxfs"), "dxf").map { dxf ->
            val format = LinkedHashMap<String, JsonElement>()
            kid(dxf, "font")?.let { font ->
                if (kid(font, "b") != null) format["fontWeight"] = JsonPrimitive("bold")
                if (kid(font, "i") != null) format["fontStyle"] = JsonPrimitive("italic")
                if (kid(font, "u") != null) format["textDecoration"] = JsonPrimitive("underline")
                fromArgb(attr(kid(font, "color"), "rgb"))?.let { format["color"] = JsonPrimitive(it) }
            }
            val pattern = kid(kid(dxf, "fill"), "patternFill")
            (fromArgb(attr(kid(pattern, "bgColor"), "rgb")) ?: fromArgb(attr(kid(pattern, "fgColor"), "rgb")))?.let { format["backgroundColor"] = JsonPrimitive(it) }
            if (format.isEmpty()) null else JsonObject(format)
        }
        return Styles(cells, differentials)
    }

    private fun fontStyle(font: XmlElement): Map<String, JsonElement> {
        val style = LinkedHashMap<String, JsonElement>()
        if (kid(font, "b") != null) style["fontWeight"] = JsonPrimitive("bold")
        if (kid(font, "i") != null) style["fontStyle"] = JsonPrimitive("italic")
        if (kid(font, "strike") != null) style["textDecoration"] = JsonPrimitive("line-through")
        // 11pt is the default every workbook opens at; recording it would size every cell.
        num(attr(kid(font, "sz"), "val"))?.takeIf { it != 11.0 }?.let { style["fontSize"] = JsonPrimitive("${jsNumber(it)}pt") }
        fromArgb(attr(kid(font, "color"), "rgb"))?.let { style["color"] = JsonPrimitive(it) }
        attr(kid(font, "name"), "val")?.takeIf { it.isNotEmpty() && it != "Calibri" }?.let { style["fontFamily"] = JsonPrimitive(it) }
        return style
    }

    private fun fillColor(fill: XmlElement): String? {
        val pattern = kid(fill, "patternFill") ?: return null
        if (attr(pattern, "patternType") != "solid") return null
        return fromArgb(attr(kid(pattern, "fgColor"), "rgb"))
    }

    private fun borderStyle(border: XmlElement): Map<String, JsonElement> {
        val style = LinkedHashMap<String, JsonElement>()
        for ((tag, key) in listOf("left" to "borderLeft", "right" to "borderRight", "top" to "borderTop", "bottom" to "borderBottom")) {
            borderWeight(attr(kid(border, tag), "style"))?.let { style[key] = JsonPrimitive(it) }
        }
        return style
    }

    private fun sharedStrings(): List<String> {
        val root = xml("xl/sharedStrings.xml") ?: return emptyList()
        return kids(root, "si").map { si -> all(si, "t").joinToString("") { it.textContent } }
    }

    // ── Cells ───────────────────────────────────────────────────────────────────────────────────

    private fun cell(c: XmlElement, shared: List<String>, styles: Styles): Pair<String, JsonObject>? {
        val id = attr(c, "r")
        if (id.isNullOrEmpty() || parseCellId(id) == null) return null
        val type = attr(c, "t") ?: "n"
        val styleIndex = num(attr(c, "s"))?.toInt() ?: 0
        val cellStyle = styles.cells.getOrNull(styleIndex)
        val formula = kid(c, "f")?.textContent ?: ""
        val rawValue = kid(c, "v")?.textContent ?: ""
        val display = when (type) {
            "inlineStr" -> all(kid(c, "is") ?: c, "t").joinToString("") { it.textContent }
            "s" -> rawValue.trim().toDoubleOrNull()?.toInt()?.let { shared.getOrNull(it) } ?: ""
            "b" -> if (rawValue == "1") "TRUE" else "FALSE"
            else -> rawValue
        }
        val raw = if (formula.isNotEmpty()) "=$formula" else display
        if (raw.isEmpty() && display.isEmpty() && cellStyle == null) return null
        val out = linkedMapOf<String, JsonElement>("id" to JsonPrimitive(id), "raw" to JsonPrimitive(raw), "value" to JsonPrimitive(display))
        if (cellStyle != null) out["cellStyle"] = cellStyle
        return id to JsonObject(out)
    }

    // ── Conditional formats ─────────────────────────────────────────────────────────────────────

    private fun cfRule(rule: XmlElement, range: String, index: Int, sheet: Int, styles: Styles): JsonObject? {
        val type = attr(rule, "type")
        val dxfId = num(attr(rule, "dxfId"))?.toInt()
        val format: JsonObject = dxfId?.let { styles.differentials.getOrNull(it) } ?: JsonObject(emptyMap())
        val formulas = kids(rule, "formula").map { it.textContent }
        val base = linkedMapOf<String, JsonElement>("id" to JsonPrimitive("cf-$sheet-$index"), "range" to JsonPrimitive(range))
        if (attr(rule, "stopIfTrue") == "1") base["stopIfTrue"] = JsonPrimitive(true)
        fun unquote(v: String) = if (Regex("^\".*\"$", RegexOption.DOT_MATCHES_ALL).matches(v)) v.substring(1, v.length - 1).replace("\"\"", "\"") else v
        fun with(spec: Map<String, JsonElement>) = JsonObject(base + ("rule" to JsonObject(spec)))
        fun s(v: String) = JsonPrimitive(v)

        return when (type) {
            "cellIs" -> {
                val condition = CF_OPERATOR_BACK[attr(rule, "operator") ?: ""] ?: return null
                val spec = linkedMapOf<String, JsonElement>("kind" to s("singleColor"), "condition" to s(condition), "value" to s(unquote(formulas.getOrElse(0) { "" })))
                if (formulas.size > 1) spec["value2"] = s(unquote(formulas[1]))
                spec["format"] = format
                with(spec)
            }
            "containsText" -> with(linkedMapOf("kind" to s("singleColor"), "condition" to s("containsText"), "value" to s(attr(rule, "text") ?: ""), "format" to format))
            "containsBlanks" -> with(linkedMapOf("kind" to s("singleColor"), "condition" to s("isEmpty"), "format" to format))
            "notContainsBlanks" -> with(linkedMapOf("kind" to s("singleColor"), "condition" to s("isNotEmpty"), "format" to format))
            "timePeriod" -> {
                val condition = CF_TIME_PERIOD_BACK[attr(rule, "timePeriod") ?: ""] ?: return null
                with(linkedMapOf("kind" to s("singleColor"), "condition" to s(condition), "format" to format))
            }
            "expression" -> with(linkedMapOf("kind" to s("formula"), "formula" to s("=${formulas.getOrElse(0) { "" }}"), "format" to format))
            "duplicateValues" -> with(linkedMapOf("kind" to s("duplicates"), "format" to format))
            "uniqueValues" -> with(linkedMapOf("kind" to s("uniques"), "format" to format))
            "top10" -> with(linkedMapOf(
                "kind" to s("topBottom"),
                "direction" to s(if (attr(rule, "bottom") == "1") "bottom" else "top"),
                "type" to s(if (attr(rule, "percent") == "1") "percent" else "n"),
                "value" to jsonNumber(num(attr(rule, "rank")) ?: 10.0),
                "format" to format,
            ))
            "aboveAverage" -> with(linkedMapOf("kind" to s("average"), "direction" to s(if (attr(rule, "aboveAverage") == "0") "below" else "above"), "format" to format))
            "colorScale" -> {
                val colors = kids(kid(rule, "colorScale"), "color").mapNotNull { fromArgb(attr(it, "rgb")) }
                if (colors.size < 2) return null
                with(if (colors.size >= 3) linkedMapOf("kind" to s("colorScale"), "minColor" to s(colors[0]), "midColor" to s(colors[1]), "maxColor" to s(colors[2]))
                else linkedMapOf("kind" to s("colorScale"), "minColor" to s(colors[0]), "maxColor" to s(colors[1])))
            }
            "dataBar" -> with(linkedMapOf("kind" to s("dataBar"), "color" to s(fromArgb(attr(kid(kid(rule, "dataBar"), "color"), "rgb")) ?: "#638ec6"), "gradient" to JsonPrimitive(true)))
            "iconSet" -> with(linkedMapOf("kind" to s("iconSet"), "iconSet" to s(CF_ICON_SET_BACK[attr(kid(rule, "iconSet"), "iconSet") ?: ""] ?: "trafficLights")))
            else -> null
        }
    }

    private fun tableRegion(ref: String, index: Int, sheet: Int, styleId: String?): JsonObject? {
        val parts = ref.split(":")
        val start = parseCellId(parts.getOrElse(0) { "" }) ?: return null
        val end = parseCellId(parts.getOrNull(1) ?: parts[0]) ?: return null
        return JsonObject(linkedMapOf(
            "id" to JsonPrimitive("table-$sheet-$index"), "styleId" to JsonPrimitive(styleId ?: XlsxCodec.DEFAULT_TABLE_STYLE),
            "minR" to JsonPrimitive(start.second), "maxR" to JsonPrimitive(end.second), "minC" to JsonPrimitive(start.first), "maxC" to JsonPrimitive(end.first),
        ))
    }

    private fun extras(): JsonObject {
        val root = xml(EXTRAS_PART) ?: return JsonObject(emptyMap())
        val payload = all(root, "payload").firstOrNull()?.textContent
        if (payload.isNullOrEmpty()) return JsonObject(emptyMap())
        return runCatching { Json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())
    }

    // ── Entry point ─────────────────────────────────────────────────────────────────────────────

    fun read(): JsonObject {
        val workbook = xml("xl/workbook.xml") ?: throw IllegalArgumentException("not a workbook: xl/workbook.xml is missing")
        val rels = rels("xl/workbook.xml")
        val styles = styles()
        val shared = sharedStrings()
        val extras = extras()
        val extraSheets = extras["sheets"] as? JsonObject
        val sheets = ArrayList<JsonElement>()
        for ((index, entry) in all(workbook, "sheet").withIndex()) {
            val extra = extraSheets?.get(index.toString()) as? JsonObject ?: JsonObject(emptyMap())
            val path = rels[attr(entry, "id") ?: ""]
            val name = attr(entry, "name") ?: "Sheet ${index + 1}"
            val doc = path?.let(::xml)
            // A tab whose part is missing — a chart sheet — still has to exist, or every tab after it shifts.
            if (doc == null) {
                sheets += JsonObject(mapOf("name" to JsonPrimitive(name), "cells" to JsonObject(emptyMap())))
                continue
            }
            sheets += readSheet(doc, path, name, index, styles, shared, extra)
        }
        if (sheets.isEmpty()) sheets += JsonObject(mapOf("name" to JsonPrimitive("Sheet 1"), "cells" to JsonObject(emptyMap())))
        return JsonObject(mapOf("sheets" to JsonArray(sheets)))
    }

    private fun readSheet(root: XmlElement, path: String, name: String, index: Int, styles: Styles, shared: List<String>, extra: JsonObject): JsonObject {
        val sheet = linkedMapOf<String, JsonElement>("name" to JsonPrimitive(name))
        fromArgb(attr(kid(kid(root, "sheetPr"), "tabColor"), "rgb"))?.let { sheet["color"] = JsonPrimitive(it) }

        val cells = LinkedHashMap<String, JsonObject>()
        val clipped = (extra["clipped"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }?.toSet().orEmpty()
        val rows = kids(kid(root, "sheetData"), "row")
        for (row in rows) for (c in kids(row, "c")) {
            val (id, parsed) = cell(c, shared, styles) ?: continue
            // OOXML has `wrapText` and no third state: `clip` comes back from the extras entry.
            cells[id] = if (id in clipped) {
                val style = (parsed["cellStyle"] as? JsonObject).orEmpty() + ("wrapMode" to JsonPrimitive("clip"))
                JsonObject(parsed + ("cellStyle" to JsonObject(style)))
            } else parsed
        }

        val colWidths = LinkedHashMap<String, JsonElement>()
        for (col in kids(kid(root, "cols"), "col")) {
            val width = num(attr(col, "width")) ?: continue
            val min = num(attr(col, "min"))?.toInt() ?: continue
            val max = num(attr(col, "max"))?.toInt() ?: min
            for (i in min..max) colWidths[(i - 1).toString()] = jsonNumber(charWidthToPx(width))
        }

        val rowHeights = LinkedHashMap<String, JsonElement>()
        for (row in rows) {
            val r = num(attr(row, "r")) ?: continue
            val ht = num(attr(row, "ht")) ?: continue
            if (attr(row, "customHeight") == "1") rowHeights[(r.toInt() - 1).toString()] = jsonNumber(pointsToPx(ht))
        }

        // Merges, expanded back onto every covered cell.
        fun blank(id: String) = JsonObject(linkedMapOf("id" to JsonPrimitive(id), "raw" to JsonPrimitive(""), "value" to JsonPrimitive("")))
        for (merge in kids(kid(root, "mergeCells"), "mergeCell")) {
            val ref = attr(merge, "ref") ?: continue
            val parts = ref.split(":")
            val start = parseCellId(parts.getOrElse(0) { "" }) ?: continue
            val end = parseCellId(parts.getOrElse(1) { "" }) ?: continue
            val anchor = parts[0]
            cells[anchor] = JsonObject((cells[anchor] ?: blank(anchor)) + mapOf(
                "colSpan" to JsonPrimitive(end.first - start.first + 1), "rowSpan" to JsonPrimitive(end.second - start.second + 1)))
            for (c in start.first..end.first) for (r in start.second..end.second) {
                val id = "${numberToColumn(c)}$r"
                if (id == anchor) continue
                cells[id] = JsonObject((cells[id] ?: blank(id)) + ("mergeAnchor" to JsonPrimitive(anchor)))
            }
        }
        sheet["cells"] = JsonObject(cells)
        if (colWidths.isNotEmpty()) sheet["colWidths"] = JsonObject(colWidths)
        if (rowHeights.isNotEmpty()) sheet["rowHeights"] = JsonObject(rowHeights)

        // Conditional formats, then the extras overlay by rule index.
        val rules = ArrayList<JsonElement>()
        for (block in kids(root, "conditionalFormatting")) {
            val range = attr(block, "sqref") ?: ""
            for (rule in kids(block, "cfRule")) cfRule(rule, range, rules.size, index, styles)?.let { rules += it }
        }
        (extra["conditionalFormats"] as? JsonObject)?.forEach { (at, rule) ->
            val i = at.toIntOrNull() ?: return@forEach
            if (i >= 0 && i < rules.size) rules[i] = rule else if (i == rules.size) rules += rule
        }
        if (rules.isNotEmpty()) sheet["conditionalFormats"] = JsonArray(rules)
        (extra["cfVariables"] as? JsonArray)?.takeIf { it.isNotEmpty() }?.let { sheet["cfVariables"] = it }

        val partIds = kids(kid(root, "tableParts"), "tablePart").mapNotNull { attr(it, "id")?.takeIf { id -> id.isNotEmpty() } }
        if (partIds.isNotEmpty()) {
            val sheetRels = rels(path)
            val tableStyles = extra["tableStyles"] as? JsonObject
            val regions = partIds.mapIndexedNotNull { i, id ->
                val ref = sheetRels[id]?.let(::xml)?.let { attr(it, "ref") }
                ref?.let { tableRegion(it, i, index, (tableStyles?.get(i.toString()) as? JsonPrimitive)?.content) }
            }
            if (regions.isNotEmpty()) sheet["tables"] = JsonArray(regions)
        }
        (extra["charts"] as? JsonArray)?.takeIf { it.isNotEmpty() }?.let { sheet["charts"] = it }
        return JsonObject(sheet)
    }

    private fun JsonObject?.orEmpty(): Map<String, JsonElement> = this ?: emptyMap()
}
