package su.seaf.archtool.jsonata.debug

import su.seaf.archtool.jsonata.Jsonata
import su.seaf.archtool.jsonata.parser.JsonataException
import su.seaf.archtool.jsonata.value.JsonWriter
import su.seaf.archtool.jsonata.value.Value
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The debugging session: breakpoints, steps, pause, terminate (story 5 step 5.3, CAP-10; TC-12, TC-15). */
class DebugSessionTest {
    private fun json(text: String): Value = Jsonata.compile(text).evaluate(null)!!

    /** Runs a session on its own thread and hands the stops to the test thread. */
    private class Driver(expr: String, options: DebugOptions = DebugOptions()) : DebugEvents {
        val stops = LinkedBlockingQueue<Pair<StopReason, NodeInfo>>()
        val output = StringBuilder()
        val outcome = AtomicReference<EvalOutcome>()
        val session = DebugSession(Jsonata.compile(expr), this, options)
        val text = expr
        private var thread: Thread? = null

        override fun stopped(reason: StopReason, at: NodeInfo) {
            stops.put(reason to at)
        }

        override fun output(text: String) {
            synchronized(output) { output.append(text) }
        }

        override fun terminated(outcome: EvalOutcome) {
            this.outcome.set(outcome)
        }

        fun start(input: Value?) {
            thread = Thread { runCatching { session.run(input) } }.also { it.start() }
        }

        /** The next stop: "REASON text-of-node". */
        fun next(): String {
            val (reason, node) = stops.poll(10, TimeUnit.SECONDS) ?: error("no stop; outcome ${outcome.get()}")
            return "$reason ${source(node)}"
        }

        fun source(node: NodeInfo): String = if (node.sourceId == "main") text.substring(node.start, node.end) else "${node.sourceId}:${node.type}"

        fun finish(): EvalOutcome {
            thread!!.join(10_000)
            assertTrue(stops.isEmpty(), "unexpected stops: $stops")
            return outcome.get()
        }

        fun input(): String = session.stoppedInput()?.let { JsonWriter.stringify(it) } ?: "undefined"
    }

    private val orders = """(
  ${'$'}rate := 2;
  ${'$'}total := function(${'$'}p){ ${'$'}p.Price * ${'$'}p.Quantity };
  Account.Order.{
    "id": OrderID,
    "sum": ${'$'}sum(Product.(${'$'}total(${'$'}) * ${'$'}rate))
  }
)"""

    private val ordersData = """{"Account":{"Order":[
        {"OrderID":"o1","Product":[{"Name":"Hat","Price":10,"Quantity":2},{"Name":"Cap","Price":5,"Quantity":1}]},
        {"OrderID":"o2","Product":[{"Name":"Coat","Price":12,"Quantity":3}]}]}}"""

    private fun line(n: Int) = orders.lines()[n - 1]

    @Test
    fun `breakpoints with condition stack variables and step out (TC-12)`() {
        val d = Driver(orders)
        val bps = d.session.setBreakpoints("main", listOf(
            BreakpointSpec(5, line(5).indexOf("OrderID") + 1),
            BreakpointSpec(3, line(3).indexOf("*") + 1, condition = "\$p.Price > 5"),
        ))
        assertTrue(bps.all { it.verified }, bps.map { it.message }.toString())
        assertEquals("OrderID", d.source(bps[0].node!!))
        assertEquals("\$p.Price * \$p.Quantity", d.source(bps[1].node!!))
        d.start(json(ordersData))

        assertEquals("BREAKPOINT OrderID", d.next())
        assertEquals("{\"OrderID\":\"o1\",\"Product\":[{\"Name\":\"Hat\",\"Price\":10,\"Quantity\":2},{\"Name\":\"Cap\",\"Price\":5,\"Quantity\":1}]}", d.input())
        d.session.resume()
        // the conditional breakpoint stops for Hat (price 10), not for Cap (price 5)
        assertEquals("BREAKPOINT \$p.Price * \$p.Quantity", d.next())
        val st = d.session.stackTrace()
        assertEquals(listOf("total", "<main>"), st.frames.map { it.name })
        assertEquals(2, st.totalFrames)
        assertEquals("\$total(\$)", orders.substring(st.frames[1].start, st.frames[1].end))
        val vars = d.session.frameAt(0)!!.bindings().map { it.name }
        assertTrue(vars.containsAll(listOf("p", "rate", "total")), vars.toString())
        d.session.stepOut()
        assertEquals("STEP \$p.Price * \$p.Quantity", d.next())
        assertEquals("20", JsonWriter.stringify(d.session.returnValue!!))
        d.session.resume()
        assertEquals("BREAKPOINT OrderID", d.next())
        d.session.resume()
        assertEquals("BREAKPOINT \$p.Price * \$p.Quantity", d.next()) // Coat
        d.session.resume()
        val outcome = d.finish()
        assertEquals("[{\"id\":\"o1\",\"sum\":50},{\"id\":\"o2\",\"sum\":72}]", outcome.toString())
    }

    @Test
    fun `step over a path step goes to its next item`() {
        val d = Driver("Account.Order.OrderID")
        d.session.setBreakpoints("main", listOf(BreakpointSpec(1, 15)))
        d.start(json(ordersData))
        assertEquals("BREAKPOINT OrderID", d.next())
        assertTrue(d.input().contains("\"o1\""))
        d.session.setBreakpoints("main", emptyList()) // else the breakpoint, not the step, reports the next stop
        d.session.stepOver()
        assertEquals("STEP OrderID", d.next())
        assertTrue(d.input().contains("\"o2\""))
        d.session.resume()
        assertEquals("[\"o1\",\"o2\"]", d.finish().toString())
    }

    @Test
    fun `step in by operators and by nodes (TC-15)`() {
        val expr = "Account.Order.Product[Price>5].Name"
        fun stops(g: Granularity): List<String> {
            val d = Driver(expr, DebugOptions(stopOnEntry = true))
            d.start(json(ordersData))
            val seen = ArrayList<String>()
            seen += d.next()
            while (true) {
                d.session.stepIn(g)
                val s = d.stops.poll(10, TimeUnit.SECONDS)
                if (s == null) break
                seen += "${s.first} ${d.source(s.second)}"
                if (seen.size > 200) break
                if (d.outcome.get() != null) break
            }
            d.finish()
            return seen
        }
        val operators = stops(Granularity.OPERATOR)
        val nodes = stops(Granularity.NODE)
        assertTrue(nodes.size > operators.size, "operators $operators nodes $nodes")
        assertTrue(nodes.any { it == "STEP Price>5" }, nodes.toString())
        assertTrue(operators.none { it.endsWith("Price>5") }, operators.toString())
        assertTrue((operators + nodes).none { it.endsWith(" 5") }, "the literal 5 never stops")
        assertTrue(operators.drop(1).all { it.substringAfter(' ') in setOf("Account", "Order", "Product[Price>5]", "Name", "Price") }, operators.toString())
    }

    @Test
    fun `recursion stack and the lambda of a higher-order function (TC-15)`() {
        val expr = "(\$f := function(\$n){ \$n = 0 ? 0 : 1 + \$f(\$n - 1) }; \$f(3))"
        val d = Driver(expr)
        d.session.setBreakpoints("main", listOf(BreakpointSpec(1, expr.indexOf("= 0") + 1, condition = "\$n = 1")))
        d.start(null)
        assertEquals("BREAKPOINT \$n = 0", d.next())
        assertEquals(listOf("f", "f", "f", "<main>"), d.session.stackTrace().frames.map { it.name })
        assertEquals(listOf("f", "f"), d.session.stackTrace(1, 2).frames.map { it.name })
        assertEquals(4, d.session.stackTrace(1, 2).totalFrames)
        d.session.resume()
        assertEquals("3", d.finish().toString())

        val m = Driver("\$map([1, 2], function(\$x){ \$x * 10 })")
        m.session.setBreakpoints("main", listOf(BreakpointSpec(1, "\$map([1, 2], function(\$x){ \$x * 10 })".indexOf("*") + 1)))
        m.start(null)
        assertEquals("BREAKPOINT \$x * 10", m.next())
        assertEquals(listOf("λ via \$map", "<main>"), m.session.stackTrace().frames.map { it.name })
        m.session.resume()
        assertEquals("BREAKPOINT \$x * 10", m.next())
        m.session.resume()
        assertEquals("[10,20]", m.finish().toString())
    }

    @Test
    fun `tail recursion replaces the frame (TC-15)`() {
        val expr = "(\$loop := function(\$n){ \$n = 0 ? 0 : \$loop(\$n - 1) }; \$loop(1000))"
        val d = Driver(expr)
        d.session.setBreakpoints("main", listOf(BreakpointSpec(1, expr.indexOf("= 0") + 1, condition = "\$n = 500")))
        d.start(null)
        assertEquals("BREAKPOINT \$n = 0", d.next())
        val frames = d.session.stackTrace().frames
        assertEquals(listOf("loop", "<main>"), frames.map { it.name })
        assertTrue(frames[0].tailCall)
        d.session.resume()
        assertEquals("0", d.finish().toString())
    }

    @Test
    fun `eval is a source of its own (TC-15)`() {
        val expr = "\$eval(\"1 + 1\") * 10"
        // by default: no stop inside the text of $eval, its breakpoints stay unverified
        val d = Driver(expr)
        val bp = d.session.setBreakpoints("eval:1", listOf(BreakpointSpec(1, 3)))
        assertTrue(!bp[0].verified)
        d.start(null)
        assertEquals("20", d.finish().toString())
        assertEquals(listOf("main", "eval:1"), d.session.sources().map { it.id })
        // stepping into $eval: the breakpoint is bound when the source appears and stops there
        val e = Driver(expr, DebugOptions(stepIntoEval = true))
        e.session.setBreakpoints("eval:1", listOf(BreakpointSpec(1, 3)))
        e.start(null)
        assertEquals("BREAKPOINT eval:1:binary", e.next())
        e.session.resume()
        assertEquals("20", e.finish().toString())
    }

    @Test
    fun `pause terminate and parallel sessions (TC-15)`() {
        val slow = "\$reduce([1..3000000], function(\$a, \$v){ \$a + \$v })"
        val d = Driver(slow)
        d.start(null)
        Thread.sleep(200)
        d.session.pause()
        assertTrue(d.next().startsWith("PAUSE "))
        assertNotNull(d.session.stackTrace().frames.firstOrNull())
        d.session.terminate()
        val outcome = d.finish()
        assertEquals("X0009", (outcome.error as JsonataException).code)

        // two sessions at once do not affect each other
        val a = Driver(orders)
        val b = Driver(orders)
        a.session.setBreakpoints("main", listOf(BreakpointSpec(5, line(5).indexOf("OrderID") + 1)))
        a.start(json(ordersData))
        b.start(json(ordersData))
        assertEquals("[{\"id\":\"o1\",\"sum\":50},{\"id\":\"o2\",\"sum\":72}]", b.finish().toString())
        assertEquals("BREAKPOINT OrderID", a.next())
        a.session.resume()
        assertEquals("BREAKPOINT OrderID", a.next())
        a.session.resume()
        assertEquals("[{\"id\":\"o1\",\"sum\":50},{\"id\":\"o2\",\"sum\":72}]", a.finish().toString())
    }

    @Test
    fun `exceptions logpoints and hit counts`() {
        val d = Driver("[1, 2, 3].(\$ = 3 ? \$ + \"x\" : \$)")
        d.session.setExceptionBreakpoints(ExceptionMode.ALL)
        d.start(null)
        assertEquals("EXCEPTION \$ + \"x\"", d.next())
        d.session.resume()
        assertEquals("T2002", (d.finish().error as JsonataException).code)

        val l = Driver("[1, 2, 3, 4].(\$ * 2)")
        l.session.setBreakpoints("main", listOf(BreakpointSpec(1, 17, logMessage = "item {\$} doubled {\$ * 2}")))
        l.start(null)
        assertEquals("[2,4,6,8]", l.finish().toString())
        assertEquals("item 1 doubled 2\nitem 2 doubled 4\nitem 3 doubled 6\nitem 4 doubled 8\n", l.output.toString())

        val h = Driver("[1, 2, 3, 4].(\$ * 2)")
        h.session.setBreakpoints("main", listOf(BreakpointSpec(1, 17, hitCondition = ">=3")))
        h.start(null)
        assertEquals("BREAKPOINT \$ * 2", h.next())
        assertEquals("3", h.input())
        h.session.resume()
        assertEquals("BREAKPOINT \$ * 2", h.next())
        h.session.resume()
        h.finish()
        // a breakpoint on an empty line is not verified
        assertNull(h.session.setBreakpoints("main", listOf(BreakpointSpec(9)))[0].node)
    }
}
