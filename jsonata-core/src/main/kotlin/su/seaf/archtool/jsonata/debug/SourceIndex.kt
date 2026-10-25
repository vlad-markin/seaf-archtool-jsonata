package su.seaf.archtool.jsonata.debug

import su.seaf.archtool.jsonata.Jsonata
import su.seaf.archtool.jsonata.parser.LineIndex

/** A range of a source in lines and columns (from 1, UTF-16 units), end exclusive. */
public class TextRange(public val startLine: Int, public val startColumn: Int, public val endLine: Int, public val endColumn: Int) {
    override fun toString(): String = "$startLine:$startColumn-$endLine:$endColumn"
}

/**
 * The nodes of an expression by place (CAP-10 p. 1–2): where a breakpoint goes, what is under the cursor. Built from
 * the debugging tree of the expression (the same ids and ranges the listener reports).
 */
public class SourceIndex internal constructor(
    public val sourceId: String,
    public val text: String,
    /** Every node by id. */
    public val nodes: List<NodeInfo>,
) {
    private val lines = LineIndex(text)

    /** Offset of a line and column (from 1). */
    public fun offset(line: Int, column: Int): Int {
        var l = 1
        var i = 0
        while (l < line && i < text.length) {
            val c = text[i++]
            if (c == '\n' || (c == '\r' && (i >= text.length || text[i] != '\n'))) l++
        }
        return (i + column - 1).coerceIn(0, text.length)
    }

    public fun range(node: NodeInfo): TextRange =
        TextRange(lines.line(node.start), lines.column(node.start), lines.line(node.end), lines.column(node.end))

    /** Where the evaluator can stop: executable and not a literal. */
    private fun stoppable(n: NodeInfo) = n.executable && !n.literal && n.start >= 0

    /**
     * The innermost stoppable node at [offset]. When several nodes share its range (the parser's synthetic nodes of
     * `??` and `?:`), the outermost of them.
     */
    public fun nodeAt(offset: Int): NodeInfo? {
        val inner = nodes.filter { stoppable(it) && it.start <= offset && offset < it.end }.minByOrNull { it.end - it.start } ?: return null
        var best = inner
        var p = parentOf(best)
        while (p != null && p.start == inner.start && p.end == inner.end) {
            if (stoppable(p)) best = p
            p = parentOf(p)
        }
        return best
    }

    public fun nodeAt(line: Int, column: Int): NodeInfo? = nodeAt(offset(line, column))

    /**
     * The node a breakpoint at [line] (and [column], when given) binds to (CAP-10 p. 2): with a column — the node at
     * that place; without — the first operator stop that starts on the line, else the first stoppable node starting on
     * it, else the innermost node covering the line start. A place on a stage, a lambda parameter or a tail-call
     * thunk moves to the nearest stoppable node. Null when the line has no code.
     */
    public fun resolveBreakpoint(line: Int, column: Int? = null): NodeInfo? {
        if (column != null) {
            // the name of a called function stands for the call: `$sum(…)` at `$sum` is the call
            var n = nodeAt(line, column) ?: return null
            var p = parentOf(n)
            while (!n.operatorStop && p != null && p.start == n.start && stoppable(p)) {
                n = p
                p = parentOf(n)
            }
            return n
        }
        val lineStart = offset(line, 1)
        val starting = nodes.filter { stoppable(it) && lines.line(it.start) == line }
        starting.filter { it.operatorStop }.minWithOrNull(compareBy<NodeInfo> { it.start }.thenByDescending { it.end - it.start })?.let { return it }
        starting.minWithOrNull(compareBy<NodeInfo> { it.start }.thenByDescending { it.end - it.start })?.let { return it }
        if (lineStart >= text.length) return null
        return nodeAt(lineStart)
    }

    private fun parentOf(n: NodeInfo): NodeInfo? = if (n.parentId < 0) null else nodes.getOrNull(n.parentId)

    public companion object {
        /** The index of the main source of [expression]. */
        @JvmStatic
        public fun of(expression: Jsonata): SourceIndex = SourceIndex("main", expression.expression, expression.debugTree().infos)
    }
}
