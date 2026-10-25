package su.seaf.archtool.jsonata.parser

import su.seaf.archtool.jsonata.CompileOptions
import su.seaf.archtool.jsonata.Jsonata
import su.seaf.archtool.jsonata.engine.Node
import su.seaf.archtool.jsonata.value.ArrayValue
import su.seaf.archtool.jsonata.value.ObjectValue
import su.seaf.archtool.jsonata.value.StringValue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Source ranges of AST and compiled nodes (story 2 step 2.1, TC-14). */
class RangesTest {
    /** Expressions of the vendored jsonata-js 2.2.2 test-suite (a JSON text is a JSONata expression). */
    private fun suiteExpressions(): List<Pair<String, String>> {
        val groups = File("../compat/jsonata-js/test-suite/groups")
        val out = ArrayList<Pair<String, String>>()
        for (group in groups.listFiles()!!.filter { it.isDirectory }.sortedBy { it.name }) {
            for (file in group.listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }) {
                val parsed = Jsonata.compile(file.readText()).evaluate(null)
                val items = if (parsed is ArrayValue) (0 until parsed.size).map { parsed[it] } else listOf(parsed)
                items.forEachIndexed { i, c ->
                    c as ObjectValue
                    val expr = (c["expr-file"] as? StringValue)?.let { File(group, it.value).readText() } ?: (c["expr"] as StringValue).value
                    out += "${group.name}/${file.name}[$i]" to expr
                }
            }
        }
        return out
    }

    private val notChildren = setOf("ancestor", "slot", "seekingParent", "signature")

    private fun children(n: AstNode): List<AstNode> = n.entries.filter { it.key !in notChildren }.flatMap { (_, v) ->
        when (v) {
            is AstNode -> listOf(v)
            is List<*> -> v.flatMap { x -> if (x is AstNode) listOf(x) else if (x is List<*>) x.filterIsInstance<AstNode>() else emptyList() }
            else -> emptyList()
        }
    }

    /** Problems of the AST ranges of [expr]: every node has a non-empty range inside its parent's and the source. */
    private fun astProblems(expr: String): List<String> {
        val parsed = try {
            JsonataParser.parseWithRanges(expr)
        } catch (_: JsonataException) {
            return emptyList()
        }
        val problems = ArrayList<String>()
        val stack = ArrayList<Pair<AstNode, SourceRange?>>()
        stack.add(parsed.ast to SourceRange(0, expr.length))
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<AstNode, Boolean>())
        while (stack.isNotEmpty()) {
            val (n, parent) = stack.removeAt(stack.size - 1)
            if (!seen.add(n)) continue
            val r = parsed.ranges[n]
            when {
                r == null -> problems += "no range: ${n.type} ${n["value"]}"
                r.start >= r.end -> problems += "empty range $r: ${n.type}"
                parent != null && !parent.contains(r) -> problems += "range $r of ${n.type} ${n["value"]} outside the parent $parent"
            }
            for (c in children(n)) stack.add(c to (r ?: parent))
        }
        return problems
    }

    @Test
    fun `every node of every test-suite expression has a range inside its parent`() {
        val cases = suiteExpressions()
        assertEquals(1686, cases.size)
        val problems = cases.flatMap { (id, expr) -> astProblems(expr).map { "$id: $it   in $expr" } }
        assertTrue(problems.isEmpty(), "${problems.size} problems:\n" + problems.take(40).joinToString("\n"))
    }

    @Test
    fun `ranges of operators with synthetic nodes`() {
        fun range(expr: String, pick: (AstNode) -> AstNode): String {
            val p = JsonataParser.parseWithRanges(expr)
            val r = p.ranges[pick(p.ast)]!!
            return expr.substring(r.start, r.end)
        }
        assertEquals("a ?? b", range("a ?? b") { it })
        assertEquals("??", range("a ?? b") { it.node("condition")!! }) // the synthetic $exists(a)
        assertEquals("??", range("a ?? b") { (it.node("condition")!!.nodes("arguments")!![0] as AstNode) }) // the copy of a
        assertEquals("a", range("a ?? b") { it.node("then")!! })
        assertEquals("a ?: b", range("a ?: b") { it })
        assertEquals("?:", range("a ?: b") { it.node("condition")!! }) // the copy of a
        assertEquals("a", range("a ?: b") { it.node("then")!! })
        assertEquals("x^(>y)", range("x^(>y)") { it })
        assertEquals("^(>y)", range("x^(>y)") { it.nodes("steps")!![1] as AstNode })
        assertEquals("[b > 1]", range("a[b > 1].c") { (it.nodes("steps")!![0] as AstNode).nodes("stages")!![0] as AstNode })
        assertEquals("function(\$x){ \$x + 1 }", range("(\$f := function(\$x){ \$x + 1 })") { (it.nodes("expressions")!![0] as AstNode).node("rhs")!! })
        assertEquals("\$f := function(\$x){ \$x + 1 }", range("(\$f := function(\$x){ \$x + 1 })") { it.nodes("expressions")!![0] as AstNode })
        assertEquals("{\"a\": 1}", range("{\"a\": 1}") { it })
        assertEquals("|\$|{\"a\":1}|", range("\$ ~> |\$|{\"a\":1}|") { it.node("rhs")!! })
        assertEquals("a@\$x.b", range("a@\$x.b") { it })
        assertEquals("a#\$i", range("a#\$i") { it })
        assertEquals("\$sum([1, 2])", range("\$sum([1, 2])") { it })
    }

    @Test
    fun `compiled nodes carry the ranges`() {
        val expr = "Account.Order[0].Product.(Price * Quantity) ~> \$sum()"
        val root = Jsonata.compile(expr, CompileOptions()).compiledRoot
        val problems = ArrayList<String>()
        walk(root, null) { node, parent ->
            if (node.start < 0 || node.end <= node.start) problems += "no range: ${node.javaClass.simpleName} ${node.ast.type}"
            if (parent != null && (node.start < parent.start || node.end > parent.end)) problems += "${node.javaClass.simpleName} outside its parent"
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
        assertEquals(0, root.start)
        assertEquals(expr.length, root.end)
    }

    /** Visits compiled nodes through their fields (reflection: the node classes have no common child list yet). */
    private fun walk(node: Node, parent: Node?, visit: (Node, Node?) -> Unit) {
        visit(node, parent)
        var cls: Class<*>? = node.javaClass
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
        while (cls != null && cls != Any::class.java) {
            for (f in cls.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                f.isAccessible = true
                for (child in nodesIn(f.get(node))) if (seen.add(child)) walk(child, node, visit)
            }
            cls = cls.superclass
        }
    }

    private fun nodesIn(v: Any?): List<Node> = when (v) {
        is Node -> listOf(v)
        is Array<*> -> v.flatMap { nodesIn(it) }
        is List<*> -> v.flatMap { nodesIn(it) }
        else -> if (v != null && v.javaClass.name.startsWith("su.seaf.archtool.jsonata.engine.") && v !is Node) {
            v.javaClass.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }.flatMap { f -> f.isAccessible = true; nodesIn(f.get(v)) }
        } else {
            emptyList()
        }
    }

    @Test
    fun `line and column`() {
        val idx = LineIndex("ab\ncd\r\nef\rg")
        assertEquals(1, idx.line(0)); assertEquals(1, idx.column(0))
        assertEquals(1, idx.line(2)); assertEquals(3, idx.column(2))
        assertEquals(2, idx.line(3)); assertEquals(1, idx.column(3))
        assertEquals(3, idx.line(7)); assertEquals(1, idx.column(7))
        assertEquals(4, idx.line(10)); assertEquals(1, idx.column(10))
    }
}
