package com.neutrino.shared.core.ooxml

/**
 * The namespace URIs an OOXML package is written in. Prefixes vary between producers; these do not,
 * so every attribute read resolves against one of these — see [XmlElement.attribute].
 */
object OoxmlNamespace {
    /** WordprocessingML — the body, its paragraphs, runs and properties. */
    const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    /** Relationships, as referenced *from* a part (`r:id`, `r:embed`). */
    const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    /** The relationship parts themselves (the `_rels` parts). */
    const val PACKAGE_RELS = "http://schemas.openxmlformats.org/package/2006/relationships"
    /** DrawingML — pictures. */
    const val A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    const val WP = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
    const val PIC = "http://schemas.openxmlformats.org/drawingml/2006/picture"
    const val CONTENT_TYPES = "http://schemas.openxmlformats.org/package/2006/content-types"
    const val DC = "http://purl.org/dc/elements/1.1/"
    const val CP = "http://schemas.openxmlformats.org/package/2006/metadata/core-properties"
    const val DCTERMS = "http://purl.org/dc/terms/"
    const val XSI = "http://www.w3.org/2001/XMLSchema-instance"
    /** `docProps/app.xml`. */
    const val EXTENDED_PROPS = "http://schemas.openxmlformats.org/officeDocument/2006/extended-properties"
    /** `docProps/custom.xml`, and the variant types its values are written as. */
    const val CUSTOM_PROPERTIES = "http://schemas.openxmlformats.org/officeDocument/2006/custom-properties"
    const val VT = "http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes"
    /** The custom XML store Word preserves across an edit — where the extras part lives. */
    const val CUSTOM_XML = "http://schemas.openxmlformats.org/officeDocument/2006/customXml"
}

/** Relationship types: how a package says what a part *is* rather than where it sits. */
object OoxmlRelationship {
    private const val BASE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    const val OFFICE_DOCUMENT = "$BASE/officeDocument"
    const val STYLES = "$BASE/styles"
    const val NUMBERING = "$BASE/numbering"
    const val SETTINGS = "$BASE/settings"
    const val FOOTNOTES = "$BASE/footnotes"
    const val HEADER = "$BASE/header"
    const val FOOTER = "$BASE/footer"
    const val IMAGE = "$BASE/image"
    const val HYPERLINK = "$BASE/hyperlink"
    const val CUSTOM_XML = "$BASE/customXml"
    const val CUSTOM_XML_PROPS = "$BASE/customXmlProps"
    const val CORE_PROPERTIES = "http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties"
    const val EXTENDED_PROPERTIES = "$BASE/extended-properties"
    const val CUSTOM_PROPERTIES = "$BASE/custom-properties"
}

/** The part paths a `.docx` is made of. */
object OoxmlPackage {
    const val CONTENT_TYPES_PART = "[Content_Types].xml"
    const val ROOT_RELS_PART = "_rels/.rels"
    const val DOCUMENT_PART = "word/document.xml"
    const val DOCUMENT_RELS_PART = "word/_rels/document.xml.rels"
    const val STYLES_PART = "word/styles.xml"
    const val NUMBERING_PART = "word/numbering.xml"
    const val SETTINGS_PART = "word/settings.xml"
    const val FOOTNOTES_PART = "word/footnotes.xml"
    const val CORE_PROPS_PART = "docProps/core.xml"
    const val APP_PROPS_PART = "docProps/app.xml"
    const val CUSTOM_PROPS_PART = "docProps/custom.xml"

    fun headerPart(index: Int) = "word/header$index.xml"
    fun footerPart(index: Int) = "word/footer$index.xml"

    /** The `_rels` part that describes `path`'s own relationships. */
    fun relsPart(path: String): String {
        val slash = path.lastIndexOf('/')
        if (slash < 0) return "_rels/$path.rels"
        return "${path.substring(0, slash)}/_rels/${path.substring(slash + 1)}.rels"
    }

    /** A relationship target resolved against the directory of the part that declared it. */
    fun resolve(target: String, directory: String): String {
        if (target.startsWith("/")) return target.substring(1)
        val out = ArrayList<String>()
        for (segment in "$directory/$target".split('/')) {
            when (segment) {
                "", "." -> {}
                ".." -> if (out.isNotEmpty()) out.removeAt(out.lastIndex)
                else -> out += segment
            }
        }
        return out.joinToString("/")
    }
}

/**
 * Escaping for the XML the writer emits as text. Everything that reaches a document goes through
 * [escape] — a stray `&` in a paragraph is enough for Word to declare the file corrupt.
 */
object XmlText {
    const val DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"

    /**
     * Character data with the five predefined entities replaced. XML 1.0 cannot carry most control
     * characters at all, so they are dropped: tab, newline and carriage return are the legal three.
     */
    fun escape(string: String): String {
        val out = StringBuilder(string.length + 16)
        for (c in string) {
            when (c) {
                '&' -> out.append("&amp;")
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                '"' -> out.append("&quot;")
                '\'' -> out.append("&apos;")
                '\t', '\n', '\r' -> out.append(c)
                else -> if (c.code >= 0x20) out.append(c)
            }
        }
        return out.toString()
    }

    fun attributeValue(string: String): String = escape(string)
}

/** Swift's `.rounded()` — to nearest, halves away from zero — which `kotlin.math.round` is not. */
internal fun roundHalfAway(value: Double): Double =
    if (value >= 0) kotlin.math.floor(value + 0.5) else -kotlin.math.floor(-value + 0.5)
