package com.neutrino.shared.core.ooxml

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.Base64
import kotlin.system.exitProcess

/**
 * The Kotlin half of `scripts/crosscheck-web-docx.sh`.
 *
 * `emit out.json` writes a set of documents and the `.docx` this writer makes of each. The web half
 * reads every package with the web's own `readDocx` and checks it reads the same as the package the
 * web's `writeDocx` makes of the same document, then hands back its own packages. `verify a.json
 * w.json` reads those here and checks the same thing from this side. "Reads the same" is the
 * interoperability property: a document written on either client opens identically on each.
 */
object DocxCrossCheck {

    private fun p(vararg content: DocNode, attrs: Map<String, DocValue>? = null) = DocNode("paragraph", attrs = attrs, content = content.toList())
    private fun t(s: String, vararg marks: DocMark) = DocNode("text", marks = marks.toList().takeIf { it.isNotEmpty() }, text = s)
    private fun doc(vararg blocks: DocNode, meta: DocValue = TestMeta.base()) = DocModel(DocNode("doc", content = blocks.toList()), meta)

    private val png = "neutrino-drive:file-7"

    fun cases(): List<Triple<String, DocModel, Map<String, ByteArray>>> = listOf(
        Triple("marks", doc(p(
            t("plain "), t("bold", DocMark("bold")), t("italic", DocMark("italic")), t("under", DocMark("underline")),
            t("strike", DocMark("strike")), t("sup", DocMark("superscript")), t("sub", DocMark("subscript")), t("code", DocMark("code")),
            t("styled", DocMark("textStyle", mapOf("color" to docString("#ff0000"), "fontSize" to docString("18pt"), "fontFamily" to docString("Georgia")))),
            t("hi", DocMark("highlight", mapOf("color" to docString("#ffff00")))),
            t("shade", DocMark("highlight", mapOf("color" to docString("#abcdef")))),
            t("link", DocMark("link", mapOf("href" to docString("https://example.com/a")))),
            t("ins", DocMark("trackedInsertion", mapOf("author" to docString("Ada")))),
        )), emptyMap()),
        Triple("blocks", doc(
            DocNode("heading", attrs = mapOf("level" to docInt(2)), content = listOf(t("Chapter"))),
            p(t("centred"), attrs = mapOf("textAlign" to docString("center"), "indent" to docInt(2))),
            DocNode("blockquote", content = listOf(p(t("first")), p(t("second")))),
            DocNode("codeBlock", content = listOf(t("let x = 1\nlet y = 2"))),
            DocNode("horizontalRule"),
            DocNode("sectionBreak"),
            p(t("a"), DocNode("hardBreak"), t("b")),
        ), emptyMap()),
        Triple("lists", doc(
            DocNode("bulletList", attrs = mapOf("listStyleType" to docString("square")), content = listOf(
                DocNode("listItem", content = listOf(p(t("one")), DocNode("orderedList", attrs = mapOf("listStyleType" to docString("lower-roman")),
                    content = listOf(DocNode("listItem", content = listOf(p(t("nested")))))))),
                DocNode("listItem", content = listOf(p(t("two")))),
            )),
            DocNode("orderedList", attrs = mapOf("listStyleType" to docString("decimal")), content = listOf(DocNode("listItem", content = listOf(p(t("first")))))),
        ), emptyMap()),
        Triple("table", doc(DocNode("table", content = listOf(DocNode("tableRow", content = listOf(
            DocNode("tableCell", attrs = mapOf("colspan" to docInt(2), "rowspan" to docInt(1), "colwidth" to docNull,
                "backgroundColor" to docString("#ddeeff"), "borderColor" to docNull, "borderWidth" to docNull), content = listOf(p(t("cell")))),
        ))))), emptyMap()),
        Triple("fields-and-references", doc(
            DocNode("heading", attrs = mapOf("level" to docInt(1)), content = listOf(t("Intro"))),
            p(t("see", DocMark("crossRef", mapOf("headingText" to docString("Intro")))),
                DocNode("docField", attrs = mapOf("code" to docString("page"), "arg" to docNull, "showCode" to docBool(false))),
                DocNode("docField", attrs = mapOf("code" to docString("custom"), "arg" to docString("client"), "showCode" to docBool(true))),
                DocNode("footnote", attrs = mapOf("id" to docString("fn-1"), "text" to docString("A note.")))),
            DocNode("tableOfContents"),
        ), emptyMap()),
        Triple("embeds-and-images", doc(
            p(DocNode("image", attrs = mapOf("src" to docString(png), "width" to docString("320"), "alt" to docString("A cat"),
                "shadow" to docString("md"), "caption" to docString("Fig 1")))),
            p(DocNode("image", attrs = mapOf("src" to docString("neutrino-drive:offline"), "width" to docString("200")))),
            DocNode("sheetEmbed", attrs = mapOf("spreadsheetId" to docString("s1"), "title" to docString("Q1"))),
            DocNode("columnLayout", attrs = mapOf("columns" to docInt(2)), content = listOf(p(t("left")), p(t("right")))),
        ), mapOf(png to TestMeta.pngBytes)),
        Triple("layout", doc(p(t("x")), meta = TestMeta.base(
            watermark = "DRAFT", bgColor = "#fdfdfd", theme = "serif",
            properties = mapOf("author" to docString("Ada"), "subject" to docString("Engines"), "company" to docString("Acme"),
                "category" to docString("Notes"), "keywords" to docString("a,b"), "manager" to docString("Grace"),
                "custom" to docObject("client" to docString("Initech"))),
            pageSetup = mapOf("pageSize" to docString("a4"), "orientation" to docString("landscape"),
                "marginTop" to docInt(90), "marginBottom" to docInt(54), "marginLeft" to docInt(108), "marginRight" to docInt(36)),
            differentFirstPage = true, differentEvenOdd = true, headerMargin = 54, footerMargin = 27,
            variants = mapOf(
                "default" to TestMeta.band(listOf("Acme", "Report", "{{date}}"), listOf("", "Page {{page}} of {{pages}}", "v2")),
                "first" to TestMeta.band(listOf("Cover", "", ""), listOf("", "", "")),
                "even" to TestMeta.band(listOf("Even", "", ""), listOf("", "", "")),
            ),
            headerText = "Report", footerText = "Page {{page}} of {{pages}}", showPageNumbers = true,
        )), emptyMap()),
    )

    private val b64 = Base64.getEncoder()
    private val unb64 = Base64.getDecoder()

    @JvmStatic
    fun main(args: Array<String>) {
        when (args.firstOrNull()) {
            "emit" -> emit(File(args[1]))
            "verify" -> exitProcess(if (verify(File(args[1]), File(args[2]))) 0 else 1)
            else -> error("usage: emit out.json | verify android.json web.json")
        }
    }

    private fun emit(out: File) {
        val cases = cases().map { (name, model, images) ->
            JsonObject(mapOf(
                "name" to JsonPrimitive(name),
                "model" to Json.parseToJsonElement(model.toJSON()),
                "images" to JsonObject(images.mapValues { JsonPrimitive(b64.encodeToString(it.value)) }),
                "docx" to JsonPrimitive(b64.encodeToString(DocxWriter.write(model, DocxWriteOptions("Cross-check", images)))),
            ))
        }
        out.writeText(JsonObject(mapOf("cases" to JsonArray(cases))).toString())
    }

    private fun verify(androidFile: File, webFile: File): Boolean {
        val android = Json.parseToJsonElement(androidFile.readText()).jsonObject["cases"]!!.jsonArray.associateBy { it.jsonObject["name"]!!.jsonPrimitive.content }
        val web = Json.parseToJsonElement(webFile.readText()).jsonObject["cases"]!!.jsonArray
        var failures = 0
        for (case in web) {
            val name = case.jsonObject["name"]!!.jsonPrimitive.content
            val ours = DocxReader.read(unb64.decode(android.getValue(name).jsonObject["docx"]!!.jsonPrimitive.content))
            val theirs = DocxReader.read(unb64.decode(case.jsonObject["docx"]!!.jsonPrimitive.content))
            val ok = ours == theirs
            println("${if (ok) "PASS" else "FAIL"}  web-written $name reads on Android as Android's own does")
            if (!ok) {
                failures++
                println("  android: ${ours.toJSON()}")
                println("  web:     ${theirs.toJSON()}")
            }
        }
        return failures == 0
    }
}
