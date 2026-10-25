package su.seaf.archtool.jsonata.engine

import su.seaf.archtool.jsonata.CompileOptions
import su.seaf.archtool.jsonata.EvaluationOptions
import su.seaf.archtool.jsonata.FunctionRegistry
import su.seaf.archtool.jsonata.Jsonata
import su.seaf.archtool.jsonata.parser.JsonataException
import su.seaf.archtool.jsonata.value.ArrayValue
import su.seaf.archtool.jsonata.value.JsonWriter
import su.seaf.archtool.jsonata.value.ObjectValue
import su.seaf.archtool.jsonata.value.StringValue
import su.seaf.archtool.jsonata.value.Value
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Decision 15 / review RV-1: an expression compiled with debugging and evaluated with a hook gives exactly the result
 * (or the error code, position and token) of the normal compilation, on every test-suite case.
 */
class DebugParityTest {
    private val suite = File(System.getProperty("jsonata.compat"), "jsonata-js/test-suite")

    /** JSON text is a JSONata expression: no JSON library needed in the core tests. */
    private fun json(text: String): Value? = Jsonata.compile(text).evaluate(null)

    private class CountingHook : DebugHook {
        var events = 0
        override fun enter(node: Node, input: Value?, frame: Frame) { events++ }
        override fun exit(node: Node, input: Value?, frame: Frame, result: Value?) { events++ }
    }

    private fun outcome(expr: String, input: Value?, bindings: Map<String, Value?>, debug: Boolean, hook: CountingHook): String = try {
        val compiled = Jsonata.compile(expr, CompileOptions(FunctionRegistry.standard(), debug))
        val clock = { 1_700_000_000_000L }
        val r = if (debug) compiled.evaluate(input, bindings, EvaluationOptions(clock = clock), hook)
        else compiled.evaluate(input, bindings, EvaluationOptions(clock = clock))
        "value " + JsonWriter.stringify(r)
    } catch (e: JsonataException) {
        "error ${e.code} ${e.position} ${e.token}"
    }

    @Test
    fun `debug compilation gives the same results on the whole test-suite`() {
        val datasets = HashMap<String, Value?>()
        val hook = CountingHook()
        val mismatches = ArrayList<String>()
        var count = 0
        for (group in suite.resolve("groups").listFiles()!!.filter { it.isDirectory }.sortedBy { it.name }) {
            for (file in group.listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }) {
                val parsed = json(file.readText())
                val cases = if (parsed is ArrayValue) parsed.toList() else listOf(parsed!!)
                for (c in cases) {
                    c as ObjectValue
                    if (c["expr"] == null && c["expr-file"] == null) continue
                    val expr = (c["expr"] as? StringValue)?.value ?: File(group, (c["expr-file"] as StringValue).value).readText()
                    val input = when {
                        c.containsKey("data") -> c["data"]
                        c["dataset"] is StringValue -> datasets.getOrPut((c["dataset"] as StringValue).value) {
                            json(suite.resolve("datasets/${(c["dataset"] as StringValue).value}.json").readText())
                        }
                        else -> null
                    }
                    if (c.containsKey("timelimit")) continue // timing-dependent
                    val bindings = (c["bindings"] as? ObjectValue)?.entries?.toMap() ?: emptyMap()
                    if (listOf("\$random", "\$shuffle", "\$now", "\$millis").any { expr.contains(it) }) continue // nondeterministic
                    count++
                    val plain = outcome(expr, input, bindings, false, hook)
                    val debug = outcome(expr, input, bindings, true, hook)
                    if (plain != debug) mismatches += "${group.name}/${file.name}: $expr\n  plain $plain\n  debug $debug"
                }
            }
        }
        assertTrue(count > 1500, "cases: $count")
        assertTrue(hook.events > 10_000)
        assertTrue(mismatches.isEmpty(), mismatches.joinToString("\n"))
    }

    @Test
    fun `review RV-1 cases`() {
        val hook = CountingHook()
        val cases = listOf(
            "[1..5].\$string()" to null,
            "[1, 2, [3, 4]][-1][-1]" to null,
            "nums^(\$)" to """{"nums":[3,1,2]}""",
            "x.y[0].z" to """{"x":{"y":[{"z":1},{"z":2}]}}""",
            "1 ~> \$abs()" to null,
            "x.{ %.k: v }" to """{"k":"K","x":[{"v":1}]}""",
            "Employee@\$e.Contact@\$c[\$c.ssn = \$e.SSN]{ \$e.FirstName: \$c.Phone }" to """{"Employee":[{"SSN":"1","FirstName":"A"}],"Contact":[{"ssn":"1","Phone":"p"}]}""",
            "\$#\$pos[\$pos<2]" to "[5,6,7]",
            "(\$f := function(\$n){ \$n = 0 ? 0 : \$f(\$n - 1) }; \$f(1000))" to null,
        )
        for ((expr, input) in cases) {
            val v = input?.let { json(it) }
            assertEquals(outcome(expr, v, emptyMap(), false, hook), outcome(expr, v, emptyMap(), true, hook), expr)
        }
        assertEquals("value [\"1\",\"2\",\"3\",\"4\",\"5\"]", outcome("[1..5].\$string()", null, emptyMap(), true, hook))
    }
}
