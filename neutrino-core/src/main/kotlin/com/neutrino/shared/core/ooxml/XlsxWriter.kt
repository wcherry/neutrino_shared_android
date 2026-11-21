package com.neutrino.shared.core.ooxml

import com.neutrino.shared.core.ooxml.XlsxCodec.CF_HEAT_MAP_PRESETS
import com.neutrino.shared.core.ooxml.XlsxCodec.CF_ICON_SET
import com.neutrino.shared.core.ooxml.XlsxCodec.CF_OPERATOR
import com.neutrino.shared.core.ooxml.XlsxCodec.CF_THEME_COLORS
import com.neutrino.shared.core.ooxml.XlsxCodec.CF_TIME_PERIOD
import com.neutrino.shared.core.ooxml.XlsxCodec.EXTRAS_NAMESPACE
import com.neutrino.shared.core.ooxml.XlsxCodec.EXTRAS_PART
import com.neutrino.shared.core.ooxml.XlsxCodec.EXTRAS_PROPS_PART
import com.neutrino.shared.core.ooxml.XlsxCodec.FIRST_CUSTOM_FORMAT_ID
import com.neutrino.shared.core.ooxml.XlsxCodec.H_ALIGN
import com.neutrino.shared.core.ooxml.XlsxCodec.NS_MAIN
import com.neutrino.shared.core.ooxml.XlsxCodec.NS_REL
import com.neutrino.shared.core.ooxml.XlsxCodec.V_ALIGN
import com.neutrino.shared.core.ooxml.XlsxCodec.arr
import com.neutrino.shared.core.ooxml.XlsxCodec.bool
import com.neutrino.shared.core.ooxml.XlsxCodec.formatCodeFor
import com.neutrino.shared.core.ooxml.XlsxCodec.int
import com.neutrino.shared.core.ooxml.XlsxCodec.jsNumber
import com.neutrino.shared.core.ooxml.XlsxCodec.jsParseFloat
import com.neutrino.shared.core.ooxml.XlsxCodec.needsCfExtra
import com.neutrino.shared.core.ooxml.XlsxCodec.num
import com.neutrino.shared.core.ooxml.XlsxCodec.numberToColumn
import com.neutrino.shared.core.ooxml.XlsxCodec.obj
import com.neutrino.shared.core.ooxml.XlsxCodec.parseCellId
import com.neutrino.shared.core.ooxml.XlsxCodec.pxToCharWidth
import com.neutrino.shared.core.ooxml.XlsxCodec.pxToPoints
import com.neutrino.shared.core.ooxml.XlsxCodec.str
import com.neutrino.shared.core.ooxml.XlsxCodec.toArgb
import com.neutrino.shared.core.ooxml.XlsxCodec.xmlEscape
import com.neutrino.shared.core.ooxml.XlsxCodec.xmlSafe
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

/**
 * The sheets editor's model → a real `.xlsx`: the web's `write.ts`, part for part and string for
 * string. Read with [XlsxReader]; they are two halves of one mapping.
 */
internal object XlsxWriter {
    private const val XML_DECL = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
    private const val NS_PKG_RELS = "http://schemas.openxmlformats.org/package/2006/relationships"

    /** Every distinct font, fill, border and format written once; cells index into the tables. */
    private class StyleTable {
        private val numFmts = LinkedHashMap<String, Int>()
        private val fonts = hashMapOf("" to 0)
        private val fills = hashMapOf("" to 0, "gray125" to 1)
        private val borders = hashMapOf("" to 0)
        private val xfs = hashMapOf("0|0|0|0|" to 0)
        private val fontXml = arrayListOf("<font><sz val=\"11\"/><name val=\"Calibri\"/></font>")
        private val fillXml = arrayListOf("<fill><patternFill patternType=\"none\"/></fill>", "<fill><patternFill patternType=\"gray125\"/></fill>")
        private val borderXml = arrayListOf("<border><left/><right/><top/><bottom/><diagonal/></border>")
        private val xfXml = arrayListOf("<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>")
        private val numFmtXml = ArrayList<String>()
        private val dxfXml = ArrayList<String>()

        fun cellStyle(style: JsonObject?): Int {
            if (style == null) return 0
            val numFmtId = numberFormat(style)
            val fontId = font(style)
            val fillId = fill(style)
            val borderId = border(style)
            val alignment = alignmentXml(style)
            val key = "$numFmtId|$fontId|$fillId|$borderId|$alignment"
            xfs[key]?.let { return it }
            val index = xfXml.size
            val applies = (if (numFmtId != 0) " applyNumberFormat=\"1\"" else "") + (if (fontId != 0) " applyFont=\"1\"" else "") +
                (if (fillId != 0) " applyFill=\"1\"" else "") + (if (borderId != 0) " applyBorder=\"1\"" else "") + (if (alignment.isNotEmpty()) " applyAlignment=\"1\"" else "")
            xfXml += "<xf numFmtId=\"$numFmtId\" fontId=\"$fontId\" fillId=\"$fillId\" borderId=\"$borderId\" xfId=\"0\"$applies>$alignment</xf>"
            xfs[key] = index
            return index
        }

        fun differential(format: JsonObject?): Int? {
            if (format == null) return null
            val font = StringBuilder()
            if (format.str("fontWeight") == "bold") font.append("<b/>")
            if (format.str("fontStyle") == "italic") font.append("<i/>")
            if (format.str("textDecoration") == "underline") font.append("<u/>")
            toArgb(format.str("color"))?.let { font.append("<color rgb=\"$it\"/>") }
            val background = toArgb(format.str("backgroundColor"))
            val parts = (if (font.isNotEmpty()) "<font>$font</font>" else "") +
                (if (background != null) "<fill><patternFill><bgColor rgb=\"$background\"/></patternFill></fill>" else "")
            if (parts.isEmpty()) return null
            dxfXml += "<dxf>$parts</dxf>"
            return dxfXml.size - 1
        }

        private fun numberFormat(style: JsonObject): Int {
            val code = formatCodeFor(style)
            if (code == null || code == "General") return 0
            XlsxCodec.BUILTIN_FORMATS.entries.firstOrNull { it.value == code }?.let { return it.key }
            numFmts[code]?.let { return it }
            val id = FIRST_CUSTOM_FORMAT_ID + numFmts.size
            numFmts[code] = id
            numFmtXml += "<numFmt numFmtId=\"$id\" formatCode=\"${xmlEscape(xmlSafe(code))}\"/>"
            return id
        }

        private fun font(style: JsonObject): Int {
            val size = style.str("fontSize")?.takeIf { it.isNotEmpty() }?.let(::jsParseFloat)
            val color = toArgb(style.str("color"))
            val parts = (if (style.str("fontWeight") == "bold") "<b/>" else "") +
                (if (style.str("fontStyle") == "italic") "<i/>" else "") +
                (if (style.str("textDecoration") == "line-through") "<strike/>" else "") +
                (if (size != null && size != 0.0) "<sz val=\"${jsNumber(size)}\"/>" else "<sz val=\"11\"/>") +
                (if (color != null) "<color rgb=\"$color\"/>" else "") +
                "<name val=\"${xmlEscape(xmlSafe(style.str("fontFamily")?.takeIf { it.isNotEmpty() } ?: "Calibri"))}\"/>"
            return intern(fonts, fontXml, "<font>$parts</font>", 0)
        }

        private fun fill(style: JsonObject): Int {
            val color = toArgb(style.str("backgroundColor")) ?: return 0
            return intern(fills, fillXml, "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"$color\"/><bgColor indexed=\"64\"/></patternFill></fill>", 0)
        }

        private fun border(style: JsonObject): Int {
            val parts = XlsxCodec.BORDER_SIDES.map { (side, tag) ->
                val weight = (style.str(side) ?: style.str("borderStyle"))?.takeIf { it != "none" && it.isNotEmpty() }
                if (weight != null) "<$tag style=\"$weight\"><color rgb=\"FF000000\"/></$tag>" else "<$tag/>"
            }
            return intern(borders, borderXml, "<border>${parts.joinToString("")}<diagonal/></border>", 0)
        }

        private fun intern(seen: HashMap<String, Int>, xml: ArrayList<String>, value: String, empty: Int): Int {
            seen[value]?.let { return it }
            if (value == xml[empty]) return empty
            xml += value
            seen[value] = xml.size - 1
            return xml.size - 1
        }

        fun toXml(): String {
            val numFmts = if (numFmtXml.isNotEmpty()) "<numFmts count=\"${numFmtXml.size}\">${numFmtXml.joinToString("")}</numFmts>" else ""
            val dxfs = if (dxfXml.isNotEmpty()) "<dxfs count=\"${dxfXml.size}\">${dxfXml.joinToString("")}</dxfs>" else "<dxfs count=\"0\"/>"
            return "$XML_DECL<styleSheet xmlns=\"$NS_MAIN\">$numFmts" +
                "<fonts count=\"${fontXml.size}\">${fontXml.joinToString("")}</fonts>" +
                "<fills count=\"${fillXml.size}\">${fillXml.joinToString("")}</fills>" +
                "<borders count=\"${borderXml.size}\">${borderXml.joinToString("")}</borders>" +
                "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
                "<cellXfs count=\"${xfXml.size}\">${xfXml.joinToString("")}</cellXfs>" +
                "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>" +
                "$dxfs</styleSheet>"
        }
    }

    private fun alignmentXml(style: JsonObject): String {
        val horizontal = style.str("textAlign")?.let { H_ALIGN[it] }
        val vertical = style.str("verticalAlign")?.let { V_ALIGN[it] }
        val wrap = style.str("wrapMode") == "wrap"
        if (horizontal == null && vertical == null && !wrap) return ""
        return "<alignment${horizontal?.let { " horizontal=\"$it\"" } ?: ""}${vertical?.let { " vertical=\"$it\"" } ?: ""}${if (wrap) " wrapText=\"1\"" else ""}/>"
    }

    // ── Cells ───────────────────────────────────────────────────────────────────────────────────

    /** Text that is a number OOXML may store as one — narrowly: `0015` is a product code, `1e5` is text. */
    private val numeric = Regex("^-?(?:0|[1-9]\\d*)(?:\\.\\d+)?$|^-?\\.\\d+$")

    private fun numericValue(text: String?): String? {
        if (text == null || !numeric.matches(text)) return null
        val n = text.toDouble()
        return if (n.isFinite()) jsNumber(n) else null
    }

    private fun cellXml(id: String, cell: JsonObject, styleIndex: Int): String {
        val s = if (styleIndex != 0) " s=\"$styleIndex\"" else ""
        val raw = cell.str("raw") ?: ""
        if (raw.startsWith("=")) {
            // The cached value goes beside the formula, so a reader that does not evaluate still has it.
            val formula = xmlEscape(xmlSafe(raw.drop(1)))
            val cached = cell.str("value") ?: ""
            numericValue(cached)?.let { return "<c r=\"$id\"$s><f>$formula</f><v>$it</v></c>" }
            if (cached.isEmpty()) return "<c r=\"$id\"$s><f>$formula</f></c>"
            return "<c r=\"$id\"$s t=\"str\"><f>$formula</f><v>${xmlEscape(xmlSafe(cached))}</v></c>"
        }
        // "1,234.50" is entered text the editor holds as the number in `value`.
        val number = numericValue(raw) ?: if (',' in raw) numericValue(cell.str("value")) else null
        if (number != null) return "<c r=\"$id\"$s><v>$number</v></c>"
        if (raw.isEmpty()) return if (s.isNotEmpty()) "<c r=\"$id\"$s/>" else ""
        return "<c r=\"$id\"$s t=\"inlineStr\"><is><t xml:space=\"preserve\">${xmlEscape(xmlSafe(raw))}</t></is></c>"
    }

    // ── Conditional formats ─────────────────────────────────────────────────────────────────────

    private fun firstCellOf(range: String): String {
        val first = range.split(":")[0].replace("$", "")
        return if (parseCellId(first) != null) first else "A1"
    }

    private class CfContext(val styles: StyleTable) {
        var next = 1
        fun priority() = next++
    }

    private fun cfRuleXml(rule: JsonObject, ctx: CfContext): String {
        val spec = rule.obj("rule") ?: return ""
        val stop = if (rule.bool("stopIfTrue") == true) " stopIfTrue=\"1\"" else ""
        val anchor = firstCellOf(rule.str("range") ?: "")
        fun dxf(format: JsonObject?) = ctx.styles.differential(format)?.let { " dxfId=\"$it\"" } ?: ""
        fun p() = " priority=\"${ctx.priority()}\""

        return when (spec.str("kind")) {
            "singleColor" -> {
                val format = dxf(spec.obj("format"))
                val text = spec.str("value") ?: ""
                val condition = spec.str("condition") ?: ""
                if (condition == "isEmpty" || condition == "isNotEmpty") {
                    val type = if (condition == "isEmpty") "containsBlanks" else "notContainsBlanks"
                    val formula = if (condition == "isEmpty") "LEN(TRIM($anchor))=0" else "LEN(TRIM($anchor))&gt;0"
                    return "<cfRule type=\"$type\"$format${p()}$stop><formula>$formula</formula></cfRule>"
                }
                if (condition == "containsText") {
                    val quoted = "\"${text.replace("\"", "\"\"")}\""
                    return "<cfRule type=\"containsText\"$format${p()}$stop operator=\"containsText\" text=\"${xmlEscape(xmlSafe(text))}\">" +
                        "<formula>NOT(ISERROR(SEARCH(${xmlEscape(quoted)},$anchor)))</formula></cfRule>"
                }
                CF_TIME_PERIOD[condition]?.let { period ->
                    return "<cfRule type=\"timePeriod\"$format${p()}$stop timePeriod=\"$period\"><formula>FLOOR($anchor,1)=TODAY()</formula></cfRule>"
                }
                if (condition == "dateIsPastDue") return "<cfRule type=\"cellIs\"$format${p()}$stop operator=\"lessThan\"><formula>TODAY()</formula></cfRule>"
                val operator = CF_OPERATOR[condition] ?: return ""
                val formulas = if (operator == "between") "<formula>${cfValue(spec.str("value"))}</formula><formula>${cfValue(spec.str("value2"))}</formula>"
                else "<formula>${cfValue(spec.str("value"))}</formula>"
                "<cfRule type=\"cellIs\"$format${p()}$stop operator=\"$operator\">$formulas</cfRule>"
            }
            "formula" -> "<cfRule type=\"expression\"${dxf(spec.obj("format"))}${p()}$stop><formula>${xmlEscape(xmlSafe((spec.str("formula") ?: "").removePrefix("=")))}</formula></cfRule>"
            "duplicates", "uniques" -> "<cfRule type=\"${if (spec.str("kind") == "duplicates") "duplicateValues" else "uniqueValues"}\"${dxf(spec.obj("format"))}${p()}$stop/>"
            "topBottom" -> "<cfRule type=\"top10\"${dxf(spec.obj("format"))}${p()}$stop rank=\"${rawNumber(spec["value"])}\"" +
                "${if (spec.str("direction") == "bottom") " bottom=\"1\"" else ""}${if (spec.str("type") == "percent") " percent=\"1\"" else ""}/>"
            "average" -> "<cfRule type=\"aboveAverage\"${dxf(spec.obj("format"))}${p()}$stop${if (spec.str("direction") == "below") " aboveAverage=\"0\"" else ""}/>"
            "colorScale" -> colorScaleXml(
                listOfNotNull(spec.str("minColor") ?: "", spec.str("midColor")?.takeIf { it.isNotEmpty() }, spec.str("maxColor") ?: ""), p(), stop)
            "heatMap" -> {
                val preset = CF_HEAT_MAP_PRESETS[spec.str("preset")] ?: CF_HEAT_MAP_PRESETS.getValue("financial")
                colorScaleXml(listOf(
                    spec.str("lowColor")?.takeIf { it.isNotEmpty() } ?: preset[0],
                    spec.str("midColor")?.takeIf { it.isNotEmpty() } ?: preset[1],
                    spec.str("highColor")?.takeIf { it.isNotEmpty() } ?: preset[2]), p(), stop)
            }
            "dataBar" -> dataBarXml(spec.str("color") ?: "", p(), stop, null, null)
            "progressBar" -> dataBarXml(spec.str("color") ?: "", p(), stop, spec["minValue"], spec["maxValue"])
            "iconSet" -> {
                val set = CF_ICON_SET[spec.str("iconSet")] ?: CF_ICON_SET.getValue("trafficLights")
                "<cfRule type=\"iconSet\"${p()}$stop><iconSet iconSet=\"$set\"><cfvo type=\"percent\" val=\"0\"/><cfvo type=\"percent\" val=\"33\"/><cfvo type=\"percent\" val=\"67\"/></iconSet></cfRule>"
            }
            "statusIndicator" -> "<cfRule type=\"iconSet\"${p()}$stop><iconSet iconSet=\"3TrafficLights1\"><cfvo type=\"percent\" val=\"0\"/><cfvo type=\"percent\" val=\"33\"/><cfvo type=\"percent\" val=\"67\"/></iconSet></cfRule>"
            "themeColor" -> {
                val (background, color) = CF_THEME_COLORS[spec.str("token")] ?: (null to null)
                val format = if (background != null) JsonObject(mapOf("backgroundColor" to JsonPrimitive(background), "color" to JsonPrimitive(color!!))) else null
                val single = LinkedHashMap<String, JsonElement>(mapOf("kind" to JsonPrimitive("singleColor")))
                spec["condition"]?.let { single["condition"] = it }
                spec["value"]?.let { single["value"] = it }
                spec["value2"]?.let { single["value2"] = it }
                format?.let { single["format"] = it }
                cfRuleXml(JsonObject(rule + ("rule" to JsonObject(single))), ctx)
            }
            // The definition is in `cfVariables`; a rule that never fires keeps the position occupied.
            "variable" -> "<cfRule type=\"expression\"${p()}$stop><formula>FALSE()</formula></cfRule>"
            else -> ""
        }
    }

    /** A JSON value interpolated the way a JS template literal would. */
    private fun rawNumber(value: JsonElement?): String = when (value) {
        null -> "undefined"
        is JsonPrimitive -> if (value.isString) value.content else value.content.toDoubleOrNull()?.let(::jsNumber) ?: value.content
        else -> value.toString()
    }

    private fun cfValue(value: String?): String {
        val text = value ?: ""
        if (text.isNotBlank() && text.trim().toDoubleOrNull()?.isFinite() == true) return text
        return xmlEscape(xmlSafe("\"${text.replace("\"", "\"\"")}\""))
    }

    private fun colorScaleXml(colors: List<String>, priority: String, stop: String): String {
        val points = if (colors.size == 3) "<cfvo type=\"min\"/><cfvo type=\"percentile\" val=\"50\"/><cfvo type=\"max\"/>" else "<cfvo type=\"min\"/><cfvo type=\"max\"/>"
        val rgb = colors.joinToString("") { "<color rgb=\"${toArgb(it) ?: "FFFFFFFF"}\"/>" }
        return "<cfRule type=\"colorScale\"$priority$stop><colorScale>$points$rgb</colorScale></cfRule>"
    }

    private fun dataBarXml(color: String, priority: String, stop: String, min: JsonElement?, max: JsonElement?): String {
        val low = if (min == null) "<cfvo type=\"min\"/>" else "<cfvo type=\"num\" val=\"${rawNumber(min)}\"/>"
        val high = if (max == null) "<cfvo type=\"max\"/>" else "<cfvo type=\"num\" val=\"${rawNumber(max)}\"/>"
        return "<cfRule type=\"dataBar\"$priority$stop><dataBar>$low$high<color rgb=\"${toArgb(color) ?: "FF638EC6"}\"/></dataBar></cfRule>"
    }

    // ── Worksheets ──────────────────────────────────────────────────────────────────────────────

    private class WrittenSheet(val xml: String, val tables: List<Pair<String, String>>, val extra: JsonObject)

    private fun writeSheet(sheet: JsonObject, styles: StyleTable, tableIdBase: Int): WrittenSheet {
        val extra = LinkedHashMap<String, JsonElement>()
        val cells = sheet.obj("cells") ?: JsonObject(emptyMap())

        val rows = sortedMapOf<Int, MutableList<Triple<String, JsonObject, Int>>>()
        var maxCol = 0
        var maxRow = 0
        val clipped = ArrayList<String>()
        for ((id, value) in cells) {
            val cell = value as? JsonObject ?: continue
            val (col, row) = parseCellId(id) ?: continue
            if (cell.obj("cellStyle")?.str("wrapMode") == "clip") clipped += id
            rows.getOrPut(row) { ArrayList() } += Triple(id, cell, col)
            maxCol = maxOf(maxCol, col)
            maxRow = maxOf(maxRow, row)
        }
        if (clipped.isNotEmpty()) extra["clipped"] = JsonArray(clipped.sorted().map(::JsonPrimitive))

        val heights = sheet.obj("rowHeights")
        val rowXml = StringBuilder()
        for ((r, list) in rows) {
            val cellsXml = list.sortedBy { it.third }.joinToString("") { (id, cell, _) -> cellXml(id, cell, styles.cellStyle(cell.obj("cellStyle"))) }
            val height = (heights?.get((r - 1).toString()) as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()
            val ht = if (height != null) " ht=\"${jsNumber(pxToPoints(height))}\" customHeight=\"1\"" else ""
            rowXml.append("<row r=\"$r\"$ht>$cellsXml</row>")
        }

        val colXml = (sheet.obj("colWidths") ?: JsonObject(emptyMap())).entries
            .mapNotNull { (key, px) ->
                val index = key.toDoubleOrNull()?.let { it + 1 } ?: return@mapNotNull null
                val width = (px as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull() ?: return@mapNotNull null
                if (!index.isFinite() || index <= 0) null else index to width
            }
            .sortedBy { it.first }
            .joinToString("") { (index, px) ->
                val i = jsNumber(index)
                "<col min=\"$i\" max=\"$i\" width=\"${String.format(Locale.ROOT, "%.4f", pxToCharWidth(px))}\" customWidth=\"1\"/>"
            }

        val merges = ArrayList<String>()
        for ((id, value) in cells) {
            val cell = value as? JsonObject ?: continue
            val cols = cell.int("colSpan") ?: 1
            val rowsSpan = cell.int("rowSpan") ?: 1
            if (cols <= 1 && rowsSpan <= 1) continue
            val (col, row) = parseCellId(id) ?: continue
            merges += "<mergeCell ref=\"$id:${numberToColumn(col + cols - 1)}${row + rowsSpan - 1}\"/>"
        }
        val mergeXml = if (merges.isNotEmpty()) "<mergeCells count=\"${merges.size}\">${merges.joinToString("")}</mergeCells>" else ""

        val ctx = CfContext(styles)
        val cfExtras = LinkedHashMap<String, JsonElement>()
        val cfXml = (sheet.arr("conditionalFormats") ?: JsonArray(emptyList())).mapIndexed { i, element ->
            val rule = element as? JsonObject ?: return@mapIndexed ""
            val spec = rule.obj("rule")
            if (spec != null && needsCfExtra(spec.str("kind") ?: "", spec.str("condition"))) cfExtras[i.toString()] = rule
            val body = cfRuleXml(rule, ctx)
            if (body.isNotEmpty()) "<conditionalFormatting sqref=\"${xmlEscape(rule.str("range") ?: "")}\">$body</conditionalFormatting>" else ""
        }.joinToString("")
        if (cfExtras.isNotEmpty()) extra["conditionalFormats"] = JsonObject(cfExtras)
        sheet.arr("cfVariables")?.takeIf { it.isNotEmpty() }?.let { extra["cfVariables"] = it }

        val tables = ArrayList<Pair<String, String>>()
        val tableStyles = LinkedHashMap<String, JsonElement>()
        val tableRels = ArrayList<String>()
        sheet.arr("tables")?.forEachIndexed { i, element ->
            val region = element as? JsonObject ?: return@forEachIndexed
            val id = tableIdBase + i
            val minC = region.int("minC") ?: 1
            val maxC = region.int("maxC") ?: minC
            val minR = region.int("minR") ?: 1
            val maxR = region.int("maxR") ?: minR
            val ref = "${numberToColumn(minC)}$minR:${numberToColumn(maxC)}$maxR"
            val columns = (minC..maxC).map { c ->
                val header = (cells["${numberToColumn(c)}$minR"] as? JsonObject)?.str("raw")?.trim()
                val name = header?.takeIf { it.isNotEmpty() } ?: "Column${c - minC + 1}"
                "<tableColumn id=\"${c - minC + 1}\" name=\"${xmlEscape(xmlSafe(name))}\"/>"
            }
            tables += "xl/tables/table$id.xml" to ("$XML_DECL<table xmlns=\"$NS_MAIN\" id=\"$id\" name=\"Table$id\" displayName=\"Table$id\" ref=\"$ref\" totalsRowShown=\"0\">" +
                "<autoFilter ref=\"$ref\"/><tableColumns count=\"${columns.size}\">${columns.joinToString("")}</tableColumns>" +
                "<tableStyleInfo name=\"TableStyleMedium2\" showFirstColumn=\"0\" showLastColumn=\"0\" showRowStripes=\"1\" showColumnStripes=\"0\"/></table>")
            tableRels += "<tablePart r:id=\"rId${i + 1}\"/>"
            region.str("styleId")?.takeIf { it.isNotEmpty() }?.let { tableStyles[i.toString()] = JsonPrimitive(it) }
        }
        if (tableStyles.isNotEmpty()) extra["tableStyles"] = JsonObject(tableStyles)
        sheet.arr("charts")?.takeIf { it.isNotEmpty() }?.let { extra["charts"] = it }

        val tabColor = toArgb(sheet.str("color"))
        val dimension = if (maxRow > 0) "<dimension ref=\"A1:${numberToColumn(maxCol)}$maxRow\"/>" else ""
        // Element order is the schema's, and Excel enforces it.
        val xml = "$XML_DECL<worksheet xmlns=\"$NS_MAIN\" xmlns:r=\"$NS_REL\">" +
            (if (tabColor != null) "<sheetPr><tabColor rgb=\"$tabColor\"/></sheetPr>" else "") +
            dimension + "<sheetFormatPr defaultRowHeight=\"15\"/>" +
            (if (colXml.isNotEmpty()) "<cols>$colXml</cols>" else "") +
            "<sheetData>$rowXml</sheetData>" + mergeXml + cfXml +
            (if (tableRels.isNotEmpty()) "<tableParts count=\"${tableRels.size}\">${tableRels.joinToString("")}</tableParts>" else "") +
            "</worksheet>"
        return WrittenSheet(xml, tables, JsonObject(extra))
    }

    // ── Names ───────────────────────────────────────────────────────────────────────────────────

    /** Tab names a workbook accepts: Excel refuses empty, long, duplicate, quoted and `[]:*?/\` ones. */
    fun tabNames(sheets: List<JsonObject>): List<String> {
        val used = HashSet<String>()
        return sheets.mapIndexed { index, sheet ->
            val cleaned = (sheet.str("name") ?: "").replace(Regex("[\\[\\]:*?/\\\\]"), " ")
                .replace(Regex("^'+|'+$"), "").trim().let { if (it.length > 31) it.take(31) else it }.trim()
            val base = cleaned.ifEmpty { "Sheet${index + 1}" }
            var name = base
            var n = 2
            while (name.lowercase() in used) {
                val suffix = " ($n)"
                name = base.take(31 - suffix.length) + suffix
                n++
            }
            used += name.lowercase()
            name
        }
    }

    // ── Entry point ─────────────────────────────────────────────────────────────────────────────

    fun write(file: JsonObject, keep: ByteArray?): ByteArray {
        val zip = ZipArchive()
        val given = file.arr("sheets")?.mapNotNull { it as? JsonObject }.orEmpty()
        val sheets = given.ifEmpty { listOf(JsonObject(mapOf("name" to JsonPrimitive("Sheet 1"), "cells" to JsonObject(emptyMap())))) }
        val names = tabNames(sheets)
        val styles = StyleTable()

        val extraSheets = LinkedHashMap<String, JsonElement>()
        var tableId = 1
        val written = sheets.mapIndexed { i, sheet ->
            writeSheet(sheet, styles, tableId).also {
                tableId += it.tables.size
                if (it.extra.isNotEmpty()) extraSheets[i.toString()] = it.extra
            }
        }
        val hasExtras = extraSheets.isNotEmpty()

        written.forEachIndexed { i, sheet ->
            zip.set("xl/worksheets/sheet${i + 1}.xml", sheet.xml)
            for ((path, xml) in sheet.tables) zip.set(path, xml)
            if (sheet.tables.isNotEmpty()) {
                val rels = sheet.tables.mapIndexed { t, (path, _) -> "<Relationship Id=\"rId${t + 1}\" Type=\"$NS_REL/table\" Target=\"../${path.replaceFirst("xl/", "")}\"/>" }.joinToString("")
                zip.set("xl/worksheets/_rels/sheet${i + 1}.xml.rels", "$XML_DECL<Relationships xmlns=\"$NS_PKG_RELS\">$rels</Relationships>")
            }
        }
        zip.set("xl/styles.xml", styles.toXml())
        val sheetEntries = names.mapIndexed { i, name -> "<sheet name=\"${xmlEscape(xmlSafe(name))}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>" }.joinToString("")
        zip.set("xl/workbook.xml", "$XML_DECL<workbook xmlns=\"$NS_MAIN\" xmlns:r=\"$NS_REL\"><sheets>$sheetEntries</sheets></workbook>")

        val workbookRels = names.indices.joinToString("") { i -> "<Relationship Id=\"rId${i + 1}\" Type=\"$NS_REL/worksheet\" Target=\"worksheets/sheet${i + 1}.xml\"/>" } +
            "<Relationship Id=\"rId${names.size + 1}\" Type=\"$NS_REL/styles\" Target=\"styles.xml\"/>" +
            (if (hasExtras) "<Relationship Id=\"rId${names.size + 2}\" Type=\"$NS_REL/customXml\" Target=\"../$EXTRAS_PART\"/>" else "")
        zip.set("xl/_rels/workbook.xml.rels", "$XML_DECL<Relationships xmlns=\"$NS_PKG_RELS\">$workbookRels</Relationships>")
        zip.set("_rels/.rels", "$XML_DECL<Relationships xmlns=\"$NS_PKG_RELS\"><Relationship Id=\"rId1\" Type=\"$NS_REL/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>")

        if (hasExtras) {
            val payload = JsonObject(mapOf("sheets" to JsonObject(extraSheets))).toString()
            zip.set(EXTRAS_PART, "$XML_DECL<sheetExtras xmlns=\"$EXTRAS_NAMESPACE\" version=\"1\"><payload>${xmlEscape(xmlSafe(payload))}</payload></sheetExtras>")
            zip.set(EXTRAS_PROPS_PART, "$XML_DECL<ds:datastoreItem xmlns:ds=\"http://schemas.openxmlformats.org/officeDocument/2006/customXml\" ds:itemID=\"{4E9C2B71-9F3C-4E77-9E3E-1C2D3A4B5C6D}\">" +
                "<ds:schemaRefs><ds:schemaRef ds:uri=\"$EXTRAS_NAMESPACE\"/></ds:schemaRefs></ds:datastoreItem>")
            zip.set("customXml/_rels/item1.xml.rels", "$XML_DECL<Relationships xmlns=\"$NS_PKG_RELS\"><Relationship Id=\"rId1\" Type=\"$NS_REL/customXmlProps\" Target=\"itemProps1.xml\"/></Relationships>")
        }

        val carried = if (keep != null) carryForeignParts(zip, keep) else emptyList()
        val overrides = listOf("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>") +
            names.indices.map { i -> "<Override PartName=\"/xl/worksheets/sheet${i + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" } +
            "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" +
            written.flatMap { s -> s.tables.map { (path, _) -> "<Override PartName=\"/$path\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.table+xml\"/>" } } +
            (if (hasExtras) listOf("<Override PartName=\"/$EXTRAS_PROPS_PART\" ContentType=\"application/vnd.openxmlformats-officedocument.customXmlProperties+xml\"/>") else emptyList()) +
            carried
        zip.set("[Content_Types].xml", "$XML_DECL<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Default Extension=\"png\" ContentType=\"image/png\"/>" +
            "<Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>" +
            "${overrides.joinToString("")}</Types>")
        return zip.serialized()
    }

    /**
     * Parts of the package being replaced that this writer has no notion of — pivot tables, images,
     * charts another tool wrote — carried into the new one, with their content-type overrides.
     * Never the relationship files, which name parts by ids this rewrites.
     */
    private fun carryForeignParts(zip: ZipArchive, previous: ByteArray): List<String> {
        val old = runCatching { ZipArchive(previous) }.getOrNull() ?: return emptyList()
        val ours = Regex("^xl/(workbook\\.xml|styles\\.xml|worksheets/|tables/|sharedStrings\\.xml)|_rels|\\[Content_Types\\]")
        val types = HashMap<String, String>()
        old.text("[Content_Types].xml")?.let { xml ->
            Regex("<Override\\s+PartName=\"([^\"]+)\"\\s+ContentType=\"([^\"]+)\"\\s*/>").findAll(xml).forEach { types[it.groupValues[1]] = it.groupValues[2] }
        }
        val overrides = ArrayList<String>()
        for (path in old.names) {
            if (!path.startsWith("xl/") || ours.containsMatchIn(path) || "/_rels/" in path) continue
            if (path in zip) continue
            zip.set(path, old.data(path)!!)
            types["/$path"]?.let { overrides += "<Override PartName=\"/$path\" ContentType=\"$it\"/>" }
        }
        return overrides
    }
}
