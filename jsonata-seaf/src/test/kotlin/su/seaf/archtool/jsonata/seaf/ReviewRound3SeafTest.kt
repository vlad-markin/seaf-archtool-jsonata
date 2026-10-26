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
}
