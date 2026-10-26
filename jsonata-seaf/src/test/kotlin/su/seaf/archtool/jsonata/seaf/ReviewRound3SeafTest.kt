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

    @Test
    fun `RV3-36 the functions section is parsed as the core does`() {
        // an entry without code is a function too: its id conflicts like any other
        val conflict = kotlin.test.assertFailsWith<JsonataException> {
            SeafEnvironment.create(SeafEnvironmentTest.NoHost, json("""{"sum": {"title": "no code"}, "wcard": {"code": "1"}}"""))
        }
        assertEquals("X0014", conflict.code)
        val message = conflict.details["message"] as String
        assertTrue(message.contains("sum (stdlib)") && message.contains("wcard (core)"), message)
        val env = SeafEnvironment.create(SeafEnvironmentTest.NoHost, json(
            """{"nocode": {"title": "no code"}, "bad": {"params": {"alias": "a"}, "code": "1"}, "res": {"code": "1", "result": {"callback": true}}, "ok": {"code": "1", "result": {"type": "number"}}}""",
        ))
        fun call(expr: String) = env.evaluate(env.compile(expr), null)
        // without code: undefined
        assertEquals(null, call("${'$'}nocode()"))
        // params that are not an array: X0017 at the call
        val bad = kotlin.test.assertFailsWith<JsonataException> { call("${'$'}bad()") }
        assertEquals("X0017", bad.code)
        assertEquals("bad", bad.functionId)
        // result does not know callback/params
        assertEquals("X0017", kotlin.test.assertFailsWith<JsonataException> { call("${'$'}res()") }.code)
        assertEquals("1", str(call("${'$'}ok()")))
        // the detector finds both declarations
        val found = SeafDetector.invalidDeclarations(json(
            """{"bad": {"params": {"alias": "a"}, "code": "1"}, "res": {"code": "1", "result": {"callback": true}}, "ok": {"code": "1"}}""",
        )).map { it.recommendation.substringBefore(":") }
        assertEquals(listOf("manifest function bad", "manifest function res"), found)
    }

    @Test
    fun `RV3-37 a schema nested too deep is an error of the schema`() {
        val env = SeafEnvironment.create(SeafEnvironmentTest.NoHost)
        val deep = "${'$'}reduce([1..1100], function(${'$'}a, ${'$'}v){ {\"items\": ${'$'}a} }, {})"
        val e = kotlin.test.assertFailsWith<JsonataException> { env.evaluate(env.compile("${'$'}jsonschema($deep)([1])"), null) }
        assertEquals("X0012", e.code)
        assertTrue((e.details["message"] as String).contains("nested deeper than 1000"), e.details.toString())
        // under $tryexecute: an error of the expression, not a host exception
        val caught = str(env.evaluate(env.compile("${'$'}tryexecute(function(){ ${'$'}jsonschema($deep)([1]) })"), null))
        assertTrue(caught.startsWith("{\"success\":false"), caught)
        assertTrue(caught.contains("X0012") || caught.contains("nested deeper"), caught)
    }

    /** A call context whose callback fails with [failure], reported as a host exception or not. */
    private class FailingCall(private val failure: RuntimeException, private val host: Boolean) : su.seaf.archtool.jsonata.CallContext {
        override val input: Value? = null
        override val timestamp: Long = 0
        override val root: Value? = null
        override fun call(function: su.seaf.archtool.jsonata.value.FunctionValue, args: List<Value?>): Value? = throw failure
        override val cancellation: su.seaf.archtool.jsonata.CancellationSignal = object : su.seaf.archtool.jsonata.CancellationSignal {
            override val isCancelled: Boolean = false
            override fun throwIfCancelled() {}
        }
        override fun remainingNanos(): Long = Long.MAX_VALUE
        override fun checkpoint() {}
        override fun isHostException(error: Throwable): Boolean = host && error === failure
        override fun <T : Any> local(key: Any, init: () -> T): T = init()
        override fun attribute(name: String): Any? = null
        override fun evaluate(expression: su.seaf.archtool.jsonata.Jsonata, input: Value?, bindings: Map<String, Value?>, frameName: String): Value? = null
    }

    @Test
    fun `RV3-38 tryexecute lets engine faults through as X0008`() {
        val core = su.seaf.archtool.jsonata.seaf.core.CoreFunctions(SeafEnvironmentTest.NoHost)
        val f = su.seaf.archtool.jsonata.FunctionRegistry.function(null, { _, _ -> null })
        val fault = IllegalStateException("engine fault")
        val e = kotlin.test.assertFailsWith<JsonataException> { core.tryExecute(FailingCall(fault, false), listOf(f, su.seaf.archtool.jsonata.value.ArrayValue())) }
        assertEquals("X0008", e.code)
        kotlin.test.assertSame(fault, e.cause)
        // an exception of a host function is caught: success false with its message
        val caught = core.tryExecute(FailingCall(IllegalStateException("source is not allowed"), true), listOf(f, su.seaf.archtool.jsonata.value.ArrayValue()))
        assertEquals("""{"success":false,"result":null,"error":"source is not allowed"}""", str(caught))
    }

    /** A host that records the manifest each $parsesource call gets. */
    private class ManifestHost : SeafHost by SeafEnvironmentTest.NoHost {
        val manifests = ArrayList<String>()
        override fun loadSource(manifest: Value?, source: Value?, request: SeafRequest?, context: su.seaf.archtool.jsonata.CallContext): Value? {
            manifests += str(manifest)
            return null
        }
        private fun str(v: Value?) = if (v == null) "undefined" else JsonWriter.stringify(v)
    }

    @Test
    fun `RV3-39 the root of a call comes from the lexical frame`() {
        val host = ManifestHost()
        val env = SeafEnvironment.create(host, json(
            """{
              "apply": {"code": "${'$'}f()", "params": [{"alias": "f", "callback": true}]},
              "maker": {"code": "function(){ ${'$'}parsesource(\"s\") }", "params": [{"alias": "x"}]},
              "load": {"code": "${'$'}parsesource(\"s\")", "params": [{"alias": "y"}]}
            }""",
        ))
        fun run(expr: String) = env.evaluate(env.compile(expr), json("""{"m": 1}"""))
        // a lambda of the outer expression called inside a manifest function: the outer input
        run("${'$'}apply(function(){ ${'$'}parsesource(\"s\") })")
        // a lambda of a manifest function called outside: the input of the function
        run("${'$'}maker(5)()")
        // the controls: directly outside and directly inside
        run("${'$'}parsesource(\"s\")")
        run("${'$'}load(7)")
        assertEquals(listOf("""{"m":1}""", """{"x":5}""", """{"m":1}""", """{"y":7}"""), host.manifests)
    }

    @Test
    fun `RV3-40 the detector finds functions passed as values`() {
        fun ids(expr: String) = SeafDetector.scan(su.seaf.archtool.jsonata.Jsonata.compile(expr).ast()).map { it.id }
        assertEquals(listOf("DEV-15"), ids("${'$'}map([\"a\"], ${'$'}get)"))
        assertEquals(listOf("DEV-15"), ids("(${'$'}s := ${'$'}set; ${'$'}s(\"k\", 1))"))
        assertEquals(listOf("DEV-15"), ids("\"k\" ~> ${'$'}set(1)"))
        assertEquals(listOf("DEV-14"), ids("${'$'}map([{}], ${'$'}jsonschema)"))
        // a name the expression binds itself is not the core's function
        assertEquals(emptyList(), ids("(${'$'}set := function(${'$'}k, ${'$'}v){ ${'$'}v }; ${'$'}set(\"k\", 1); ${'$'}map([1], ${'$'}set))"))
        assertEquals(emptyList(), ids("${'$'}map([1], function(${'$'}get){ ${'$'}get })"))
        // calls still found, the T1006 rule only for calls
        assertEquals(listOf("T1006"), ids("${'$'}wcard(\"a\", \"b\")"))
        assertEquals(emptyList(), ids("${'$'}map([1], ${'$'}wcard)"))
    }

    @Test
    fun `RV3-42 the DEV-09 probe does not run side effects`() {
        val host = LogHost(true)
        val env = SeafEnvironment.create(host)
        val probes = ArrayList<String>()
        val options = env.limits.options().let { o -> EvaluationOptions(o.maxDepth, o.timeoutMillis, o.maxSequence, deviations = { id, _ -> probes += id }) }
        fun failing(expr: String) = kotlin.test.assertFailsWith<JsonataException>(expr) { env.evaluate(env.compile(expr), null, options = options) }
        // a value after the failing one would log: the probe stops there instead of logging
        assertEquals("D3137", failing("[${'$'}log(\"before\"), ${'$'}error(\"e\"), ${'$'}log(\"after\")]").code)
        assertEquals(listOf("content=\"before\" tag=null"), host.records)
        // $set and a shared regex variable in a constructor: the store is written once
        val stored = failing("(${'$'}r := /a/; [${'$'}set(\"k\", ${'$'}match(\"a\", ${'$'}r).match), ${'$'}error(\"e\"), ${'$'}set(\"k2\", ${'$'}match(\"a\", ${'$'}r).match)])")
        assertEquals("D3137", stored.code)
        // the probe still fires where nothing has side effects
        probes.clear()
        failing("{\"a\": ${'$'}error(\"e\"), \"b\": 1 + \"x\"}")
        assertEquals(listOf("DEV-09"), probes)
    }

    @Test
    fun `RV3-43 the schema cache of an evaluation is bounded`() {
        val env = SeafEnvironment.create(SeafEnvironmentTest.NoHost)
        val functions = env.registry.extend()
            .register("cacheSize", null, { c, _ -> su.seaf.archtool.jsonata.value.NumberValue.of(c.local(su.seaf.archtool.jsonata.seaf.core.CoreFunctions.SCHEMA_CACHE) { su.seaf.archtool.jsonata.seaf.core.SchemaCache() }.size.toDouble()) })
            .freeze()
        val options = env.limits.options().let { o -> EvaluationOptions(o.maxDepth, o.timeoutMillis, o.maxSequence, functions = functions) }
        val expr = """(${'$'}n := ${'$'}count(${'$'}map([1..1000], function(${'$'}i){ ${'$'}jsonschema({"type":"number","minimum":${'$'}i})(${'$'}i) })); [${'$'}n, ${'$'}cacheSize()])"""
        assertEquals("[1000,128]", str(env.evaluate(env.compile(expr), null, options = options)))
        // the case of the review: a schema built per item, 200 000 items
        val many = """${'$'}count(${'$'}map([1..200000], function(${'$'}i){ ${'$'}jsonschema({"type":"object","properties":{"a":{"type":"number","minimum":${'$'}i}},"required":["a"]})({"a":${'$'}i}) }))"""
        assertEquals("200000", str(env.evaluate(env.compile(many), null)))
    }
}
