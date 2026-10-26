package su.seaf.archtool.jsonata.compat

import su.seaf.archtool.jsonata.EvaluationOptions
import su.seaf.archtool.jsonata.Jsonata
import su.seaf.archtool.jsonata.parser.JsonataException
import su.seaf.archtool.jsonata.value.FunctionValue
import su.seaf.archtool.jsonata.value.JsonWriter
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The traps catalogued by the dashjoin evaluation (compat/traps, see its README): every case must give the jsonata-js
 * 2.2.2 outcome recorded in expected.jsonl, except the ones listed in [allowed] with a reason.
 */
class TrapsTest {
    private val root = File(System.getProperty("jsonata.compat"), "traps")

    /** Case id → why our outcome may differ. */
    private val allowed = mapOf(
        "RV2-14#1" to "jsonata-js fails with a JS SyntaxError (\\k inside a class with named groups); we report X0003 (DEV-04)",
        "RV2-14#2" to "jsonata-js fails with a JS SyntaxError (\\k without a name with named groups); we report X0003 (DEV-04)",
        "RE-01#4" to "jsonata-js fails with a JS SyntaxError (not a JSONata error); we report X0003 (docs/deviations.md DEV-04)",
        "RE-01#5" to "the same: (?i) is Java syntax, invalid in JS",
        "AHEAD-01#1" to "jsonata-js 2.2.2 crashes with a TypeError on an undefined matcher; we report T1006",
        "RV-11#1" to "case folding with the i flag is java.util.regex Unicode folding (DEV-06, owner's decision)",
        "RV-11#2" to "DEV-06: Kelvin sign folds to k",
        "RV-11#3" to "DEV-06: dotted capital I folds to i",
        "RV-11#4" to "DEV-06: dotless i folds to I",
        "RV-11#5" to "DEV-06: sharp s folds to capital sharp s",
        "RV-11#6" to "DEV-06: a back reference to a group not captured yet fails in Java, matches empty in JS",
        "STR-03#1" to "DEV-11: base64 in UTF-8",
        "STR-03#2" to "DEV-11: base64 in UTF-8",
        "t-str-b64-utf" to "DEV-11: base64 in UTF-8",
        "t-str-b64-dec-utf" to "DEV-11: base64 in UTF-8",
        "FZ-07#1" to "DEV-11: base64 in UTF-8 (the bytes from the low bytes of the letters are not valid UTF-8)",
        "FZ-04#2" to "DEV-02: roman numerals of huge numbers exhaust the JS stack (RangeError); we report X0002",
        "FZ-05#1" to "DEV-08: jsonata-js pushes undefined into the empty input array",
        "FZ-05#2" to "DEV-08: jsonata-js pushes undefined into the empty array of the clone",
    )

    /**
     * Outcomes of new traps pinned by hand until expected.jsonl is regenerated on Node 22 (README): used while the id
     * is missing there, and checked against it once it is present.
     */
    private val pinned: Map<String, String> = mapOf(
        // review RV3-14: a class escape as a range bound, the hyphen is a literal without the u flag
        "RV3-14#1" to "json \"a-1\"",
        "RV3-14#2" to "json \"x.-a\"",
        "RV3-14#3" to "json \" -1\"",
        "RV3-14#4" to "json \"5-z\"",
    )

    private fun outcome(c: com.fasterxml.jackson.databind.JsonNode): String {
        val options = c.get("options")
        val bounds = c.get("bounds")
        val limits = when {
            bounds != null -> EvaluationOptions(maxDepth = bounds.get("depth").asInt(), timeoutMillis = bounds.get("timelimit").asLong())
            options != null -> EvaluationOptions(
                maxDepth = options.get("stack")?.asInt() ?: 0,
                timeoutMillis = options.get("timeout")?.asLong() ?: 0,
                maxSequence = options.get("sequence")?.asInt() ?: 0,
            )
            else -> EvaluationOptions.DEFAULT
        }
        return try {
            val r = Jsonata.compile(c.get("expr").asText()).evaluate(c.get("input")?.let { toValue(it) }, emptyMap(), limits)
            when (r) {
                null -> "undefined"
                is FunctionValue -> "function"
                else -> "json " + JsonWriter.stringify(r)
            }
        } catch (e: JsonataException) {
            val code = if (bounds != null && (e.code == "D1011" || e.code == "D1012")) "U1001" else e.code
            "error $code @${e.position} ${tokenText(e.token)}"
        }
    }

    private fun expected(e: com.fasterxml.jackson.databind.JsonNode): String = when {
        e.has("json") -> "json " + e.get("json").asText()
        e.has("undefined") -> "undefined"
        e.has("error") -> "error " + e.get("error").asText() + " @" + e.get("position")?.takeIf { !it.isNull }?.asInt() + " " +
            e.get("token")?.takeIf { !it.isNull }?.let { if (it.isNumber) su.seaf.archtool.jsonata.value.JsNumber.toString(it.asDouble()) else it.asText() }
        e.has("crash") -> "crash " + e.get("crash").asText()
        else -> "function"
    }

    private fun tokenText(token: Any?): String? = when (token) {
        null -> null
        is Double -> su.seaf.archtool.jsonata.value.JsNumber.toString(token)
        else -> token.toString()
    }

    @Test
    fun `traps behave as jsonata-js 2_2_2`() {
        val expected = File(root, "expected.jsonl").readLines().filter { it.isNotBlank() }.associate { l ->
            val n = mapper.readTree(l)
            n.get("id").asText() to expected(n)
        }
        val cases = listOf("divergences.jsonl", "targeted.jsonl", "review.jsonl", "fuzz.jsonl").flatMap { f -> File(root, f).readLines().filter { it.isNotBlank() } }
            .map { mapper.readTree(it) }.filter { !it.has("functions") }
        val mismatches = ArrayList<String>()
        for (c in cases) {
            val id = c.get("id").asText()
            val want = expected[id] ?: pinned[id] ?: error("no expected outcome for $id")
            pinned[id]?.let { pin -> expected[id]?.let { assertEquals(it, pin, "the pinned outcome of $id differs from expected.jsonl") } }
            val got = outcome(c)
            if (got != want && id !in allowed) mismatches += "$id: expected <$want> got <$got>  expr ${c.get("expr").asText()}"
        }
        assertTrue(mismatches.isEmpty(), "${mismatches.size} of ${cases.size} traps differ:\n" + mismatches.joinToString("\n"))
    }
}
