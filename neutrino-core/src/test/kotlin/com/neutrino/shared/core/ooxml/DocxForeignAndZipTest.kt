package com.neutrino.shared.core.ooxml

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/**
 * A `.docx` written by something that has never heard of Neutrino. Nothing may be *lost*: an
 * unrecognised construct degrades to its text. The mirror of `DocxForeignDocumentTests.swift`.
 */
class DocxForeignDocumentTest {

    private fun foreignDocument(): ByteArray {
        val body = "<w:p><w:pPr><w:pStyle w:val=\"Heading1\"/></w:pPr><w:r><w:t>Foreign Title</w:t></w:r></w:p>" +
            "<w:p><w:r><w:rPr><w:b/></w:rPr><w:t>body text</w:t></w:r></w:p>" +
            "<w:p><w:pPr><w:pStyle w:val=\"IntenseQuote\"/></w:pPr><w:r><w:t>styled oddly</w:t></w:r></w:p>" +
            "<w:tbl><w:tr><w:tc><w:p><w:r><w:t>c1</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>c2</w:t></w:r></w:p></w:tc></w:tr></w:tbl>" +
            "<w:p><w:pPr><w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"7\"/></w:numPr></w:pPr><w:r><w:t>a bullet</w:t></w:r></w:p>" +
            "<w:sectPr><w:pgSz w:w=\"15840\" w:h=\"12240\" w:orient=\"landscape\"/>" +
            "<w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\"/></w:sectPr>"
        val document = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<w:document xmlns:w=\"${OoxmlNamespace.W}\" xmlns:r=\"${OoxmlNamespace.R}\"><w:body>$body</w:body></w:document>"
        // Word's own numbering, with the bullet glyph it uses rather than the one this writer uses.
        val numbering = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<w:numbering xmlns:w=\"${OoxmlNamespace.W}\"><w:abstractNum w:abstractNumId=\"3\"><w:lvl w:ilvl=\"0\">" +
            "<w:numFmt w:val=\"bullet\"/><w:lvlText w:val=\"•\"/></w:lvl></w:abstractNum>" +
            "<w:num w:numId=\"7\"><w:abstractNumId w:val=\"3\"/></w:num></w:numbering>"
        return ZipArchive().apply {
            set(OoxmlPackage.CONTENT_TYPES_PART, "<Types/>")
            set(OoxmlPackage.DOCUMENT_PART, document)
            set(OoxmlPackage.NUMBERING_PART, numbering)
        }.serialized()
    }

    @Test
    fun readsItsStructureWithoutAnExtrasPart() {
        val model = DocxReader.read(foreignDocument())
        val types = model.doc.children.map { it.type }
        assertTrue("heading" in types)
        assertTrue("table" in types)
        assertTrue("bulletList" in types)
        assertEquals(docString("landscape"), model.meta["pageSetup"]["orientation"])
        assertEquals(docString("default"), model.meta["docTheme"])
    }

    @Test
    fun keepsTheTextOfAParagraphWhoseStyleItDoesNotKnow() {
        val text = DocxReader.read(foreignDocument()).doc.plainText
        for (part in listOf("Foreign Title", "styled oddly", "c1", "c2")) assertTrue(part, part in text)
    }

    @Test
    fun keepsRunFormattingAForeignWriterApplied() {
        val body = DocxReader.read(foreignDocument()).doc.children.first { it.children.firstOrNull()?.text == "body text" }
        assertEquals(listOf(DocMark("bold")), body.children.first().marks)
    }

    @Test
    fun readsAListWrittenWithWordsOwnNumbering() {
        val list = DocxReader.read(foreignDocument()).doc.children.first { it.type == "bulletList" }
        assertEquals("disc", list.attr("listStyleType")?.stringValue)
        assertEquals("a bullet", list.plainText)
    }

    @Test
    fun rejectsAZipThatIsNotADocument() {
        val bytes = ZipArchive().apply { set("hello.txt", "not a document") }.serialized()
        assertFalse(DocxCodec.isDocument(bytes))
        try {
            DocxReader.read(bytes); fail()
        } catch (_: DocxException.NotADocument) {
        }
    }

    /** Another editor drops the custom part; the base content is real OOXML and still reads. */
    @Test
    fun aPackageWithNoExtrasStillReadsItsImages() {
        val model = DocModel(DocNode("doc", content = listOf(DocNode("paragraph", attrs = emptyMap(), content = listOf(
            DocNode("image", attrs = mapOf("src" to docString("neutrino-drive:file-1"), "width" to docString("200"))),
        )))), TestMeta.base())
        val bytes = DocxWriter.write(model, DocxWriteOptions("RT", mapOf("neutrino-drive:file-1" to TestMeta.pngBytes)))
        val archive = ZipArchive(bytes).apply { remove(DocxMapping.EXTRAS_PART) }
        val image = DocxReader.read(archive.serialized()).doc.children.first().children.first()
        assertEquals("image", image.type)
        assertTrue(image.attr("src")?.stringValue!!.startsWith("data:image/png;base64,"))
    }

    /** Entities, CDATA, comments, a DOCTYPE, CRLF and a BOM — what other producers write. */
    @Test
    fun theXmlParserReadsWhatOtherProducersWrite() {
        val xml = "﻿<?xml version=\"1.0\"?>\r\n<!DOCTYPE x [<!ENTITY e \"boom\">]><!-- c --><a xmlns:w=\"${OoxmlNamespace.W}\" w:val=\"x&amp;y\" plain='q'>" +
            "one &lt;&#65;&#x42;&e;<![CDATA[<raw>]]><b/>\r\ntwo</a>"
        val root = XmlElement.parse(xml)!!
        assertEquals("a", root.localName)
        assertEquals("x&y", root.value)
        assertEquals("q", root.attribute("plain"))
        // An undefined entity is left as written: nothing from a DOCTYPE is ever honoured.
        assertEquals("one <AB&e;<raw>\ntwo", root.textContent)
        assertEquals(1, root.children.size)
    }

    @Test
    fun aTruncatedPartKeepsWhatWasRead() {
        val root = XmlElement.parse("<w:numbering><w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/></w:num><w:num w:nu")
        assertEquals(1, root?.elements("num")?.size)
    }
}

/** The container: a `.docx` Word refuses to open is not a document, however good its XML. */
class ZipArchiveTest {

    @Test
    fun roundTripsPartsInOrder() {
        val archive = ZipArchive().apply {
            set("[Content_Types].xml", "<Types/>")
            set("word/document.xml", "<w:document>hello</w:document>")
            set("word/media/image1.png", byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x00, 0x01))
        }
        val reopened = ZipArchive(archive.serialized())
        assertEquals(listOf("[Content_Types].xml", "word/document.xml", "word/media/image1.png"), reopened.names)
        assertEquals("<w:document>hello</w:document>", reopened.text("word/document.xml"))
        assertArrayEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x00, 0x01), reopened.data("word/media/image1.png"))
    }

    @Test
    fun roundTripsCompressibleContent() {
        val text = "<w:p><w:r><w:t>paragraph</w:t></w:r></w:p>".repeat(500)
        val bytes = ZipArchive().apply { set("word/document.xml", text) }.serialized()
        assertTrue("compressible content should deflate", bytes.size < text.length / 4)
        assertEquals(text, ZipArchive(bytes).text("word/document.xml"))
    }

    @Test
    fun roundTripsIncompressibleContent() {
        val random = Random(7).nextBytes(4096)
        assertArrayEquals(random, ZipArchive(ZipArchive().apply { set("word/media/image1.jpeg", random) }.serialized()).data("word/media/image1.jpeg"))
    }

    @Test
    fun twoWritesOfTheSamePartsAreIdentical() {
        val archive = ZipArchive().apply {
            set("word/document.xml", "<w:document/>")
            set("word/styles.xml", "<w:styles/>")
        }
        assertArrayEquals(archive.serialized(), archive.serialized())
    }

    @Test
    fun contentTypesIsWrittenFirstWhateverOrderItWasAddedIn() {
        val archive = ZipArchive().apply {
            set("word/document.xml", "<w:document/>")
            set("[Content_Types].xml", "<Types/>")
        }
        assertEquals("[Content_Types].xml", ZipArchive(archive.serialized()).names.first())
    }

    @Test
    fun replacingAPartKeepsItsPosition() {
        val archive = ZipArchive().apply {
            set("a.xml", "<a/>")
            set("b.xml", "<b/>")
            set("a.xml", "<a2/>")
        }
        assertEquals(listOf("a.xml", "b.xml"), archive.names)
        assertEquals("<a2/>", archive.text("a.xml"))
    }

    @Test
    fun rejectsBytesThatAreNotAnArchive() {
        try {
            ZipArchive("not a zip".toByteArray()); fail()
        } catch (_: ZipException.NotAnArchive) {
        }
        assertFalse(ZipArchive.looksLikeArchive("not a zip".toByteArray()))
    }

    @Test
    fun readsAnArchiveTheJdkWrote() {
        // A zip written with data descriptors and directory entries, as other tools write them.
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("word/"))
            zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry("word/document.xml"))
            zip.write("<w:document/>".repeat(100).toByteArray())
            zip.closeEntry()
        }
        val archive = ZipArchive(out.toByteArray())
        assertEquals(listOf("word/document.xml"), archive.names)
        assertEquals("<w:document/>".repeat(100), archive.text("word/document.xml"))
    }
}
