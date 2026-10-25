package su.seaf.archtool.jsonata.debug

import su.seaf.archtool.jsonata.CompileOptions
import su.seaf.archtool.jsonata.FunctionDefinition
import su.seaf.archtool.jsonata.FunctionDoc
import su.seaf.archtool.jsonata.FunctionRegistry
import su.seaf.archtool.jsonata.Jsonata
import su.seaf.archtool.jsonata.LocalizedText
import su.seaf.archtool.jsonata.parser.JsonataException
import su.seaf.archtool.jsonata.value.JsonWriter
import su.seaf.archtool.jsonata.value.NumberValue
import su.seaf.archtool.jsonata.value.Value
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Frames, scopes, variables and watch expressions of a debugging session (story 5 step 5.4, CAP-10 p. 7–8; TC-13). */
class InspectionTest {
    private fun json(text: String): Value = Jsonata.compile(text).evaluate(null)!!

    private val data = """{"Account":{"Order":[{"OrderID":"o1","Product":[{"Name":"Hat","Price":10,"Quantity":2}]}]}}"""

    private class Stops : DebugEvents {
        val stops = LinkedBlockingQueue<NodeInfo>()
        val outcome = AtomicReference<EvalOutcome>()
        override fun stopped(reason: StopReason, at: NodeInfo) {
            stops.put(at)
        }
        override fun terminated(outcome: EvalOutcome) {
            this.outcome.set(outcome)
        }
        fun next(): NodeInfo = stops.poll(10, TimeUnit.SECONDS) ?: error("no stop; outcome ${outcome.get()}")
    }

    @Test
    fun `stop on an error with variables and a watch expression (TC-13)`() {
        val expr = "Account.Order.Product.(\n  Name & \": \" & Price * Quantity + Name\n)"
        val events = Stops()
        val session = DebugSession(Jsonata.compile(expr), events)
        session.setExceptionBreakpoints(ExceptionMode.ALL)
        val failure = AtomicReference<Throwable>()
        val t = Thread { runCatching { session.run(json(data)) }.onFailure { failure.set(it) } }
        t.start()
        val at = events.next()
        assertEquals("binary", at.type)
        assertEquals("+", at.value)
        val idx = SourceIndex.of(Jsonata.compile(expr))
        // `&` and `+` bind alike: the failing `+` is ((Name & ": ") & Price * Quantity) + Name
        assertEquals("Name & \": \" & Price * Quantity + Name", expr.substring(at.start, at.end))
        assertEquals("2:3-2:40", idx.range(at).toString())
        assertEquals(listOf("<main>"), session.stackTrace().frames.map { it.name })
        val context = session.scopes(0).first { it.name == "Context" }
        val dollar = session.variables(context.variablesReference).first { it.name == "\$" }
        assertEquals("object", dollar.type)
        assertEquals(listOf("Name", "Price", "Quantity"), session.variables(dollar.variablesReference).map { it.name })
        assertEquals("20", session.evaluate(0, "Price * Quantity").toString())
        session.resume()
        t.join(10_000)
        val e = failure.get() as JsonataException
        assertEquals("T2001", e.code)
        assertSame(e, events.outcome.get().error) // the error reaches the caller unchanged
    }

    @Test
    fun `scopes of a lambda frame and an isolated watch`() {
        val expr = "(\n  \$rate := 2;\n  \$total := function(\$p){ \$p.Price * \$p.Quantity * \$rate };\n  Account.Order.Product.\$total(\$)\n)"
        val events = Stops()
        val session = DebugSession(Jsonata.compile(expr), events)
        val line3 = expr.lines()[2]
        session.setBreakpoints("main", listOf(BreakpointSpec(3, line3.lastIndexOf("*") + 1)))
        val t = Thread { runCatching { session.run(json(data)) } }
        t.start()
        events.next()
        val scopes = session.scopes(0)
        assertEquals(listOf("Context", "Arguments", "Closure", "Global"), scopes.map { it.name })
        assertEquals(listOf("p"), session.variables(scopes[1].variablesReference).map { it.name })
        assertEquals(listOf("rate", "total"), session.variables(scopes[2].variablesReference).map { it.name })
        // a watch expression evaluates in the frame; := does not reach the frame
        assertEquals("100", session.evaluate(0, "(\$rate := 100; \$rate)").toString())
        assertEquals("2", session.evaluate(0, "\$rate").toString())
        assertEquals("\"Hat: 20\"", session.evaluate(0, "\$p.Name & \": \" & \$p.Price * \$p.Quantity").toString())
        // the caller's frame sees the block's variables
        assertTrue(session.scopes(1).any { it.name == "Global" || it.name == "Local" })
        // undefined and null are told apart
        val undefinedVar = References().view("x", null)
        assertEquals("undefined" to "undefined", undefinedVar.type to undefinedVar.value)
        session.resume()
        t.join(10_000)
        assertEquals("[40]".removeSurrounding("[", "]"), events.outcome.get().toString())
    }

    @Test
    fun `functions with side effects are not available in a watch`() {
        val registry = FunctionRegistry.create()
            .register(FunctionDefinition("log", "<x:x>", { _, a -> a[0] }, FunctionDoc(LocalizedText.ru("Пишет в журнал"))))
            .register(FunctionDefinition("pure", "<n:n>", { _, a -> NumberValue.of((a[0] as NumberValue).value + 1) }, FunctionDoc(LocalizedText.ru("Чистая")), sideEffects = false))
        val expr = Jsonata.compile("[1, 2].(\$ * 2)", CompileOptions(registry))
        val events = Stops()
        val session = DebugSession(expr, events)
        session.setBreakpoints("main", listOf(BreakpointSpec(1, 11)))
        val t = Thread { runCatching { session.run(null) } }
        t.start()
        events.next()
        val e = session.evaluate(0, "\$log(\$)").error as JsonataException
        assertEquals("X0011", e.code)
        assertEquals("Function \"log\" is not available in a watch expression", e.message)
        assertEquals("2", session.evaluate(0, "\$pure(\$)").toString())
        // a watch has its own budget
        assertEquals("X0010", (session.evaluate(0, "(\$f := function(\$n){ \$f(\$n + 1) }; \$f(0))").error as JsonataException).code)
        session.resume()
        events.next()
        session.resume()
        t.join(10_000)
        assertEquals("[2,4]", JsonWriter.stringify(json(events.outcome.get().toString())))
    }
}
