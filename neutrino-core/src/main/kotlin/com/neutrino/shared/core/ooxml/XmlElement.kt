package com.neutrino.shared.core.ooxml

/**
 * The tiny read-only DOM the OOXML parts are read through — the Kotlin half of `XMLElement.swift`.
 *
 * A part is walked in several directions at once (a paragraph's properties, then its runs, then each
 * run's properties), which a streaming parser cannot express, so the document is built into this
 * tree first. The parser is this file's own rather than the platform's: the JVM and Android ship
 * different XML stacks, and one that behaves the same in the JVM tests as on a phone — and resolves
 * no external entities at all — is worth the hundred lines.
 *
 * ## Prefixes are not namespaces
 *
 * Qualified names are kept as written and the `xmlns` declarations are resolved into a prefix map on
 * the way past. [attribute] matches on the namespace when given one and on the local name when not,
 * because `w:id` and `r:id` sit on the same element meaning different things, and a producer that
 * chose other prefixes must still read.
 */
class XmlElement internal constructor(
    /** The name as written, prefix included: `w:p`. */
    val qualifiedName: String,
    /** Attributes keyed by qualified name, `xmlns` declarations excluded. */
    val attributes: Map<String, String>,
    /** Prefix → namespace URI, inherited from every ancestor. `""` is the default namespace. */
    val namespaces: Map<String, String>,
) {
    /** The name with any prefix removed: `p`. */
    val localName: String = local(qualifiedName)

    private val _children = mutableListOf<XmlElement>()
    val children: List<XmlElement> get() = _children

    private val _text = StringBuilder()

    /** Character data directly inside this element, none from its children. */
    val text: String get() = _text.toString()

    /** Direct children with this local name, whatever prefix they were written with. */
    fun elements(name: String): List<XmlElement> = _children.filter { it.localName == name }

    fun element(name: String): XmlElement? = _children.firstOrNull { it.localName == name }

    /** The first descendant with this local name, breadth-first. */
    fun descendant(name: String): XmlElement? {
        val queue = ArrayDeque(_children)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.localName == name) return node
            queue.addAll(node._children)
        }
        return null
    }

    /** Every descendant with this local name, in document order. */
    fun descendants(name: String): List<XmlElement> {
        val out = mutableListOf<XmlElement>()
        fun walk(e: XmlElement) {
            for (child in e._children) {
                if (child.localName == name) out += child
                walk(child)
            }
        }
        walk(this)
        return out
    }

    /** All character data under this element, children included — own text first, as iOS reads it. */
    val textContent: String
        get() = StringBuilder(_text).also { sb -> _children.forEach { sb.append(it.textContent) } }.toString()

    /**
     * An attribute by local name, preferring the one in `uri` when a namespace is given; falls back
     * to the exact name and then to any prefix, because a package is free to declare a default
     * namespace and write `val` where another writes `w:val`.
     */
    fun attribute(name: String, uri: String? = null): String? {
        if (uri != null) {
            for ((key, value) in attributes) {
                if (local(key) != name) continue
                val prefix = prefix(key)
                // An unprefixed attribute is in no namespace — never the default one.
                if (prefix.isNotEmpty() && namespaces[prefix] == uri) return value
            }
        }
        attributes[name]?.let { return it }
        for ((key, value) in attributes) if (local(key) == name) return value
        return null
    }

    /** `w:val`, the single most common thing to ask an OOXML element for. */
    val value: String? get() = attribute("val", OoxmlNamespace.W)

    /**
     * Whether a toggle property is on: `<w:b/>` and `<w:b w:val="true"/>` are bold, and
     * `<w:b w:val="false"/>` — how a run inside a bold style turns it back off — is not.
     */
    val isToggleOn: Boolean
        get() {
            val v = value ?: return true
            return v.isEmpty() || v == "true" || v == "1" || v == "on"
        }

    companion object {
        fun local(qualifiedName: String): String = qualifiedName.substringAfter(':', qualifiedName)
        fun prefix(qualifiedName: String): String = if (':' in qualifiedName) qualifiedName.substringBefore(':') else ""

        /**
         * Parses `xml`, or null when there is no root element at all. A part that fails halfway is
         * still worth what it produced — a truncated `numbering.xml` costs the bullet glyph, where
         * refusing the document costs the document — so a malformed tail ends the parse, not the read.
         */
        fun parse(xml: String): XmlElement? = Parser(xml).run()
    }

    private class Parser(source: String) {
        // XML normalises every line ending to LF before anything else reads the text.
        private val s = source.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
        private var i = 0
        private val stack = ArrayList<XmlElement>()
        private var root: XmlElement? = null

        fun run(): XmlElement? {
            try {
                while (i < s.length) {
                    if (s[i] != '<') {
                        val end = s.indexOf('<', i).let { if (it < 0) s.length else it }
                        stack.lastOrNull()?._text?.append(decode(s, i, end))
                        i = end
                        continue
                    }
                    when {
                        s.startsWith("<?", i) -> i = after("?>", i + 2)
                        s.startsWith("<!--", i) -> i = after("-->", i + 4)
                        s.startsWith("<![CDATA[", i) -> {
                            val end = s.indexOf("]]>", i + 9)
                            if (end < 0) return root
                            stack.lastOrNull()?._text?.append(s, i + 9, end)
                            i = end + 3
                        }
                        s.startsWith("<!", i) -> i = skipDeclaration(i)
                        s.startsWith("</", i) -> {
                            val end = s.indexOf('>', i)
                            if (end < 0) return root
                            if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                            i = end + 1
                        }
                        else -> if (!startTag()) return root
                    }
                }
            } catch (_: IndexOutOfBoundsException) {
                // A truncated part: keep what was read.
            }
            return root
        }

        private fun after(token: String, from: Int): Int {
            val at = s.indexOf(token, from)
            return if (at < 0) s.length else at + token.length
        }

        /** `<!DOCTYPE …>`, internal subset included. Nothing in it is honoured — no entity is ever defined. */
        private fun skipDeclaration(from: Int): Int {
            var depth = 0
            var j = from + 2
            while (j < s.length) {
                when (s[j]) {
                    '[' -> depth++
                    ']' -> depth--
                    '>' -> if (depth <= 0) return j + 1
                }
                j++
            }
            return s.length
        }

        private fun startTag(): Boolean {
            var j = i + 1
            val nameStart = j
            while (j < s.length && !s[j].isWhitespace() && s[j] != '>' && s[j] != '/') j++
            if (j == nameStart || j >= s.length) return false
            val name = s.substring(nameStart, j)
            val attrs = LinkedHashMap<String, String>()
            var selfClosing = false
            while (true) {
                while (j < s.length && s[j].isWhitespace()) j++
                if (j >= s.length) return false
                if (s[j] == '>') { j++; break }
                if (s.startsWith("/>", j)) { selfClosing = true; j += 2; break }
                val keyStart = j
                while (j < s.length && s[j] != '=' && !s[j].isWhitespace() && s[j] != '>' && s[j] != '/') j++
                val key = s.substring(keyStart, j)
                while (j < s.length && s[j].isWhitespace()) j++
                if (j >= s.length || s[j] != '=') {
                    if (key.isEmpty()) return false
                    continue // an attribute with no value is not XML; skip it rather than fail
                }
                j++
                while (j < s.length && s[j].isWhitespace()) j++
                if (j >= s.length) return false
                val quote = s[j]
                if (quote != '"' && quote != '\'') return false
                val valueEnd = s.indexOf(quote, j + 1)
                if (valueEnd < 0) return false
                // Attribute-value normalisation: literal whitespace characters become spaces.
                attrs[key] = decode(s, j + 1, valueEnd).let { v ->
                    if (v.any { it == '\t' || it == '\n' }) v.replace('\t', ' ').replace('\n', ' ') else v
                }
                j = valueEnd + 1
            }
            i = j

            val namespaces = HashMap(stack.lastOrNull()?.namespaces ?: emptyMap())
            val plain = LinkedHashMap<String, String>()
            for ((k, v) in attrs) {
                when {
                    k == "xmlns" -> namespaces[""] = v
                    k.startsWith("xmlns:") -> namespaces[k.removePrefix("xmlns:")] = v
                    else -> plain[k] = v
                }
            }
            val element = XmlElement(name, plain, namespaces)
            stack.lastOrNull()?._children?.add(element)
            if (root == null) root = element
            if (!selfClosing) stack.add(element)
            return true
        }

        /** Character data with the predefined entities and character references resolved. */
        private fun decode(text: String, from: Int, to: Int): String {
            val amp = text.indexOf('&', from)
            if (amp < 0 || amp >= to) return text.substring(from, to)
            val out = StringBuilder(to - from)
            var j = from
            while (j < to) {
                val c = text[j]
                if (c != '&') { out.append(c); j++; continue }
                val semi = text.indexOf(';', j)
                if (semi < 0 || semi >= to || semi - j > 12) { out.append(c); j++; continue }
                val entity = text.substring(j + 1, semi)
                val resolved: String? = when {
                    entity == "amp" -> "&"
                    entity == "lt" -> "<"
                    entity == "gt" -> ">"
                    entity == "quot" -> "\""
                    entity == "apos" -> "'"
                    entity.startsWith("#x") || entity.startsWith("#X") -> entity.substring(2).toIntOrNull(16)?.let(::codePoint)
                    entity.startsWith("#") -> entity.substring(1).toIntOrNull()?.let(::codePoint)
                    else -> null
                }
                if (resolved == null) { out.append(c); j++; continue }
                out.append(resolved)
                j = semi + 1
            }
            return out.toString()
        }

        private fun codePoint(value: Int): String? =
            if (value in 0..0x10FFFF && value !in 0xD800..0xDFFF) String(Character.toChars(value)) else null
    }
}
