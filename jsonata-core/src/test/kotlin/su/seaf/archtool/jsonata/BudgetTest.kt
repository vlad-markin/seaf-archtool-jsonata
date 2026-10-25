package su.seaf.archtool.jsonata

import su.seaf.archtool.jsonata.parser.JsonataException
import su.seaf.archtool.jsonata.value.JsonWriter
import su.seaf.archtool.jsonata.value.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Budgets of steps, strings and the result, and the sequence limits (story 4 step 4.3, CAP-13; TC-19). */
class BudgetTest {
    private fun eval(expr: String, options: EvaluationOptions = EvaluationOptions.DEFAULT, input: Value? = null): String =
        Jsonata.compile(expr).evaluate(input, emptyMap(), options).let { if (it == null) "undefined" else JsonWriter.stringify(it) }

    private fun fails(expr: String, options: EvaluationOptions): JsonataException =
        assertFailsWith<JsonataException>(expr) { Jsonata.compile(expr).evaluate(null, emptyMap(), options) }

    private fun budget(e: JsonataException): String = "${e.code} ${e.details["budget"]}"

    @Test
    fun `doubling a string hits the string budget and the next evaluation runs (TC-19)`() {
        val options = EvaluationOptions(budgets = Budgets(maxStringLength = 1 shl 20))
        val e = fails("\$reduce([1..40], function(\$a, \$v){\$a & \$a}, \"x\")", options)
        assertEquals("X0010 string", budget(e))
        assertEquals("The evaluation exceeded the string budget (1048576)", e.message)
        assertEquals("1048576", eval("\$length(\$reduce([1..20], function(\$a, \$v){\$a & \$a}, \"x\"))", options))
    }

    @Test
    fun `strings are checked before they are built where the length is known`() {
        val options = EvaluationOptions(budgets = Budgets(maxStringLength = 1000))
        assertEquals("X0010 string", budget(fails("\$join([1..600].\$string(), \"-\")", options)))
        assertEquals("X0010 string", budget(fails("\$pad(\"x\", 5000)", options)))
        assertEquals("X0010 string", budget(fails("\$replace(\$pad(\"\", 900, \"a\"), \"a\", \"bb\")", options)))
        assertEquals("X0010 string", budget(fails("\$replace(\$pad(\"\", 900, \"a\"), /a/, \"bb\")", options)))
        assertEquals("X0010 string", budget(fails("\$string([1..500])", options)))
        assertEquals("999", eval("\$length(\$pad(\"x\", 999))", options))
    }

    @Test
    fun `a step budget stops an endless loop (TC-19)`() {
        val e = fails("(\$f := function(\$n){ \$f(\$n + 1) }; \$f(0))", EvaluationOptions(budgets = Budgets(maxSteps = 100_000)))
        assertEquals("X0010 steps", budget(e))
        assertEquals(100_000.0, e.value)
        assertEquals("5050", eval("\$sum([1..100])", EvaluationOptions(budgets = Budgets(maxSteps = 1000))))
    }

    @Test
    fun `the result size budget`() {
        val options = EvaluationOptions(budgets = Budgets(maxResultSize = 1000))
        assertEquals("X0010 result", budget(fails("[1..2000]", options)))
        assertEquals("X0010 result", budget(fails("{\"k\": \$pad(\"\", 2000, \"a\")}", options)))
        assertEquals("500", eval("\$count([1..500])", options))
    }

    @Test
    fun `sequence limits as jsonata-js (TC-19)`() {
        assertEquals("D2014", fails("[1..20000000]", EvaluationOptions.DEFAULT).code)
        assertEquals("D2015", fails("\$append([1..600],[1..600])", EvaluationOptions(maxSequence = 1000)).code)
    }
}
