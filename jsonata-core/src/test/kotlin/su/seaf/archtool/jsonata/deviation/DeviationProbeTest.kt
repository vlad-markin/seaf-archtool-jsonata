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
}
