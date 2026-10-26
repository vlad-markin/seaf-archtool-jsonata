package su.seaf.archtool.jsonata.deviation

import su.seaf.archtool.jsonata.EvaluationOptions
import su.seaf.archtool.jsonata.Jsonata
import su.seaf.archtool.jsonata.parser.JsonataException
import su.seaf.archtool.jsonata.value.JsonWriter
import su.seaf.archtool.jsonata.value.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Deliberate differences from jsonata-js 2.2.2: behaviour, probes and static rules (docs/deviations.md). */
class DeviationProbeTest {
    private class Recorder : DeviationSink {
        val hits = ArrayList<Pair<String, Int?>>()
        override fun hit(id: String, position: Int?) {
            hits += id to position
        }
    }

    private fun options(r: Recorder) = EvaluationOptions(deviations = r)

    private fun json(text: String): Value? = Jsonata.compile(text).evaluate(null)

    private fun eval(expr: String, input: String? = null, r: Recorder = Recorder()): String =
        Jsonata.compile(expr).evaluate(input?.let { json(it) }, emptyMap(), options(r)).let { if (it == null) "undefined" else JsonWriter.stringify(it) }

    private fun error(expr: String, r: Recorder = Recorder()): JsonataException =
        assertFailsWith<JsonataException> { Jsonata.compile(expr).evaluate(null, emptyMap(), options(r)) }

    private fun scan(expr: String): List<String> = Deviations.scan(Jsonata.compile(expr).ast()).map { "${it.id}@${it.position}" }

    private fun hits(expr: String, input: String? = null): List<String> {
        val r = Recorder()
        runCatching { eval(expr, input, r) }
        return r.hits.map { it.first }.distinct()
    }

    @Test
    fun `DEV-01 to DEV-08 probes fire where the result may differ (step 1-4)`() {
        assertEquals(listOf("DEV-01"), hits("(\$r := \$ ~> |\$\$.a|{\"b\":99}|; [\$r, \$\$.a.b])", "{\"a\":{\"b\":1}}"))
        assertEquals(emptyList(), hits("\$ ~> |a|{\"b\":99}|", "{\"a\":{\"b\":1}}"))
        assertEquals(listOf("DEV-02"), hits("\$formatInteger(10000000, 'I')"))
        assertEquals(listOf("DEV-03"), hits("\$count(?)(5)"))
        assertEquals(listOf("DEV-03"), hits("\$uppercase(?)(1)"))
        assertEquals(emptyList(), hits("\$uppercase(?)(\"a\")"))
        assertEquals(listOf("DEV-04"), hits("\$contains(\"a\", /a++/)"))
        assertEquals(listOf("DEV-05"), hits("\$contains(\"\\uE000\", /x/)"))
        assertEquals(emptyList(), hits("\$contains(\"abc\", /x/)"))
        assertEquals(listOf("DEV-07"), hits("\$toMillis(\"2023-11-14T22:13:20\")"))
        assertEquals(emptyList(), hits("\$toMillis(\"2023-11-14T22:13:20Z\")"))
        assertEquals(listOf("DEV-08"), hits("x{\"k\": \"v\"}", "{\"x\": []}"))
        assertEquals(emptyList(), hits("x{\"k\": \"v\"}", "{\"x\": [1]}"))
    }

    @Test
    fun `DEV-01 to DEV-08 static rules`() {
        assertTrue(scan("\$ ~> |\$\$.a|{\"b\":99}|").any { it.startsWith("DEV-01") })
        assertTrue(scan("\$ ~> |a|{\"b\":99}|").none { it.startsWith("DEV-01") })
        assertTrue(scan("\$map([1], \$string(?))").any { it.startsWith("DEV-03") })
        assertTrue(scan("/a++/").any { it.startsWith("DEV-04") })
        assertTrue(scan("/(?i)a/").any { it.startsWith("DEV-04") })
        assertTrue(scan("/a+/").none { it.startsWith("DEV-04") })
        assertTrue(scan("/\uE000/").any { it.startsWith("DEV-05") })
        assertTrue(scan("/s/i").any { it.startsWith("DEV-06") })
        assertTrue(scan("/(a)\\2(b)/").any { it.startsWith("DEV-06") })
        assertTrue(scan("/a/i").none { it.startsWith("DEV-06") })
        assertTrue(scan("\$toMillis(x)").any { it.startsWith("DEV-07") })
        assertTrue(scan("\$toMillis(x, '[Y]')").none { it.startsWith("DEV-07") })
        assertTrue(scan("x{\"k\": \"v\"}").any { it.startsWith("DEV-08") })
    }

    @Test
    fun `DEV-09 the probe fires only when another value of the constructor fails with another code`() {
        val r = Recorder()
        assertEquals("T0410", error("{\"a\": (\$pad(2.5, null)), \"b\": (false < \$nothing)}", r).code)
        assertEquals(listOf("DEV-09"), r.hits.map { it.first })
        val array = Recorder()
        assertEquals("T0410", error("[\$pad(2.5, null), 1, false < \$nothing]", array).code)
        assertEquals(listOf("DEV-09"), array.hits.map { it.first })
        // one failing value, or the same code twice: no hit
        for (expr in listOf("{\"a\": 1 + \"x\", \"b\": 2}", "[1 + \"x\", 2 + \"y\"]", "[1 + \"x\"]")) {
            val n = Recorder()
            error(expr, n)
            assertEquals(emptyList(), n.hits.map { it.first }, expr)
        }
        // without a sink the other values are not evaluated
        assertEquals("T0410", assertFailsWith<JsonataException> { Jsonata.compile("[\$pad(2.5, null), \$x()]").evaluate(null) }.code)
    }

    @Test
    fun `DEV-10 an error inside a lambda keeps the position of the operator`() {
        val r = Recorder()
        val e = error("(\$f := function(){ 1 + \"a\" }; \$f())", r)
        assertEquals("T2002", e.code)
        assertEquals(22, e.position) // jsonata-js 2.2.2: 33, the call $f()
        assertEquals("+", e.token)
        assertEquals(listOf<Pair<String, Int?>>("DEV-10" to 22), r.hits)
        // nested lambdas: still the operator (jsonata-js: the outermost call)
        assertEquals(25, error("(\$g := function(\$x){ \$x + \"a\" }; \$f := function(){ \$g(1) }; \$f())").position)
        // an error without a position of its own takes the call site, as in jsonata-js: no probe
        val r2 = Recorder()
        val e2 = error("(\$f := function(\$x)<n:n>{ \$x }; \$f(\"a\"))", r2)
        assertEquals("T0410", e2.code)
        assertEquals(35, e2.position)
        assertEquals(emptyList<Pair<String, Int?>>(), r2.hits)
        // the depth limit inside a recursion: the call site, as before
        val e3 = assertFailsWith<JsonataException> {
            Jsonata.compile("(\$inf := function(\$n){\$n+\$inf(\$n-1)};  \$inf(5))").evaluate(null, emptyMap(), EvaluationOptions(maxDepth = 300))
        }
        assertEquals("D1011", e3.code)
        assertEquals(30, e3.position)
        // no lambda, no difference
        val r4 = Recorder()
        assertEquals(3, error("1 + \"a\"", r4).position)
        assertEquals(emptyList<Pair<String, Int?>>(), r4.hits)
        assertEquals(listOf("DEV-10@16"), scan("(\$f := function(){ 1 + \"a\" }; \$f())").filter { it.startsWith("DEV-10") })
        assertTrue(scan("1 + 2").isEmpty())
    }

    @Test
    fun `DEV-11 base64 in UTF-8`() {
        // expectations: Node 22 Buffer.from(s, 'utf8').toString('base64') and Buffer.from(b, 'base64').toString('utf8')
        val encode = listOf(
            "hello" to "aGVsbG8=", "é" to "w6k=", "Привет" to "0J/RgNC40LLQtdGC", "\uD83D\uDE00" to "8J+YgA==",
            "a\uD800b" to "Ye+/vWI=", "\uDC00" to "77+9", "ÿĀ" to "w7/EgA==",
        )
        for ((s, b) in encode) assertEquals(b, Jsonata.compile("\$base64encode(\$)").evaluate(su.seaf.archtool.jsonata.value.StringValue.of(s)).let { (it as su.seaf.archtool.jsonata.value.StringValue).value }, s)
        val decode = listOf(
            "w6k=" to "é", "6Q==" to "\uFFFD", "0J/RgNC40LLQtdGC" to "Привет", "8J+YgA==" to "\uD83D\uDE00", "!!!" to "",
            "gA==" to "\uFFFD", "wMCA" to "\uFFFD\uFFFD\uFFFD", "7aCA" to "\uFFFD\uFFFD\uFFFD", "8J+Y" to "\uFFFD", "aGk" to "hi",
        )
        for ((b, s) in decode) assertEquals(s, Jsonata.compile("\$base64decode(\$)").evaluate(su.seaf.archtool.jsonata.value.StringValue.of(b)).let { (it as su.seaf.archtool.jsonata.value.StringValue).value }, b)
        // jsonata-js 2.2.2 gives "6Q==" and "Ã©"; the probe fires only where the reference differs
        val r = Recorder()
        assertEquals("\"w6k=\"", eval("\$base64encode('é')", r = r))
        assertEquals("\"aGk=\"", eval("\$base64encode('hi')", r = r))
        assertEquals("\"é\"", eval("\$base64decode('w6k=')", r = r))
        assertEquals("\"hi\"", eval("\$base64decode('aGk=')", r = r))
        assertEquals(listOf("DEV-11", "DEV-11"), r.hits.map { it.first })
        assertEquals(listOf("DEV-11@14", "DEV-11@35"), scan("\$base64encode('é') & \$base64decode('w6k=')").filter { it.startsWith("DEV-11") })
    }

    @Test
    fun `DEV-12 undefined does not enter sequences`() {
        val input = """{"x":[{"a":1},{"b":2},{"c":3}]}"""
        val r = Recorder()
        assertEquals("1", eval("x.a[true]", input, r)) // jsonata-js 2.2.2: [1,null,null]
        assertEquals(listOf<Pair<String, Int?>>("DEV-12" to 8, "DEV-12" to 8), r.hits)
        assertEquals("[1,3]", eval("x.(a ? a : c)[true]", """{"x":[{"a":1},{"b":2},{"c":3}]}"""))
        // the predicate is still evaluated for the missing value: its errors stay
        assertEquals("T2002", error("{\"x\":[{\"a\":1},{\"b\":2}]}.x.a[\$ - \"s\"]").code)
        // a numeric index never selected undefined: no difference, no probe
        val r2 = Recorder()
        assertEquals("1", eval("x.a[0]", input, r2))
        assertEquals(emptyList<Pair<String, Int?>>(), r2.hits)

        // a step after a filtered array constructor iterates the selected item (jsonata-js 2.2.2: undefined)
        val r3 = Recorder()
        assertEquals("1", eval("[{\"a\":1},{\"a\":2}][0].a", r = r3))
        assertEquals(listOf<Pair<String, Int?>>("DEV-12" to 1), r3.hits)
        assertEquals("2", eval("[{\"a\":1},{\"a\":2}][a=2].a"))
        assertEquals("6", eval("[5][0].(\$ + 1)"))
        assertEquals("\"ABC\"", eval("[\"abc\"][0].\$uppercase(\$)")) // jsonata-js 2.2.2: ["A","B","C"]
        // unchanged: several selected items, an array item, parentheses, a single step
        val r4 = Recorder()
        assertEquals("[1,2]", eval("[{\"a\":1},{\"a\":2}][true].a", r = r4))
        assertEquals("[10,20]", eval("[[1,2]][0].(\$ * 10)", r = r4))
        assertEquals("1", eval("([{\"a\":1},{\"a\":2}][0]).a", r = r4))
        assertEquals("{\"a\":1}", eval("[{\"a\":1},{\"a\":2}][0]", r = r4))
        assertEquals(emptyList<Pair<String, Int?>>(), r4.hits)

        assertEquals(listOf("DEV-12@1"), scan("[{\"a\":1},{\"a\":2}][0].a").filter { it.startsWith("DEV-12") })
        assertEquals(listOf("DEV-12@3"), scan("x.a[true]").filter { it.startsWith("DEV-12") })
        assertTrue(scan("x.a[0]").none { it.startsWith("DEV-12") })
    }
}
