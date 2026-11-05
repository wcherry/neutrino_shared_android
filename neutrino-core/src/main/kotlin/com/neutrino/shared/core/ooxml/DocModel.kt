package com.neutrino.shared.core.ooxml

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// The document model the editors share: the ProseMirror (TipTap) tree the web editor produces, and
// the `_meta` layout block stored beside it. The Kotlin half of `DocModel.swift` in
// neutrino_shared_ios and of `web/apps/web/src/lib/ooxml/docx/mapping.ts`.

/**
 * A JSON value held exactly as it was written.
 *
 * The model carries far more than the `.docx` mapping understands — a table cell's `colspan`, a
 * tracked change's author, a layout block's theme — so attributes are kept as JSON rather than
 * decoded into fixed types: everything a newer editor adds survives a read and a write here.
 *
 * `1` and `1.0` stay different values (their `content` differs), as `.int` and `.double` do on iOS,
 * so `{"level": 1}` re-encodes as `1`.
 */
typealias DocValue = JsonElement

/** The value as a string, only when it *is* a string. */
val DocValue.stringValue: String?
    get() = (this as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content

/** A number, or a string holding a whole number. A fractional number is truncated, as Swift's `Int(_:)` does. */
val DocValue.intValue: Int?
    get() {
        val p = this as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        if (p.isString) return p.content.toIntOrNull()
        if (p.content == "true" || p.content == "false") return null
        return p.content.toIntOrNull() ?: p.content.toDoubleOrNull()?.takeIf { it.isFinite() }?.toInt()
    }

val DocValue.doubleValue: Double?
    get() {
        val p = this as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        if (!p.isString && (p.content == "true" || p.content == "false")) return null
        return p.content.toDoubleOrNull()
    }

val DocValue.boolValue: Boolean?
    get() {
        val p = this as? JsonPrimitive ?: return null
        if (p is JsonNull || p.isString) return null
        return when (p.content) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

val DocValue.objectValue: Map<String, DocValue>? get() = this as? JsonObject
val DocValue.arrayValue: List<DocValue>? get() = this as? JsonArray
val DocValue.isNull: Boolean get() = this is JsonNull

operator fun DocValue?.get(key: String): DocValue? = (this as? JsonObject)?.get(key)

/**
 * The value as the text an attribute holds — what a call site writing it into XML wants, where a
 * number and its string are the same thing.
 */
val DocValue.text: String?
    get() {
        val p = this as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        return p.content
    }

fun docString(value: String): DocValue = JsonPrimitive(value)
fun docInt(value: Int): DocValue = JsonPrimitive(value)
fun docDouble(value: Double): DocValue = JsonPrimitive(value)
fun docBool(value: Boolean): DocValue = JsonPrimitive(value)
val docNull: DocValue get() = JsonNull
fun docObject(vararg pairs: Pair<String, DocValue>): DocValue = JsonObject(linkedMapOf(*pairs))
fun docObject(map: Map<String, DocValue>): DocValue = JsonObject(map)
fun docArray(values: List<DocValue>): DocValue = JsonArray(values)

/** An inline mark — `bold`, `link`, and the editor's own `trackedInsertion` and friends. */
data class DocMark(val type: String, val attrs: Map<String, DocValue>? = null) {
    fun attr(name: String): DocValue? = attrs?.get(name)
}

/**
 * One node of the ProseMirror document tree.
 *
 * Schema-less on purpose: any `type` decodes and unknown attributes survive re-encoding, so a node a
 * newer web release introduces is carried through rather than dropped. `attrs = null` and
 * `attrs = {}` are different nodes, as they are on iOS and to the editor.
 */
data class DocNode(
    val type: String,
    val attrs: Map<String, DocValue>? = null,
    val content: List<DocNode>? = null,
    val marks: List<DocMark>? = null,
    val text: String? = null,
) {
    val children: List<DocNode> get() = content ?: emptyList()

    fun attr(name: String): DocValue? = attrs?.get(name)

    fun mark(name: String): DocMark? = marks?.firstOrNull { it.type == name }

    /** Every text node under this one, concatenated. */
    val plainText: String get() = text ?: children.joinToString("") { it.plainText }

    companion object {
        fun text(string: String, marks: List<DocMark>? = null): DocNode =
            DocNode(type = "text", marks = marks?.takeIf { it.isNotEmpty() }, text = string)
    }
}

/**
 * A whole document: the tree, plus the web editor's `_meta` — page setup, header and footer bands,
 * watermark, background, theme and document properties. `meta` stays raw JSON because every client
 * stores it verbatim, and a field one of them has not learned about must survive the round trip.
 */
data class DocModel(val doc: DocNode, val meta: DocValue? = null) {

    /** The stored envelope `{ doc, _meta }`, with sorted keys so equal documents encode to equal bytes. */
    fun toJSON(): String = DocJson.canonical(JsonObject(buildMap {
        put("doc", DocJson.encode(doc))
        meta?.let { put("_meta", it) }
    }))

    companion object {
        /** An empty document, which is what a newly created file holds. */
        val empty = DocModel(DocNode(type = "doc", content = emptyList()))

        /** Reads `{ doc, _meta }`, or a bare document node; null when it is neither. */
        fun fromJSON(json: String): DocModel? {
            val element = runCatching { Json.parseToJsonElement(json) }.getOrNull() as? JsonObject ?: return null
            val wrapped = element["doc"] as? JsonObject
            if (wrapped != null) {
                val doc = DocJson.decode(wrapped) ?: return null
                if (doc.type == "doc") return DocModel(doc, element["_meta"]?.takeIf { it !is JsonNull })
            }
            val node = DocJson.decode(element) ?: return null
            return if (node.type == "doc") DocModel(node) else null
        }
    }
}

/** The tree's JSON shape, as ProseMirror writes it: absent keys rather than nulls. */
object DocJson {

    fun encode(node: DocNode): JsonObject = JsonObject(buildMap {
        put("type", JsonPrimitive(node.type))
        node.attrs?.let { put("attrs", JsonObject(it)) }
        node.content?.let { c -> put("content", JsonArray(c.map(::encode))) }
        node.marks?.let { m -> put("marks", JsonArray(m.map(::encodeMark))) }
        node.text?.let { put("text", JsonPrimitive(it)) }
    })

    fun encodeMark(mark: DocMark): JsonObject = JsonObject(buildMap {
        put("type", JsonPrimitive(mark.type))
        mark.attrs?.let { put("attrs", JsonObject(it)) }
    })

    /** Null when `element` is not a node — no string `type`. */
    fun decode(element: JsonElement): DocNode? {
        val obj = element as? JsonObject ?: return null
        val type = obj["type"]?.stringValue ?: return null
        val content = (obj["content"] as? JsonArray)?.map { decode(it) ?: return null }
        val marks = (obj["marks"] as? JsonArray)?.map { decodeMark(it) ?: return null }
        return DocNode(
            type = type,
            attrs = (obj["attrs"] as? JsonObject)?.toMap(),
            content = content,
            marks = marks,
            text = obj["text"]?.stringValue,
        )
    }

    fun decodeMark(element: JsonElement): DocMark? {
        val obj = element as? JsonObject ?: return null
        val type = obj["type"]?.stringValue ?: return null
        return DocMark(type, (obj["attrs"] as? JsonObject)?.toMap())
    }

    /**
     * JSON with object keys sorted and no whitespace: callers compare the bytes (the offline cache
     * decides whether a document changed by comparing what it encoded), so key order must not
     * depend on how a map happened to be built.
     */
    fun canonical(element: JsonElement): String = StringBuilder().also { write(element, it) }.toString()

    private fun write(element: JsonElement, out: StringBuilder) {
        when (element) {
            is JsonObject -> {
                out.append('{')
                element.keys.sorted().forEachIndexed { i, key ->
                    if (i > 0) out.append(',')
                    out.append(JsonPrimitive(key).toString()).append(':')
                    write(element.getValue(key), out)
                }
                out.append('}')
            }
            is JsonArray -> {
                out.append('[')
                element.forEachIndexed { i, item ->
                    if (i > 0) out.append(',')
                    write(item, out)
                }
                out.append(']')
            }
            else -> out.append(element.toString())
        }
    }
}
