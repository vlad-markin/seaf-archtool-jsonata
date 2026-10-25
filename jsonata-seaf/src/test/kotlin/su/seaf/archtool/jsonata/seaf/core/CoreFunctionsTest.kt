package su.seaf.archtool.jsonata.seaf.core

import org.junit.jupiter.api.Assumptions.assumeTrue
import su.seaf.archtool.jsonata.Budgets
import su.seaf.archtool.jsonata.CallContext
import su.seaf.archtool.jsonata.CancellationToken
import su.seaf.archtool.jsonata.EvaluationOptions
import su.seaf.archtool.jsonata.FunctionSource
import su.seaf.archtool.jsonata.json.JsonataJson
import su.seaf.archtool.jsonata.parser.JsonataException
import su.seaf.archtool.jsonata.seaf.SeafEnvironment
import su.seaf.archtool.jsonata.seaf.SeafHost
import su.seaf.archtool.jsonata.seaf.SeafRequest
import su.seaf.archtool.jsonata.value.JsonWriter
import su.seaf.archtool.jsonata.value.ObjectValue
import su.seaf.archtool.jsonata.value.StringValue
import su.seaf.archtool.jsonata.value.Value
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Core functions of SEAF (story 3 step 3.3; TC-23 on synthetic inputs until the reference values of step 3.0). */
class CoreFunctionsTest {
    private class Host : SeafHost {
        val logged = ArrayList<String>()
        val schemaCalls = AtomicInteger()
        var lastRequest: SeafRequest? = null
        var lastManifest: Value? = null
        override fun loadSource(manifest: Value?, source: Value?, request: SeafRequest?, context: CallContext): Value? {
            lastRequest = request
            lastManifest = manifest
            if ((source as? StringValue)?.value == "fail") throw IllegalStateException("source is not allowed")
            return JsonataJson.parse("""{"loaded": ${JsonWriter.stringify(source)}}""")
        }
        override fun manifestSchema(): Value {
            schemaCalls.incrementAndGet()
            return JsonataJson.parse("""{"type":"object"}""")
        }
        override fun versions(): Value = JsonataJson.parse("""{"core":"1.0"}""")
        override fun log(tag: String?, content: Value?) {
            logged += "$tag:${JsonWriter.stringify(content)}"
        }
    }

    private val host = Host()
    private val env = SeafEnvironment.create(host)

    private fun json(text: String): Value = JsonataJson.parse(text)
    private fun str(v: Value?) = if (v == null) "undefined" else JsonWriter.stringify(v)
    private fun eval(expr: String, input: Value? = null, options: EvaluationOptions = env.limits.options(), request: SeafRequest? = null): String =
        str(env.evaluate(env.compile(expr), input, request = request, options = options))

    @Test
    fun `every function is registered as a core function with a full description`() {
        val catalog = env.registry.catalog()
        for (name in CoreFunctions.NAMES) {
            val f = env.registry.lookup(name)
            assertTrue(f != null, name)
            val doc = CoreDocs.doc(name)
            assertTrue(doc.summary.get()!!.length > 10, "$name summary")
            assertTrue(doc.result != null, "$name result")
        }
        assertTrue(env.registry.conflicts().any { it.name == "lookup" && it.effective == FunctionSource.CORE })
        assertTrue(catalog.toString().isNotEmpty())
    }

    @Test
    fun `examples of the descriptions give the shown results`() {
        var count = 0
        for (name in CoreFunctions.NAMES) for (ex in CoreDocs.doc(name).examples) {
            assertEquals(ex.result, eval(ex.expr), ex.expr)
            count++
        }
        assertTrue(count >= 10, "examples: $count")
    }

    @Test
    fun lookup() {
        assertEquals("null", eval("\$lookup({\"k\": 1}, null)"))
        assertEquals("1", eval("\$lookup(\"k\")", json("""{"k": 1}""")))
        assertEquals("[1,2]", eval("\$lookup(items, \"k\")", json("""{"items":[{"k":1},{"x":0},{"k":2}]}""")))
        assertEquals("undefined", eval("\$lookup({\"k\": 1}, \"x\")"))
        assertEquals("2", eval("\$lookup({\"1\": 2}, 1)"))
    }

    @Test
    fun `tryexecute catches errors of the expression but not its limits (design table)`() {
        assertEquals("""{"success":false,"result":null,"error":"The right side of the \"+\" operator must evaluate to a number"}""", eval("\$tryexecute(function(){ 1 + \"a\" })"))
        assertEquals("""{"success":true,"result":[1,2]}""", eval("\$tryexecute(function(\$a){ \$a }, [[1, 2]])"))
        assertEquals("""{"success":true}""", eval("\$tryexecute(function(){ \$nothing })"))
        // a host exception: its message
        assertEquals("""{"success":false,"result":null,"error":"source is not allowed"}""", eval("\$tryexecute(\$parsesource, \"fail\")"))
        // D2014 is an error of the expression
        assertTrue(eval("\$tryexecute(function(){ [1..10000001] })").contains("\"success\":false"))
        // not caught: depth, deadline, budgets, cancellation
        val deep = "\$tryexecute(function(){ (\$f := function(\$n){ 1 + \$f(\$n) }; \$f(0)) })"
        assertEquals("D1011", assertFailsWith<JsonataException> { eval(deep, options = EvaluationOptions(maxDepth = 5000)) }.code)
        val loop = "\$tryexecute(function(){ (\$f := function(){ \$f() }; \$f()) })"
        assertEquals("D1012", assertFailsWith<JsonataException> { eval(loop, options = EvaluationOptions(timeoutMillis = 100)) }.code)
        assertEquals("X0010", assertFailsWith<JsonataException> { eval("\$tryexecute(function(){ [1..1000] })", options = EvaluationOptions(budgets = Budgets(maxResultSize = 100))) }.code)
        val token = CancellationToken().also { it.cancel() }
        assertEquals("X0009", assertFailsWith<JsonataException> { eval(loop, options = EvaluationOptions(cancellation = token)) }.code)
        // arguments
        assertTrue(eval("\$tryexecute(1)").contains("\"success\":false"))
    }

    @Test
    fun `set and get live for one evaluation`() {
        val e = env.compile("[\$get(\"k\"), \$set(\"k\", 1), \$get(\"k\")]")
        assertEquals("[1,1]", str(env.evaluate(e, null)))
        assertEquals("[1,1]", str(env.evaluate(e, null)))
        assertEquals("undefined", eval("\$get(\"k\")"))
    }

    @Test
    fun `jsonschema validates with the core's ajv setup`() {
        assertEquals("true", eval("\$jsonschema({\"type\": \"object\", \"required\": [\"a\"]})({\"a\": 1})"))
        val errors = eval("\$jsonschema({\"type\": \"object\", \"required\": [\"a\"], \"properties\": {\"k\": {\"enum\": [1, 2]}}})({\"k\": 3})")
        val parsed = json(errors) as su.seaf.archtool.jsonata.value.ArrayValue
        assertEquals(setOf("required", "enum"), parsed.map { ((it as ObjectValue)["keyword"] as StringValue).value }.toSet())
        assertTrue(errors.contains("\"allowedValues\":[1,2]"))
        // keyword if is dropped as the core does
        assertEquals("1", eval("\$count(\$jsonschema({\"if\": {\"type\": \"number\"}, \"then\": {\"minimum\": 5}})(1))"))
        // formats and $rels
        assertEquals("true", eval("\$jsonschema({\"format\": \"date\", \"\$rels\": {}})(\"2020-01-31\")"))
        val bad = assertFailsWith<JsonataException> { eval("\$jsonschema({\"type\": \"string\", \"example\": \"x\"})") }
        assertEquals("X0012", bad.code)
        assertEquals("Invalid schema in \$jsonschema: strict mode: unknown keyword: \"example\"", bad.message)
        // one compilation per schema value in an evaluation
        assertEquals("[true,true,true]", eval("(\$s := {\"type\": \"number\"}; [1, 2, 3].\$jsonschema(\$s)(\$))"))
    }

    @Test
    fun `host functions get the request, the manifest and logging`() {
        val request = SeafRequest(mapOf("user" to "u1"))
        assertEquals("""{"loaded":"lake/data.yaml"}""", eval("\$parsesource(\"lake/data.yaml\")", json("""{"m":1}"""), request = request))
        assertSame(request, host.lastRequest)
        assertEquals("""{"m":1}""", str(host.lastManifest))
        assertEquals("42", eval("\$log(\"t\", 42)"))
        assertEquals("\"x\"", eval("\$log(\"x\")"))
        assertEquals(listOf("t:42", "null:\"x\""), host.logged)
        assertEquals("{\"type\":\"object\"}", eval("\$manifestschema()"))
        eval("\$manifestschema()")
        assertEquals(1, host.schemaCalls.get())
        assertEquals("{\"core\":\"1.0\"}", eval("\$versions()"))
    }

    /** TC-23: reference values of the core (step 3.0, produced locally); skipped until they exist. */
    @Test
    fun `reference values of the core`() {
        val expected = File(System.getProperty("jsonata.compat"), "seaf/expected.jsonl")
        assumeTrue(expected.isFile, "compat/seaf/expected.jsonl is produced locally (step 3.0)")
        val mismatches = ArrayList<String>()
        for (line in expected.readLines().drop(1).filter { it.isNotBlank() }) {
            val c = json(line) as ObjectValue
            val expr = (c["expr"] as StringValue).value
            val input = c["input"]
            val want = c["result"]?.let { str(it) } ?: "undefined"
            val got = try {
                str(env.evaluate(env.compile(expr), input))
            } catch (e: JsonataException) {
                "error ${e.message}"
            }
            val wantError = (c["error"] as? StringValue)?.value?.let { "error $it" }
            if (got != (wantError ?: want)) mismatches += "$expr: core ${wantError ?: want}, engine $got"
        }
        assertTrue(mismatches.isEmpty(), mismatches.joinToString("\n"))
    }
}
