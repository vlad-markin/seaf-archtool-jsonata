package su.seaf.archtool.jsonata.seaf

import su.seaf.archtool.jsonata.CancellationToken
import su.seaf.archtool.jsonata.EvaluationOptions
import su.seaf.archtool.jsonata.json.JsonataJson
import su.seaf.archtool.jsonata.parser.JsonataException
import su.seaf.archtool.jsonata.value.JsonWriter
import su.seaf.archtool.jsonata.value.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Regression tests of review round 3 (`docs/review/round-3.md`) in the SEAF functions. */
class ReviewRound3SeafTest {
    private fun json(text: String): Value = JsonataJson.parse(text)
    private fun str(v: Value?) = if (v == null) "undefined" else JsonWriter.stringify(v)

    @Test
    fun `RV3-13 schema checks stop at the deadline and on cancellation`() {
        // a pattern with catastrophic backtracking: seconds on 28 letters
        val manifest = json("""{"slow": {"params": [{"alias": "s", "type": "string", "pattern": "(?:a*)*?b\\0"}], "code": "s"}}""")
        val env = SeafEnvironment.create(SeafEnvironmentTest.NoHost, manifest)
        val text = "a".repeat(28) + "!"
        val schemaCall = env.compile("""${'$'}jsonschema({"type":"string","pattern":"(?:a*)*?b\\0"})("$text")""")
        val manifestCall = env.compile("""${'$'}slow("$text")""")
        // warm up the schema library outside the measured calls
        assertEquals("true", str(env.evaluate(env.compile("""${'$'}jsonschema({"type":"string","pattern":"^a"})("a")"""), null)))
        assertEquals("\"b\\u0000\"", str(env.evaluate(env.compile("""${'$'}slow("b\u0000")"""), null)))
        fun failure(options: EvaluationOptions, expr: su.seaf.archtool.jsonata.Jsonata): Pair<String, Long> {
            val t0 = System.nanoTime()
            val e = runCatching { env.evaluate(expr, null, options = options) }.exceptionOrNull()
            val ms = (System.nanoTime() - t0) / 1_000_000
            return ((e as? JsonataException)?.code ?: "no error: $e") to ms
        }
        for (expr in listOf(schemaCall, manifestCall)) {
            val (code, ms) = failure(EvaluationOptions(timeoutMillis = 200), expr)
            assertEquals("D1012", code)
            assertTrue(ms <= 400, "D1012 after $ms ms")
            val token = CancellationToken()
            val canceller = Thread {
                Thread.sleep(100)
                token.cancel()
            }.apply { start() }
            val (cancelled, cms) = failure(EvaluationOptions(cancellation = token), expr)
            canceller.join()
            assertEquals("X0009", cancelled)
            assertTrue(cms <= 400, "X0009 after $cms ms")
        }
    }

    @Test
    fun `RV3-14 a class escape as a range bound in a schema pattern`() {
        // pinned from ajv 8.20.0 of the core (the cases are added to compat/node/ajv-expected.mjs for the local run)
        fun compile(pattern: String, mode: su.seaf.archtool.jsonata.seaf.schema.SchemaMode) =
            su.seaf.archtool.jsonata.seaf.schema.JsonSchemas.compile(json("""{"type":"string","pattern":${JsonWriter.quote(pattern)}}"""), mode)
        for (p in listOf("[\\d-z]", "^[\\w-.]+${'$'}")) {
            // params of manifest functions: the u flag, a SyntaxError in ajv
            kotlin.test.assertFailsWith<su.seaf.archtool.jsonata.seaf.schema.SchemaCompileException>(p) { compile(p, su.seaf.archtool.jsonata.seaf.schema.SchemaMode.MANIFEST_FUNCTION) }
            // $jsonschema: no u flag, the hyphen is a literal
            assertTrue(compile(p, su.seaf.archtool.jsonata.seaf.schema.SchemaMode.JSONSCHEMA).validate(json("\"-\"")).isEmpty(), p)
        }
        val word = compile("^[\\w-.]+${'$'}", su.seaf.archtool.jsonata.seaf.schema.SchemaMode.JSONSCHEMA)
        assertTrue(word.validate(json("\"a-b.c\"")).isEmpty())
        assertEquals("pattern", word.validate(json("\"a b\"")).single().keyword)
    }

    @Test
    fun `RV3-15 indexing the violations of jsonschema is a DEV-14 finding`() {
        fun found(expr: String) = SeafDetector.indexedViolations(su.seaf.archtool.jsonata.Jsonata.compile(expr).ast())
        for (e in listOf("${'$'}jsonschema(s)(x)[0]", "${'$'}jsonschema(s)(x)[2].keyword", "${'$'}jsonschema(s)(x)[-1]", "(${'$'}jsonschema(s)(x))[0]")) {
            val f = found(e)
            assertEquals(1, f.size, e)
            assertEquals("DEV-14", f[0].id)
            assertTrue(f[0].recommendation.contains("order"), f[0].recommendation)
        }
        for (e in listOf("${'$'}count(${'$'}jsonschema(s)(x))", "${'$'}jsonschema(s)(x).keyword", "${'$'}jsonschema(s)(x)[keyword = \"required\"]", "${'$'}lookup(x)[0]")) {
            assertTrue(found(e).isEmpty(), e)
        }
        // scan reports it next to the finding about the texts
        val all = SeafDetector.scan(su.seaf.archtool.jsonata.Jsonata.compile("${'$'}jsonschema(s)(x)[0]").ast()).map { it.id }
        assertEquals(listOf("DEV-14", "DEV-14"), all)
    }

    @Test
    fun `RV3-15 the known order of violations differs from ajv`() {
        val env = SeafEnvironment.create(SeafEnvironmentTest.NoHost)
        val expr = """${'$'}jsonschema({"type":"object","properties":{"c":{"type":"string"},"d":{"minimum":5}},"required":["a","b"],"additionalProperties":false})({"c":1,"d":1,"x":1}).(keyword & ":" & instancePath)"""
        val engine = str(env.evaluate(env.compile(expr), null))
        // the order of the engine (networknt: the order of the schema); a change of it must not pass unnoticed
        assertEquals("""["type:/c","minimum:/d","required:","required:","additionalProperties:"]""", engine)
        // ajv 8.20.0 of the core on the same case (reference, review round 3): the same multiset, another order
        val ajv = """["required:","required:","additionalProperties:","type:/c","minimum:/d"]"""
        assertTrue(engine != ajv)
        assertEquals(json(ajv).let { (it as su.seaf.archtool.jsonata.value.ArrayValue).map { v -> str(v) }.sorted() },
            (json(engine) as su.seaf.archtool.jsonata.value.ArrayValue).map { v -> str(v) }.sorted())
    }

    @Test
    fun `RV3-16 tryexecute checks its arguments as the core`() {
        val env = SeafEnvironment.create(SeafEnvironmentTest.NoHost)
        fun eval(expr: String) = str(env.evaluate(env.compile(expr), null))
        assertEquals("""{"success":false,"result":null,"error":"${'$'}tryexecute expects an array of arguments as its second argument"}""", eval("${'$'}tryexecute(function(${'$'}x){ ${'$'}x }, 1)"))
        assertEquals("""{"success":false,"result":null,"error":"${'$'}tryexecute expects a function as its first argument"}""", eval("${'$'}tryexecute(1, [])"))
        // an array, null and no arguments as before
        assertEquals("""{"success":true,"result":5}""", eval("${'$'}tryexecute(function(${'$'}x){ ${'$'}x }, [5])"))
        assertEquals("""{"success":true,"result":"null"}""", eval("${'$'}tryexecute(function(${'$'}x){ ${'$'}type(${'$'}x) }, null)"))
        assertEquals("""{"success":true,"result":1}""", eval("${'$'}tryexecute(function(){ 1 })"))
    }

    /** A host that keeps its log records, or drops them when logging is off (a setting of the host). */
    private class LogHost(private val enabled: Boolean) : SeafHost by SeafEnvironmentTest.NoHost {
        val records = ArrayList<String>()
        override fun log(content: Value?, tag: Value?) {
            if (enabled) records += "content=${content?.let { str(it) }} tag=${tag?.let { str(it) }}"
        }
        private fun str(v: Value) = JsonWriter.stringify(v)
    }

    @Test
    fun `RV3-17 log takes content then tag and gives no value`() {
        val host = LogHost(true)
        val env = SeafEnvironment.create(host)
        fun eval(expr: String) = str(env.evaluate(env.compile(expr), null))
        assertEquals("undefined", eval("${'$'}log(\"x\", \"tag\")"))
        assertEquals("{}", eval("{\"r\": ${'$'}log(\"x\")}"))
        assertEquals(listOf("content=\"x\" tag=\"tag\"", "content=\"x\" tag=null"), host.records)
        // logging off in the host: nothing is written, the result is the same
        val off = LogHost(false)
        val quiet = SeafEnvironment.create(off)
        assertEquals("undefined", str(quiet.evaluate(quiet.compile("${'$'}log(\"x\", \"tag\")"), null)))
        assertTrue(off.records.isEmpty())
    }

    @Test
    fun `RV3-18 lookup allowedValues and manifestschema as the core`() {
        val env = SeafEnvironment.create(SeafEnvironmentTest.NoHost)
        fun eval(expr: String, input: String? = null) = str(env.evaluate(env.compile(expr), input?.let { json(it) }))
        assertEquals("[1]", eval("${'$'}lookup([{\"a\":1}], \"a\")"))
        assertEquals("[]", eval("${'$'}lookup([{\"b\":1}], \"a\")"))
        assertEquals("undefined", eval("${'$'}lookup(5)", "{\"5\":\"x\"}"))
        assertEquals("\"x\"", eval("${'$'}lookup(\"5\")", "{\"5\":\"x\"}"))
        assertEquals("null", eval("${'$'}lookup(null)", "{\"5\":\"x\"}"))
        assertEquals("[1,2,3]", eval("${'$'}lookup([{\"k\":1},{\"k\":[2,3]}], \"k\")"))
        assertEquals("1", eval("${'$'}lookup({\"a\":1}, \"a\")"))
        // allowedValues only inside params
        val violation = eval("${'$'}jsonschema({\"enum\":[1,2]})(3)[0]")
        assertTrue(violation.contains("\"params\":{\"allowedValues\":[1,2]}"), violation)
        assertEquals("[\"instancePath\",\"schemaPath\",\"keyword\",\"params\",\"message\"]", eval("${'$'}keys(${'$'}jsonschema({\"enum\":[1,2]})(3)[0])"))
        // no schema at the host
        assertEquals("{}", eval("${'$'}manifestschema()"))
    }

    @Test
    fun `RV3-19 log is replaced for one evaluation`() {
        val host = LogHost(true)
        val env = SeafEnvironment.create(host, json("""{"inner": {"code": "${'$'}log(\"from a manifest function\")"}}"""))
        fun journal(records: MutableList<String>) = su.seaf.archtool.jsonata.FunctionDefinition(
            "log", null, { _, a -> records += JsonWriter.stringify(a[0]!!) + ":" + (a.getOrNull(1)?.let { JsonWriter.stringify(it) }); null },
            su.seaf.archtool.jsonata.FunctionDoc(su.seaf.archtool.jsonata.LocalizedText.ru("Журнал вычисления")), overridesForEvaluation = true,
        )
        val first = java.util.Collections.synchronizedList(ArrayList<String>())
        val second = java.util.Collections.synchronizedList(ArrayList<String>())
        val expr = env.compile("(${'$'}log(\$, \"t\"); ${'$'}inner(); 1)")
        fun run(input: String, records: MutableList<String>) = env.evaluate(
            expr, json(input), options = env.limits.options().let { o ->
                EvaluationOptions(o.maxDepth, o.timeoutMillis, o.maxSequence, functions = env.registry.extend().register(journal(records)).freeze())
            },
        )
        val threads = listOf(Thread { repeat(50) { run("\"a\"", first) } }, Thread { repeat(50) { run("\"b\"", second) } })
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        // each evaluation writes to its own journal only, manifest functions included; the host log sees nothing
        assertEquals(100, first.size)
        assertEquals(setOf("\"a\":\"t\"", "\"from a manifest function\":null"), first.toSet())
        assertEquals(setOf("\"b\":\"t\"", "\"from a manifest function\":null"), second.toSet())
        assertTrue(host.records.isEmpty())
        // without an override the core's $log writes to the host
        assertEquals("1", str(env.evaluate(expr, json("\"c\""))))
        assertEquals("content=\"c\" tag=\"t\"", host.records.first())
        // without the flag: X0001, as before
        val plain = su.seaf.archtool.jsonata.FunctionDefinition("log", null, { _, _ -> null }, su.seaf.archtool.jsonata.FunctionDoc(su.seaf.archtool.jsonata.LocalizedText.ru("x")))
        assertEquals("X0001", kotlin.test.assertFailsWith<JsonataException> { env.registry.extend().register(plain) }.code)
        // the flag does not apply to the standard library, to a core function without side effects, to a manifest function
        for (name in listOf("string", "lookup", "inner")) {
            val d = su.seaf.archtool.jsonata.FunctionDefinition(name, null, { _, _ -> null }, su.seaf.archtool.jsonata.FunctionDoc(su.seaf.archtool.jsonata.LocalizedText.ru("x")), overridesForEvaluation = true)
            assertEquals("X0001", kotlin.test.assertFailsWith<JsonataException>(name) { env.registry.extend().register(d) }.code, name)
        }
    }
}
