package com.neutrino.shared.core.ooxml

import java.util.Base64

/** What the caller supplies that the model cannot carry. */
data class DocxWriteOptions(
    /** Document title, which is also what the `TITLE` field resolves to. */
    val title: String,
    /**
     * Bytes for each image `src`. A `neutrino-drive:` reference must be resolved by the caller —
     * this module cannot reach Drive — and one left unresolved is written as a placeholder carrying
     * the whole image node rather than as a broken picture.
     */
    val images: Map<String, ByteArray> = emptyMap(),
    /** Fixed timestamp for revision marks, so a write is deterministic. */
    val revisionDate: String = "2000-01-01T00:00:00Z",
)

/**
 * Neutrino's document model → a real `.docx`. The Kotlin half of `DocxWriter*.swift`, read
 * together with [DocxReader]: they are two halves of one mapping, and the round-trip tests assert
 * they agree.
 *
 * Everything the editor can hold is written as the OOXML element that means it — page setup as
 * `w:sectPr`, bands as header/footer parts, footnotes, field codes as `w:fldSimple`, the TOC as a
 * `TOC` field, cross-references as bookmarks and `REF` fields, tracked changes as `w:ins`/`w:del`,
 * document properties in `docProps/`. What OOXML cannot express goes into the extras part.
 *
 * The XML is written as text: every part has a fixed shape, and a template says what it is far more
 * legibly than builder calls. Everything from a document goes through [XmlText.escape].
 */
object DocxWriter {

    fun write(model: DocModel, options: DocxWriteOptions): ByteArray {
        val meta = DocxMeta(model.meta)
        val context = WriteContext(options.images, options.revisionDate)

        // The body first: writing it discovers the footnotes, bookmarks, numbering, images,
        // hyperlinks and extras every other part depends on.
        val body = model.doc.children.flatMap { blockXml(it, context) }.joinToString("")

        meta.docTheme?.let { if (it != "default") context.extras.theme = it }
        for ((columns, blockCount) in context.columnLayouts) context.extras.addColumnLayout(columns, blockCount)

        val archive = ZipArchive()
        val bands = bandParts(meta, context)
        archive.set(OoxmlPackage.DOCUMENT_PART, documentPart(body, meta, bands))
        for (band in bands) archive.set(band.path, band.xml)
        for (image in context.imageParts) archive.set(image.path, image.data)

        archive.set(OoxmlPackage.STYLES_PART, stylesPart())
        archive.set(OoxmlPackage.NUMBERING_PART, numberingPart(context))
        archive.set(OoxmlPackage.SETTINGS_PART, settingsPart(meta))
        archive.set(OoxmlPackage.FOOTNOTES_PART, footnotesPart(context))
        archive.set(OoxmlPackage.DOCUMENT_RELS_PART, documentRelsPart(bands, context))

        archive.set(OoxmlPackage.CORE_PROPS_PART, corePropsPart(options.title, meta))
        archive.set(OoxmlPackage.APP_PROPS_PART, appPropsPart())
        customPropsPart(meta)?.let { archive.set(OoxmlPackage.CUSTOM_PROPS_PART, it) }

        context.extras.parts()?.let { (item, props) ->
            archive.set(DocxMapping.EXTRAS_PART, item)
            archive.set(DocxMapping.EXTRAS_PROPS_PART, props)
            archive.set(OoxmlPackage.relsPart(DocxMapping.EXTRAS_PART), extrasRelsPart())
        }

        archive.set(OoxmlPackage.ROOT_RELS_PART, rootRelsPart(meta.hasCustomProperties))
        archive.set(OoxmlPackage.CONTENT_TYPES_PART, contentTypesPart(bands, context, meta))
        return archive.serialized()
    }

    /** Every image `src` the document references, in document order and without duplicates. */
    fun imageSources(model: DocModel): List<String> {
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        fun walk(node: DocNode) {
            if (node.type == "image") {
                val src = node.attr("src")?.stringValue
                if (!src.isNullOrEmpty() && seen.add(src)) out += src
            }
            node.children.forEach(::walk)
        }
        walk(model.doc)
        return out
    }

    // ── Element helpers ─────────────────────────────────────────────────────────────────────────

    /** One element, self-closing when it has no children. */
    internal fun el(name: String, attributes: List<Pair<String, String>> = emptyList(), children: String? = null): String {
        val attrs = attributes.joinToString("") { " ${it.first}=\"${XmlText.attributeValue(it.second)}\"" }
        return if (children.isNullOrEmpty()) "<$name$attrs/>" else "<$name$attrs>$children</$name>"
    }

    /** `w:val="…"`, which is how OOXML spells nearly every simple property. */
    internal fun valEl(name: String, value: String) = el(name, listOf("w:val" to value))

    // ── Run properties ──────────────────────────────────────────────────────────────────────────

    internal data class RunStyle(
        var styleId: String? = null,
        var font: String? = null,
        var bold: Boolean = false,
        var italic: Boolean = false,
        var strike: Boolean = false,
        var color: String? = null,
        var halfPoints: Int? = null,
        var highlight: String? = null,
        var underline: Boolean = false,
        var shadingFill: String? = null,
        var vertAlign: String? = null,
    ) {
        val isEmpty: Boolean
            get() = styleId == null && font == null && !bold && !italic && !strike && color == null && halfPoints == null &&
                highlight == null && !underline && shadingFill == null && vertAlign == null
    }

    /**
     * Marks collapsed into run properties. A highlight outside OOXML's closed sixteen-colour list is
     * written as run shading, which takes any colour; both come back as a highlight mark.
     */
    internal fun runStyle(marks: List<DocMark>?): RunStyle {
        val style = RunStyle()
        for (mark in marks.orEmpty()) {
            when (mark.type) {
                "bold" -> style.bold = true
                "italic" -> style.italic = true
                "underline" -> style.underline = true
                "strike" -> style.strike = true
                "superscript" -> style.vertAlign = "superscript"
                "subscript" -> style.vertAlign = "subscript"
                "code" -> style.styleId = DocxMapping.CODE_STYLE_ID
                "highlight" -> {
                    val hex = DocxMapping.normalizeColor(mark.attr("color")?.stringValue)
                    val name = DocxMapping.highlightNames[hex]
                    if (name != null) style.highlight = name else if (hex.isNotEmpty()) style.shadingFill = DocxMapping.hexToOoxml(hex)
                }
                "textStyle" -> {
                    val color = DocxMapping.normalizeColor(mark.attr("color")?.stringValue)
                    if (color.isNotEmpty()) style.color = DocxMapping.hexToOoxml(color)
                    DocxMapping.fontSizeToHalfPoints(mark.attr("fontSize")?.stringValue)?.let { style.halfPoints = it }
                    mark.attr("fontFamily")?.stringValue?.takeIf { it.isNotEmpty() }?.let { style.font = it }
                }
            }
        }
        return style
    }

    /** `w:rPr`, children in schema order — Word tolerates another order, other suites' validators do not. */
    internal fun runPropertiesXml(style: RunStyle): String {
        if (style.isEmpty) return ""
        val out = StringBuilder()
        style.styleId?.let { out.append(valEl("w:rStyle", it)) }
        style.font?.let { out.append(el("w:rFonts", listOf("w:ascii" to it, "w:hAnsi" to it, "w:cs" to it))) }
        if (style.bold) out.append(el("w:b"))
        if (style.italic) out.append(el("w:i"))
        if (style.strike) out.append(el("w:strike"))
        style.color?.let { out.append(valEl("w:color", it)) }
        style.halfPoints?.let {
            out.append(valEl("w:sz", it.toString()))
            out.append(valEl("w:szCs", it.toString()))
        }
        style.highlight?.let { out.append(valEl("w:highlight", it)) }
        if (style.underline) out.append(valEl("w:u", "single"))
        style.shadingFill?.let { out.append(el("w:shd", listOf("w:val" to "clear", "w:color" to "auto", "w:fill" to it))) }
        style.vertAlign?.let { out.append(valEl("w:vertAlign", it)) }
        return el("w:rPr", emptyList(), out.toString())
    }

    internal fun runXml(style: RunStyle, content: String) = el("w:r", emptyList(), runPropertiesXml(style) + content)

    /** `w:t`, always `xml:space="preserve"` — a run that begins or ends with a space is ordinary. */
    internal fun textEl(text: String, deleted: Boolean = false): String {
        val name = if (deleted) "w:delText" else "w:t"
        return "<$name xml:space=\"preserve\">${XmlText.escape(text)}</$name>"
    }

    // ── Inline content ──────────────────────────────────────────────────────────────────────────

    internal fun inlineXml(nodes: List<DocNode>, context: WriteContext): String {
        val out = StringBuilder()
        for (node in nodes) {
            when (node.type) {
                "text" -> out.append(textRunsXml(node, context))
                "hardBreak" -> out.append(el("w:r", emptyList(), el("w:br")))
                "image" -> out.append(imageXml(node, context))
                "footnote" -> {
                    val number = context.footnoteNumber(node.attr("text")?.stringValue ?: "")
                    out.append(el("w:r", emptyList(),
                        el("w:rPr", emptyList(), valEl("w:rStyle", "FootnoteReference")) +
                            el("w:footnoteReference", listOf("w:id" to number.toString()))))
                }
                "docField" -> out.append(fieldXml(node, context))
                "sheetEmbed", "diagramEmbed" -> out.append(embedXml(node, context))
                // An inline node the mapping does not know: its text still belongs in the document.
                else -> node.content?.let { out.append(inlineXml(it, context)) }
            }
        }
        return out.toString()
    }

    /**
     * A text node as runs. A link, a tracked change and a cross-reference are elements that
     * *contain* the run in OOXML; they nest in the order Word writes, the hyperlink outermost.
     */
    private fun textRunsXml(node: DocNode, context: WriteContext): String {
        val style = runStyle(node.marks)
        val text = node.text ?: ""
        val link = node.mark("link")
        val crossRef = node.mark("crossRef")
        val inserted = node.mark("trackedInsertion")
        val deleted = node.mark("trackedDeletion")
        if (link != null) style.styleId = "Hyperlink"

        val run = when {
            crossRef != null -> {
                val index = context.nextIndex("crossRef")
                val headingText = crossRef.attr("headingText")?.stringValue ?: ""
                context.extras.addCrossRef(headingText, index)
                // A REF field at the bookmark the heading carries, with its cached result so the text survives.
                val instruction = "REF ${context.bookmark(headingText)} \\h"
                el("w:fldSimple", listOf("w:instr" to instruction), runXml(style, textEl(text)))
            }
            inserted != null || deleted != null -> {
                val mark = inserted ?: deleted
                val attributes = listOf(
                    "w:id" to context.nextRevisionId().toString(),
                    "w:author" to (mark?.attr("author")?.stringValue ?: "Unknown"),
                    "w:date" to context.revisionDate,
                )
                el(if (deleted != null) "w:del" else "w:ins", attributes, runXml(style, textEl(text, deleted = deleted != null)))
            }
            else -> runXml(style, textEl(text))
        }

        link ?: return run
        val href = link.attr("href")?.stringValue ?: ""
        return el("w:hyperlink", listOf("r:id" to context.hyperlinkRelationship(href)), run)
    }

    /** A `docField` as a real Word field, so it stays live in Word. */
    private fun fieldXml(node: DocNode, context: WriteContext): String {
        val code = node.attr("code")?.stringValue ?: "title"
        val argument = node.attr("arg")?.stringValue
        val index = context.nextIndex("docField")
        if (node.attr("showCode")?.boolValue == true) context.extras.addFieldShowCode(index)
        val instruction = when {
            code == "custom" && !argument.isNullOrEmpty() -> DocxMapping.docPropertyInstruction(argument)
            DocxMapping.fieldToInstruction[code] != null -> DocxMapping.fieldToInstruction.getValue(code)
            else -> DocxMapping.docPropertyInstruction(argument ?: code)
        }
        return el("w:fldSimple", listOf("w:instr" to instruction))
    }

    /** An embed as the only thing OOXML can honestly carry — its title — with its attributes in the extras. */
    private fun embedXml(node: DocNode, context: WriteContext): String {
        context.extras.addPlaceholder(node.type, node.attrs ?: emptyMap())
        val fallback = if (node.type == "sheetEmbed") "Embedded sheet" else "Embedded diagram"
        return placeholderRunXml("[${node.attr("title")?.stringValue ?: fallback}]")
    }

    /** Italic, and in a character style of its own so it can be found again. */
    private fun placeholderRunXml(text: String): String =
        runXml(RunStyle(styleId = DocxMapping.PLACEHOLDER_STYLE_ID, italic = true), textEl(text))

    /**
     * An image as a real `w:drawing`. An unresolved `neutrino-drive:` reference — a save with no
     * connection — goes out as a placeholder carrying the whole node, and deliberately consumes no
     * image index: the reader counts those off drawings, and an entry with no drawing behind it would
     * shift every later image's extras.
     */
    private fun imageXml(node: DocNode, context: WriteContext): String {
        val src = node.attr("src")?.stringValue ?: ""
        val bytes = imageData(src, context)
        if (bytes == null) {
            context.extras.addPlaceholder("image", node.attrs ?: emptyMap())
            val caption = node.attr("caption")?.stringValue
            return placeholderRunXml(caption?.let { "[$it]" } ?: "[image]")
        }

        val index = context.nextIndex("image")
        val width = node.attr("width")?.doubleValue ?: 480.0
        val height = roundHalfAway(width * 0.6)

        val extra = DocxExtras.ImageExtra(
            shadow = node.attr("shadow")?.stringValue?.takeIf { it != "none" },
            filter = node.attr("imageFilter")?.stringValue?.takeIf { it != "none" },
            caption = node.attr("caption")?.stringValue?.takeIf { it.isNotEmpty() },
            driveRef = src.takeIf { it.startsWith(DocxCodec.DRIVE_REFERENCE_SCHEME) },
        )
        context.extras.addImage(extra, index)

        val relId = context.imageRelationship(src, bytes)
        val drawingId = context.nextDrawing()
        val alt = node.attr("alt")?.stringValue ?: ""
        val cx = DocxMapping.pxToEmu(width).toString()
        val cy = DocxMapping.pxToEmu(height).toString()

        val picture = el("pic:pic", listOf("xmlns:pic" to OoxmlNamespace.PIC),
            el("pic:nvPicPr", emptyList(),
                el("pic:cNvPr", listOf("id" to "0", "name" to "image$drawingId", "descr" to alt)) + el("pic:cNvPicPr")) +
                el("pic:blipFill", emptyList(), el("a:blip", listOf("r:embed" to relId)) + el("a:stretch", emptyList(), el("a:fillRect"))) +
                el("pic:spPr", emptyList(),
                    el("a:xfrm", emptyList(), el("a:off", listOf("x" to "0", "y" to "0")) + el("a:ext", listOf("cx" to cx, "cy" to cy))) +
                        el("a:prstGeom", listOf("prst" to "rect"), el("a:avLst"))))

        val inline = el("wp:inline", listOf("distT" to "0", "distB" to "0", "distL" to "0", "distR" to "0"),
            el("wp:extent", listOf("cx" to cx, "cy" to cy)) +
                el("wp:effectExtent", listOf("l" to "0", "t" to "0", "r" to "0", "b" to "0")) +
                el("wp:docPr", listOf("id" to drawingId.toString(), "name" to "Picture $drawingId", "descr" to alt)) +
                el("wp:cNvGraphicFramePr", emptyList(), el("a:graphicFrameLocks", listOf("xmlns:a" to OoxmlNamespace.A, "noChangeAspect" to "1"))) +
                el("a:graphic", listOf("xmlns:a" to OoxmlNamespace.A), el("a:graphicData", listOf("uri" to OoxmlNamespace.PIC), picture)))

        return el("w:r", emptyList(), el("w:drawing", emptyList(), inline))
    }

    /** What the caller resolved, or the payload of a base64 `data:` URL, which already is the picture. */
    private fun imageData(src: String, context: WriteContext): ByteArray? {
        context.images[src]?.let { return it }
        if (!src.startsWith("data:")) return null
        val comma = src.indexOf(',')
        if (comma < 0 || !src.substring(0, comma).endsWith(";base64")) return null
        return runCatching { Base64.getDecoder().decode(src.substring(comma + 1)) }.getOrNull()
    }

    // ── Block content ───────────────────────────────────────────────────────────────────────────

    internal data class ListPosition(val numId: Int, val level: Int)

    internal fun blockXml(node: DocNode, context: WriteContext, list: ListPosition? = null): List<String> = when (node.type) {
        // Inside a list the indentation belongs to the numbering level, so the level wins.
        "paragraph" -> listOf(paragraphXml(paragraphPropertiesXml(node, list = list, indented = list == null), inlineXml(node.children, context)))

        "heading" -> {
            val level = node.attr("level")?.intValue ?: 1
            // A bookmark on every heading is what makes REF cross-references resolve in Word.
            val name = context.bookmark(node.plainText)
            val bookmarkId = context.nextBookmark().toString()
            val content = el("w:bookmarkStart", listOf("w:id" to bookmarkId, "w:name" to name)) +
                inlineXml(node.children, context) + el("w:bookmarkEnd", listOf("w:id" to bookmarkId))
            listOf(paragraphXml(paragraphPropertiesXml(node, style = DocxMapping.headingStyleId(level)), content))
        }

        "bulletList", "orderedList" -> listXml(node, context)

        // A quote is paragraphs carrying the quote style; a list inside a quote is still a list.
        "blockquote" -> node.children.flatMap { child ->
            if (child.type != "paragraph") blockXml(child, context)
            else listOf(paragraphXml(paragraphPropertiesXml(child, style = DocxMapping.QUOTE_STYLE_ID), inlineXml(child.children, context)))
        }

        // The text verbatim, newlines included: `w:br` would look right in Word and lose the text on the way back.
        "codeBlock" -> listOf(paragraphXml(paragraphPropertiesXml(node, style = DocxMapping.CODE_BLOCK_STYLE_ID), runXml(RunStyle(), textEl(node.plainText))))

        // Block nodes in the schema, so the placeholder gets a paragraph of its own.
        "sheetEmbed", "diagramEmbed" -> listOf(paragraphXml("", embedXml(node, context)))

        "horizontalRule" -> {
            val border = el("w:pBdr", emptyList(), el("w:bottom", listOf("w:val" to "single", "w:sz" to "6", "w:space" to "1", "w:color" to "auto")))
            listOf(paragraphXml(el("w:pPr", emptyList(), border), ""))
        }

        "sectionBreak", "pageBreak" -> listOf(paragraphXml("", el("w:r", emptyList(), el("w:br", listOf("w:type" to "page")))))

        "tableOfContents" -> listOf(tableOfContentsXml())

        "columnLayout" -> {
            val blocks = node.children.flatMap { blockXml(it, context) }
            context.columnLayouts += (node.attr("columns")?.intValue ?: 2) to blocks.size
            blocks
        }

        "table" -> listOf(tableXml(node, context))

        "image" -> listOf(paragraphXml("", imageXml(node, context)))

        // Unknown block: keep its text rather than dropping the node.
        else -> listOf(paragraphXml("", inlineXml(node.children, context)))
    }

    private fun paragraphXml(properties: String, content: String) = el("w:p", emptyList(), properties + content)

    /** `w:pPr`, children in schema order. */
    private fun paragraphPropertiesXml(node: DocNode, style: String? = null, list: ListPosition? = null, indented: Boolean = true): String {
        val out = StringBuilder()
        style?.let { out.append(valEl("w:pStyle", it)) }
        list?.let { out.append(el("w:numPr", emptyList(), valEl("w:ilvl", it.level.toString()) + valEl("w:numId", it.numId.toString()))) }
        if (indented) {
            val level = node.attr("indent")?.intValue
            if (level != null && level > 0) {
                out.append(el("w:ind", listOf("w:left" to DocxMapping.pxToTwip(level * DocxMapping.INDENT_PX_PER_LEVEL).toString())))
            }
        }
        node.attr("textAlign")?.stringValue?.let { align -> DocxMapping.alignmentToOoxml[align]?.let { out.append(valEl("w:jc", it)) } }
        return if (out.isEmpty()) "" else el("w:pPr", emptyList(), out.toString())
    }

    /** A list as paragraphs bound to one numbering definition per distinct list style. */
    private fun listXml(node: DocNode, context: WriteContext, level: Int = 0): List<String> {
        val ordered = node.type == "orderedList"
        val styleType = node.attr("listStyleType")?.stringValue ?: if (ordered) "decimal" else "disc"
        val numId = context.numberingId(ordered, styleType)
        val out = ArrayList<String>()
        for (item in node.children) {
            for (child in item.children) {
                out += if (child.type == "bulletList" || child.type == "orderedList") listXml(child, context, level + 1)
                else blockXml(child, context, ListPosition(numId, level))
            }
        }
        return out
    }

    /** A real TOC field: Word offers to update it, and it reads back as this node. */
    private fun tableOfContentsXml(): String {
        val properties = el("w:sdtPr", emptyList(),
            valEl("w:alias", "Table of Contents") +
                el("w:docPartObj", emptyList(), valEl("w:docPartGallery", "Table of Contents") + el("w:docPartUnique")))
        val field = el("w:p", emptyList(),
            el("w:r", emptyList(), el("w:fldChar", listOf("w:fldCharType" to "begin", "w:dirty" to "true"))) +
                el("w:r", emptyList(), "<w:instrText xml:space=\"preserve\">TOC \\o &quot;1-6&quot; \\h</w:instrText>") +
                el("w:r", emptyList(), el("w:fldChar", listOf("w:fldCharType" to "separate"))) +
                el("w:r", emptyList(), textEl("Right-click and update this table of contents.")) +
                el("w:r", emptyList(), el("w:fldChar", listOf("w:fldCharType" to "end"))))
        return el("w:sdt", emptyList(), properties + el("w:sdtContent", emptyList(), field))
    }

    private fun tableXml(node: DocNode, context: WriteContext): String {
        val properties = el("w:tblPr", emptyList(),
            el("w:tblW", listOf("w:w" to "5000", "w:type" to "pct")) + el("w:tblLayout", listOf("w:type" to "autofit")))
        val rows = node.children.joinToString("") { row ->
            el("w:tr", emptyList(), row.children.joinToString("") { tableCellXml(it, context) })
        }
        // `w:tblGrid` is required and as wide as the widest row, or Word cannot place the cells.
        val columns = node.children.maxOfOrNull { it.children.size } ?: 1
        val grid = el("w:tblGrid", emptyList(), el("w:gridCol").repeat(maxOf(1, columns)))
        return el("w:tbl", emptyList(), properties + grid + rows)
    }

    private fun tableCellXml(cell: DocNode, context: WriteContext): String {
        val properties = StringBuilder()
        val firstWidth = cell.attr("colwidth")?.arrayValue?.firstOrNull()?.doubleValue
        if (firstWidth != null && firstWidth > 0) {
            properties.append(el("w:tcW", listOf("w:w" to DocxMapping.pxToTwip(firstWidth).toString(), "w:type" to "dxa")))
        }
        cell.attr("colspan")?.intValue?.takeIf { it > 1 }?.let { properties.append(valEl("w:gridSpan", it.toString())) }
        cell.attr("rowspan")?.intValue?.takeIf { it > 1 }?.let { properties.append(valEl("w:vMerge", "restart")) }

        val borderColor = DocxMapping.normalizeColor(cell.attr("borderColor")?.stringValue)
        val borderWidth = cell.attr("borderWidth")?.text?.replace("px", "")?.toDoubleOrNull()
        if (borderColor.isNotEmpty() || borderWidth != null) {
            val size = roundHalfAway((borderWidth ?: 1.0) * 8).toInt().toString()
            val color = DocxMapping.hexToOoxml(borderColor.ifEmpty { "#000000" })
            val edges = listOf("w:top", "w:left", "w:bottom", "w:right").joinToString("") {
                el(it, listOf("w:val" to "single", "w:sz" to size, "w:space" to "0", "w:color" to color))
            }
            properties.append(el("w:tcBorders", emptyList(), edges))
        }
        val background = DocxMapping.normalizeColor(cell.attr("backgroundColor")?.stringValue)
        if (background.isNotEmpty()) {
            properties.append(el("w:shd", listOf("w:val" to "clear", "w:color" to "auto", "w:fill" to DocxMapping.hexToOoxml(background))))
        }
        // A cell must hold at least one block, or Word declares the file corrupt.
        val content = cell.children.flatMap { blockXml(it, context) }.joinToString("").ifEmpty { el("w:p") }
        return el("w:tc", emptyList(), (if (properties.isEmpty()) "" else el("w:tcPr", emptyList(), properties.toString())) + content)
    }

    // ── Band parts ──────────────────────────────────────────────────────────────────────────────

    internal data class BandPart(val path: String, val relId: String, val kind: String, val variant: String, val xml: String)

    /** The header and footer parts the bands call for. A variant with nothing in it gets no part. */
    private fun bandParts(meta: DocxMeta, context: WriteContext): List<BandPart> {
        val parts = ArrayList<BandPart>()
        var headers = 0
        var footers = 0
        val width = textWidth(meta)
        for (variant in DocxMeta.Variant.entries) {
            if (!meta.writes(variant)) continue
            val band = meta.band(variant)
            val watermark = if (variant == DocxMeta.Variant.DEFAULT) meta.watermarkText else ""
            if (!band.header.isEmpty || watermark.isNotEmpty()) {
                headers++
                val content = (if (watermark.isEmpty()) "" else watermarkXml(watermark)) + bandParagraphXml(band.header, width)
                parts += BandPart(OoxmlPackage.headerPart(headers), context.relationshipId(), "header", variant.key, bandPartXml("w:hdr", content))
            }
            if (!band.footer.isEmpty) {
                footers++
                parts += BandPart(OoxmlPackage.footerPart(footers), context.relationshipId(), "footer", variant.key,
                    bandPartXml("w:ftr", bandParagraphXml(band.footer, width)))
            }
        }
        return parts
    }

    /** The width of the text column in twips — where a band's centre and right tab stops go. */
    private fun textWidth(meta: DocxMeta): Int {
        val (w, h) = DocxMapping.pageSizeTwips[meta.pageSize] ?: DocxMapping.pageSizeTwips.getValue("letter")
        val pageWidth = if (meta.isLandscape) h else w
        return pageWidth - DocxMapping.ptToTwip(meta.margin("Left")) - DocxMapping.ptToTwip(meta.margin("Right"))
    }

    /** One band as a single centre-tabbed paragraph, as Word's own header styles lay one out. */
    private fun bandParagraphXml(slots: DocxMeta.Slots, width: Int): String {
        val content = StringBuilder()
        fun push(text: String) {
            for ((literal, field) in splitFields(text)) {
                if (field != null) content.append(el("w:fldSimple", listOf("w:instr" to field)))
                else if (literal.isNotEmpty()) content.append(runXml(RunStyle(), textEl(literal)))
            }
        }
        // A tab is a run child, never a paragraph child: Word rejects the latter outright.
        fun tab() = content.append(el("w:r", emptyList(), el("w:tab")))

        push(slots.left)
        if (slots.center.isNotEmpty() || slots.right.isNotEmpty()) tab()
        push(slots.center)
        if (slots.right.isNotEmpty()) tab()
        push(slots.right)

        val tabs = el("w:tabs", emptyList(),
            el("w:tab", listOf("w:val" to "center", "w:pos" to (width / 2).toString())) +
                el("w:tab", listOf("w:val" to "right", "w:pos" to width.toString())))
        return el("w:p", emptyList(), el("w:pPr", emptyList(), tabs) + content)
    }

    /**
     * Band text split into literal runs and field instructions: `{{page}}` in a footer has to become
     * a `PAGE` field, not the text "1". Only a bare token is a field; `{{ not a code }}` is text.
     */
    fun splitFields(text: String): List<Pair<String, String?>> {
        val out = ArrayList<Pair<String, String?>>()
        val literal = StringBuilder()
        var index = 0
        while (index < text.length) {
            val close = if (text.startsWith("{{", index)) text.indexOf("}}", index) else -1
            if (close < 0) {
                literal.append(text[index]); index++; continue
            }
            val name = text.substring(index + 2, close)
            if (name.isEmpty() || !name.all { it.isLetterOrDigit() || it == '_' }) {
                literal.append(text[index]); index++; continue
            }
            if (literal.isNotEmpty()) { out += literal.toString() to null; literal.clear() }
            out += "" to (DocxMapping.fieldToInstruction[name] ?: DocxMapping.docPropertyInstruction(name))
            index = close + 2
        }
        if (literal.isNotEmpty()) out += literal.toString() to null
        return out
    }

    private fun bandPartXml(root: String, content: String) = XmlText.DECLARATION +
        "<$root xmlns:w=\"${OoxmlNamespace.W}\" xmlns:r=\"${OoxmlNamespace.R}\" " +
        "xmlns:v=\"urn:schemas-microsoft-com:vml\" " +
        "xmlns:o=\"urn:schemas-microsoft-com:office:office\">" + content + "</$root>"

    /** A watermark: a VML shape in the header, which Word renders behind the text. */
    private fun watermarkXml(text: String): String {
        val style = "position:absolute;margin-left:0;margin-top:0;width:468pt;height:117pt;" +
            "rotation:315;z-index:-251654144;mso-position-horizontal:center;" +
            "mso-position-horizontal-relative:margin;mso-position-vertical:center;" +
            "mso-position-vertical-relative:margin"
        return el("w:p", emptyList(), el("w:r", emptyList(), el("w:pict", emptyList(),
            el("v:shape", listOf("id" to "NeutrinoWatermark", "o:spid" to "_x0000_s2049", "type" to "#_x0000_t136",
                "style" to style, "fillcolor" to "#c0c0c0", "stroked" to "f"),
                el("v:textpath", listOf("style" to "font-family:\"Calibri\";font-size:1pt", "string" to text))))))
    }

    // ── The document part ───────────────────────────────────────────────────────────────────────

    private fun documentPart(body: String, meta: DocxMeta, bands: List<BandPart>): String {
        val background = if (meta.backgroundColor.isEmpty()) "" else el("w:background", listOf("w:color" to DocxMapping.hexToOoxml(meta.backgroundColor)))
        return XmlText.DECLARATION +
            "<w:document xmlns:w=\"${OoxmlNamespace.W}\" xmlns:r=\"${OoxmlNamespace.R}\" " +
            "xmlns:wp=\"${OoxmlNamespace.WP}\" xmlns:a=\"${OoxmlNamespace.A}\" " +
            "xmlns:pic=\"${OoxmlNamespace.PIC}\" xmlns:v=\"urn:schemas-microsoft-com:vml\" " +
            "xmlns:o=\"urn:schemas-microsoft-com:office:office\">" +
            background + el("w:body", emptyList(), body + sectionPropertiesXml(meta, bands)) + "</w:document>"
    }

    /** The page, its margins and its bands. Landscape swaps the dimensions, not only the attribute. */
    private fun sectionPropertiesXml(meta: DocxMeta, bands: List<BandPart>): String {
        val out = StringBuilder()
        for (band in bands) out.append(el("w:${band.kind}Reference", listOf("w:type" to band.variant, "r:id" to band.relId)))
        val (w, h) = DocxMapping.pageSizeTwips[meta.pageSize] ?: DocxMapping.pageSizeTwips.getValue("letter")
        val width = if (meta.isLandscape) h else w
        val height = if (meta.isLandscape) w else h
        val page = mutableListOf("w:w" to width.toString(), "w:h" to height.toString())
        if (meta.isLandscape) page += "w:orient" to "landscape"
        out.append(el("w:pgSz", page))
        out.append(el("w:pgMar", listOf(
            "w:top" to DocxMapping.ptToTwip(meta.margin("Top")).toString(),
            "w:right" to DocxMapping.ptToTwip(meta.margin("Right")).toString(),
            "w:bottom" to DocxMapping.ptToTwip(meta.margin("Bottom")).toString(),
            "w:left" to DocxMapping.ptToTwip(meta.margin("Left")).toString(),
            "w:header" to DocxMapping.ptToTwip(meta.headerMargin).toString(),
            "w:footer" to DocxMapping.ptToTwip(meta.footerMargin).toString(),
            "w:gutter" to "0",
        )))
        if (meta.differentFirstPage) out.append(el("w:titlePg"))
        return el("w:sectPr", emptyList(), out.toString())
    }

    // ── Supporting parts ────────────────────────────────────────────────────────────────────────

    /** Low and fixed; [WriteContext.relationshipId] numbers from 100 so nothing collides with them. */
    private object FixedRel {
        const val STYLES = "rId1"
        const val NUMBERING = "rId2"
        const val SETTINGS = "rId3"
        const val FOOTNOTES = "rId4"
        const val EXTRAS = "rId5"
        const val DOCUMENT = "rId1"
        const val CORE_PROPS = "rId2"
        const val APP_PROPS = "rId3"
        const val CUSTOM_PROPS = "rId4"
        const val EXTRAS_PROPS = "rId1"
    }

    private fun documentRelsPart(bands: List<BandPart>, context: WriteContext): String {
        val out = StringBuilder()
        out.append(relationship(FixedRel.STYLES, OoxmlRelationship.STYLES, "styles.xml"))
        out.append(relationship(FixedRel.NUMBERING, OoxmlRelationship.NUMBERING, "numbering.xml"))
        out.append(relationship(FixedRel.SETTINGS, OoxmlRelationship.SETTINGS, "settings.xml"))
        out.append(relationship(FixedRel.FOOTNOTES, OoxmlRelationship.FOOTNOTES, "footnotes.xml"))
        if (!context.extras.isEmpty) out.append(relationship(FixedRel.EXTRAS, OoxmlRelationship.CUSTOM_XML, "../${DocxMapping.EXTRAS_PART}"))
        for (band in bands) {
            val type = if (band.kind == "header") OoxmlRelationship.HEADER else OoxmlRelationship.FOOTER
            out.append(relationship(band.relId, type, band.path.removePrefix("word/")))
        }
        for (image in context.imageParts) out.append(relationship(image.relId, OoxmlRelationship.IMAGE, image.path.removePrefix("word/")))
        for ((relId, target) in context.hyperlinks) out.append(relationship(relId, OoxmlRelationship.HYPERLINK, target, external = true))
        return relationshipsPart(out.toString())
    }

    private fun rootRelsPart(hasCustomProps: Boolean): String {
        val out = StringBuilder()
        out.append(relationship(FixedRel.DOCUMENT, OoxmlRelationship.OFFICE_DOCUMENT, "word/document.xml"))
        out.append(relationship(FixedRel.CORE_PROPS, OoxmlRelationship.CORE_PROPERTIES, "docProps/core.xml"))
        out.append(relationship(FixedRel.APP_PROPS, OoxmlRelationship.EXTENDED_PROPERTIES, "docProps/app.xml"))
        if (hasCustomProps) out.append(relationship(FixedRel.CUSTOM_PROPS, OoxmlRelationship.CUSTOM_PROPERTIES, "docProps/custom.xml"))
        return relationshipsPart(out.toString())
    }

    /** Without it Word treats the extras item as an orphan and drops it on the first save. */
    private fun extrasRelsPart(): String =
        relationshipsPart(relationship(FixedRel.EXTRAS_PROPS, OoxmlRelationship.CUSTOM_XML_PROPS, "itemProps1.xml"))

    private fun relationshipsPart(relationships: String) =
        XmlText.DECLARATION + "<Relationships xmlns=\"${OoxmlNamespace.PACKAGE_RELS}\">$relationships</Relationships>"

    private fun relationship(id: String, type: String, target: String, external: Boolean = false): String {
        val attrs = mutableListOf("Id" to id, "Type" to type, "Target" to target)
        if (external) attrs += "TargetMode" to "External"
        return el("Relationship", attrs)
    }

    private fun contentTypesPart(bands: List<BandPart>, context: WriteContext, meta: DocxMeta): String {
        val wordMl = "application/vnd.openxmlformats-officedocument.wordprocessingml"
        val out = StringBuilder()
        out.append(el("Default", listOf("Extension" to "rels", "ContentType" to "application/vnd.openxmlformats-package.relationships+xml")))
        out.append(el("Default", listOf("Extension" to "xml", "ContentType" to "application/xml")))
        // Only the image types actually in the package.
        val extensions = context.imageParts.map { it.path.substringAfterLast('.', "png") }.filter { it.isNotEmpty() }.toSortedSet()
        for (ext in extensions) out.append(el("Default", listOf("Extension" to ext, "ContentType" to "image/$ext")))

        fun override(part: String, type: String) = out.append(el("Override", listOf("PartName" to "/$part", "ContentType" to type)))
        override(OoxmlPackage.DOCUMENT_PART, "$wordMl.document.main+xml")
        override(OoxmlPackage.STYLES_PART, "$wordMl.styles+xml")
        override(OoxmlPackage.NUMBERING_PART, "$wordMl.numbering+xml")
        override(OoxmlPackage.SETTINGS_PART, "$wordMl.settings+xml")
        override(OoxmlPackage.FOOTNOTES_PART, "$wordMl.footnotes+xml")
        for (band in bands) override(band.path, "$wordMl.${band.kind}+xml")
        override(OoxmlPackage.CORE_PROPS_PART, "application/vnd.openxmlformats-package.core-properties+xml")
        override(OoxmlPackage.APP_PROPS_PART, "application/vnd.openxmlformats-officedocument.extended-properties+xml")
        if (meta.hasCustomProperties) override(OoxmlPackage.CUSTOM_PROPS_PART, "application/vnd.openxmlformats-officedocument.custom-properties+xml")
        if (!context.extras.isEmpty) {
            override(DocxMapping.EXTRAS_PART, "application/xml")
            override(DocxMapping.EXTRAS_PROPS_PART, "application/vnd.openxmlformats-officedocument.customXmlProperties+xml")
        }
        return XmlText.DECLARATION + "<Types xmlns=\"${OoxmlNamespace.CONTENT_TYPES}\">$out</Types>"
    }

    private fun settingsPart(meta: DocxMeta): String {
        val out = StringBuilder()
        if (meta.differentEvenOdd) out.append(el("w:evenAndOddHeaders"))
        // Word ignores `w:background` unless the document asks for it to be drawn.
        if (meta.backgroundColor.isNotEmpty()) out.append(el("w:displayBackgroundShape"))
        out.append(el("w:footnotePr", emptyList(), el("w:footnote", listOf("w:id" to "-1")) + el("w:footnote", listOf("w:id" to "0"))))
        return XmlText.DECLARATION + "<w:settings xmlns:w=\"${OoxmlNamespace.W}\">$out</w:settings>"
    }

    private fun footnotesPart(context: WriteContext): String {
        // Ids below 1 are the separator marks Word draws above a note, not notes.
        val out = StringBuilder()
        out.append(el("w:footnote", listOf("w:id" to "-1", "w:type" to "separator"), el("w:p", emptyList(), el("w:r", emptyList(), el("w:separator")))))
        out.append(el("w:footnote", listOf("w:id" to "0", "w:type" to "continuationSeparator"),
            el("w:p", emptyList(), el("w:r", emptyList(), el("w:continuationSeparator")))))
        for ((id, text) in context.footnotes) {
            val paragraph = el("w:p", emptyList(),
                el("w:pPr", emptyList(), valEl("w:pStyle", "FootnoteText")) +
                    el("w:r", emptyList(), el("w:rPr", emptyList(), valEl("w:rStyle", "FootnoteReference")) + el("w:footnoteRef")) +
                    runXml(RunStyle(), textEl(" $text")))
            out.append(el("w:footnote", listOf("w:id" to id.toString()), paragraph))
        }
        return XmlText.DECLARATION + "<w:footnotes xmlns:w=\"${OoxmlNamespace.W}\">$out</w:footnotes>"
    }

    private fun numberingPart(context: WriteContext): String {
        val out = StringBuilder()
        for (definition in context.numbering) {
            val abstractId = definition.numId - 1
            val levels = StringBuilder()
            for (level in 0 until 5) {
                val format = if (definition.ordered) DocxMapping.orderedStyleToNumFmt[definition.styleType] ?: "decimal" else "bullet"
                val text = if (definition.ordered) "%${level + 1}." else DocxMapping.bulletGlyph[definition.styleType] ?: "\u25CF"
                levels.append(el("w:lvl", listOf("w:ilvl" to level.toString()),
                    valEl("w:start", "1") + valEl("w:numFmt", format) + valEl("w:lvlText", text) + valEl("w:lvlJc", "left") +
                        el("w:pPr", emptyList(), el("w:ind", listOf("w:left" to (720 * (level + 1)).toString(), "w:hanging" to "360")))))
            }
            out.append(el("w:abstractNum", listOf("w:abstractNumId" to abstractId.toString()), valEl("w:multiLevelType", "hybridMultilevel") + levels))
        }
        for (definition in context.numbering) {
            out.append(el("w:num", listOf("w:numId" to definition.numId.toString()), valEl("w:abstractNumId", (definition.numId - 1).toString())))
        }
        return XmlText.DECLARATION + "<w:numbering xmlns:w=\"${OoxmlNamespace.W}\">$out</w:numbering>"
    }

    /** The author is written even when empty: a reader finding no `dc:creator` is free to invent one. */
    private fun corePropsPart(title: String, meta: DocxMeta): String {
        val out = "<dc:title>${XmlText.escape(title)}</dc:title>" +
            "<dc:subject>${XmlText.escape(meta.subject)}</dc:subject>" +
            "<dc:creator>${XmlText.escape(meta.author)}</dc:creator>" +
            "<cp:keywords>${XmlText.escape(meta.keywords)}</cp:keywords>" +
            "<dc:description>${XmlText.escape(meta.category)}</dc:description>" +
            "<cp:lastModifiedBy>${XmlText.escape(meta.author)}</cp:lastModifiedBy>"
        return XmlText.DECLARATION +
            "<cp:coreProperties xmlns:cp=\"${OoxmlNamespace.CP}\" xmlns:dc=\"${OoxmlNamespace.DC}\" " +
            "xmlns:dcterms=\"${OoxmlNamespace.DCTERMS}\" xmlns:xsi=\"${OoxmlNamespace.XSI}\">" + out + "</cp:coreProperties>"
    }

    private fun appPropsPart(): String =
        XmlText.DECLARATION + "<Properties xmlns=\"${OoxmlNamespace.EXTENDED_PROPS}\"><Application>Neutrino Docs</Application></Properties>"

    /** `docProps/custom.xml` — where `DOCPROPERTY` looks, so a `{{whatever}}` field resolves in Word. */
    private fun customPropsPart(meta: DocxMeta): String? {
        if (!meta.hasCustomProperties) return null
        val entries = ArrayList<Pair<String, String>>()
        if (meta.company.isNotEmpty()) entries += "company" to meta.company
        if (meta.manager.isNotEmpty()) entries += "manager" to meta.manager
        entries += meta.customProperties
        val out = StringBuilder()
        entries.forEachIndexed { index, (name, value) ->
            // Property ids start at 2 — 0 and 1 are reserved by the format.
            out.append(el("property", listOf("fmtid" to "{D5CDD505-2E9C-101B-9397-08002B2CF9AE}", "pid" to (index + 2).toString(), "name" to name),
                "<vt:lpwstr>${XmlText.escape(value)}</vt:lpwstr>"))
        }
        return XmlText.DECLARATION + "<Properties xmlns=\"${OoxmlNamespace.CUSTOM_PROPERTIES}\" xmlns:vt=\"${OoxmlNamespace.VT}\">$out</Properties>"
    }

    /** Everything the mapping refers to by style id, so a heading read back is still a heading. */
    private fun stylesPart(): String {
        val defaults = el("w:docDefaults", emptyList(),
            el("w:rPrDefault", emptyList(), el("w:rPr", emptyList(), el("w:rFonts", listOf("w:ascii" to "Calibri", "w:hAnsi" to "Calibri")) + valEl("w:sz", "22"))) +
                el("w:pPrDefault", emptyList(), el("w:pPr", emptyList(), el("w:spacing", listOf("w:after" to "160", "w:line" to "259", "w:lineRule" to "auto")))))

        val styles = StringBuilder()
        styles.append(el("w:style", listOf("w:type" to "paragraph", "w:default" to "1", "w:styleId" to "Normal"), valEl("w:name", "Normal")))
        styles.append(el("w:style", listOf("w:type" to "character", "w:default" to "1", "w:styleId" to "DefaultParagraphFont"), valEl("w:name", "Default Paragraph Font")))
        val headingSizes = listOf(32, 26, 24, 22, 22, 22)
        for (level in 1..6) {
            styles.append(el("w:style", listOf("w:type" to "paragraph", "w:styleId" to DocxMapping.headingStyleId(level)),
                valEl("w:name", "heading $level") + valEl("w:basedOn", "Normal") + valEl("w:next", "Normal") +
                    el("w:pPr", emptyList(), el("w:spacing", listOf("w:before" to "240", "w:after" to "120")) + valEl("w:outlineLvl", (level - 1).toString())) +
                    el("w:rPr", emptyList(), el("w:b") + valEl("w:sz", headingSizes[level - 1].toString()) + valEl("w:szCs", headingSizes[level - 1].toString()))))
        }
        styles.append(el("w:style", listOf("w:type" to "character", "w:styleId" to "Hyperlink"),
            valEl("w:name", "Hyperlink") + el("w:rPr", emptyList(), valEl("w:color", "0563C1") + valEl("w:u", "single"))))
        styles.append(el("w:style", listOf("w:type" to "character", "w:styleId" to "FootnoteReference"),
            valEl("w:name", "footnote reference") + el("w:rPr", emptyList(), valEl("w:vertAlign", "superscript"))))
        styles.append(el("w:style", listOf("w:type" to "paragraph", "w:styleId" to "FootnoteText"),
            valEl("w:name", "footnote text") + valEl("w:basedOn", "Normal") + el("w:rPr", emptyList(), valEl("w:sz", "20"))))
        styles.append(el("w:style", listOf("w:type" to "paragraph", "w:styleId" to DocxMapping.QUOTE_STYLE_ID),
            valEl("w:name", "Neutrino Quote") + valEl("w:basedOn", "Normal") + valEl("w:next", "Normal") +
                el("w:pPr", emptyList(), el("w:spacing", listOf("w:before" to "120", "w:after" to "120")) + el("w:ind", listOf("w:left" to "720"))) +
                el("w:rPr", emptyList(), el("w:i") + valEl("w:color", "555555"))))
        styles.append(el("w:style", listOf("w:type" to "paragraph", "w:styleId" to DocxMapping.CODE_BLOCK_STYLE_ID),
            valEl("w:name", "Neutrino Code Block") + valEl("w:basedOn", "Normal") + valEl("w:next", "Normal") +
                el("w:pPr", emptyList(), el("w:spacing", listOf("w:before" to "120", "w:after" to "120"))) +
                // The tint is a run property: shading in `w:pPr` is silently dropped.
                el("w:rPr", emptyList(), el("w:rFonts", listOf("w:ascii" to "Consolas", "w:hAnsi" to "Consolas")) + valEl("w:sz", "20") +
                    el("w:shd", listOf("w:val" to "clear", "w:color" to "auto", "w:fill" to "F5F5F5")))))
        styles.append(el("w:style", listOf("w:type" to "character", "w:styleId" to DocxMapping.CODE_STYLE_ID),
            valEl("w:name", "Neutrino Code") + valEl("w:basedOn", "DefaultParagraphFont") +
                el("w:rPr", emptyList(), el("w:rFonts", listOf("w:ascii" to "Consolas", "w:hAnsi" to "Consolas")) +
                    el("w:shd", listOf("w:val" to "clear", "w:color" to "auto", "w:fill" to "F5F5F5")))))
        styles.append(el("w:style", listOf("w:type" to "character", "w:styleId" to DocxMapping.PLACEHOLDER_STYLE_ID),
            valEl("w:name", "Neutrino Placeholder") + valEl("w:basedOn", "DefaultParagraphFont") + el("w:rPr", emptyList(), el("w:i"))))

        return XmlText.DECLARATION + "<w:styles xmlns:w=\"${OoxmlNamespace.W}\">$defaults$styles</w:styles>"
    }
}

/** Everything the body walk accumulates that the rest of the package needs afterwards. */
internal class WriteContext(val images: Map<String, ByteArray>, val revisionDate: String) {

    data class ImagePart(val path: String, val data: ByteArray, val relId: String)
    data class NumberingDefinition(val numId: Int, val ordered: Boolean, val styleType: String)

    val extras = DocxExtras()
    val columnLayouts = ArrayList<Pair<Int, Int>>()
    val footnotes = ArrayList<Pair<Int, String>>()
    val numbering = ArrayList<NumberingDefinition>()
    val imageParts = ArrayList<ImagePart>()
    val hyperlinks = ArrayList<Pair<String, String>>()

    private val numberingKeys = HashMap<String, Int>()
    private val imageRels = HashMap<String, String>()
    private val hyperlinkRels = HashMap<String, String>()
    private val bookmarks = HashMap<String, String>()
    private val counters = HashMap<String, Int>()
    private var nextRelId = 100
    private var nextRevision = 0
    private var nextBookmarkId = 0
    private var nextDrawingId = 0

    /** The next index for a kind of node — how the extras address it. */
    fun nextIndex(kind: String): Int {
        val index = counters[kind] ?: 0
        counters[kind] = index + 1
        return index
    }

    fun nextRevisionId(): Int = ++nextRevision
    fun nextBookmark(): Int = ++nextBookmarkId
    fun nextDrawing(): Int = ++nextDrawingId

    /** Numbered from 100, clear of the fixed low ids every package's standard parts use. */
    fun relationshipId(): String = "rId${++nextRelId}"

    /** The same bookmark name for the same heading text, which is what makes a `REF` field resolve. */
    fun bookmark(headingText: String): String {
        val key = headingText.trim().ifEmpty { "_top" }
        return bookmarks.getOrPut(key) { "_Nx${bookmarks.size}" }
    }

    fun footnoteNumber(text: String): Int {
        val id = footnotes.size + 1
        footnotes += id to text
        return id
    }

    fun numberingId(ordered: Boolean, styleType: String): Int {
        val key = "${if (ordered) "o" else "b"}:$styleType"
        numberingKeys[key]?.let { return it }
        val numId = numbering.size + 1
        numberingKeys[key] = numId
        numbering += NumberingDefinition(numId, ordered, styleType)
        return numId
    }

    fun imageRelationship(src: String, data: ByteArray): String {
        imageRels[src]?.let { return it }
        val relId = relationshipId()
        imageParts += ImagePart("word/media/image${imageParts.size + 1}.${extension(data)}", data, relId)
        imageRels[src] = relId
        return relId
    }

    fun hyperlinkRelationship(target: String): String {
        hyperlinkRels[target]?.let { return it }
        val relId = relationshipId()
        hyperlinkRels[target] = relId
        hyperlinks += relId to target
        return relId
    }

    companion object {
        /** The format of image bytes, read from their own leading bytes, never from the `src`. */
        fun extension(data: ByteArray): String {
            if (data.size < 3) return "png"
            val b0 = data[0].toInt() and 0xFF
            val b1 = data[1].toInt() and 0xFF
            val b2 = data[2].toInt() and 0xFF
            return when {
                b0 == 0xFF && b1 == 0xD8 -> "jpeg"
                b0 == 0x47 && b1 == 0x49 && b2 == 0x46 -> "gif"
                b0 == 0x42 && b1 == 0x4D -> "bmp"
                else -> "png"
            }
        }
    }
}
