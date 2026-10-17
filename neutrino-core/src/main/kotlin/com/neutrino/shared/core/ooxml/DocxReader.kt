package com.neutrino.shared.core.ooxml

import java.util.Base64

sealed class DocxException(message: String) : Exception(message) {
    /** The bytes are a zip, but not a Word document — no `word/document.xml`. */
    class NotADocument : DocxException("This file is not a Word document.")
}

/**
 * A `.docx` → Neutrino's document model. The Kotlin half of `DocxReader*.swift`, and the other half
 * of [DocxWriter].
 *
 * Anything it does not recognise degrades to its text rather than vanishing: a `.docx` from Word is
 * written by software that knows constructs this does not, and losing a paragraph is much worse
 * than losing its styling.
 *
 * Known gap, shared with the web and iOS readers on purpose: `styles.xml` is not resolved, so a
 * heading is a heading because its style is spelled `Heading2`.
 */
object DocxReader {

    /** Never throws for a document it only partly understands; an unreadable part yields its default. */
    fun read(data: ByteArray): DocModel {
        val archive = ZipArchive(data)
        val documentXml = archive.text(OoxmlPackage.DOCUMENT_PART) ?: throw DocxException.NotADocument()
        val document = XmlElement.parse(documentXml) ?: throw DocxException.NotADocument()

        val rels = relationships(archive, OoxmlPackage.DOCUMENT_PART)
        val context = ReadContext(numbering(archive), footnotes(archive), DocxExtras.read(archive), rels, media(archive, rels))

        val body = document.element("body")
        var content = body?.let { blocks(it, context) } ?: emptyList()
        val sectPr = body?.element("sectPr")
        val bands = bands(archive, sectPr, rels)
        val background = DocxMapping.ooxmlToHex(document.element("background")?.attribute("color", OoxmlNamespace.W))

        // Placeholders last: nodes the writer had to replace with text, matched back by position.
        val cursor = intArrayOf(0)
        content = restorePlaceholders(content, context.extras, cursor)

        val meta = metaValue(bands, background, context.extras, properties(archive), pageSetup(sectPr))
        return DocModel(DocNode(type = "doc", content = content), meta)
    }

    /** Whether `data` could be a `.docx` at all — a zip holding a `word/document.xml`. */
    fun isDocument(data: ByteArray): Boolean {
        if (!ZipArchive.looksLikeArchive(data)) return false
        return runCatching { OoxmlPackage.DOCUMENT_PART in ZipArchive(data) }.getOrDefault(false)
    }

    // ── Read context ────────────────────────────────────────────────────────────────────────────

    internal data class ListStyle(val ordered: Boolean, val styleType: String)

    internal class ReadContext(
        val numbering: Map<String, ListStyle>,
        val footnotes: Map<String, String>,
        val extras: DocxExtras,
        val rels: Map<String, String>,
        /** Relationship id → the `data:` URL its media part decodes to. */
        val media: Map<String, String>,
    ) {
        private val counters = HashMap<String, Int>()

        fun nextIndex(kind: String): Int {
            val index = counters[kind] ?: 0
            counters[kind] = index + 1
            return index
        }
    }

    /** Node types that exist only between parsing and regrouping; none reaches the model. */
    private const val PAGE_BREAK = "__pageBreak"
    private const val PLACEHOLDER = "__placeholder"

    // ── Package parts ───────────────────────────────────────────────────────────────────────────

    private fun relationships(archive: ZipArchive, part: String): Map<String, String> {
        val root = archive.text(OoxmlPackage.relsPart(part))?.let(XmlElement::parse) ?: return emptyMap()
        val out = HashMap<String, String>()
        for (relationship in root.elements("Relationship")) {
            val id = relationship.attribute("Id") ?: continue
            val target = relationship.attribute("Target") ?: continue
            out[id] = target
        }
        return out
    }

    /** Every image the document relates to, as a `data:` URL: a caller that dropped the package cannot resolve a part path. */
    private fun media(archive: ZipArchive, rels: Map<String, String>): Map<String, String> {
        val out = HashMap<String, String>()
        for ((id, target) in rels) {
            val path = OoxmlPackage.resolve(target, "word")
            if (!path.startsWith("word/media/")) continue
            val data = archive.data(path) ?: continue
            val mime = when (path.substringAfterLast('.', "").lowercase()) {
                "png" -> "image/png"
                "gif" -> "image/gif"
                "svg" -> "image/svg+xml"
                "bmp" -> "image/bmp"
                else -> "image/jpeg"
            }
            out[id] = "data:$mime;base64,${Base64.getEncoder().encodeToString(data)}"
        }
        return out
    }

    /** `numId` → what the list looks like, through its `w:abstractNum`. Only level 0 is read. */
    private fun numbering(archive: ZipArchive): Map<String, ListStyle> {
        val root = archive.text(OoxmlPackage.NUMBERING_PART)?.let(XmlElement::parse) ?: return emptyMap()
        val abstract = HashMap<String, ListStyle>()
        for (definition in root.elements("abstractNum")) {
            val id = definition.attribute("abstractNumId", OoxmlNamespace.W) ?: continue
            val levels = definition.elements("lvl")
            val level = levels.firstOrNull { it.attribute("ilvl", OoxmlNamespace.W) == "0" } ?: levels.firstOrNull() ?: continue
            val format = level.element("numFmt")?.value ?: "decimal"
            abstract[id] = if (format == "bullet") {
                val glyph = level.element("lvlText")?.value ?: "●"
                ListStyle(false, DocxMapping.glyphToBullet[glyph] ?: "disc")
            } else {
                ListStyle(true, DocxMapping.numFmtToOrderedStyle[format] ?: "decimal")
            }
        }
        val out = HashMap<String, ListStyle>()
        for (num in root.elements("num")) {
            val numId = num.attribute("numId", OoxmlNamespace.W) ?: continue
            val abstractId = num.element("abstractNumId")?.value ?: continue
            out[numId] = abstract[abstractId] ?: continue
        }
        return out
    }

    private fun footnotes(archive: ZipArchive): Map<String, String> {
        val root = archive.text(OoxmlPackage.FOOTNOTES_PART)?.let(XmlElement::parse) ?: return emptyMap()
        val out = HashMap<String, String>()
        for (note in root.elements("footnote")) {
            val id = note.attribute("id", OoxmlNamespace.W) ?: continue
            // Word reserves 0 and -1 for the separator marks; they are not notes.
            if ((id.toIntOrNull() ?: 0) < 1) continue
            out[id] = note.elements("p").joinToString("\n") { paragraphText(it) }.trim()
        }
        return out
    }

    private fun paragraphText(paragraph: XmlElement): String {
        val text = StringBuilder()
        fun walk(element: XmlElement) {
            for (child in element.children) {
                when (child.localName) {
                    "t", "delText" -> text.append(child.textContent)
                    "tab" -> text.append('\t')
                    else -> walk(child)
                }
            }
        }
        walk(paragraph)
        return text.toString()
    }

    private fun properties(archive: ZipArchive): Map<String, DocValue> {
        var author = ""; var subject = ""; var keywords = ""; var category = ""
        var company = ""; var manager = ""
        val custom = LinkedHashMap<String, DocValue>()
        archive.text(OoxmlPackage.CORE_PROPS_PART)?.let(XmlElement::parse)?.let { root ->
            author = root.descendant("creator")?.textContent ?: ""
            subject = root.descendant("subject")?.textContent ?: ""
            keywords = root.descendant("keywords")?.textContent ?: ""
            category = root.descendant("description")?.textContent ?: ""
        }
        archive.text(OoxmlPackage.CUSTOM_PROPS_PART)?.let(XmlElement::parse)?.let { root ->
            for (property in root.elements("property")) {
                val name = property.attribute("name") ?: continue
                val value = property.children.firstOrNull()?.textContent ?: ""
                when (name) {
                    "company" -> company = value
                    "manager" -> manager = value
                    else -> custom[name] = docString(value)
                }
            }
        }
        return mapOf(
            "author" to docString(author), "subject" to docString(subject), "company" to docString(company),
            "category" to docString(category), "keywords" to docString(keywords), "manager" to docString(manager),
            "custom" to docObject(custom),
        )
    }

    // ── Section properties, headers and footers ─────────────────────────────────────────────────

    private class Bands {
        var differentFirstPage = false
        var differentEvenOdd = false
        var headerMargin = 36.0
        var footerMargin = 36.0
        val variants = HashMap<String, Pair<DocxMeta.Slots, DocxMeta.Slots>>()
        var watermark = ""
    }

    private fun pageSetup(sectPr: XmlElement?): Map<String, DocValue> {
        sectPr ?: return DocxMapping.defaultPageSetup
        val size = sectPr.element("pgSz")
        val margins = sectPr.element("pgMar")
        val width = size?.attribute("w", OoxmlNamespace.W)?.toIntOrNull() ?: 0
        val height = size?.attribute("h", OoxmlNamespace.W)?.toIntOrNull() ?: 0
        val orient = size?.attribute("orient", OoxmlNamespace.W)
        val landscape = orient == "landscape" || (orient == null && width > height)

        fun margin(name: String, fallback: Double): DocValue {
            val twips = margins?.attribute(name, OoxmlNamespace.W)?.toDoubleOrNull() ?: return docDouble(fallback)
            val points = DocxMapping.twipToPt(twips)
            // Whole points re-encode as integers, so an untouched document compares equal to its source.
            return if (points == roundHalfAway(points)) docInt(points.toInt()) else docDouble(points)
        }

        return mapOf(
            "pageSize" to docString(DocxMapping.pageSize(width, height) ?: "letter"),
            "orientation" to docString(if (landscape) "landscape" else "portrait"),
            "marginTop" to margin("top", 72.0),
            "marginBottom" to margin("bottom", 72.0),
            "marginLeft" to margin("left", 72.0),
            "marginRight" to margin("right", 72.0),
        )
    }

    private fun bands(archive: ZipArchive, sectPr: XmlElement?, rels: Map<String, String>): Bands {
        val bands = Bands()
        sectPr ?: return bands
        bands.differentFirstPage = sectPr.element("titlePg")?.isToggleOn ?: false
        sectPr.element("pgMar")?.let { margins ->
            margins.attribute("header", OoxmlNamespace.W)?.toDoubleOrNull()?.let { bands.headerMargin = DocxMapping.twipToPt(it) }
            margins.attribute("footer", OoxmlNamespace.W)?.toDoubleOrNull()?.let { bands.footerMargin = DocxMapping.twipToPt(it) }
        }
        archive.text(OoxmlPackage.SETTINGS_PART)?.let(XmlElement::parse)?.let { root ->
            bands.differentEvenOdd = root.element("evenAndOddHeaders")?.isToggleOn ?: false
        }
        for (kind in listOf("header", "footer")) {
            for (reference in sectPr.elements("${kind}Reference")) {
                val variant = reference.attribute("type", OoxmlNamespace.W) ?: "default"
                val relId = reference.attribute("id", OoxmlNamespace.R) ?: continue
                val target = rels[relId] ?: continue
                val root = archive.text(OoxmlPackage.resolve(target, "word"))?.let(XmlElement::parse) ?: continue
                val pair = bands.variants[variant] ?: (DocxMeta.Slots() to DocxMeta.Slots())
                bands.variants[variant] = if (kind == "header") {
                    if (bands.watermark.isEmpty()) bands.watermark = root.descendant("textpath")?.attribute("string") ?: ""
                    slots(root) to pair.second
                } else {
                    pair.first to slots(root)
                }
            }
        }
        return bands
    }

    /** A band back into three slots: written as one paragraph with centre and right tab stops. */
    private fun slots(root: XmlElement): DocxMeta.Slots {
        // A watermark is a paragraph of its own before the band.
        val paragraph = root.elements("p").firstOrNull { it.descendant("pict") == null } ?: root.elements("p").firstOrNull()
            ?: return DocxMeta.Slots()
        val parts = mutableListOf(StringBuilder())
        for (element in paragraph.children) {
            if (element.localName == "fldSimple") {
                val instruction = (element.attribute("instr", OoxmlNamespace.W) ?: "").trim()
                fieldCode(instruction)?.let { parts.last().append("{{$it}}") }
                continue
            }
            if (element.localName != "r") continue
            for (child in element.children) {
                if (child.localName == "tab") parts += StringBuilder()
                else if (child.localName == "t") parts.last().append(child.textContent)
            }
        }
        return DocxMeta.Slots(parts.getOrNull(0)?.toString() ?: "", parts.getOrNull(1)?.toString() ?: "", parts.getOrNull(2)?.toString() ?: "")
    }

    /** The `{{token}}` a field instruction came from, or null for one nothing writes. */
    fun fieldCode(instruction: String): String? {
        if (instruction.uppercase().startsWith("DOCPROPERTY ")) {
            return instruction.substring("DOCPROPERTY ".length).trim().ifEmpty { null }
        }
        val head = instruction.split(' ').firstOrNull { it.isNotEmpty() } ?: instruction
        return DocxMapping.instructionToField[head]
    }

    /** The `_meta` block, in the shape every Neutrino client stores it in. */
    private fun metaValue(bands: Bands, background: String, extras: DocxExtras, properties: Map<String, DocValue>, pageSetup: Map<String, DocValue>): DocValue {
        fun slotsValue(s: DocxMeta.Slots) = docObject("left" to docString(s.left), "center" to docString(s.center), "right" to docString(s.right))
        val variants = LinkedHashMap<String, DocValue>()
        for (variant in DocxMeta.Variant.entries) {
            val (header, footer) = bands.variants[variant.key] ?: (DocxMeta.Slots() to DocxMeta.Slots())
            variants[variant.key] = docObject("header" to slotsValue(header), "footer" to slotsValue(footer))
        }
        val headerFooter = docObject(
            "differentFirstPage" to docBool(bands.differentFirstPage),
            "differentEvenOdd" to docBool(bands.differentEvenOdd),
            "headerMargin" to docInt(roundHalfAway(bands.headerMargin).toInt()),
            "footerMargin" to docInt(roundHalfAway(bands.footerMargin).toInt()),
            "variants" to docObject(variants),
        )
        val (defaultHeader, defaultFooter) = bands.variants["default"] ?: (DocxMeta.Slots() to DocxMeta.Slots())
        val pageNumberSource = defaultFooter.center + defaultHeader.right
        return docObject(
            "headerFooter" to headerFooter,
            // The flattened legacy view of the default variant, for builds without the band editor.
            "headerText" to docString(defaultHeader.center),
            "footerText" to docString(defaultFooter.center),
            "showPageNumbers" to docBool("{{page}}" in pageNumberSource),
            "watermarkText" to docString(bands.watermark),
            "bgColor" to docString(background),
            "docTheme" to docString(extras.theme ?: "default"),
            "properties" to docObject(properties),
            "pageSetup" to docObject(pageSetup),
        )
    }

    // ── Runs and marks ──────────────────────────────────────────────────────────────────────────

    private fun marks(rPr: XmlElement?): List<DocMark> {
        rPr ?: return emptyList()
        val marks = ArrayList<DocMark>()
        for ((element, mark) in listOf("b" to "bold", "i" to "italic", "u" to "underline", "strike" to "strike")) {
            val node = rPr.element(element) ?: continue
            if (element == "u") {
                // `w:u` names an underline style; `none` means off.
                if ((node.value ?: "single") != "none") marks += DocMark(mark)
                continue
            }
            if (node.isToggleOn) marks += DocMark(mark)
        }
        when (rPr.element("vertAlign")?.value) {
            "superscript" -> marks += DocMark("superscript")
            "subscript" -> marks += DocMark("subscript")
        }
        if (rPr.element("rStyle")?.value == DocxMapping.CODE_STYLE_ID) marks += DocMark("code")

        val textStyle = LinkedHashMap<String, DocValue>()
        val color = DocxMapping.ooxmlToHex(rPr.element("color")?.value)
        if (color.isNotEmpty()) textStyle["color"] = docString(color)
        rPr.element("sz")?.value?.toIntOrNull()?.takeIf { it > 0 }?.let { textStyle["fontSize"] = docString(DocxMapping.halfPointsToFontSize(it)) }
        rPr.element("rFonts")?.let { fonts ->
            (fonts.attribute("ascii", OoxmlNamespace.W) ?: fonts.attribute("hAnsi", OoxmlNamespace.W))?.let { textStyle["fontFamily"] = docString(it) }
        }
        if (textStyle.isNotEmpty()) marks += DocMark("textStyle", textStyle)

        val highlight = rPr.element("highlight")?.value
        if (highlight != null && highlight != "none") {
            marks += DocMark("highlight", mapOf("color" to docString(DocxMapping.nameToHighlight[highlight] ?: highlight)))
        } else {
            val fill = DocxMapping.ooxmlToHex(rPr.element("shd")?.attribute("fill", OoxmlNamespace.W))
            if (fill.isNotEmpty() && fill != "#ffffff") marks += DocMark("highlight", mapOf("color" to docString(fill)))
        }
        return marks
    }

    /** Inline children of a paragraph, in order. */
    private fun inline(parent: XmlElement, context: ReadContext, extraMarks: List<DocMark> = emptyList()): List<DocNode> {
        val out = ArrayList<DocNode>()

        fun pushText(text: String, marks: List<DocMark>) {
            if (text.isEmpty()) return
            // Word splits runs freely; adjacent runs with identical marks are one text node.
            val last = out.lastOrNull()
            if (last != null && last.type == "text" && (last.marks ?: emptyList()) == marks) {
                out[out.lastIndex] = last.copy(text = (last.text ?: "") + text)
                return
            }
            out += DocNode.text(text, marks.takeIf { it.isNotEmpty() })
        }

        for (element in parent.children) {
            when (element.localName) {
                "r" -> {
                    val rPr = element.element("rPr")
                    val marks = marks(rPr) + extraMarks
                    // Kept as a node of its own so it cannot merge into an adjacent italic run.
                    val isPlaceholder = rPr?.element("rStyle")?.value == DocxMapping.PLACEHOLDER_STYLE_ID
                    for (child in element.children) {
                        when (child.localName) {
                            "t", "delText" -> if (isPlaceholder) out += DocNode(type = PLACEHOLDER, text = child.textContent)
                            else pushText(child.textContent, marks)
                            "tab" -> pushText("\t", marks)
                            "br" -> out += if (child.attribute("type", OoxmlNamespace.W) == "page") DocNode(type = PAGE_BREAK) else DocNode(type = "hardBreak")
                            "footnoteReference" -> {
                                val id = child.attribute("id", OoxmlNamespace.W) ?: ""
                                out += DocNode(type = "footnote", attrs = mapOf("id" to docString("fn-$id"), "text" to docString(context.footnotes[id] ?: "")))
                            }
                            "drawing" -> out += image(child, context)
                        }
                    }
                }
                "hyperlink" -> {
                    val relId = element.attribute("id", OoxmlNamespace.R)
                    val href = if (relId != null) context.rels[relId] ?: "" else "#${element.attribute("anchor", OoxmlNamespace.W) ?: ""}"
                    for (node in inline(element, context, extraMarks)) {
                        out += if (node.type == "text") node.copy(marks = (node.marks ?: emptyList()) + DocMark("link", mapOf("href" to docString(href)))) else node
                    }
                }
                "ins", "del" -> {
                    val type = if (element.localName == "ins") "trackedInsertion" else "trackedDeletion"
                    val author = element.attribute("author", OoxmlNamespace.W)
                    out += inline(element, context, extraMarks + DocMark(type, mapOf("author" to (author?.let(::docString) ?: docNull))))
                }
                "fldSimple" -> out += field(element, context)
                "bookmarkStart", "bookmarkEnd", "proofErr" -> {}
                // A content control, a smart tag: its runs are still content.
                else -> if (element.children.isNotEmpty()) out += inline(element, context, extraMarks)
            }
        }
        return out
    }

    /** A `REF` field is a cross-reference on its cached text; everything else is a `docField`. */
    private fun field(element: XmlElement, context: ReadContext): DocNode {
        val instruction = (element.attribute("instr", OoxmlNamespace.W) ?: "").trim()
        val cached = inline(element, context).joinToString("") { it.text ?: "" }
        if (instruction.startsWith("REF ")) {
            val headingText = context.extras.crossRef(context.nextIndex("crossRef")) ?: ""
            return DocNode.text(cached, listOf(DocMark("crossRef", mapOf("headingText" to docString(headingText)))))
        }
        val showCode = context.extras.showsCode(context.nextIndex("docField"))
        if (instruction.uppercase().startsWith("DOCPROPERTY ")) {
            val name = instruction.substring("DOCPROPERTY ".length).trim()
            return DocNode(type = "docField", attrs = mapOf("code" to docString("custom"), "arg" to docString(name), "showCode" to docBool(showCode)))
        }
        val head = instruction.split(' ').firstOrNull { it.isNotEmpty() } ?: instruction
        val code = DocxMapping.instructionToField[head] ?: "title"
        return DocNode(type = "docField", attrs = mapOf("code" to docString(code), "arg" to docNull, "showCode" to docBool(showCode)))
    }

    /** A drawing back to an image node; the Drive reference wins over the embedded bytes. */
    private fun image(element: XmlElement, context: ReadContext): DocNode {
        val extra = context.extras.image(context.nextIndex("image"))
        val relId = element.descendant("blip")?.attribute("embed", OoxmlNamespace.R)
        val media = relId?.let { context.media[it] }
        val attrs = LinkedHashMap<String, DocValue>()
        attrs["src"] = docString(extra?.driveRef ?: media ?: "")
        element.descendant("extent")?.attribute("cx")?.toDoubleOrNull()?.let {
            attrs["width"] = docString(roundHalfAway(DocxMapping.emuToPx(it)).toInt().toString())
        }
        // Alt text is real OOXML, so a picture described in Word arrives described.
        element.descendant("docPr")?.attribute("descr")?.takeIf { it.isNotEmpty() }?.let { attrs["alt"] = docString(it) }
        extra?.shadow?.let { attrs["shadow"] = docString(it) }
        extra?.filter?.let { attrs["imageFilter"] = docString(it) }
        extra?.caption?.let { attrs["caption"] = docString(it) }
        return DocNode(type = "image", attrs = attrs)
    }

    // ── Paragraphs and blocks ───────────────────────────────────────────────────────────────────

    private data class ParsedBlock(val node: DocNode, val list: Pair<String, Int>? = null)

    private fun paragraph(element: XmlElement, context: ReadContext): List<ParsedBlock> {
        val pPr = element.element("pPr")
        val styleId = pPr?.element("pStyle")?.value
        val content = inline(element, context)

        // A paragraph that is only page breaks is section breaks, not an empty paragraph holding them.
        val breaks = content.count { it.type == PAGE_BREAK }
        if (breaks > 0 && breaks == content.size) return List(breaks) { ParsedBlock(DocNode(type = "sectionBreak")) }
        val cleaned = content.filter { it.type != PAGE_BREAK }

        // An empty paragraph with a bottom border is OOXML's only way to draw a rule.
        if (cleaned.isEmpty() && pPr?.element("pBdr")?.element("bottom") != null) return listOf(ParsedBlock(DocNode(type = "horizontalRule")))

        val attrs = LinkedHashMap<String, DocValue>()
        pPr?.element("jc")?.value?.let { jc -> DocxMapping.ooxmlToAlignment[jc]?.let { attrs["textAlign"] = docString(it) } }

        val numPr = pPr?.element("numPr")
        // Not read off a numbered paragraph: there `w:ind` is the numbering level's own indentation.
        if (numPr == null) {
            pPr?.element("ind")?.attribute("left", OoxmlNamespace.W)?.toDoubleOrNull()?.let { twips ->
                val level = roundHalfAway(DocxMapping.twipToPx(twips) / DocxMapping.INDENT_PX_PER_LEVEL).toInt()
                if (level > 0) attrs["indent"] = docInt(level)
            }
        }

        if (styleId == DocxMapping.CODE_BLOCK_STYLE_ID) {
            val text = cleaned.joinToString("") { it.text ?: "" }
            return listOf(ParsedBlock(DocNode(type = "codeBlock", content = if (text.isEmpty()) emptyList() else listOf(DocNode.text(text)))))
        }
        if (styleId == DocxMapping.QUOTE_STYLE_ID) {
            val paragraph = DocNode(type = "paragraph", attrs = attrs.takeIf { it.isNotEmpty() }, content = cleaned)
            return listOf(ParsedBlock(DocNode(type = "blockquote", content = listOf(paragraph))))
        }

        val headingLevel = DocxMapping.headingLevel(styleId)
        val node = if (headingLevel != null) {
            DocNode(type = "heading", attrs = attrs + ("level" to docInt(headingLevel)), content = cleaned)
        } else {
            // No attributes rather than an empty set: `attrs: {}` compares unequal to the paragraph
            // the editor builds on Return, which it reads as an unsaved change.
            DocNode(type = "paragraph", attrs = attrs.takeIf { it.isNotEmpty() }, content = cleaned)
        }
        val list = numPr?.let { (it.element("numId")?.value ?: "") to (it.element("ilvl")?.value?.toIntOrNull() ?: 0) }
        return listOf(ParsedBlock(node, list))
    }

    private fun table(element: XmlElement, context: ReadContext): DocNode {
        val rows = element.elements("tr").map { row ->
            val cells = row.elements("tc").map { cell ->
                val tcPr = cell.element("tcPr")
                // Every attribute the cell node declares, present whether or not the package said
                // anything: the editor serialises its `null` defaults, and omitting a key would make a
                // plain cell read back as a different node.
                val attrs = linkedMapOf(
                    "colspan" to docInt(tcPr?.element("gridSpan")?.value?.toIntOrNull() ?: 1),
                    "rowspan" to docInt(1),
                    "colwidth" to docNull,
                    "backgroundColor" to docNull,
                    "borderColor" to docNull,
                    "borderWidth" to docNull,
                )
                tcPr?.element("tcW")?.let { width ->
                    if (width.attribute("type", OoxmlNamespace.W) == "dxa") {
                        width.attribute("w", OoxmlNamespace.W)?.toDoubleOrNull()?.let {
                            attrs["colwidth"] = docArray(listOf(docInt(roundHalfAway(DocxMapping.twipToPx(it)).toInt())))
                        }
                    }
                }
                val fill = DocxMapping.ooxmlToHex(tcPr?.element("shd")?.attribute("fill", OoxmlNamespace.W))
                if (fill.isNotEmpty()) attrs["backgroundColor"] = docString(fill)
                tcPr?.element("tcBorders")?.element("top")?.let { top ->
                    val color = DocxMapping.ooxmlToHex(top.attribute("color", OoxmlNamespace.W))
                    if (color.isNotEmpty()) attrs["borderColor"] = docString(color)
                    top.attribute("sz", OoxmlNamespace.W)?.toDoubleOrNull()?.let { size ->
                        val px = size / 8
                        attrs["borderWidth"] = docString(if (px == roundHalfAway(px)) "${px.toInt()}px" else "${px}px")
                    }
                }
                DocNode(type = "tableCell", attrs = attrs, content = blocks(cell, context))
            }
            DocNode(type = "tableRow", content = cells)
        }
        return DocNode(type = "table", content = rows)
    }

    /** Every block child of `parent`, with lists regrouped and extras reapplied. */
    private fun blocks(parent: XmlElement, context: ReadContext): List<DocNode> {
        val parsed = ArrayList<ParsedBlock>()
        for (element in parent.children) {
            when (element.localName) {
                "p" -> parsed += paragraph(element, context)
                "tbl" -> parsed += ParsedBlock(table(element, context))
                // The only tag the writer emits is the TOC; anything else wraps blocks that still belong here.
                "sdt" -> if (element.element("sdtPr")?.element("alias")?.value == "Table of Contents") {
                    parsed += ParsedBlock(DocNode(type = "tableOfContents"))
                } else {
                    element.element("sdtContent")?.let { c -> parsed += blocks(c, context).map { ParsedBlock(it) } }
                }
            }
        }
        return groupColumnLayouts(mergeQuotes(groupLists(parsed, context)), context)
    }

    /** Consecutive quote paragraphs folded into one blockquote, so a quote does not splinter with every save. */
    private fun mergeQuotes(blocks: List<DocNode>): List<DocNode> {
        val out = ArrayList<DocNode>()
        for (node in blocks) {
            val last = out.lastOrNull()
            if (node.type == "blockquote" && last != null && last.type == "blockquote") {
                out[out.lastIndex] = last.copy(content = last.children + node.children)
                continue
            }
            out += node
        }
        return out
    }

    /**
     * List paragraphs folded back into lists, nesting carried by `w:ilvl`. A run does not end at the
     * first change of `w:numId`: a deeper paragraph continues it whatever numbering it uses, and only
     * one back at the run's level under different numbering starts a new list.
     */
    private fun groupLists(blocks: List<ParsedBlock>, context: ReadContext): List<DocNode> {
        val out = ArrayList<DocNode>()
        var index = 0
        while (index < blocks.size) {
            val list = blocks[index].list
            if (list == null) {
                out += blocks[index].node
                index++
                continue
            }
            val (numId, baseLevel) = list
            val run = ArrayList<ParsedBlock>()
            while (index < blocks.size) {
                val current = blocks[index].list ?: break
                if (current.second <= baseLevel && current.first != numId) break
                run += blocks[index]
                index++
            }
            out += buildLists(run, baseLevel, context)
        }
        return out
    }

    private fun buildLists(run: List<ParsedBlock>, level: Int, context: ReadContext): List<DocNode> {
        val out = ArrayList<DocNode>()
        var index = 0
        while (index < run.size) {
            val numId = run[index].list?.first ?: ""
            val style = context.numbering[numId]
            val items = ArrayList<DocNode>()
            while (index < run.size) {
                val current = run[index].list ?: break
                if (current.first != numId || current.second > level) break
                val children = mutableListOf(run[index].node)
                index++
                // Anything deeper belongs inside the item just added.
                val nested = ArrayList<ParsedBlock>()
                while (index < run.size && (run[index].list?.second ?: 0) > level) {
                    nested += run[index]
                    index++
                }
                if (nested.isNotEmpty()) children += buildLists(nested, level + 1, context)
                items += DocNode(type = "listItem", content = children)
            }
            if (items.isEmpty()) {
                // Levels that do not descend — `ilvl` 3 with no 2 above it. Keeping the paragraph as
                // an item of its own also guarantees `index` moves.
                items += DocNode(type = "listItem", content = listOf(run[index].node))
                index++
            }
            val ordered = style?.ordered ?: false
            out += DocNode(
                type = if (ordered) "orderedList" else "bulletList",
                attrs = mapOf("listStyleType" to docString(style?.styleType ?: if (ordered) "decimal" else "disc")),
                content = items,
            )
        }
        return out
    }

    /** Column layouts rebuilt from the extras. A document edited elsewhere will not match, and its blocks stay put. */
    private fun groupColumnLayouts(blocks: List<DocNode>, context: ReadContext): List<DocNode> {
        val layouts = context.extras.columnLayouts
        if (layouts.isNullOrEmpty()) return blocks
        val out = ArrayList<DocNode>()
        var index = 0
        var layout = 0
        while (index < blocks.size) {
            if (layout < layouts.size) {
                val spec = layouts[layout]
                if (spec.blockCount > 0 && index + spec.blockCount <= blocks.size) {
                    out += DocNode(type = "columnLayout", attrs = mapOf("columns" to docInt(spec.columns)), content = blocks.subList(index, index + spec.blockCount).toList())
                    index += spec.blockCount
                    layout++
                    continue
                }
            }
            out += blocks[index]
            index++
        }
        return out
    }

    /**
     * Placeholder runs turned back into the nodes they stood in for, matched in document order. A
     * block placeholder was given a paragraph to itself, so the paragraph is what is replaced. One
     * left over — a document edited elsewhere — stays as the italic text it reads as.
     */
    private fun restorePlaceholders(nodes: List<DocNode>, extras: DocxExtras, cursor: IntArray): List<DocNode> = nodes.map { node ->
        val entry = extras.placeholder(cursor[0])
        when {
            entry != null && entry.kind in DocxMapping.blockPlaceholderKinds && node.type == "paragraph" &&
                node.children.size == 1 && node.children[0].type == PLACEHOLDER -> {
                cursor[0]++
                DocNode(type = entry.kind, attrs = entry.attrs)
            }
            node.type == PLACEHOLDER -> if (entry != null) {
                cursor[0]++
                DocNode(type = entry.kind, attrs = entry.attrs)
            } else {
                DocNode.text(node.text ?: "", listOf(DocMark("italic")))
            }
            node.content != null -> node.copy(content = restorePlaceholders(node.content, extras, cursor))
            else -> node
        }
    }
}
