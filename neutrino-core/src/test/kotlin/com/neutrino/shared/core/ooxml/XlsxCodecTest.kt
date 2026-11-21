package com.neutrino.shared.core.ooxml

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XlsxCodecTest {

    private fun sheet(model: JsonObject, i: Int = 0) = model["sheets"]!!.jsonArray[i].jsonObject
    private fun cell(model: JsonObject, id: String) = sheet(model)["cells"]!!.jsonObject[id]!!.jsonObject

    @Test
    fun everyCrossCheckCaseRoundTrips() {
        for ((name, model) in XlsxCrossCheck.cases()) {
            val once = XlsxCodec.decode(XlsxCodec.encode(model))
            val twice = XlsxCodec.decode(XlsxCodec.encode(once))
            assertEquals(name, XlsxCrossCheck.canonical(once), XlsxCrossCheck.canonical(twice))
        }
    }

    @Test
    fun valuesFormulasAndCodesSurvive() {
        val model = XlsxCodec.decode(XlsxCodec.encode(XlsxCrossCheck.cases().first().second))
        assertEquals("=SUM(B1:B1)", cell(model, "C1")["raw"]!!.jsonPrimitive.content)
        assertEquals("42", cell(model, "C1")["value"]!!.jsonPrimitive.content)
        assertEquals("02134", cell(model, "A2")["raw"]!!.jsonPrimitive.content)
        assertEquals("1e5", cell(model, "B2")["raw"]!!.jsonPrimitive.content)
        // Entered text with a thousands separator is stored as the number it means.
        assertEquals("1234.5", cell(model, "D2")["raw"]!!.jsonPrimitive.content)
        assertEquals("0.1", cell(model, "E2")["raw"]!!.jsonPrimitive.content)
        assertEquals("a & b < c \"q\"", cell(model, "A3")["raw"]!!.jsonPrimitive.content)
        assertEquals("Second   bad  name", sheet(model, 1)["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun clipAndChartsComeBackThroughTheExtrasPart() {
        val styles = XlsxCodec.decode(XlsxCodec.encode(XlsxCrossCheck.cases()[1].second))
        assertEquals("clip", cell(styles, "H1")["cellStyle"]!!.jsonObject["wrapMode"]!!.jsonPrimitive.content)
        val charts = XlsxCodec.decode(XlsxCodec.encode(XlsxCrossCheck.cases()[4].second))
        assertEquals("Totals", sheet(charts)["charts"]!!.jsonArray[0].jsonObject["title"]!!.jsonPrimitive.content)
    }

    @Test
    fun partsNothingHereUnderstandsAreCarriedForward() {
        val first = ZipArchive(XlsxCodec.emptyWorkbook())
        first.set("xl/pivotTables/pivotTable1.xml", "<pivotTableDefinition/>")
        first.set("[Content_Types].xml", first.text("[Content_Types].xml")!!.replace("</Types>",
            "<Override PartName=\"/xl/pivotTables/pivotTable1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.pivotTable+xml\"/></Types>"))
        val next = ZipArchive(XlsxCodec.encode(XlsxCodec.emptyModel, keep = first.serialized()))
        assertEquals("<pivotTableDefinition/>", next.text("xl/pivotTables/pivotTable1.xml"))
        assertTrue(next.text("[Content_Types].xml")!!.contains("pivotTable1.xml"))
    }

    @Test
    fun aLegacyModelPartWinsOnlyWhileTheDigestHolds() {
        val zip = ZipArchive(XlsxCodec.emptyWorkbook())
        val model = """{"sheets":[{"name":"Legacy","cells":{"A1":{"id":"A1","raw":"old","value":"old"}}}]}"""
        val envelope = JsonObject(mapOf("version" to JsonPrimitive(1), "app" to JsonPrimitive("sheets"),
            "digest" to JsonPrimitive(XlsxCodec.digest(zip)), "model" to JsonPrimitive(model)))
        zip.set(XlsxCodec.LEGACY_MODEL_PART, envelope.toString())
        assertEquals("Legacy", sheet(XlsxCodec.decode(zip.serialized()))["name"]!!.jsonPrimitive.content)
        // Something rewrote the package since: the model is not to be trusted.
        zip.set("xl/extra.xml", "<x/>")
        assertEquals("Sheet 1", sheet(XlsxCodec.decode(zip.serialized()))["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun theWebsDigestOfAKnownPackage() {
        val zip = ZipArchive()
        zip.set("a.xml", "<a/>")
        // FNV-1a 64 over "a.xml<a/>", as `digestParts` computes it.
        var h = java.math.BigInteger("cbf29ce484222325", 16)
        val mask = java.math.BigInteger.ONE.shiftLeft(64).subtract(java.math.BigInteger.ONE)
        for (b in "a.xml<a/>".toByteArray()) h = h.xor(java.math.BigInteger.valueOf(b.toLong() and 0xff)).multiply(java.math.BigInteger("100000001b3", 16)).and(mask)
        assertEquals(h.toString(16).padStart(16, '0'), XlsxCodec.digest(zip))
    }

    @Test
    fun numbersPrintAsJavaScriptPrintsThem() {
        assertEquals("15", XlsxCodec.jsNumber(15.0))
        assertEquals("15.75", XlsxCodec.jsNumber(15.75))
        assertEquals("0.1", XlsxCodec.jsNumber(0.1))
        assertEquals("1e-7", XlsxCodec.jsNumber(1e-7))
        assertEquals("1.5e+21", XlsxCodec.jsNumber(1.5e21))
        assertEquals("12345678901234567000", XlsxCodec.jsNumber(1.2345678901234567e19))
        assertEquals("0", XlsxCodec.jsNumber(-0.0))
        assertEquals(10.5, XlsxCodec.jsParseFloat("10.5pt"))
        assertNull(XlsxCodec.jsParseFloat("pt"))
    }

    @Test
    fun colourAndAddressVocabulary() {
        assertEquals("#aabbcc", XlsxCodec.normalizeColor("#ABC"))
        assertEquals("#ff8000", XlsxCodec.normalizeColor("rgb(255, 128, 0)"))
        assertEquals("FFFF8000", XlsxCodec.toArgb("rgba(255,128,0,0.5)"))
        assertEquals("#123456", XlsxCodec.fromArgb("FF123456"))
        assertEquals("AA", XlsxCodec.numberToColumn(27))
        assertEquals(27 to 10, XlsxCodec.parseCellId("AA10"))
        assertNull(XlsxCodec.parseCellId("a1"))
        assertEquals("Budget.xlsx", XlsxCodec.withExtension("Budget"))
        assertEquals("Budget", XlsxCodec.strippingExtension("Budget.XLSX"))
        assertFalse(XlsxCodec.isWorkbook("nope".toByteArray()))
        assertTrue(XlsxCodec.isWorkbook(XlsxCodec.emptyWorkbook()))
        assertEquals(Json.parseToJsonElement("""{"sheets":[{"name":"Sheet 1","cells":{}}]}"""), XlsxCodec.decode(XlsxCodec.emptyWorkbook()))
    }
}
