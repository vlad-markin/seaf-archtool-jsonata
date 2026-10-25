package su.seaf.archtool.jsonata

import su.seaf.archtool.jsonata.parser.JsonataException
import su.seaf.archtool.jsonata.value.JsonWriter
import su.seaf.archtool.jsonata.value.Value
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Isolation of evaluations and an unchanged input (story 4 step 4.4, CAP-13 p. 7–9; TC-20). */
class IsolationTest {
    private fun json(text: String): Value = Jsonata.compile(text).evaluate(null)!!

    @Test
    fun `one expression on 8 threads x 20 000 calls with different inputs`() {
        val expr = Jsonata.compile("id & ':' & \$\$.id & ':' & \$count(items)")
        val inputs = (0 until 64).map { i -> json("{\"id\": \"i$i\", \"items\": [${(0 until i % 7).joinToString(",")}]}") }
        val pool = Executors.newFixedThreadPool(8)
        try {
            val wrong = (0 until 8).map { t ->
                pool.submit<Int> {
                    var bad = 0
                    repeat(20_000) { k ->
                        val i = (t * 31 + k) % inputs.size
                        if (JsonWriter.stringify(expr.evaluate(inputs[i])!!) != "\"i$i:i$i:${i % 7}\"") bad++
                    }
                    bad
                }
            }.sumOf { it.get(5, TimeUnit.MINUTES) }
            assertEquals(0, wrong)
        } finally {
            pool.shutdown()
        }
    }

    @Test
    fun `a top-level binding does not leak into the next evaluation`() {
        val expr = Jsonata.compile("\$y := (\$exists(\$y) ? \$y + 1 : 1)")
        assertEquals("1", JsonWriter.stringify(expr.evaluate(null)!!))
        assertEquals("1", JsonWriter.stringify(expr.evaluate(null)!!))
    }

    @Test
    fun `now is the start of each evaluation`() {
        val expr = Jsonata.compile("[\$now(), \$millis()]")
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit<String> { JsonWriter.stringify(expr.evaluate(null)!!) }.get()
            Thread.sleep(300)
            val second = pool.submit<String> { JsonWriter.stringify(expr.evaluate(null)!!) }.get()
            assertNotEquals(first, second)
        } finally {
            pool.shutdown()
        }
        // within one evaluation the time does not move
        assertEquals("true", JsonWriter.stringify(Jsonata.compile("(\$a := \$millis(); \$sum([1..200000]); \$a = \$millis())").evaluate(null)!!))
    }

    @Test
    fun `an expression cannot switch off limits or debugging`() {
        val shadow = "(\$__evaluate_entry := 1; \$__evaluate_exit := 1; \$stack := 0; \$timeout := 0; \$f := function(\$n){ 1 + \$f(\$n + 1) }; \$f(0))"
        assertEquals("D1011", assertFailsWith<JsonataException> { Jsonata.compile(shadow).evaluate(null, emptyMap(), EvaluationOptions(maxDepth = 500)) }.code)
        assertEquals("D1012", assertFailsWith<JsonataException> { Jsonata.compile(shadow).evaluate(null, emptyMap(), EvaluationOptions(maxDepth = 0, timeoutMillis = 100)) }.code)
        val token = CancellationToken().also { it.cancel() }
        assertEquals("X0009", assertFailsWith<JsonataException> { Jsonata.compile(shadow).evaluate(null, emptyMap(), EvaluationOptions(cancellation = token)) }.code)
    }

    @Test
    fun `the input is unchanged by transforms and a user clone`() {
        val input = json("{\"a\": {\"x\": 1}, \"list\": [{\"a\": {\"y\": 2}}]}")
        val before = JsonWriter.stringify(input)
        val identityClone = FunctionRegistry.function("<x:x>", { _, a -> a[0] })
        for (expr in listOf("\$ ~> |a|{\"b\":1}|", "\$ ~> |\$\$.a|{\"b\":1}|", "\$ ~> |list.a|{\"c\":3}, ['y']|", "(\$x := \$ ~> |a|{}, ['x']|; \$x)")) {
            Jsonata.compile(expr).evaluate(input)
            Jsonata.compile(expr).evaluate(input, mapOf("clone" to identityClone))
            assertEquals(before, JsonWriter.stringify(input), expr)
        }
        assertTrue(JsonWriter.stringify(Jsonata.compile("\$ ~> |a|{\"b\":1}|").evaluate(input)!!).contains("\"b\":1"))
    }
}
