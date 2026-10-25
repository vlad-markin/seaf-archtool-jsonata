package su.seaf.archtool.jsonata.parser

/** A range of the source text: [start] is the first UTF-16 unit, [end] the offset after the last one. */
public class SourceRange(public val start: Int, public val end: Int) {
    override fun equals(other: Any?): Boolean = other is SourceRange && other.start == start && other.end == end
    override fun hashCode(): Int = start * 31 + end
    override fun toString(): String = "$start..$end"

    /** Whether [other] lies inside this range. */
    public fun contains(other: SourceRange): Boolean = other.start >= start && other.end <= end
}

/**
 * The source range of every node of a parsed expression (CAP-9, CAP-10), kept beside the AST so that `ast()` and
 * the AST snapshots stay as in jsonata-js. A node's range covers its own tokens and those of its children; a node
 * made by processAST takes the range of the node it was made from.
 */
internal class SourceRanges(private val ranges: java.util.IdentityHashMap<AstNode, SourceRange>) {
    operator fun get(node: AstNode): SourceRange? = ranges[node]

    val size: Int get() = ranges.size
}

/** The result of parsing with ranges: the AST of `ast()` and the ranges of its nodes. */
internal class ParsedExpression(val ast: AstNode, val ranges: SourceRanges)

/**
 * Line and column (from 1, in UTF-16 units) of an offset in [source]; the line table is built on first use. Line
 * terminators are `\n`, `\r\n` and `\r`.
 */
public class LineIndex(private val source: String) {
    private val starts: IntArray by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val list = ArrayList<Int>()
        list.add(0)
        var i = 0
        while (i < source.length) {
            val c = source[i]
            if (c == '\r' && i + 1 < source.length && source[i + 1] == '\n') {
                list.add(i + 2)
                i += 2
                continue
            }
            if (c == '\n' || c == '\r') list.add(i + 1)
            i++
        }
        list.toIntArray()
    }

    /** The line of [offset], from 1. */
    public fun line(offset: Int): Int {
        val idx = java.util.Arrays.binarySearch(starts, offset.coerceIn(0, source.length))
        return if (idx >= 0) idx + 1 else -idx - 1
    }

    /** The column of [offset], from 1. */
    public fun column(offset: Int): Int = offset.coerceIn(0, source.length) - starts[line(offset) - 1] + 1
}
