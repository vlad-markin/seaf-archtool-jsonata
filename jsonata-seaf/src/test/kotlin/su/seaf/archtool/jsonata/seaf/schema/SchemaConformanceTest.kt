package su.seaf.archtool.jsonata.seaf.schema

import su.seaf.archtool.jsonata.FunctionRegistry
import su.seaf.archtool.jsonata.json.JsonataJson
import su.seaf.archtool.jsonata.value.ArrayValue
import su.seaf.archtool.jsonata.value.BooleanValue
import su.seaf.archtool.jsonata.value.JsonWriter
import su.seaf.archtool.jsonata.value.ObjectValue
import su.seaf.archtool.jsonata.value.StringValue
import su.seaf.archtool.jsonata.value.Value
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The adapter against ajv 8.20.0 and ajv-formats 2.1.1 as the SEAF core configures them (story 3 step 3.2):
 * `compat/seaf/ajv-expected.jsonl` from `compat/node/ajv-expected.mjs`. Compared: compile error, validity, and the
 * multiset of `instancePath` + `keyword` + `params` of the errors; texts are not (they are networknt's, a DEV record). Known
 * differences are listed in [ALLOWED] with the DEV record that explains them.
 *
 * The order of violations is not compared (DEV-14): both lists are sorted. The order differs from ajv and stays so
 * (owner's decision, review RV3-15); a separate test pins the known difference (`ReviewRound3SeafTest`).
 */
class SchemaConformanceTest {
    private val file = File(System.getProperty("jsonata.compat"), "seaf/ajv-expected.jsonl")
    private val function = FunctionRegistry.function(null, { _, _ -> null })

    private fun revive(v: Value?): Value? = when (v) {
        is ObjectValue -> if (v.size == 1 && v["\$function"] == BooleanValue.TRUE) function else ObjectValue().also { o -> for ((k, x) in v.entries) o.put(k, revive(x)!!) }
        is ArrayValue -> ArrayValue(v.size).also { a -> for (x in v) a.add(revive(x)!!) }
        else -> v
    }

    private fun key(path: String, keyword: String, params: Value?) = "$path $keyword ${canonical(params)}"

    /** JSON with sorted keys and numbers as JS writes them. */
    private fun canonical(v: Value?): String = when (v) {
        is ObjectValue -> v.entries.sortedBy { it.first }.joinToString(",", "{", "}") { (k, x) -> JsonWriter.stringify(StringValue.of(k)) + ":" + canonical(x) }
        is ArrayValue -> v.joinToString(",", "[", "]") { canonical(it) }
        else -> JsonWriter.stringify(v)
    }

    private val ORDER_NOT_COMPARED = "the order of violations is not compared (DEV-14)"

    /** Case id (and data index) → why the outcome differs from ajv. */
    private val ALLOWED: Map<String, String> = mapOf()

    @Test
    fun `validation agrees with ajv of the core`() {
        val lines = file.readLines().filter { it.isNotBlank() }
        val header = JsonataJson.parse(lines.first()) as ObjectValue
        assertEquals("8.20.0", (header["ajv"] as StringValue).value)
        val mismatches = ArrayList<String>()
        var compared = 0
        for (line in lines.drop(1)) {
            val c = JsonataJson.parse(line) as ObjectValue
            val id = (c["id"] as StringValue).value
            val mode = if ((c["mode"] as StringValue).value == "params") SchemaMode.MANIFEST_FUNCTION else SchemaMode.JSONSCHEMA
            val schema = c["schema"]
            val expectCompileError = c["compileError"] == BooleanValue.TRUE
            val compiled = try {
                JsonSchemas.compile(schema, mode)
            } catch (e: SchemaCompileException) {
                if (!expectCompileError && id !in ALLOWED) mismatches += "$id ${JsonWriter.stringify(schema)}: compile error ${e.message}"
                compared++
                null
            }
            if (compiled == null) continue
            if (expectCompileError) {
                if (id !in ALLOWED) mismatches += "$id ${JsonWriter.stringify(schema)}: compiles, ajv: ${(c["message"] as StringValue).value}"
                compared++
                continue
            }
            for ((index, r) in (c["results"] as ArrayValue).withIndex()) {
                r as ObjectValue
                compared++
                val data = revive(r["data"])
                val expected = ((r["errors"] as? ArrayValue)?.map { e -> e as ObjectValue; key((e["instancePath"] as StringValue).value, (e["keyword"] as StringValue).value, e["params"]) } ?: emptyList()).sorted()
                val actual = compiled.validate(data).map { key(it.instancePath, it.keyword, it.params) }.sorted()
                if (expected != actual && "$id#$index" !in ALLOWED) {
                    mismatches += "$id#$index ${JsonWriter.stringify(schema)} on ${JsonWriter.stringify(r["data"])}:\n    ajv      $expected\n    networknt $actual"
                }
            }
        }
        assertTrue(compared > 300, "compared $compared")
        println("ajv conformance: $compared comparisons, ${mismatches.size} mismatches; $ORDER_NOT_COMPARED")
        assertTrue(mismatches.isEmpty(), "${mismatches.size} mismatches ($ORDER_NOT_COMPARED):\n" + mismatches.joinToString("\n"))
    }
}
