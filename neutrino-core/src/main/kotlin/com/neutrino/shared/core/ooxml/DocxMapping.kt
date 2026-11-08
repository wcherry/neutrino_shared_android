package com.neutrino.shared.core.ooxml

import kotlin.math.abs

/**
 * The one place Neutrino's document model and OOXML are mapped to each other.
 *
 * [DocxWriter] and [DocxReader] both read from here and neither hard-codes a correspondence of its
 * own, so the two halves cannot drift apart silently. This is the Kotlin third of a single wire
 * format — `DocxMapping.swift` in neutrino_shared_ios and `web/apps/web/src/lib/ooxml/docx/mapping.ts`
 * are the other two — and a document written on any client is opened on the others, so a change to
 * one belongs in all three. `scripts/crosscheck-web-docx.sh` runs the web's own reader and writer
 * against this one.
 *
 * Units: twips (1/20 pt), half-points for font size, EMUs (914400 to the inch) for drawings; the
 * editor measures page setup in points and everything else in CSS pixels.
 */
object DocxMapping {

    // ── Units ───────────────────────────────────────────────────────────────────────────────────

    fun ptToTwip(pt: Double): Int = roundHalfAway(pt * 20).toInt()
    fun twipToPt(twip: Double): Double = twip / 20

    /** CSS pixels (96 dpi) to twips: 1px = 0.75pt = 15 twips. */
    fun pxToTwip(px: Double): Int = roundHalfAway(px * 15).toInt()
    fun twipToPx(twip: Double): Double = twip / 15

    /** CSS pixels to EMUs, for image extents: 1px = 9525 EMU. */
    fun pxToEmu(px: Double): Int = roundHalfAway(px * 9525).toInt()
    fun emuToPx(emu: Double): Double = emu / 9525

    /** One indent level in the editor is 24px of left margin. */
    const val INDENT_PX_PER_LEVEL = 24.0

    // ── Page size ───────────────────────────────────────────────────────────────────────────────

    /** Portrait dimensions in twips. Landscape swaps them. */
    val pageSizeTwips: Map<String, Pair<Int, Int>> = mapOf(
        "letter" to (12240 to 15840),
        "legal" to (12240 to 20160),
        "tabloid" to (15840 to 24480),
        "executive" to (10440 to 15120),
        "a3" to (16838 to 23814),
        "a4" to (11906 to 16838),
        "a5" to (8391 to 11906),
    )

    /** The page setup a document with none stored lays out to. */
    val defaultPageSetup: Map<String, DocValue> = mapOf(
        "marginTop" to docInt(72), "marginBottom" to docInt(72),
        "marginLeft" to docInt(72), "marginRight" to docInt(72),
        "orientation" to docString("portrait"), "pageSize" to docString("letter"),
    )

    /**
     * The page size whose portrait dimensions match `w`×`h`, within 20 twips: a document that has
     * been through Word comes back with its A4 height as 16840 rather than 16838.
     */
    fun pageSize(fromTwipsW: Int, h: Int): String? {
        val (pw, ph) = if (fromTwipsW > h) h to fromTwipsW else fromTwipsW to h
        for (name in pageSizeTwips.keys.sorted()) {
            val (w0, h0) = pageSizeTwips.getValue(name)
            if (abs(w0 - pw) <= 20 && abs(h0 - ph) <= 20) return name
        }
        return null
    }

    // ── Enumerations ────────────────────────────────────────────────────────────────────────────

    /** TipTap `textAlign` → `w:jc/@w:val`. */
    val alignmentToOoxml = mapOf("left" to "left", "center" to "center", "right" to "right", "justify" to "both")

    val ooxmlToAlignment = mapOf(
        "left" to "left", "start" to "left", "center" to "center", "right" to "right",
        "end" to "right", "both" to "justify", "distribute" to "justify",
    )

    /** CSS `list-style-type` → the glyph a bullet level shows (the shape lives in `w:lvlText`). */
    val bulletGlyph = mapOf("disc" to "●", "circle" to "○", "square" to "■")

    val glyphToBullet = mapOf(
        "●" to "disc", "○" to "circle", "■" to "square",
        "•" to "disc", "o" to "circle", "▪" to "square",
    )

    val orderedStyleToNumFmt = mapOf(
        "decimal" to "decimal",
        "lower-alpha" to "lowerLetter",
        "upper-alpha" to "upperLetter",
        "lower-roman" to "lowerRoman",
        "upper-roman" to "upperRoman",
    )

    val numFmtToOrderedStyle: Map<String, String> = orderedStyleToNumFmt.entries.associate { (k, v) -> v to k }

    /**
     * Highlight colours → `w:highlight/@w:val`. OOXML's highlight is a closed sixteen-colour list;
     * anything else is written as run shading (`w:shd`) instead.
     */
    val highlightNames = mapOf(
        "#ffff00" to "yellow", "#00ff00" to "green", "#00ffff" to "cyan", "#ff00ff" to "magenta",
        "#0000ff" to "blue", "#ff0000" to "red", "#000080" to "darkBlue", "#008080" to "darkCyan",
        "#008000" to "darkGreen", "#800080" to "darkMagenta", "#800000" to "darkRed",
        "#808000" to "darkYellow", "#808080" to "darkGray", "#c0c0c0" to "lightGray",
        "#000000" to "black", "#ffffff" to "white",
    )

    val nameToHighlight: Map<String, String> = highlightNames.entries.associate { (k, v) -> v to k }

    // ── Field codes ─────────────────────────────────────────────────────────────────────────────

    /** `docField` codes → Word field instructions, so `{{page}}` stays live in Word. */
    val fieldToInstruction = mapOf(
        "page" to "PAGE", "pages" to "NUMPAGES", "title" to "TITLE", "date" to "DATE", "time" to "TIME",
        "author" to "AUTHOR", "subject" to "SUBJECT", "keywords" to "KEYWORDS", "filename" to "FILENAME",
    )

    val instructionToField: Map<String, String> = fieldToInstruction.entries.associate { (k, v) -> v to k }

    /** A code with no built-in Word field goes out as a custom document property. */
    fun docPropertyInstruction(code: String) = "DOCPROPERTY $code"

    // ── The extras part ─────────────────────────────────────────────────────────────────────────

    /** A custom XML part, which Word keeps across an edit-and-save and discards nothing of. */
    const val EXTRAS_PART = "customXml/item1.xml"
    const val EXTRAS_PROPS_PART = "customXml/itemProps1.xml"
    const val EXTRAS_NAMESPACE = "https://neutrino.app/ns/doc-extras"

    /** Placeholder kinds that stood in for a *block* node: written as a paragraph of their own. */
    val blockPlaceholderKinds = setOf("sheetEmbed", "diagramEmbed")

    /** The character style every placeholder run carries, so it can be found again. */
    const val PLACEHOLDER_STYLE_ID = "NeutrinoPlaceholder"
    const val CODE_STYLE_ID = "NeutrinoCode"
    const val CODE_BLOCK_STYLE_ID = "NeutrinoCodeBlock"
    const val QUOTE_STYLE_ID = "NeutrinoQuote"

    // ── Headings ────────────────────────────────────────────────────────────────────────────────

    fun headingStyleId(level: Int) = "Heading${minOf(6, maxOf(1, level))}"

    /** The level a paragraph style id denotes, or null when it is not a heading. */
    fun headingLevel(styleId: String?): Int? {
        styleId ?: return null
        val normalized = styleId.replace(" ", "").lowercase()
        if (!normalized.startsWith("heading") || normalized.length != "heading".length + 1) return null
        val level = normalized.last().digitToIntOrNull() ?: return null
        return level.takeIf { it in 1..6 }
    }

    // ── Colour ──────────────────────────────────────────────────────────────────────────────────

    private val namedColors = mapOf(
        "black" to "#000000", "white" to "#ffffff", "red" to "#ff0000", "green" to "#008000",
        "blue" to "#0000ff", "yellow" to "#ffff00", "cyan" to "#00ffff", "magenta" to "#ff00ff",
        "gray" to "#808080", "grey" to "#808080", "transparent" to "",
    )

    /** A CSS colour (hex, `rgb()`, or a common name) as `#rrggbb`, or `""` when it is not one. */
    fun normalizeColor(input: String?): String {
        if (input.isNullOrEmpty()) return ""
        val value = input.trim().lowercase()
        namedColors[value]?.let { return it }
        if (value.startsWith("#")) {
            val digits = value.substring(1)
            val isHex = digits.all { it in '0'..'9' || it in 'a'..'f' }
            if (isHex && digits.length == 6) return "#$digits"
            if (isHex && digits.length == 3) return "#" + digits.map { "$it$it" }.joinToString("")
            return ""
        }
        if (value.startsWith("rgb")) {
            val inside = value.substringAfter('(', "").substringBefore(')')
            val numbers = inside.split(',').mapNotNull { it.trim().toIntOrNull() }
            if (numbers.size < 3) return ""
            return "#" + numbers.take(3).joinToString("") { "%02x".format(minOf(255, maxOf(0, it))) }
        }
        return ""
    }

    fun hexToOoxml(hex: String): String = hex.replace("#", "").uppercase()

    /** `RRGGBB` back to `#rrggbb`. `auto` means "the theme decides" and has no value. */
    fun ooxmlToHex(value: String?): String {
        if (value.isNullOrEmpty() || value.lowercase() == "auto") return ""
        return "#" + value.replace("#", "").lowercase()
    }

    // ── Font size ───────────────────────────────────────────────────────────────────────────────

    /** A CSS font size as half-points; `px` at the CSS ratio (1px = 0.75pt). */
    fun fontSizeToHalfPoints(input: String?): Int? {
        input ?: return null
        val value = input.trim()
        val isPx = value.endsWith("px")
        val number = if (value.endsWith("px") || value.endsWith("pt")) value.dropLast(2) else value
        val points = number.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
        return roundHalfAway((if (isPx) points * 0.75 else points) * 2).toInt()
    }

    /** Half-points back to the `pt` string the editor stores. */
    fun halfPointsToFontSize(half: Int): String = if (half % 2 == 0) "${half / 2}pt" else "${half.toDouble() / 2}pt"
}
