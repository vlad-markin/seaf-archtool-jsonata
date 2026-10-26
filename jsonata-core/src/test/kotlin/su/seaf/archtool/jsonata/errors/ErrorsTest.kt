package su.seaf.archtool.jsonata.errors

import su.seaf.archtool.jsonata.CompileOptions
import su.seaf.archtool.jsonata.ErrorLocation
import su.seaf.archtool.jsonata.EvaluationOptions
import su.seaf.archtool.jsonata.FunctionRegistry
import su.seaf.archtool.jsonata.Jsonata
import su.seaf.archtool.jsonata.deviation.DeviationSink
import su.seaf.archtool.jsonata.parser.ErrorFrame
import su.seaf.archtool.jsonata.parser.HostErrorCodes
import su.seaf.archtool.jsonata.parser.JsonataException
import su.seaf.archtool.jsonata.value.StringValue
import su.seaf.archtool.jsonata.value.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Structured errors (story 2 step 2.4, CAP-9; TC-26). */
class ErrorsTest {
    private class HostFailure : RuntimeException("host failure")

    private val failure = HostFailure()
    private val registry = FunctionRegistry.create().register("boom", "<x?:x>", { _, _ -> throw failure })

    private fun located(expr: String): Pair<Throwable, List<String>> {
        val places = ArrayList<String>()
        val options = EvaluationOptions(hostErrors = { e, loc: ErrorLocation -> places += "${e.message}@${expr.substring(loc.start, loc.end)}" })
        val e = assertFailsWith<Throwable>(expr) { Jsonata.compile(expr, CompileOptions(registry)).evaluate(null, emptyMap(), options) }
        return e to places
    }

    @Test
    fun `a host exception reaches the caller unchanged from every higher-order place`() {
        val places = listOf(
            "\$sort([3, 1, 2], function(\$a, \$b){ \$boom() })",
            "\$replace(\"abc\", /b/, function(\$m){ \$boom() })",
            "\$eval(\"\$boom()\")",
            "true and \$boom()",
            "false or \$boom()",
            "\$map([1], function(\$v){ \$boom() })",
            "\$filter([1], function(\$v){ \$boom() })",
            "\$reduce([1, 2], function(\$a, \$v){ \$boom() })",
            "\$each({\"a\": 1}, function(\$v){ \$boom() })",
            "\$sift({\"a\": 1}, function(\$v){ \$boom() })",
            "\$single([1], function(\$v){ \$boom() })",
            "[1, 2].\$boom()",
            "1 ~> \$boom()",
        )
        for (expr in places) {
            val (e, where) = located(expr)
            assertSame(failure, e, expr)
            // the place comes through the listener once, at the innermost node: the call of $boom
            assertEquals(1, where.size, "$expr: $where")
            // inside $eval the evaluated text has no ranges in the expression: the $eval call is the place
            val call = if (expr.startsWith("\$eval")) "\$eval" else "\$boom"
            assertTrue(where[0].startsWith("host failure@$call"), "$expr: $where")
        }
    }

    @Test
    fun `eval wraps reference errors only (DEV-13)`() {
        assertEquals("D3120", assertFailsWith<JsonataException> { Jsonata.compile("\$eval(\"1 + \")").evaluate(null) }.code)
        val d3121 = assertFailsWith<JsonataException> { Jsonata.compile("\$eval(\"1 + 'a'\")").evaluate(null) }
        assertEquals("D3121", d3121.code)
        assertEquals("T2002", (d3121.cause as JsonataException).code)
        val hits = ArrayList<String>()
        val sink = DeviationSink { id, _ -> hits += id }
        // an engine error (X) passes unwrapped: jsonata-js wraps everything, here the JS SyntaxError of the regex
        val x = assertFailsWith<JsonataException> { Jsonata.compile("\$eval(\"\$contains('a', /a++/)\")").evaluate(null, emptyMap(), EvaluationOptions(deviations = sink)) }
        assertEquals("X0003", x.code)
        // a host exception passes unchanged
        val h = assertFailsWith<HostFailure> { Jsonata.compile("\$eval(\"\$boom()\")", CompileOptions(registry)).evaluate(null, emptyMap(), EvaluationOptions(deviations = sink)) }
        assertSame(failure, h)
        // the regex error also fires DEV-04 at the evaluation boundary (step 1.4)
        assertEquals(listOf("DEV-13", "DEV-04", "DEV-13"), hits)
    }

    @Test
    fun `an error carries code position range token and the call stack`() {
        val expr = "(\$f := function(\$x){ \$x + \"a\" }; \$g := function(){ \$f(1) + 1 }; \$g())" // not a tail call: frames of tail calls are replaced
        val e = assertFailsWith<JsonataException> { Jsonata.compile(expr).evaluate(null) }
        assertEquals("T2002", e.code)
        assertEquals("+", e.token)
        assertEquals(expr.indexOf('+') + 1, e.position) // DEV-10: the operator
        assertEquals("\$x + \"a\"", expr.substring(e.start, e.end))
        assertEquals(listOf("f", "g"), e.frames.map { it.name })
        assertEquals(listOf(ErrorFrame.Kind.LAMBDA, ErrorFrame.Kind.LAMBDA), e.frames.map { it.kind })
        assertEquals("\$f(1)", expr.substring(e.frames[0].start, e.frames[0].end))
        assertEquals("\$g()", expr.substring(e.frames[1].start, e.frames[1].end))
        // a native function in the stack; the range follows the position the reference gives
        val e2 = assertFailsWith<JsonataException> { Jsonata.compile("\$substring(\"abc\", \"x\")").evaluate(null) }
        assertEquals("T0410", e2.code)
        assertEquals("\$substring(\"abc\", \"x\")", "\$substring(\"abc\", \"x\")".substring(e2.start, e2.end))
        assertEquals(listOf("substring"), e2.frames.map { it.name })
        // a syntax error has a position but no node range
        val s = assertFailsWith<JsonataException> { Jsonata.compile("1 +") }
        assertEquals(-1, s.start)
    }

    @Test
    fun `and or keep the place of engine errors`() {
        val e = assertFailsWith<JsonataException> { Jsonata.compile("true and \$contains('a', /a++/)").evaluate(null) }
        assertEquals("X0003", e.code)
        assertEquals(29, e.position) // the regex literal, not the operator
        val t = assertFailsWith<JsonataException> { Jsonata.compile("true and (1 + 'a')").evaluate(null) }
        assertEquals("and", t.token) // reference errors move onto the operator, as in jsonata-js
    }

    @Test
    fun `an unexpected exception of the engine is X0008 with the cause`() {
        val bug = IllegalStateException("bug")
        val listener = object : su.seaf.archtool.jsonata.debug.EvaluationListener {
            override fun enter(node: su.seaf.archtool.jsonata.debug.NodeInfo, input: Value?, frame: su.seaf.archtool.jsonata.debug.DebugFrame, iteration: su.seaf.archtool.jsonata.debug.Iteration?) {
                if (node.type == "number") throw bug
            }
        }
        val e = assertFailsWith<JsonataException> { Jsonata.compile("1 + 2").evaluate(null, emptyMap(), EvaluationOptions(listener = listener)) }
        assertEquals("X0008", e.code)
        assertSame(bug, e.cause)
        assertEquals("Internal engine error: IllegalStateException: bug", e.message)
    }

    @Test
    fun `host error codes`() {
        HostErrorCodes.register("X0901", "The archive {{value}} is closed")
        val registry = FunctionRegistry.create().register("closed", "<s:x>", { _, a -> throw JsonataException("X0901", value = a[0]) })
        val e = assertFailsWith<JsonataException> { Jsonata.compile("\$closed(\"main\")", CompileOptions(registry)).evaluate(null) }
        assertEquals("X0901", e.code)
        assertEquals("The archive \"main\" is closed", e.message)
        assertEquals(8, e.position)
        for (bad in listOf("T0410", "D3001", "S0201", "U1001", "X0001", "X0899", "X1000", "x0901")) {
            assertFailsWith<IllegalArgumentException>(bad) { HostErrorCodes.register(bad, "x") }
        }
        assertEquals(StringValue.of("main"), e.value)
    }
}
