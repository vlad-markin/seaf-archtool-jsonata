package su.seaf.archtool.jsonata.debug

import su.seaf.archtool.jsonata.Jsonata
import su.seaf.archtool.jsonata.parser.JsonataException
import su.seaf.archtool.jsonata.value.ArrayValue
import su.seaf.archtool.jsonata.value.ObjectValue
import su.seaf.archtool.jsonata.value.StringValue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The source index and breakpoint targets (story 5 step 5.2, CAP-10 p. 1–2; TC-14). */
class SourceIndexTest {
    private val orders = """(
  ${'$'}rate := 1.2;
  ${'$'}total := function(${'$'}o){ ${'$'}sum(${'$'}o.Product.(Price * Quantity)) * ${'$'}rate };
  ${'$'}loop := function(${'$'}n){ ${'$'}n = 0 ? 0 : ${'$'}loop(${'$'}n - 1) };
  Account.Order.{ "id": OrderID, "total": ${'$'}total(${'$'}) }
)"""

    private fun text(idx: SourceIndex, n: NodeInfo?): String = n?.let { idx.text.substring(it.start, it.end) } ?: "none"

    @Test
    fun `breakpoints bind to the nodes of the line`() {
        val idx = SourceIndex.of(Jsonata.compile(orders))
        // line 5: the first operator stop of the line is the path step Account
        assertEquals("Account", text(idx, idx.resolveBreakpoint(5)))
        // with a column: the node at the place (the step OrderID)
        val col = orders.lines()[4].indexOf("OrderID") + 1
        assertEquals("OrderID", text(idx, idx.resolveBreakpoint(5, col)))
        assertEquals("5:${col}-5:${col + 7}", idx.range(idx.resolveBreakpoint(5, col)!!).toString())
        // inside a lambda body
        assertEquals("\$sum(\$o.Product.(Price * Quantity))", text(idx, idx.resolveBreakpoint(3, orders.lines()[2].indexOf("\$sum") + 1)))
        // a tail call: the thunk is not a stop, the call is
        val loopCol = orders.lines()[3].indexOf("\$loop(\$n - 1)") + 1
        val target = idx.resolveBreakpoint(4, loopCol)!!
        assertEquals("function", target.type)
        assertEquals("\$loop(\$n - 1)", text(idx, target))
        // a literal moves to the operator around it
        assertEquals("bind", idx.resolveBreakpoint(2, orders.lines()[1].indexOf("1.2") + 1)!!.type)
        // a line without code
        assertNull(idx.resolveBreakpoint(7))
    }

    @Test
    fun `nodes sharing a range resolve to the outermost`() {
        val idx = SourceIndex.of(Jsonata.compile("a ?? b"))
        // the synthetic $exists(a) and the copy of a share the operator's range: the outermost of them, $exists(...)
        val at = idx.nodeAt(2)!!
        assertEquals("function", at.type)
        assertEquals("??", text(idx, at))
        assertEquals("a", text(idx, idx.nodeAt(0)))
    }

    /** Expressions of the vendored test-suite (a JSON text is a JSONata expression). */
    private fun suiteExpressions(): List<String> {
        val groups = File("../compat/jsonata-js/test-suite/groups")
        return groups.listFiles()!!.filter { it.isDirectory }.sortedBy { it.name }.flatMap { group ->
            group.listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }.flatMap { file ->
                val parsed = Jsonata.compile(file.readText()).evaluate(null)
                val items = if (parsed is ArrayValue) (0 until parsed.size).map { parsed[it] } else listOf(parsed)
                items.map { c -> c as ObjectValue; (c["expr-file"] as? StringValue)?.let { File(group, it.value).readText() } ?: (c["expr"] as StringValue).value }
            }
        }
    }

    @Test
    fun `every node of every test-suite expression has a range inside its parent (TC-14)`() {
        val problems = ArrayList<String>()
        var indexed = 0
        for (expr in suiteExpressions()) {
            val compiled = try {
                Jsonata.compile(expr)
            } catch (_: JsonataException) {
                continue
            }
            val idx = SourceIndex.of(compiled)
            indexed++
            for (n in idx.nodes) {
                val p = if (n.parentId >= 0) idx.nodes[n.parentId] else null
                if (n.start < 0 || n.end <= n.start) problems += "$expr: no range for $n"
                else if (p != null && (n.start < p.start || n.end > p.end)) problems += "$expr: $n outside $p"
            }
            // every line resolves without failing
            for (line in 1..expr.lines().size) idx.resolveBreakpoint(line)
        }
        assertTrue(indexed > 1600, "indexed $indexed")
        assertTrue(problems.isEmpty(), problems.take(30).joinToString("\n"))
    }
}
