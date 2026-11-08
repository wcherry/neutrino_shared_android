package com.neutrino.shared.core.ooxml

/**
 * A typed reading view over the `_meta` layout block. The block itself stays raw JSON — every
 * client keeps it verbatim — and every accessor has a default, because a document written before a
 * field existed simply does not have it. The shape is the web app's `LayoutMeta`
 * (`web/apps/web/src/lib/docBody.ts`).
 */
internal class DocxMeta(val raw: DocValue?) {

    /** One band: three independently aligned slots on a single line. */
    data class Slots(val left: String = "", val center: String = "", val right: String = "") {
        val isEmpty: Boolean get() = left.isEmpty() && center.isEmpty() && right.isEmpty()
    }

    data class Band(val header: Slots = Slots(), val footer: Slots = Slots())

    /** The three variants Word can give a section: every page, page one, and even pages. */
    enum class Variant(val key: String) { DEFAULT("default"), FIRST("first"), EVEN("even") }

    // ── Page setup ──────────────────────────────────────────────────────────────────────────────

    private val pageSetup: Map<String, DocValue> get() = raw["pageSetup"]?.objectValue ?: DocxMapping.defaultPageSetup

    val pageSize: String get() = pageSetup["pageSize"]?.stringValue ?: "letter"
    val isLandscape: Boolean get() = pageSetup["orientation"]?.stringValue == "landscape"
    fun margin(edge: String): Double = pageSetup["margin$edge"]?.doubleValue ?: 72.0

    // ── Bands ───────────────────────────────────────────────────────────────────────────────────

    private val headerFooter: Map<String, DocValue> get() = raw["headerFooter"]?.objectValue ?: emptyMap()

    val differentFirstPage: Boolean get() = headerFooter["differentFirstPage"]?.boolValue ?: false
    val differentEvenOdd: Boolean get() = headerFooter["differentEvenOdd"]?.boolValue ?: false

    /** 0.5in, the word-processor default, in points. */
    val headerMargin: Double get() = headerFooter["headerMargin"]?.doubleValue ?: 36.0
    val footerMargin: Double get() = headerFooter["footerMargin"]?.doubleValue ?: 36.0

    fun band(variant: Variant): Band {
        val obj = headerFooter["variants"][variant.key]?.objectValue ?: return Band()
        return Band(slots(obj["header"]), slots(obj["footer"]))
    }

    /** `first` and `even` are written only when asked for: an empty `first` header would blank page one's band. */
    fun writes(variant: Variant): Boolean = when (variant) {
        Variant.DEFAULT -> true
        Variant.FIRST -> differentFirstPage
        Variant.EVEN -> differentEvenOdd
    }

    private fun slots(value: DocValue?): Slots {
        val obj = value?.objectValue ?: return Slots()
        return Slots(obj["left"]?.stringValue ?: "", obj["center"]?.stringValue ?: "", obj["right"]?.stringValue ?: "")
    }

    // ── Decoration ──────────────────────────────────────────────────────────────────────────────

    val watermarkText: String get() = raw["watermarkText"]?.stringValue ?: ""
    val backgroundColor: String get() = DocxMapping.normalizeColor(raw["bgColor"]?.stringValue)
    val docTheme: String? get() = raw["docTheme"]?.stringValue

    // ── Document properties ─────────────────────────────────────────────────────────────────────

    private val properties: Map<String, DocValue> get() = raw["properties"]?.objectValue ?: emptyMap()
    private fun property(name: String) = properties[name]?.stringValue ?: ""

    val author get() = property("author")
    val subject get() = property("subject")
    val keywords get() = property("keywords")
    val category get() = property("category")
    val company get() = property("company")
    val manager get() = property("manager")

    /** Anything else the user has named, reachable as `{{whatever}}`, in name order. */
    val customProperties: List<Pair<String, String>>
        get() {
            val custom = properties["custom"]?.objectValue ?: return emptyList()
            return custom.keys.sorted().mapNotNull { key -> custom.getValue(key).text?.let { key to it } }
        }

    /** Company and manager live in `docProps/custom.xml` too: `core.xml` has no element for either. */
    val hasCustomProperties: Boolean get() = company.isNotEmpty() || manager.isNotEmpty() || customProperties.isNotEmpty()
}
