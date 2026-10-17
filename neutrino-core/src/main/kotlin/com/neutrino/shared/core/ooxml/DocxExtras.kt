package com.neutrino.shared.core.ooxml

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Node-level data with no OOXML home, addressed by the node's index in document order among nodes
 * of the same kind — indices rather than ids, because every way of writing an id into the document
 * is one Word is free to renumber. An index degrades predictably instead: edit the document in Word
 * and the extras after the edit stop matching, so they are dropped and the base content — all real
 * OOXML — still reads.
 *
 * Serialised as JSON inside [DocxMapping.EXTRAS_PART], in the web's `DocExtras` shape (the keys here
 * are the keys there), with the iOS `columnLayouts` extension the web also writes.
 */
class DocxExtras {

    data class ImageExtra(val shadow: String? = null, val filter: String? = null, val caption: String? = null, val driveRef: String? = null) {
        val isEmpty: Boolean get() = shadow == null && filter == null && caption == null && driveRef == null
    }

    /** A node written as a text placeholder, in document order — one channel for every kind. */
    data class Placeholder(val kind: String, val attrs: Map<String, DocValue>)

    /** A `columnLayout`'s shape: CSS columns are a section property in OOXML, so the children are regrouped from this. */
    data class ColumnLayout(val columns: Int, val blockCount: Int)

    /** `docTheme`, the preset name no interchange format models. */
    var theme: String? = null
    var images: MutableMap<String, ImageExtra>? = null
    var crossRefs: MutableMap<String, String>? = null
    var placeholders: MutableList<Placeholder>? = null
    var fieldShowCode: MutableList<Int>? = null
    var columnLayouts: MutableList<ColumnLayout>? = null

    val isEmpty: Boolean
        get() = theme == null && images.isNullOrEmpty() && crossRefs.isNullOrEmpty() && placeholders.isNullOrEmpty() &&
            fieldShowCode.isNullOrEmpty() && columnLayouts.isNullOrEmpty()

    fun image(index: Int): ImageExtra? = images?.get(index.toString())
    fun crossRef(index: Int): String? = crossRefs?.get(index.toString())
    fun placeholder(index: Int): Placeholder? = placeholders?.getOrNull(index)
    fun showsCode(fieldIndex: Int): Boolean = fieldShowCode?.contains(fieldIndex) ?: false

    internal fun addImage(extra: ImageExtra, index: Int) {
        if (extra.isEmpty) return
        (images ?: linkedMapOf<String, ImageExtra>().also { images = it })[index.toString()] = extra
    }

    internal fun addCrossRef(headingText: String, index: Int) {
        (crossRefs ?: linkedMapOf<String, String>().also { crossRefs = it })[index.toString()] = headingText
    }

    internal fun addPlaceholder(kind: String, attrs: Map<String, DocValue>) {
        (placeholders ?: mutableListOf<Placeholder>().also { placeholders = it }) += Placeholder(kind, attrs)
    }

    internal fun addFieldShowCode(index: Int) {
        (fieldShowCode ?: mutableListOf<Int>().also { fieldShowCode = it }) += index
    }

    internal fun addColumnLayout(columns: Int, blockCount: Int) {
        (columnLayouts ?: mutableListOf<ColumnLayout>().also { columnLayouts = it }) += ColumnLayout(columns, blockCount)
    }

    fun toJson(): JsonObject = JsonObject(buildMap {
        theme?.let { put("theme", JsonPrimitive(it)) }
        images?.let { m ->
            put("images", JsonObject(m.mapValues { (_, e) ->
                JsonObject(buildMap {
                    e.shadow?.let { put("shadow", JsonPrimitive(it)) }
                    e.filter?.let { put("filter", JsonPrimitive(it)) }
                    e.caption?.let { put("caption", JsonPrimitive(it)) }
                    e.driveRef?.let { put("driveRef", JsonPrimitive(it)) }
                })
            }))
        }
        crossRefs?.let { m -> put("crossRefs", JsonObject(m.mapValues { JsonPrimitive(it.value) })) }
        placeholders?.let { l ->
            put("placeholders", JsonArray(l.map { JsonObject(mapOf("kind" to JsonPrimitive(it.kind), "attrs" to JsonObject(it.attrs))) }))
        }
        fieldShowCode?.let { l -> put("fieldShowCode", JsonArray(l.map { JsonPrimitive(it) })) }
        columnLayouts?.let { l ->
            put("columnLayouts", JsonArray(l.map {
                JsonObject(mapOf("columns" to JsonPrimitive(it.columns), "blockCount" to JsonPrimitive(it.blockCount)))
            }))
        }
    })

    /**
     * The two parts that carry the extras, or null when there are none. A custom XML part needs its
     * `itemProps` sibling and a schema reference, or Word treats it as an orphan and drops it.
     */
    internal fun parts(): Pair<String, String>? {
        if (isEmpty) return null
        val json = DocJson.canonical(toJson())
        val item = XmlText.DECLARATION +
            "<neutrino xmlns=\"${DocxMapping.EXTRAS_NAMESPACE}\">${XmlText.escape(json)}</neutrino>"
        val props = XmlText.DECLARATION +
            "<ds:datastoreItem xmlns:ds=\"${OoxmlNamespace.CUSTOM_XML}\" " +
            "ds:itemID=\"{4E4A2B37-7C21-4F1E-9C55-3F5B0E2A1D90}\">" +
            "<ds:schemaRefs><ds:schemaRef ds:uri=\"${DocxMapping.EXTRAS_NAMESPACE}\"/></ds:schemaRefs>" +
            "</ds:datastoreItem>"
        return item to props
    }

    companion object {
        /** The extras in a package, or an empty set when the part is missing or unreadable. */
        internal fun read(archive: ZipArchive): DocxExtras {
            val xml = archive.text(DocxMapping.EXTRAS_PART) ?: return DocxExtras()
            val root = XmlElement.parse(xml) ?: return DocxExtras()
            val json = root.textContent
            if (json.isEmpty()) return DocxExtras()
            val obj = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return DocxExtras()
            return runCatching { fromJson(obj) }.getOrDefault(DocxExtras())
        }

        fun fromJson(obj: JsonObject): DocxExtras = DocxExtras().apply {
            theme = obj["theme"]?.stringValue
            images = (obj["images"] as? JsonObject)?.mapValuesTo(linkedMapOf()) { (_, v) ->
                val e = v as? JsonObject ?: JsonObject(emptyMap())
                ImageExtra(e["shadow"]?.stringValue, e["filter"]?.stringValue, e["caption"]?.stringValue, e["driveRef"]?.stringValue)
            }
            crossRefs = (obj["crossRefs"] as? JsonObject)?.entries?.mapNotNull { (k, v) -> v.stringValue?.let { k to it } }?.toMap(linkedMapOf())
            placeholders = (obj["placeholders"] as? JsonArray)?.mapNotNullTo(mutableListOf()) { e ->
                val o = e as? JsonObject ?: return@mapNotNullTo null
                val kind = o["kind"]?.stringValue ?: return@mapNotNullTo null
                Placeholder(kind, (o["attrs"] as? JsonObject)?.toMap() ?: emptyMap())
            }
            fieldShowCode = (obj["fieldShowCode"] as? JsonArray)?.mapNotNullTo(mutableListOf()) { it.intValue }
            columnLayouts = (obj["columnLayouts"] as? JsonArray)?.mapNotNullTo(mutableListOf()) { e ->
                val o = e as? JsonObject ?: return@mapNotNullTo null
                ColumnLayout(o["columns"]?.intValue ?: return@mapNotNullTo null, o["blockCount"]?.intValue ?: return@mapNotNullTo null)
            }
        }
    }
}
