package com.neutrino.shared.core.ooxml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

/** The layout block a document is stored with, every field present and spelled as the web spells it. */
object TestMeta {
    fun slots(values: List<String>) = docObject("left" to docString(values[0]), "center" to docString(values[1]), "right" to docString(values[2]))
    fun band(header: List<String>, footer: List<String>) = docObject("header" to slots(header), "footer" to slots(footer))
    fun emptyBand() = band(listOf("", "", ""), listOf("", "", ""))

    val defaultProperties: Map<String, DocValue> = mapOf(
        "author" to docString(""), "subject" to docString(""), "company" to docString(""),
        "category" to docString(""), "keywords" to docString(""), "manager" to docString(""), "custom" to docObject(),
    )
    val defaultPageSetup: Map<String, DocValue> = mapOf(
        "marginTop" to docInt(72), "marginBottom" to docInt(72), "marginLeft" to docInt(72), "marginRight" to docInt(72),
        "orientation" to docString("portrait"), "pageSize" to docString("letter"),
    )

    fun base(
        watermark: String = "",
        bgColor: String = "",
        theme: String = "default",
        properties: Map<String, DocValue> = defaultProperties,
        pageSetup: Map<String, DocValue> = defaultPageSetup,
        differentFirstPage: Boolean = false,
        differentEvenOdd: Boolean = false,
        headerMargin: Int = 36,
        footerMargin: Int = 36,
        variants: Map<String, DocValue> = emptyMap(),
        headerText: String = "",
        footerText: String = "",
        showPageNumbers: Boolean = false,
    ): DocValue {
        val all = linkedMapOf("default" to emptyBand(), "first" to emptyBand(), "even" to emptyBand())
        all.putAll(variants)
        return docObject(
            "headerFooter" to docObject(
                "differentFirstPage" to docBool(differentFirstPage),
                "differentEvenOdd" to docBool(differentEvenOdd),
                "headerMargin" to docInt(headerMargin),
                "footerMargin" to docInt(footerMargin),
                "variants" to docObject(all),
            ),
            "headerText" to docString(headerText),
            "footerText" to docString(footerText),
            "showPageNumbers" to docBool(showPageNumbers),
            "watermarkText" to docString(watermark),
            "bgColor" to docString(bgColor),
            "docTheme" to docString(theme),
            "properties" to docObject(properties),
            "pageSetup" to docObject(pageSetup),
        )
    }

    /** A one-pixel PNG — enough for the writer to sniff a format and embed a media part. */
    val pngBytes: ByteArray = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==")
}

/**
 * Writer and reader agree: `read(write(model)) == model`, per feature and then over a document
 * carrying one of everything. The mirror of `DocxRoundTripTests.swift` and of the web's
 * `docxRoundTrip.test.ts`.
 */
class DocxRoundTripTest {

    private fun roundTrip(model: DocModel): DocModel = DocxReader.read(DocxWriter.write(model, DocxWriteOptions("RT")))

    private fun bodyRoundTrip(vararg content: DocNode): List<DocNode> =
        roundTrip(DocModel(DocNode("doc", content = content.toList()), TestMeta.base())).doc.children

    private fun paragraph(vararg content: DocNode, attrs: Map<String, DocValue> = emptyMap()) =
        DocNode("paragraph", attrs = attrs.takeIf { it.isNotEmpty() }, content = content.toList())

    private fun text(s: String, marks: List<DocMark>? = null) = DocNode("text", marks = marks, text = s)

    // ── Marks ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun toggleMarksSurvive() {
        for (type in listOf("bold", "italic", "underline", "strike", "superscript", "subscript", "code")) {
            val marks = listOf(DocMark(type))
            assertEquals(type, marks, bodyRoundTrip(paragraph(text("x", marks))).first().children.first().marks)
        }
    }

    @Test
    fun colourSizeAndFamilySurvive() {
        val marks = listOf(DocMark("textStyle", mapOf("color" to docString("#ff0000"), "fontSize" to docString("18pt"), "fontFamily" to docString("Georgia"))))
        assertEquals(marks, bodyRoundTrip(paragraph(text("x", marks))).first().children.first().marks)
    }

    @Test
    fun highlightOutsideTheEnumerationSurvives() {
        val marks = listOf(DocMark("highlight", mapOf("color" to docString("#abcdef"))))
        assertEquals(marks, bodyRoundTrip(paragraph(text("x", marks))).first().children.first().marks)
    }

    @Test
    fun highlightInsideTheEnumerationSurvives() {
        val marks = listOf(DocMark("highlight", mapOf("color" to docString("#ffff00"))))
        assertEquals(marks, bodyRoundTrip(paragraph(text("x", marks))).first().children.first().marks)
    }

    @Test
    fun aLinkSurvives() {
        val marks = listOf(DocMark("link", mapOf("href" to docString("https://example.com/a"))))
        assertEquals(marks, bodyRoundTrip(paragraph(text("x", marks))).first().children.first().marks)
    }

    @Test
    fun aTrackedInsertionKeepsItsAuthor() {
        val marks = listOf(DocMark("trackedInsertion", mapOf("author" to docString("Ada"))))
        assertEquals(marks, bodyRoundTrip(paragraph(text("x", marks))).first().children.first().marks)
    }

    /** Both are containers in OOXML — `w:hyperlink` and `w:del` — so they nest rather than one winning. */
    @Test
    fun aLinkInsideATrackedDeletionKeepsBoth() {
        val marks = listOf(DocMark("link", mapOf("href" to docString("https://example.com/a"))), DocMark("trackedDeletion", mapOf("author" to docString("Grace"))))
        val out = bodyRoundTrip(paragraph(text("x", marks))).first().children.first().marks.orEmpty()
        assertEquals(setOf("link", "trackedDeletion"), out.map { it.type }.toSet())
    }

    // ── Blocks ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun headingsKeepTheirLevel() {
        val out = bodyRoundTrip(DocNode("heading", attrs = mapOf("level" to docInt(3)), content = listOf(text("H"))))
        assertEquals("heading", out.first().type)
        assertEquals(3, out.first().attr("level")?.intValue)
    }

    @Test
    fun alignmentAndIndentSurvive() {
        val out = bodyRoundTrip(paragraph(text("x"), attrs = mapOf("textAlign" to docString("center"), "indent" to docInt(3))))
        assertEquals("center", out.first().attr("textAlign")?.stringValue)
        assertEquals(3, out.first().attr("indent")?.intValue)
    }

    @Test
    fun aBulletListKeepsItsGlyphStyle() {
        val list = DocNode("bulletList", attrs = mapOf("listStyleType" to docString("square")),
            content = listOf(DocNode("listItem", content = listOf(paragraph(text("one"))))))
        val out = bodyRoundTrip(list)
        assertEquals("bulletList", out.first().type)
        assertEquals("square", out.first().attr("listStyleType")?.stringValue)
    }

    @Test
    fun anOrderedListKeepsItsNumberingStyle() {
        val list = DocNode("orderedList", attrs = mapOf("listStyleType" to docString("lower-roman")),
            content = listOf(DocNode("listItem", content = listOf(paragraph(text("first"))))))
        val out = bodyRoundTrip(list)
        assertEquals("orderedList", out.first().type)
        assertEquals("lower-roman", out.first().attr("listStyleType")?.stringValue)
    }

    @Test
    fun aNestedListKeepsItsShape() {
        val inner = DocNode("bulletList", attrs = mapOf("listStyleType" to docString("disc")),
            content = listOf(DocNode("listItem", content = listOf(paragraph(text("inner"))))))
        val outer = DocNode("bulletList", attrs = mapOf("listStyleType" to docString("disc")),
            content = listOf(DocNode("listItem", content = listOf(paragraph(text("outer")), inner))))
        val nested = bodyRoundTrip(outer).first().children.first().children[1]
        assertEquals("bulletList", nested.type)
        assertEquals("inner", nested.plainText)
    }

    /** Two numbering definitions: a parser that ends a list at the first change of `w:numId` unnests this. */
    @Test
    fun aNestedListOfADifferentKindStaysNested() {
        val inner = DocNode("orderedList", attrs = mapOf("listStyleType" to docString("decimal")),
            content = listOf(DocNode("listItem", content = listOf(paragraph(text("inner"))))))
        val outer = DocNode("bulletList", attrs = mapOf("listStyleType" to docString("disc")),
            content = listOf(DocNode("listItem", content = listOf(paragraph(text("outer")), inner))))
        val out = bodyRoundTrip(outer)
        assertEquals(1, out.size)
        val nested = out.first().children.first().children[1]
        assertEquals("orderedList", nested.type)
        assertEquals("decimal", nested.attr("listStyleType")?.stringValue)
    }

    @Test
    fun aTableKeepsItsCellShadingAndSpan() {
        val cell = DocNode("tableCell", attrs = mapOf(
            "backgroundColor" to docString("#ddeeff"), "colspan" to docInt(2), "rowspan" to docInt(1),
            "colwidth" to docNull, "borderColor" to docNull, "borderWidth" to docNull,
        ), content = listOf(paragraph(text("cell"))))
        val table = DocNode("table", content = listOf(DocNode("tableRow", content = listOf(cell))))
        assertEquals(listOf(table), bodyRoundTrip(table))
    }

    @Test
    fun aBlockquoteSurvives() {
        val quote = DocNode("blockquote", content = listOf(paragraph(text("quoted"))))
        assertEquals(listOf(quote), bodyRoundTrip(quote))
    }

    @Test
    fun aMultiParagraphBlockquoteStaysOneBlockquote() {
        val quote = DocNode("blockquote", content = listOf(
            paragraph(text("first"), attrs = mapOf("textAlign" to docString("right"))),
            paragraph(text("second")),
        ))
        assertEquals(listOf(quote), bodyRoundTrip(quote))
    }

    @Test
    fun aCodeBlockSurvives() {
        val block = DocNode("codeBlock", content = listOf(text("const x = 1;")))
        assertEquals(listOf(block), bodyRoundTrip(block))
    }

    @Test
    fun aMultiLineCodeBlockKeepsItsNewlines() {
        val block = DocNode("codeBlock", content = listOf(text("a\n  b\nc")))
        assertEquals(listOf(block), bodyRoundTrip(block))
    }

    @Test
    fun aSectionBreakSurvives() {
        val out = bodyRoundTrip(paragraph(text("a")), DocNode("sectionBreak"), paragraph(text("b")))
        assertEquals(listOf("paragraph", "sectionBreak", "paragraph"), out.map { it.type })
    }

    @Test
    fun aHorizontalRuleSurvives() {
        assertEquals(listOf("paragraph", "horizontalRule"), bodyRoundTrip(paragraph(text("a")), DocNode("horizontalRule")).map { it.type })
    }

    @Test
    fun aTableOfContentsSurvives() {
        assertTrue(bodyRoundTrip(DocNode("tableOfContents")).any { it.type == "tableOfContents" })
    }

    @Test
    fun aColumnLayoutKeepsItsCountAndChildren() {
        val layout = DocNode("columnLayout", attrs = mapOf("columns" to docInt(3)), content = listOf(paragraph(text("a")), paragraph(text("b"))))
        assertEquals(listOf(layout), bodyRoundTrip(layout))
    }

    @Test
    fun aHardBreakSurvives() {
        val out = bodyRoundTrip(paragraph(text("a"), DocNode("hardBreak"), text("b")))
        assertEquals(listOf("text", "hardBreak", "text"), out.first().children.map { it.type })
    }

    @Test
    fun escapedCharactersAndControlCharactersAreSafe() {
        val out = bodyRoundTrip(paragraph(text("a < b & \"c\" 'd' \u0001 e")))
        assertEquals("a < b & \"c\" 'd'  e", out.first().plainText)
    }

    // ── Nodes with no OOXML equivalent ──────────────────────────────────────────────────────────

    @Test
    fun aFootnoteKeepsItsText() {
        val note = DocNode("footnote", attrs = mapOf("id" to docString("fn-1"), "text" to docString("The note.")))
        assertEquals("The note.", bodyRoundTrip(paragraph(text("x"), note)).first().children.last().attr("text")?.stringValue)
    }

    @Test
    fun aCrossReferenceKeepsItsTargetAndText() {
        val out = bodyRoundTrip(
            DocNode("heading", attrs = mapOf("level" to docInt(1)), content = listOf(text("Chapter One"))),
            paragraph(text("see it", listOf(DocMark("crossRef", mapOf("headingText" to docString("Chapter One")))))),
        )
        val reference = out.last().children.first()
        assertEquals("see it", reference.text)
        assertEquals(DocMark("crossRef", mapOf("headingText" to docString("Chapter One"))), reference.marks?.first())
    }

    @Test
    fun aFieldCodeStaysAField() {
        val field = DocNode("docField", attrs = mapOf("code" to docString("page"), "arg" to docNull, "showCode" to docBool(false)))
        assertEquals(field, bodyRoundTrip(paragraph(field)).first().children.first())
    }

    @Test
    fun aCustomFieldKeepsItsArgument() {
        val field = DocNode("docField", attrs = mapOf("code" to docString("custom"), "arg" to docString("client"), "showCode" to docBool(true)))
        assertEquals(field, bodyRoundTrip(paragraph(field)).first().children.first())
    }

    /** Written as a real picture, read back as the reference — not as the data URL the bytes would inline to. */
    @Test
    fun aResolvedDriveImageComesBackAsAReference() {
        val image = DocNode("image", attrs = mapOf("src" to docString("neutrino-drive:file-7"), "width" to docString("320"),
            "shadow" to docString("md"), "caption" to docString("Fig 1")))
        val model = DocModel(DocNode("doc", content = listOf(paragraph(image))), TestMeta.base())
        val bytes = DocxWriter.write(model, DocxWriteOptions("RT", mapOf("neutrino-drive:file-7" to TestMeta.pngBytes)))
        assertEquals(image, DocxReader.read(bytes).doc.children.first().children.first())
        assertTrue("word/media/image1.png" in ZipArchive(bytes))
    }

    @Test
    fun anEmbeddedPictureKeepsItsAltText() {
        val image = DocNode("image", attrs = mapOf("src" to docString("neutrino-drive:file-7"), "width" to docString("320"), "alt" to docString("A cat on a wall")))
        val model = DocModel(DocNode("doc", content = listOf(paragraph(image))), TestMeta.base())
        val bytes = DocxWriter.write(model, DocxWriteOptions("RT", mapOf("neutrino-drive:file-7" to TestMeta.pngBytes)))
        assertEquals(image, DocxReader.read(bytes).doc.children.first().children.first())
    }

    /** A save made offline: the placeholder carries the whole node, so nothing is lost. */
    @Test
    fun anUnresolvedDriveImageStillComesBackAsAnImage() {
        val image = DocNode("image", attrs = mapOf("src" to docString("neutrino-drive:file-7"), "width" to docString("320")))
        assertEquals(image, bodyRoundTrip(paragraph(image)).first().children.first())
    }

    @Test
    fun aSheetEmbedKeepsItsAttributes() {
        val embed = DocNode("sheetEmbed", attrs = mapOf("spreadsheetId" to docString("s1"), "sheetId" to docString("sh1"),
            "title" to docString("Q1"), "cachedData" to docNull))
        assertEquals(embed, bodyRoundTrip(paragraph(text("before")), embed).last())
    }

    @Test
    fun aDiagramEmbedKeepsItsAttributes() {
        val embed = DocNode("diagramEmbed", attrs = mapOf("diagramId" to docString("d1"), "title" to docString("Flow"), "cachedSvg" to docNull))
        assertEquals(embed, bodyRoundTrip(embed, paragraph(text("after"))).first())
    }

    /** The placeholder style keeps a placeholder apart from the italic text a user typed beside it. */
    @Test
    fun aPlaceholderBesideItalicTextIsStillFound() {
        val image = DocNode("image", attrs = mapOf("src" to docString("neutrino-drive:file-1"), "width" to docString("200")))
        val embed = DocNode("sheetEmbed", attrs = mapOf("spreadsheetId" to docString("s9"), "title" to docString("Later")))
        val italic = text("about that", listOf(DocMark("italic")))
        val out = bodyRoundTrip(paragraph(image, italic), embed)
        assertEquals(image, out.first().children.first())
        assertEquals(italic, out.first().children.last())
        assertEquals(embed, out.last())
    }

    // ── Layout metadata ─────────────────────────────────────────────────────────────────────────

    private fun withMeta(meta: DocValue) = roundTrip(DocModel(DocNode("doc", content = listOf(paragraph(text("x")))), meta))

    @Test
    fun pageSetupSurvives() {
        val pageSetup = mapOf("pageSize" to docString("a4"), "orientation" to docString("landscape"),
            "marginTop" to docInt(90), "marginBottom" to docInt(54), "marginLeft" to docInt(108), "marginRight" to docInt(36))
        assertEquals(docObject(pageSetup), withMeta(TestMeta.base(pageSetup = pageSetup)).meta["pageSetup"])
    }

    @Test
    fun headerAndFooterSlotsSurviveWithFieldsLeftAsFields() {
        val meta = TestMeta.base(variants = mapOf("default" to TestMeta.band(listOf("Acme", "Report", "{{date}}"), listOf("", "Page {{page}} of {{pages}}", "v2"))))
        val variants = withMeta(meta).meta["headerFooter"]["variants"]
        assertEquals(TestMeta.slots(listOf("Acme", "Report", "{{date}}")), variants["default"]["header"])
        assertEquals(TestMeta.slots(listOf("", "Page {{page}} of {{pages}}", "v2")), variants["default"]["footer"])
    }

    @Test
    fun aDifferentFirstPageSurvives() {
        val out = withMeta(TestMeta.base(differentFirstPage = true, variants = mapOf("first" to TestMeta.band(listOf("First", "", ""), listOf("", "", "")))))
        assertEquals(docBool(true), out.meta["headerFooter"]["differentFirstPage"])
        assertEquals(docString("First"), out.meta["headerFooter"]["variants"]["first"]["header"]["left"])
    }

    @Test
    fun differentOddAndEvenPagesSurvive() {
        val out = withMeta(TestMeta.base(differentEvenOdd = true, variants = mapOf("even" to TestMeta.band(listOf("Even", "", ""), listOf("", "", "")))))
        assertEquals(docBool(true), out.meta["headerFooter"]["differentEvenOdd"])
        assertEquals(docString("Even"), out.meta["headerFooter"]["variants"]["even"]["header"]["left"])
    }

    @Test
    fun headerAndFooterMarginsSurvive() {
        val out = withMeta(TestMeta.base(headerMargin = 54, footerMargin = 27, variants = mapOf("default" to TestMeta.band(listOf("x", "", ""), listOf("", "", "")))))
        assertEquals(docInt(54), out.meta["headerFooter"]["headerMargin"])
        assertEquals(docInt(27), out.meta["headerFooter"]["footerMargin"])
    }

    @Test
    fun aWatermarkSurvives() = assertEquals(docString("CONFIDENTIAL"), withMeta(TestMeta.base(watermark = "CONFIDENTIAL")).meta["watermarkText"])

    @Test
    fun backgroundColourSurvives() = assertEquals(docString("#fafafa"), withMeta(TestMeta.base(bgColor = "#fafafa")).meta["bgColor"])

    @Test
    fun theThemeNameSurvives() = assertEquals(docString("serif"), withMeta(TestMeta.base(theme = "serif")).meta["docTheme"])

    @Test
    fun documentPropertiesSurvive() {
        val properties = mapOf("author" to docString("Ada"), "subject" to docString("Engines"), "company" to docString(""),
            "category" to docString("Notes"), "keywords" to docString("a,b"), "manager" to docString(""), "custom" to docObject())
        assertEquals(docObject(properties), withMeta(TestMeta.base(properties = properties)).meta["properties"])
    }

    @Test
    fun companyManagerAndUserDefinedPropertiesSurvive() {
        val properties = mapOf("author" to docString(""), "subject" to docString(""), "company" to docString("Acme"),
            "category" to docString(""), "keywords" to docString(""), "manager" to docString("Grace"),
            "custom" to docObject("client" to docString("Initech"), "ref" to docString("R-42")))
        assertEquals(docObject(properties), withMeta(TestMeta.base(properties = properties)).meta["properties"])
    }

    @Test
    fun aDocumentWithNoAuthorDoesNotAcquireOne() =
        assertEquals(docString(""), withMeta(TestMeta.base()).meta["properties"]["author"])

    // ── The whole thing at once ─────────────────────────────────────────────────────────────────

    @Test
    fun aDocumentWithOneOfEverythingComesBackEqual() {
        val meta = TestMeta.base(
            watermark = "DRAFT",
            bgColor = "#fdfdfd",
            theme = "serif",
            properties = mapOf("author" to docString("Ada"), "subject" to docString("Engines"), "company" to docString(""),
                "category" to docString("Notes"), "keywords" to docString("a,b"), "manager" to docString(""), "custom" to docObject()),
            pageSetup = mapOf("pageSize" to docString("a4"), "orientation" to docString("landscape"),
                "marginTop" to docInt(90), "marginBottom" to docInt(54), "marginLeft" to docInt(108), "marginRight" to docInt(36)),
            differentFirstPage = true,
            variants = mapOf(
                "default" to TestMeta.band(listOf("Acme", "Report", "{{date}}"), listOf("", "Page {{page}} of {{pages}}", "v2")),
                "first" to TestMeta.band(listOf("Cover", "", ""), listOf("", "", "")),
            ),
            headerText = "Report",
            footerText = "Page {{page}} of {{pages}}",
            showPageNumbers = true,
        )
        val content = listOf(
            DocNode("heading", attrs = mapOf("level" to docInt(1)), content = listOf(text("Chapter One"))),
            paragraph(
                text("plain "), text("bold", listOf(DocMark("bold"))), text(" and "),
                text("coloured", listOf(DocMark("textStyle", mapOf("color" to docString("#ff0000"), "fontSize" to docString("18pt"), "fontFamily" to docString("Georgia"))))),
                DocNode("footnote", attrs = mapOf("id" to docString("fn-1"), "text" to docString("A note."))),
                attrs = mapOf("textAlign" to docString("center"), "indent" to docInt(2)),
            ),
            DocNode("bulletList", attrs = mapOf("listStyleType" to docString("square")), content = listOf(
                DocNode("listItem", content = listOf(paragraph(text("one")))),
                DocNode("listItem", content = listOf(paragraph(text("two")))),
            )),
            DocNode("orderedList", attrs = mapOf("listStyleType" to docString("upper-roman")), content = listOf(
                DocNode("listItem", content = listOf(paragraph(text("first")))),
            )),
            DocNode("blockquote", content = listOf(paragraph(text("quoted")))),
            DocNode("codeBlock", content = listOf(text("const x = 1;"))),
            DocNode("table", content = listOf(DocNode("tableRow", content = listOf(
                DocNode("tableCell", attrs = mapOf("colspan" to docInt(1), "rowspan" to docInt(1), "colwidth" to docNull,
                    "backgroundColor" to docString("#ddeeff"), "borderColor" to docNull, "borderWidth" to docNull),
                    content = listOf(paragraph(text("cell")))),
            )))),
            DocNode("tableOfContents"),
            DocNode("sectionBreak"),
            paragraph(
                text("see it", listOf(DocMark("crossRef", mapOf("headingText" to docString("Chapter One"))))),
                DocNode("docField", attrs = mapOf("code" to docString("page"), "arg" to docNull, "showCode" to docBool(false))),
            ),
            DocNode("sheetEmbed", attrs = mapOf("spreadsheetId" to docString("s1"), "title" to docString("Q1"))),
        )
        val out = roundTrip(DocModel(DocNode("doc", content = content), meta))
        assertEquals(content, out.doc.children)
        assertEquals(meta, out.meta)
    }

    // ── The stored envelope ─────────────────────────────────────────────────────────────────────

    @Test
    fun theJsonEnvelopeRoundTripsToo() {
        val model = DocModel(DocNode("doc", content = listOf(paragraph(text("hello")))), TestMeta.base())
        val json = model.toJSON()
        assertEquals(model, DocModel.fromJSON(json))
        assertEquals(json, DocxCodec.decode(DocxCodec.encode(DocModel.fromJSON(json)!!, "Report")).toJSON())
    }

    /** The offline cache decides whether a document changed by comparing what it encoded. */
    @Test
    fun encodingIsDeterministic() {
        val model = DocModel(DocNode("doc", content = listOf(paragraph(text("hello")))), TestMeta.base())
        assertTrue(DocxCodec.encode(model, "R").contentEquals(DocxCodec.encode(model, "R")))
    }

    @Test
    fun anEmptyDocumentIsAValidPackage() {
        val bytes = DocxCodec.emptyDocument("Untitled document")
        assertTrue(DocxCodec.isDocument(bytes))
        assertEquals(emptyList<DocNode>(), DocxCodec.decode(bytes).doc.children)
    }

    @Test
    fun imageSourcesAreReportedInOrderWithoutDuplicates() {
        val model = DocModel(DocNode("doc", content = listOf(
            paragraph(DocNode("image", attrs = mapOf("src" to docString("neutrino-drive:a")))),
            paragraph(DocNode("image", attrs = mapOf("src" to docString("neutrino-drive:b"))), DocNode("image", attrs = mapOf("src" to docString("neutrino-drive:a")))),
        )))
        assertEquals(listOf("neutrino-drive:a", "neutrino-drive:b"), DocxCodec.imageSources(model))
    }

    @Test
    fun namesKeepOneExtension() {
        assertEquals("Report.docx", DocxCodec.withExtension("Report"))
        assertEquals("Report.docx", DocxCodec.withExtension("Report.docx"))
        assertEquals("Report", DocxCodec.strippingExtension("Report.DOCX"))
        assertEquals("Q3.report", DocxCodec.strippingExtension("Q3.report"))
        assertEquals("Old.doc", DocxCodec.strippingExtension("Old.doc"))
    }

    @Test
    fun aJsonStringThatIsNotADocumentIsRejected() {
        assertEquals(null, DocModel.fromJSON("{\"type\":\"paragraph\"}"))
        assertEquals(null, DocModel.fromJSON("not json"))
        assertFalse(DocxCodec.isDocument("hello".toByteArray()))
        try {
            DocxCodec.decode("hello".toByteArray()); fail()
        } catch (_: ZipException.NotAnArchive) {
        }
    }
}
