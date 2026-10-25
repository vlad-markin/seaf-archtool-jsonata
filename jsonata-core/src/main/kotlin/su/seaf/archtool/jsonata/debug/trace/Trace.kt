package su.seaf.archtool.jsonata.debug.trace

import su.seaf.archtool.jsonata.FunctionSource
import su.seaf.archtool.jsonata.debug.DebugFrame
import su.seaf.archtool.jsonata.debug.EvaluationListener
import su.seaf.archtool.jsonata.debug.Iteration
import su.seaf.archtool.jsonata.debug.NodeInfo
import su.seaf.archtool.jsonata.debug.SourceInfo
import su.seaf.archtool.jsonata.engine.FrameView
import su.seaf.archtool.jsonata.value.ArrayValue
import su.seaf.archtool.jsonata.value.BooleanValue
import su.seaf.archtool.jsonata.value.JsonWriter
import su.seaf.archtool.jsonata.value.NumberValue
import su.seaf.archtool.jsonata.value.ObjectValue
import su.seaf.archtool.jsonata.value.StringValue
import su.seaf.archtool.jsonata.value.Value

/** Several listeners as one, called in order. */
public class CompositeListener(private vararg val listeners: EvaluationListener) : EvaluationListener {
    override fun enter(node: NodeInfo, input: Value?, frame: DebugFrame, iteration: Iteration?) {
        for (l in listeners) l.enter(node, input, frame, iteration)
    }
    override fun exit(node: NodeInfo, input: Value?, frame: DebugFrame, result: Value?) {
        for (l in listeners) l.exit(node, input, frame, result)
    }
    override fun error(node: NodeInfo, input: Value?, frame: DebugFrame, error: Throwable) {
        for (l in listeners) l.error(node, input, frame, error)
    }
    override fun nativeEnter(name: String, source: FunctionSource, args: List<Value?>, frame: DebugFrame) {
        for (l in listeners) l.nativeEnter(name, source, args, frame)
    }
    override fun nativeExit(name: String, result: Value?, frame: DebugFrame) {
        for (l in listeners) l.nativeExit(name, result, frame)
    }
    override fun source(source: SourceInfo) {
        for (l in listeners) l.source(source)
    }
}

/** Statistics of one node: evaluations, own and total time, largest result. */
public class NodeProfile internal constructor(public val node: NodeInfo) {
    public var count: Long = 0
        internal set
    public var selfNanos: Long = 0
        internal set
    public var totalNanos: Long = 0
        internal set
    /** Largest result: items of an array, keys of an object, length of a string, 1 for other values. */
    public var maxResultSize: Long = 0
        internal set
    internal var active = 0
}

/** Statistics of one native function: calls and total time. */
public class FunctionProfile internal constructor(public val name: String, public val source: FunctionSource) {
    public var calls: Long = 0
        internal set
    public var totalNanos: Long = 0
        internal set
    internal var active = 0
}

/**
 * Where an evaluation spends its time (CAP-11 p. 1), node by node and function by function. The total time of a
 * recursive node counts only its outermost active evaluation, so that deep recursion does not count time over and
 * over. Not thread-safe: one evaluation per profiler.
 */
public class Profiler @JvmOverloads constructor(private val topN: Int = 20) : EvaluationListener {
    private class Open(val profile: NodeProfile, val start: Long) {
        var children = 0L
    }

    private val nodes = LinkedHashMap<String, NodeProfile>()
    private val functions = LinkedHashMap<String, FunctionProfile>()
    private val stack = ArrayList<Open>()
    private val nativeStarts = ArrayList<Long>()
    private var segments = 0

    /** Every node profiled, in order of first evaluation. */
    public val nodeProfiles: List<NodeProfile> get() = nodes.values.toList()

    /** The [topN] nodes by own time. */
    public fun top(): List<NodeProfile> = nodes.values.sortedByDescending { it.selfNanos }.take(topN)

    public val functionProfiles: List<FunctionProfile> get() = functions.values.sortedByDescending { it.totalNanos }

    /** Stack segments the evaluation entered (deep recursion, `docs/limits.md`). */
    public val stackSegments: Int get() = segments

    override fun enter(node: NodeInfo, input: Value?, frame: DebugFrame, iteration: Iteration?) {
        val p = nodes.getOrPut(node.sourceId + "#" + node.id) { NodeProfile(node) }
        p.count++
        p.active++
        stack.add(Open(p, System.nanoTime()))
    }

    override fun exit(node: NodeInfo, input: Value?, frame: DebugFrame, result: Value?) {
        close(result)
        segments = maxOf(segments, (frame as FrameView).frame.ctx.segmentsEntered)
    }

    override fun error(node: NodeInfo, input: Value?, frame: DebugFrame, error: Throwable) {
        close(null)
    }

    private fun close(result: Value?) {
        val open = stack.removeAt(stack.size - 1)
        val total = System.nanoTime() - open.start
        val p = open.profile
        p.active--
        p.selfNanos += total - open.children
        if (p.active == 0) p.totalNanos += total
        stack.lastOrNull()?.let { it.children += total }
        val size = when (result) {
            null -> 0L
            is ArrayValue -> result.size.toLong()
            is ObjectValue -> result.keys.size.toLong()
            is StringValue -> result.value.length.toLong()
            else -> 1L
        }
        if (size > p.maxResultSize) p.maxResultSize = size
    }

    override fun nativeEnter(name: String, source: FunctionSource, args: List<Value?>, frame: DebugFrame) {
        val f = functions.getOrPut(name) { FunctionProfile(name, source) }
        f.calls++
        f.active++
        nativeStarts.add(System.nanoTime())
    }

    override fun nativeExit(name: String, result: Value?, frame: DebugFrame) {
        val start = nativeStarts.removeAt(nativeStarts.size - 1)
        val f = functions.getValue(name)
        f.active--
        if (f.active == 0) f.totalNanos += System.nanoTime() - start
    }
}

/** One recorded evaluation of a traced node: input and result as JSON text, cut to the entry limit. */
public class TraceEntry(public val node: NodeInfo, public val input: String, public val result: String, public val truncated: Boolean)

/**
 * The first [k] "input → result" pairs of the selected nodes (CAP-11 p. 2–3). Values longer than [maxEntryBytes]
 * characters are cut and marked; beyond [maxEntries] entries or [maxBytes] characters in all the trace stops growing
 * ([truncated]) while the evaluation goes on.
 */
public class ValueTracer @JvmOverloads constructor(
    private val select: (NodeInfo) -> Boolean,
    private val k: Int = 3,
    private val maxEntryBytes: Int = 1024,
    private val maxEntries: Int = 1000,
    private val maxBytes: Long = 1L shl 20,
) : EvaluationListener {
    private val entries = ArrayList<TraceEntry>()
    private val counts = HashMap<String, Int>()
    private val inputs = ArrayList<Value?>()
    private var bytes = 0L

    /** True when the trace hit [maxEntries] or [maxBytes]. */
    public var truncated: Boolean = false
        private set

    public val trace: List<TraceEntry> get() = entries.toList()

    override fun enter(node: NodeInfo, input: Value?, frame: DebugFrame, iteration: Iteration?) {
        inputs.add(input)
    }

    override fun error(node: NodeInfo, input: Value?, frame: DebugFrame, error: Throwable) {
        inputs.removeAt(inputs.size - 1)
    }

    override fun exit(node: NodeInfo, input: Value?, frame: DebugFrame, result: Value?) {
        val recordedInput = inputs.removeAt(inputs.size - 1)
        if (truncated || !select(node)) return
        val key = node.sourceId + "#" + node.id
        val n = counts.getOrDefault(key, 0)
        if (n >= k) return
        if (entries.size >= maxEntries) {
            truncated = true
            return
        }
        val (i, ti) = cut(recordedInput)
        val (r, tr) = cut(result)
        if (bytes + i.length + r.length > maxBytes) {
            truncated = true
            return
        }
        counts[key] = n + 1
        bytes += i.length + r.length
        entries += TraceEntry(node, i, r, ti || tr)
    }

    private fun cut(v: Value?): Pair<String, Boolean> {
        val s = if (v == null) "undefined" else JsonWriter.stringify(v)
        return if (s.length <= maxEntryBytes) s to false else s.substring(0, maxEntryBytes) to true
    }

    public companion object {
        /** Selects nodes whose range lies within `[start, end)`. */
        @JvmStatic
        public fun inRange(start: Int, end: Int): (NodeInfo) -> Boolean = { it.start >= start && it.end <= end }
    }
}

/** A report of a profile and a trace (CAP-11 p. 5): JSON for IDEs and the portal, text for people. */
public class TraceReport @JvmOverloads constructor(
    private val source: String,
    private val profiler: Profiler?,
    private val tracer: ValueTracer? = null,
) {
    private val lines = su.seaf.archtool.jsonata.parser.LineIndex(source)

    private fun range(n: NodeInfo): String = "${lines.line(n.start)}:${lines.column(n.start)}-${lines.line(n.end)}:${lines.column(n.end)}"

    private fun text(n: NodeInfo): String = if (n.sourceId == "main" && n.start >= 0 && n.end <= source.length) source.substring(n.start, n.end) else n.type

    public fun toJson(): String {
        val root = ObjectValue()
        profiler?.let { p ->
            val nodes = ArrayValue()
            for (np in p.top()) {
                nodes.add(ObjectValue().also { o ->
                    o.put("node", NumberValue.of(np.node.id))
                    o.put("source", StringValue.of(np.node.sourceId))
                    o.put("type", StringValue.of(np.node.type))
                    o.put("range", StringValue.of(range(np.node)))
                    o.put("start", NumberValue.of(np.node.start))
                    o.put("end", NumberValue.of(np.node.end))
                    o.put("count", NumberValue.of(np.count.toDouble()))
                    o.put("selfMicros", NumberValue.of(np.selfNanos / 1000.0))
                    o.put("totalMicros", NumberValue.of(np.totalNanos / 1000.0))
                    o.put("maxResultSize", NumberValue.of(np.maxResultSize.toDouble()))
                })
            }
            root.put("nodes", nodes)
            val functions = ArrayValue()
            for (f in p.functionProfiles) {
                functions.add(ObjectValue().also { o ->
                    o.put("name", StringValue.of(f.name))
                    o.put("source", StringValue.of(f.source.name.lowercase()))
                    o.put("calls", NumberValue.of(f.calls.toDouble()))
                    o.put("totalMicros", NumberValue.of(f.totalNanos / 1000.0))
                })
            }
            root.put("functions", functions)
            root.put("stackSegments", NumberValue.of(p.stackSegments))
        }
        tracer?.let { t ->
            val entries = ArrayValue()
            for (e in t.trace) {
                entries.add(ObjectValue().also { o ->
                    o.put("node", NumberValue.of(e.node.id))
                    o.put("range", StringValue.of(range(e.node)))
                    o.put("input", StringValue.of(e.input))
                    o.put("result", StringValue.of(e.result))
                    o.put("truncated", BooleanValue.of(e.truncated))
                })
            }
            root.put("trace", entries)
            root.put("traceTruncated", BooleanValue.of(t.truncated))
        }
        return JsonWriter.stringify(root)
    }

    public fun toText(): String = buildString {
        profiler?.let { p ->
            appendLine("node                                     range        count     self ms    total ms")
            for (np in p.top()) {
                appendLine(String.format("%-40s %-12s %6d %10.3f %10.3f", text(np.node).replace('\n', ' ').take(40), range(np.node), np.count, np.selfNanos / 1e6, np.totalNanos / 1e6))
            }
            if (p.functionProfiles.isNotEmpty()) {
                appendLine("function                 calls    total ms")
                for (f in p.functionProfiles) appendLine(String.format("%-24s %6d %10.3f", "\$" + f.name, f.calls, f.totalNanos / 1e6))
            }
        }
        tracer?.let { t ->
            for (e in t.trace) appendLine("${range(e.node)} ${text(e.node).take(30)}: ${e.input} -> ${e.result}${if (e.truncated) " (truncated)" else ""}")
            if (t.truncated) appendLine("(trace truncated)")
        }
    }
}
