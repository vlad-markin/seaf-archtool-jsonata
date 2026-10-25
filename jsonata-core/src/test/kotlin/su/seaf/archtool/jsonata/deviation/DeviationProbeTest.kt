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
}
