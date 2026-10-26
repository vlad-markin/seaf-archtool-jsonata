package su.seaf.archtool.jsonata.engine

import su.seaf.archtool.jsonata.EvaluationOptions
import su.seaf.archtool.jsonata.Jsonata
import su.seaf.archtool.jsonata.parser.JsonataException
import su.seaf.archtool.jsonata.value.StringValue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import su.seaf.archtool.jsonata.value.JsonWriter
import su.seaf.archtool.jsonata.value.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Regression tests of review round 2 (`docs/review/round-2.md`); the test JVM runs with -Xmx1g. */
class ReviewRound2Test {
    private fun eval(expr: String, input: Value? = null): String = Jsonata.compile(expr).evaluate(input).let { if (it == null) "undefined" else JsonWriter.stringify(it) }

    @Test
    fun `RV2-1 local functions called in every iteration are not kept until the end`() {
        assertEquals("600010000", eval("\$sum(\$map([1..20000], function(\$v){ (\$t := [1..20000]; \$f := function(\$x){ \$x + \$count(\$t) }; \$f(\$v)) }))"))
        assertEquals("1000000", eval("\$count(\$map([1..1000000], function(\$v){ (\$f := function(\$x){ \$x }; \$f(\$v)) }))"))
    }

    private fun evalWith(expr: String, input: Value?, bindings: Map<String, Value?>, options: EvaluationOptions = EvaluationOptions.DEFAULT): String =
        Jsonata.compile(expr).evaluate(input, bindings, options).let { if (it == null) "undefined" else JsonWriter.stringify(it) }

    @Test
    fun `RV2-2 a regex value does not carry the deadline of the evaluation that used it`() {
        val r = Jsonata.compile("/b/").evaluate(null)
        val s = StringValue.of("a".repeat(20000) + "b")
        assertEquals("true", evalWith("\$contains(\$, \$r)", s, mapOf("r" to r), EvaluationOptions(timeoutMillis = 50)))
        Thread.sleep(150)
        assertEquals("true", evalWith("\$contains(\$, \$r)", s, mapOf("r" to r)))
        assertEquals("20000", evalWith("\$match(\$, \$r).index", s, mapOf("r" to r)))
    }

    @Test
    fun `RV2-2 a regex value used again keeps the deadline of the new evaluation`() {
        val r = Jsonata.compile("/(?:a*)*?b\\0/").evaluate(null)
        val s = StringValue.of("a".repeat(24) + "!")
        assertEquals("false", evalWith("\$contains(\$, \$r)", s, mapOf("r" to r)))
        val start = System.nanoTime()
        val e = assertFailsWith<JsonataException> { evalWith("\$contains(\$, \$r)", s, mapOf("r" to r), EvaluationOptions(timeoutMillis = 50)) }
        assertEquals("D1012", e.code)
        assertTrue((System.nanoTime() - start) / 1_000_000 < 1000)
    }

    @Test
    fun `RV2-2 one regex value in parallel evaluations`() {
        val r = Jsonata.compile("/b+/").evaluate(null)
        val expr = Jsonata.compile("\$match(\$, \$r).index")
        val wrong = AtomicInteger()
        val failures = AtomicInteger()
        val pool = Executors.newFixedThreadPool(8)
        repeat(8) { t ->
            pool.execute {
                repeat(4000) { i ->
                    val k = (t * 7 + i) % 50
                    val j = i % 30
                    val s = StringValue.of("a".repeat(k) + "b" + "a".repeat(j) + "bb")
                    try {
                        val got = JsonWriter.stringify(expr.evaluate(s, mapOf("r" to r)))
                        val want = if (j == 0) "$k" else "[$k,${k + 1 + j}]"
                        if (got != want) wrong.incrementAndGet()
                    } catch (e: Throwable) {
                        failures.incrementAndGet()
                    }
                }
            }
        }
        pool.shutdown()
        assertTrue(pool.awaitTermination(120, TimeUnit.SECONDS))
        assertEquals(0, failures.get(), "exceptions")
        assertEquals(0, wrong.get(), "wrong results")
    }

    private fun wideObject(n: Int): Value = su.seaf.archtool.jsonata.value.ObjectValue().also { o ->
        for (i in 0 until n) o.put("k$i", su.seaf.archtool.jsonata.value.NumberValue.of(i))
    }

    @Test
    fun `RV2-3 deleting many keys in a transform is linear and stops at the deadline`() {
        val expr = Jsonata.compile("\$count(\$keys(\$ ~> |\$|{}, \$keys(\$)|))")
        val input = wideObject(30_000)
        val start = System.nanoTime()
        assertEquals("0", JsonWriter.stringify(expr.evaluate(input)))
        assertTrue((System.nanoTime() - start) / 1_000_000 < 1500, "30 000 deletions took too long")
        // deleting part of the keys keeps the order and the lookups of the others
        val half = Jsonata.compile("\$keys(\$ ~> |\$|{}, ['k0', 'k2', 'k15']|)")
        assertEquals((listOf(1) + (3..14) + (16 until 20)).joinToString(",", "[", "]") { "\"k$it\"" }, JsonWriter.stringify(half.evaluate(wideObject(20))))
        assertEquals("19", evalWith("(\$o := \$ ~> |\$|{}, ['k0', 'k2']|; \$o.k19)", wideObject(20), emptyMap()))
        // the delete loop passes the checkpoint: D1012 within a small margin
        val big = wideObject(300_000)
        val t0 = System.nanoTime()
        val e = assertFailsWith<JsonataException> { expr.evaluate(big, emptyMap(), EvaluationOptions(timeoutMillis = 100)) }
        assertEquals("D1012", e.code)
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 2000)
    }
}
