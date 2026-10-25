package su.seaf.archtool.jsonata.debug

import su.seaf.archtool.jsonata.EvaluationOptions
import su.seaf.archtool.jsonata.FunctionSource
import su.seaf.archtool.jsonata.Jsonata
import su.seaf.archtool.jsonata.value.JsonWriter
import su.seaf.archtool.jsonata.value.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The public evaluation listener (story 5 step 5.1, CAP-10). */
class ListenerTest {
    private class Recorder : EvaluationListener {
        val events = ArrayList<String>()
        val nodes = LinkedHashMap<Int, NodeInfo>()
        val sources = ArrayList<SourceInfo>()
        override fun enter(node: NodeInfo, input: Value?, frame: DebugFrame, iteration: Iteration?) {
            nodes[node.id + node.sourceId.hashCode() * 100_000] = node
            events += "enter ${node.type}${node.value?.let { ":$it" } ?: ""}${iteration?.let { " [$it]" } ?: ""} in ${frame.name}"
        }
        override fun exit(node: NodeInfo, input: Value?, frame: DebugFrame, result: Value?) {
            events += "exit ${node.type} => ${if (result == null) "undefined" else JsonWriter.stringify(result)}"
        }
        override fun error(node: NodeInfo, input: Value?, frame: DebugFrame, error: Throwable) {
            events += "error ${node.type}"
        }
        override fun nativeEnter(name: String, source: FunctionSource, args: List<Value?>, frame: DebugFrame) {
            events += "native $name ($source) in ${frame.name}"
        }
        override fun nativeExit(name: String, result: Value?, frame: DebugFrame) {
            events += "native exit $name"
        }
        override fun source(source: SourceInfo) {
            sources += source
        }
    }

    private fun run(expr: String, input: String? = null): Pair<String, Recorder> {
        val r = Recorder()
        val v = input?.let { Jsonata.compile(it).evaluate(null) }
        val result = Jsonata.compile(expr).evaluate(v, emptyMap(), EvaluationOptions(listener = r))
        return (if (result == null) "undefined" else JsonWriter.stringify(result)) to r
    }

    @Test
    fun `events in evaluation order with nodes and ranges`() {
        val expr = "1 + 2"
        val (result, r) = run(expr)
        assertEquals("3", result)
        assertEquals(listOf("enter binary:+ in <main>", "enter number:1 in <main>", "exit number => 1", "enter number:2 in <main>", "exit number => 2", "exit binary => 3"), r.events)
        val root = r.nodes.values.first()
        assertEquals(0, root.id)
        assertEquals(-1, root.parentId)
        assertEquals("main", root.sourceId)
        assertEquals(expr, expr.substring(root.start, root.end))
        assertTrue(r.nodes.values.drop(1).all { it.parentId == 0 && it.literal })
        assertEquals(listOf("main"), r.sources.map { it.id })
    }

    @Test
    fun `path steps report the item they evaluate`() {
        val (result, r) = run("Order.OrderID", """{"Order":[{"OrderID":"a"},{"OrderID":"b"}]}""")
        assertEquals("[\"a\",\"b\"]", result)
        assertTrue(r.events.contains("enter name:OrderID [1 of 2] in <main>"), r.events.toString())
        assertTrue(r.events.contains("enter name:OrderID [2 of 2] in <main>"), r.events.toString())
        assertTrue(r.nodes.values.filter { it.type == "name" }.all { it.operatorStop })
    }

    @Test
    fun `frames of lambdas and natives`() {
        // `+ 0`: not tail calls (the trampoline replaces the frame of a tail call)
        val (result, r) = run("(\$total := function(\$o){ \$sum(\$o.v) + 0 }; \$map([{\"v\":[1,2]},{\"v\":[3]}], function(\$x){ \$total(\$x) + 0 }))")
        assertEquals("[3,3]", result)
        assertTrue(r.events.contains("native map (STDLIB) in <main>"), r.events.toString())
        assertTrue(r.events.any { it.startsWith("enter function") && it.endsWith("in λ via \$map") }, r.events.toString())
        assertTrue(r.events.contains("native sum (STDLIB) in total"), r.events.toString())
        assertTrue(r.events.contains("native exit map"))
    }

    @Test
    fun `the call stack and bindings seen from a frame`() {
        var seen: List<String>? = null
        var vars: List<String>? = null
        var root: String? = null
        val listener = object : EvaluationListener {
            override fun enter(node: NodeInfo, input: Value?, frame: DebugFrame, iteration: Iteration?) {
                if (node.type == "binary" && node.value == "*") {
                    seen = generateSequence(frame) { it.parent }.map { "${it.name}${if (it.tailCall) " (tail)" else ""}@${it.callSite?.start}" }.toList()
                    vars = frame.bindings().map { "${it.name}=${it.value?.let { v -> JsonWriter.stringify(v) } ?: "undefined"}" }
                    root = frame.root()?.let { JsonWriter.stringify(it) }
                }
            }
        }
        val expr = "(\$k := 3; \$f := function(\$x){ \$x * \$k }; \$g := function(\$y){ \$f(\$y + 1) + 0 }; \$g(\$.n))"
        Jsonata.compile(expr).evaluate(Jsonata.compile("{\"n\": 1}").evaluate(null), emptyMap(), EvaluationOptions(listener = listener))
        assertEquals(listOf("f@${expr.indexOf("\$f(\$y")}", "g@${expr.indexOf("\$g(\$.n")}", "<main>@null"), seen)
        assertEquals(listOf("x=2", "k=3", "f=", "g=", "\$={\"n\":1}").map { it.substringBefore('=') }, vars!!.map { it.substringBefore('=') })
        assertEquals("{\"n\":1}", root)
    }

    @Test
    fun `a tail call replaces the frame with a mark`() {
        var marks = HashSet<String>()
        val listener = object : EvaluationListener {
            override fun enter(node: NodeInfo, input: Value?, frame: DebugFrame, iteration: Iteration?) {
                if (node.type == "condition") marks.add("${frame.name} tail=${frame.tailCall} depth=${generateSequence(frame) { it.parent }.count()}")
            }
        }
        Jsonata.compile("(\$loop := function(\$n){ \$n = 0 ? 0 : \$loop(\$n - 1) }; \$loop(1000))").evaluate(null, emptyMap(), EvaluationOptions(listener = listener))
        // the frame of the first call, then one replaced frame per tail call: the stack does not grow
        assertTrue(marks.all { it.endsWith("depth=2") }, marks.toString())
        assertTrue("loop tail=true depth=2" in marks, marks.toString())
    }

    @Test
    fun `the text of eval is a source of its own`() {
        val (result, r) = run("\$eval(\"1 + 1\") + \$eval(\"2\")")
        assertEquals("4", result)
        assertEquals(listOf("main", "eval:1", "eval:2"), r.sources.map { it.id })
        assertEquals("1 + 1", r.sources[1].text)
        val evalNodes = r.nodes.values.filter { it.sourceId == "eval:1" }
        assertEquals(listOf("binary", "number", "number"), evalNodes.map { it.type })
        assertEquals("1 + 1", "1 + 1".substring(evalNodes[0].start, evalNodes[0].end))
    }

    @Test
    fun `errors are reported on the way up`() {
        val r = Recorder()
        runCatching { Jsonata.compile("(\$a := 1; \$a + \"x\")").evaluate(null, emptyMap(), EvaluationOptions(listener = r)) }
        assertEquals(listOf("error binary", "error block"), r.events.filter { it.startsWith("error") })
    }
}
