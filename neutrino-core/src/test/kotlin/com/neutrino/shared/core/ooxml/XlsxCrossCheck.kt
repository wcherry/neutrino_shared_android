package com.neutrino.shared.core.ooxml

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.Base64
import kotlin.system.exitProcess

/**
 * The Kotlin half of `scripts/crosscheck-web-xlsx.sh`. `emit` writes a set of workbooks and the
 * `.xlsx` this writer makes of each; the web reads every package with its own `readXlsx` and checks
 * it reads the same as the package its `writeXlsx` makes of the same model, then hands its own back.
 * `verify` checks the same from this side. "Reads the same" is the interoperability property.
 */
object XlsxCrossCheck {

    private fun j(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

    /** The workbooks: every construct the mapping carries, and the extras it cannot. */
    fun cases(): List<Pair<String, JsonObject>> = listOf(
        "values" to j("""{"sheets":[{"name":"Data","cells":{
            "A1":{"id":"A1","raw":"Widget","value":"Widget"},
            "B1":{"id":"B1","raw":"42","value":"42"},
            "C1":{"id":"C1","raw":"=SUM(B1:B1)","value":"42"},
            "D1":{"id":"D1","raw":"=A1","value":"Widget"},
            "E1":{"id":"E1","raw":"=NOW()","value":""},
            "A2":{"id":"A2","raw":"02134","value":"02134"},
            "B2":{"id":"B2","raw":"1e5","value":"1e5"},
            "C2":{"id":"C2","raw":"-4.25","value":"-4.25"},
            "D2":{"id":"D2","raw":"1,234.50","value":"1234.5"},
            "E2":{"id":"E2","raw":"0.10","value":"0.10"},
            "A3":{"id":"A3","raw":"a & b < c \"q\"","value":"a & b < c \"q\""},
            "B3":{"id":"B3","raw":"line one\nline two","value":"line one\nline two"}
        }},{"name":"Second: [bad]/name?","cells":{"A1":{"id":"A1","raw":"x","value":"x"}}}]}"""),
        "styles" to j("""{"sheets":[{"name":"S","cells":{
            "A1":{"id":"A1","raw":"bold","value":"bold","cellStyle":{"fontWeight":"bold","fontStyle":"italic","textDecoration":"line-through","fontSize":"14pt","color":"#1f2937","fontFamily":"Georgia"}},
            "B1":{"id":"B1","raw":"fill","value":"fill","cellStyle":{"backgroundColor":"#ffeeaa","textAlign":"center","verticalAlign":"middle","wrapMode":"wrap"}},
            "C1":{"id":"C1","raw":"box","value":"box","cellStyle":{"borderStyle":"thin","borderBottom":"thick"}},
            "D1":{"id":"D1","raw":"1234.5","value":"1234.5","cellStyle":{"numberFormat":"currency","decimalPlaces":2}},
            "E1":{"id":"E1","raw":"0.25","value":"0.25","cellStyle":{"numberFormat":"percent","decimalPlaces":1}},
            "F1":{"id":"F1","raw":"45000","value":"45000","cellStyle":{"numberFormat":"date"}},
            "G1":{"id":"G1","raw":"3","value":"3","cellStyle":{"customFormat":"0.000\" kg\""}},
            "H1":{"id":"H1","raw":"clipped text","value":"clipped text","cellStyle":{"wrapMode":"clip"}},
            "A2":{"id":"A2","raw":"","value":"","cellStyle":{"backgroundColor":"#000000"}},
            "B2":{"id":"B2","raw":"n","value":"n","cellStyle":{"numberFormat":"number","decimalPlaces":0}}
        }}]}"""),
        "structure" to j("""{"sheets":[{"name":"Layout","color":"#ff0000","colWidths":{"0":120,"3":64},"rowHeights":{"0":40,"4":21},"cells":{
            "A1":{"id":"A1","raw":"merged","value":"merged","colSpan":2,"rowSpan":2},
            "B1":{"id":"B1","raw":"","value":"","mergeAnchor":"A1"},
            "A2":{"id":"A2","raw":"","value":"","mergeAnchor":"A1"},
            "B2":{"id":"B2","raw":"","value":"","mergeAnchor":"A1"},
            "A4":{"id":"A4","raw":"Name","value":"Name"},"B4":{"id":"B4","raw":"Qty","value":"Qty"},
            "A5":{"id":"A5","raw":"x","value":"x"},"B5":{"id":"B5","raw":"2","value":"2"}
        },"tables":[{"id":"t1","styleId":"blue-medium","minR":4,"maxR":5,"minC":1,"maxC":2}]}]}"""),
        "conditional-formats" to j("""{"sheets":[{"name":"CF","cells":{"A1":{"id":"A1","raw":"5","value":"5"}},"conditionalFormats":[
            {"id":"r1","range":"A1:A10","rule":{"kind":"singleColor","condition":"greaterThan","value":"3","format":{"backgroundColor":"#ffdcdc","fontWeight":"bold"}}},
            {"id":"r2","range":"A1:A10","rule":{"kind":"singleColor","condition":"between","value":"1","value2":"9","format":{"color":"#0b6b2e"}}},
            {"id":"r3","range":"B1:B10","rule":{"kind":"singleColor","condition":"containsText","value":"urgent","format":{"fontStyle":"italic"}}},
            {"id":"r4","range":"B1:B10","rule":{"kind":"singleColor","condition":"isEmpty","format":{"backgroundColor":"#eeeeee"}}},
            {"id":"r5","range":"C1:C10","rule":{"kind":"singleColor","condition":"dateIsToday","format":{"backgroundColor":"#dbe9ff"}}},
            {"id":"r6","range":"C1:C10","rule":{"kind":"singleColor","condition":"dateIsPastDue","format":{"color":"#8a1414"}}},
            {"id":"r7","range":"D1:D10","rule":{"kind":"colorScale","minColor":"#ffffff","midColor":"#ffeb84","maxColor":"#63be7b"}},
            {"id":"r8","range":"D1:D10","rule":{"kind":"dataBar","color":"#638ec6","gradient":true}},
            {"id":"r9","range":"E1:E10","rule":{"kind":"iconSet","iconSet":"arrows"}},
            {"id":"r10","range":"E1:E10","rule":{"kind":"topBottom","direction":"bottom","type":"percent","value":15,"format":{"backgroundColor":"#fff4cc"}}},
            {"id":"r11","range":"F1:F10","rule":{"kind":"average","direction":"below","format":{"color":"#123a7a"}}},
            {"id":"r12","range":"F1:F10","rule":{"kind":"duplicates","format":{"backgroundColor":"#ffdcdc"}}},
            {"id":"r13","range":"G1:G10","rule":{"kind":"formula","formula":"=G1>10","format":{"textDecoration":"underline"}},"stopIfTrue":true},
            {"id":"r14","range":"H1:H10","rule":{"kind":"progressBar","color":"#34c759","minValue":0,"maxValue":100,"showLabel":true}},
            {"id":"r15","range":"H1:H10","rule":{"kind":"heatMap","preset":"temperature"}},
            {"id":"r16","range":"I1:I10","rule":{"kind":"themeColor","token":"warning","condition":"lessThan","value":"0"}},
            {"id":"r17","range":"I1:I10","rule":{"kind":"statusIndicator","template":"traffic"}},
            {"id":"r18","range":"J1:J10","rule":{"kind":"variable","variableId":"v1"}}
        ],"cfVariables":[{"id":"v1","name":"Threshold","rule":{"kind":"singleColor","condition":"greaterThan","value":"10","format":{"backgroundColor":"#ffdcdc"}}}]}]}"""),
        "charts-carried" to j("""{"sheets":[{"name":"Chart","cells":{"A1":{"id":"A1","raw":"1","value":"1"}},
            "charts":[{"id":"c1","type":"bar","range":"A1:A3","title":"Totals","options":{"stacked":false,"colors":["#007aff"]}}]},{"name":"Empty","cells":{}}]}"""),
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
        val cases = cases().map { (name, model) ->
            JsonObject(mapOf("name" to JsonPrimitive(name), "model" to model, "xlsx" to JsonPrimitive(b64.encodeToString(XlsxCodec.encode(model)))))
        }
        out.writeText(JsonObject(mapOf("cases" to JsonArray(cases))).toString())
    }

    /** Numbers compared by value: `40` and `40.0` are the same width. */
    fun canonical(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.mapValues { canonical(it.value) }.toSortedMap())
        is JsonArray -> JsonArray(e.map(::canonical))
        is JsonPrimitive -> if (!e.isString) e.content.toDoubleOrNull()?.let { JsonPrimitive(it) } ?: e else e
        else -> e
    }

    private fun verify(androidFile: File, webFile: File): Boolean {
        val android = Json.parseToJsonElement(androidFile.readText()).jsonObject["cases"]!!.jsonArray.associateBy { it.jsonObject["name"]!!.jsonPrimitive.content }
        var failures = 0
        for (case in Json.parseToJsonElement(webFile.readText()).jsonObject["cases"]!!.jsonArray) {
            val name = case.jsonObject["name"]!!.jsonPrimitive.content
            val ours = canonical(XlsxCodec.decode(unb64.decode(android.getValue(name).jsonObject["xlsx"]!!.jsonPrimitive.content)))
            val theirs = canonical(XlsxCodec.decode(unb64.decode(case.jsonObject["xlsx"]!!.jsonPrimitive.content)))
            val ok = ours == theirs
            println("${if (ok) "PASS" else "FAIL"}  web-written $name reads on Android as Android's own does")
            if (!ok) {
                failures++
                println("  android: $ours")
                println("  web:     $theirs")
            }
        }
        return failures == 0
    }
}
