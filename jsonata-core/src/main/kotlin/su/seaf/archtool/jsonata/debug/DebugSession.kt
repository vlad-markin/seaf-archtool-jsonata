package su.seaf.archtool.jsonata.debug

import su.seaf.archtool.jsonata.CancellationToken
import su.seaf.archtool.jsonata.EvaluationOptions
import su.seaf.archtool.jsonata.Jsonata
import su.seaf.archtool.jsonata.engine.Compiler
import su.seaf.archtool.jsonata.engine.FrameView
import su.seaf.archtool.jsonata.parser.JsonataException
import su.seaf.archtool.jsonata.parser.JsonataParser
import su.seaf.archtool.jsonata.value.JsonWriter
import su.seaf.archtool.jsonata.value.Value
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** A breakpoint request, as DAP `setBreakpoints`: [hitCondition] is `n`, `>=n`, `==n` or `%n`; [logMessage] with `{expr}`. */
public class BreakpointSpec @JvmOverloads constructor(
    public val line: Int,
    public val column: Int? = null,
    public val condition: String? = null,
    public val hitCondition: String? = null,
    public val logMessage: String? = null,
)

/** Where a breakpoint was bound: the node and its final range, or why it is not verified. */
public class ResolvedBreakpoint(
    public val id: Int,
    public val verified: Boolean,
    public val node: NodeInfo?,
    public val range: TextRange?,
    public val message: String?,
)

/** Stops on errors: none, every error, or every error except those inside the text of a `$eval`. */
public enum class ExceptionMode { NONE, ALL, ALL_EXCEPT_EVAL }

/**
 * Step granularity (CAP-10 p. 5): [OPERATOR] stops on path steps, function calls, blocks and lambda bodies (DAP
 * `statement`); [NODE] on every executable node that is not a literal (DAP `instruction`).
 */
public enum class Granularity { OPERATOR, NODE }

public enum class StopReason { ENTRY, BREAKPOINT, STEP, PAUSE, EXCEPTION }

/** The outcome of an evaluation or of a watch expression: a value (null: undefined) or an error. */
public class EvalOutcome(public val value: Value?, public val error: Throwable?) {
    override fun toString(): String = error?.let { "error: ${it.message}" } ?: (value?.let { JsonWriter.stringify(it) } ?: "undefined")
}

/** One frame of [StackTraceView]: [start]/[end] is where the frame is now (its current node or the call it makes). */
public class StackFrameView(
    public val id: Int,
    public val name: String,
    public val tailCall: Boolean,
    public val sourceId: String,
    public val start: Int,
    public val end: Int,
)

/** A page of the call stack and the size of the whole stack (DAP `stackTrace`: a recursion may be 100 000 deep). */
public class StackTraceView(public val frames: List<StackFrameView>, public val totalFrames: Int)

/** Session options: [stepIntoEval] lets steps, pause and breakpoints enter the text of a `$eval` (off by default). */
public class DebugOptions @JvmOverloads constructor(
    public val stepIntoEval: Boolean = false,
    public val stopOnEntry: Boolean = false,
)

/** Events of a session, called on the evaluating thread (it waits after [stopped] until a command comes). */
public interface DebugEvents {
    public fun stopped(reason: StopReason, at: NodeInfo) {}
    public fun output(text: String) {}
    public fun terminated(outcome: EvalOutcome) {}
}

/**
 * An interactive debugging session of one expression (CAP-10), shaped like DAP so that the language server can adapt
 * it (zone ide). [run] evaluates on the calling thread and blocks while the evaluation is stopped; the commands
 * ([resume], [stepIn], [stepOver], [stepOut], [pause], [terminate]) and the inspection come from other threads. One
 * session debugs one evaluation at a time; sessions do not share state.
 */
public class DebugSession @JvmOverloads constructor(
    private val expression: Jsonata,
    private val events: DebugEvents,
    private val options: DebugOptions = DebugOptions(),
) {
    private class Breakpoint(val id: Int, val spec: BreakpointSpec, val condition: Jsonata?, val logParts: List<Pair<Boolean, String>>?) {
        var hits = 0
    }

    private enum class Mode { RUN, STEP_IN, STEP_OVER, STEP_OUT }

    private val lock = ReentrantLock()
    private val commandArrived = lock.newCondition()
    private var command: (() -> Unit)? = null

    private val indexes = HashMap<String, SourceIndex>()
    private val breakpoints = HashMap<String, Map<Int, Breakpoint>>()
    private val pending = HashMap<String, List<BreakpointSpec>>()
    private val sourceList = ArrayList<SourceInfo>()
    private var nextBreakpointId = 1
    @Volatile private var exceptionMode = ExceptionMode.NONE

    @Volatile private var pauseRequested = false
    @Volatile private var mode = Mode.RUN
    @Volatile private var granularity = Granularity.OPERATOR
    private var targetLevel = 0
    private var targetFrame: DebugFrame? = null
    private var level = 0
    private var lastException: Throwable? = null
    private var entryPending = false
    private var token: CancellationToken? = null
    @Volatile private var terminating = false

    // the stop the session is in
    @Volatile private var stoppedNode: NodeInfo? = null
    @Volatile private var stoppedFrame: DebugFrame? = null
    @Volatile private var stoppedInput: Value? = null

    /** The value a lambda body returned when a step out stopped at its end. */
    @Volatile public var returnValue: Value? = null
        private set

    init {
        indexes["main"] = SourceIndex.of(expression)
        sourceList += SourceInfo("main", expression.expression, SourceInfo.Kind.MAIN)
    }

    /** Sets the breakpoints of a source; those of a `$eval` text not seen yet are bound when it appears. */
    public fun setBreakpoints(sourceId: String, points: List<BreakpointSpec>): List<ResolvedBreakpoint> = lock.withLock {
        val index = indexes[sourceId]
        if (index == null) {
            pending[sourceId] = points
            return points.map { ResolvedBreakpoint(nextBreakpointId++, false, null, null, "the source $sourceId is not known yet") }
        }
        bind(sourceId, index, points)
    }

    private fun bind(sourceId: String, index: SourceIndex, points: List<BreakpointSpec>): List<ResolvedBreakpoint> {
        val bound = HashMap<Int, Breakpoint>()
        val result = points.map { spec ->
            val id = nextBreakpointId++
            val node = index.resolveBreakpoint(spec.line, spec.column)
            if (node == null) {
                ResolvedBreakpoint(id, false, null, null, "no code at ${spec.line}${spec.column?.let { ":$it" } ?: ""}")
            } else {
                val condition = spec.condition?.let { Jsonata.compile(it) }
                bound[node.id] = Breakpoint(id, spec, condition, spec.logMessage?.let { logParts(it) })
                ResolvedBreakpoint(id, true, node, index.range(node), null)
            }
        }
        breakpoints[sourceId] = bound
        return result
    }

    /** `text {expr} text`: literal parts (false) and expressions (true). */
    private fun logParts(message: String): List<Pair<Boolean, String>> {
        val parts = ArrayList<Pair<Boolean, String>>()
        var i = 0
        while (i < message.length) {
            val open = message.indexOf('{', i)
            if (open < 0) {
                parts += false to message.substring(i)
                break
            }
            val close = message.indexOf('}', open)
            if (close < 0) {
                parts += false to message.substring(i)
                break
            }
            if (open > i) parts += false to message.substring(i, open)
            parts += true to message.substring(open + 1, close)
            i = close + 1
        }
        return parts
    }

    public fun setExceptionBreakpoints(mode: ExceptionMode) {
        exceptionMode = mode
    }

    /** The sources seen so far: the expression and the texts of `$eval`. */
    public fun sources(): List<SourceInfo> = lock.withLock { sourceList.toList() }

    /**
     * Evaluates the expression under the session; blocks while stopped. [evaluation] gives the limits; the session adds
     * its listener and a cancellation token for [terminate].
     */
    @JvmOverloads
    public fun run(input: Value?, bindings: Map<String, Value?> = emptyMap(), evaluation: EvaluationOptions = EvaluationOptions.DEFAULT): Value? {
        val t = CancellationToken()
        lock.withLock {
            token = t
            terminating = false
            mode = Mode.RUN
            level = 0
            entryPending = options.stopOnEntry
        }
        val outcome = try {
            val value = expression.evaluate(input, bindings, withSession(evaluation, t))
            EvalOutcome(value, null)
        } catch (e: Throwable) {
            EvalOutcome(null, e)
        } finally {
            stoppedNode = null
            stoppedFrame = null
        }
        events.terminated(outcome)
        outcome.error?.let { throw it }
        return outcome.value
    }

    private fun withSession(o: EvaluationOptions, t: CancellationToken) = EvaluationOptions(
        o.maxDepth, o.timeoutMillis, o.maxSequence, o.clock, o.functions, o.hostErrors, t, o.budgets, listener, o.deviations,
    )

    // ---- commands (from other threads)

    public fun resume(): Unit = send { mode = Mode.RUN }

    @JvmOverloads
    public fun stepIn(g: Granularity = Granularity.OPERATOR): Unit = send { mode = Mode.STEP_IN; granularity = g }

    @JvmOverloads
    public fun stepOver(g: Granularity = Granularity.OPERATOR): Unit = send {
        mode = Mode.STEP_OVER
        granularity = g
        targetLevel = level - 1 // the level of the node stopped at: the next node at this level or above
    }

    public fun stepOut(): Unit = send {
        val frame = stoppedFrame
        if (frame == null || frame.parent == null) {
            mode = Mode.RUN
        } else {
            mode = Mode.STEP_OUT
            targetFrame = frame
        }
    }

    /** Stops at the next executable node that is not a literal. */
    public fun pause() {
        pauseRequested = true
    }

    /** Ends the evaluation with X0009 (cancelled). */
    public fun terminate() {
        terminating = true
        token?.cancel()
        send { }
    }

    private fun send(action: () -> Unit) {
        lock.withLock {
            if (stoppedNode == null && !terminating) {
                if (action !== NOOP) action()
                return
            }
            command = action
            commandArrived.signalAll()
        }
    }

    /** True while the evaluation is stopped. */
    public val isStopped: Boolean get() = stoppedNode != null

    /** The call stack at the stop, [levels] frames from [startFrame] (0: all). */
    @JvmOverloads
    public fun stackTrace(startFrame: Int = 0, levels: Int = 0): StackTraceView {
        val top = stoppedFrame ?: return StackTraceView(emptyList(), 0)
        val node = stoppedNode!!
        val frames = ArrayList<StackFrameView>()
        var total = 0
        var f: DebugFrame? = top
        var location: NodeInfo? = node
        while (f != null) {
            if (total >= startFrame && (levels <= 0 || frames.size < levels)) {
                frames += StackFrameView(total, f.name, f.tailCall, location?.sourceId ?: "main", location?.start ?: -1, location?.end ?: -1)
            }
            total++
            location = f.callSite
            f = f.parent
        }
        return StackTraceView(frames, total)
    }

    /** The frame at [frameId] of the current stop (0: the innermost). */
    internal fun frameAt(frameId: Int): DebugFrame? = generateSequence(stoppedFrame) { it.parent }.drop(frameId).firstOrNull()

    internal fun stoppedInput(): Value? = stoppedInput

    // ---- the listener (on the evaluating thread)

    private val listener = object : EvaluationListener {
        override fun source(source: SourceInfo) {
            lock.withLock {
                if (sourceList.none { it.id == source.id }) sourceList += source
                if (source.kind == SourceInfo.Kind.EVAL && options.stepIntoEval) {
                    val parsed = JsonataParser.parseWithRanges(source.text)
                    val compiler = Compiler(true, parsed.ranges, source.id)
                    compiler.compile(parsed.ast)
                    val index = SourceIndex(source.id, source.text, compiler.infos.toList())
                    indexes[source.id] = index
                    pending.remove(source.id)?.let { bind(source.id, index, it) }
                }
            }
        }

        override fun enter(node: NodeInfo, input: Value?, frame: DebugFrame, iteration: Iteration?) {
            val atLevel = level
            level++
            if (terminating) throw JsonataException("X0009")
            val inEval = node.sourceId != "main"
            if (inEval && !options.stepIntoEval) return
            val stoppable = node.executable && !node.literal
            if (!stoppable) return
            val reason = when {
                entryPending -> StopReason.ENTRY.also { entryPending = false }
                hitsBreakpoint(node, input, frame) -> StopReason.BREAKPOINT
                pauseRequested -> StopReason.PAUSE.also { pauseRequested = false }
                mode == Mode.STEP_IN && matches(node) -> StopReason.STEP
                mode == Mode.STEP_OVER && atLevel <= targetLevel && matches(node) -> StopReason.STEP
                else -> null
            } ?: return
            stop(reason, node, frame, input)
        }

        override fun exit(node: NodeInfo, input: Value?, frame: DebugFrame, result: Value?) {
            level--
            if (mode == Mode.STEP_OUT && frame == targetFrame && isLambdaBody(node)) {
                returnValue = result
                stop(StopReason.STEP, node, frame, input)
            }
        }

        override fun error(node: NodeInfo, input: Value?, frame: DebugFrame, error: Throwable) {
            level--
            if (error === lastException || exceptionMode == ExceptionMode.NONE) return
            lastException = error
            if (exceptionMode == ExceptionMode.ALL_EXCEPT_EVAL && node.sourceId != "main") return
            if (error is JsonataException && error.code == "X0009" && terminating) return
            stop(StopReason.EXCEPTION, node, frame, input)
        }
    }

    private fun isLambdaBody(node: NodeInfo): Boolean {
        val parent = indexes[node.sourceId]?.nodes?.getOrNull(node.parentId) ?: return false
        return parent.type == "lambda"
    }

    private fun matches(node: NodeInfo): Boolean = granularity == Granularity.NODE || node.operatorStop

    private fun hitsBreakpoint(node: NodeInfo, input: Value?, frame: DebugFrame): Boolean {
        val bp = lock.withLock { breakpoints[node.sourceId]?.get(node.id) } ?: return false
        if (bp.condition != null) {
            val ok = try {
                val v = bp.condition.evaluate(input, scope(frame))
                v != null && su.seaf.archtool.jsonata.engine.Evaluator.boolize(v)
            } catch (e: JsonataException) {
                events.output("breakpoint condition failed: ${e.message}\n")
                true // an error in the condition stops, with the message
            }
            if (!ok) return false
        }
        bp.hits++
        bp.spec.hitCondition?.let { if (!hitMatches(it.trim(), bp.hits)) return false }
        bp.logParts?.let { parts ->
            val text = parts.joinToString("") { (isExpr, s) ->
                if (!isExpr) s else try {
                    Jsonata.compile(s).evaluate(input, scope(frame))?.let { v -> if (v is su.seaf.archtool.jsonata.value.StringValue) v.value else JsonWriter.stringify(v) } ?: "undefined"
                } catch (e: JsonataException) {
                    "<${e.code}>"
                }
            }
            events.output(text + "\n")
            return false
        }
        return true
    }

    private fun hitMatches(cond: String, hits: Int): Boolean = when {
        cond.startsWith(">=") -> hits >= cond.substring(2).trim().toInt()
        cond.startsWith("==") -> hits == cond.substring(2).trim().toInt()
        cond.startsWith("%") -> hits % cond.substring(1).trim().toInt() == 0
        else -> hits == cond.toInt()
    }

    /** Variables of a frame as bindings of a watch or condition expression (`$` is the input given separately). */
    internal fun scope(frame: DebugFrame): Map<String, Value?> {
        val m = LinkedHashMap<String, Value?>()
        for (b in frame.bindings().asReversed()) if (b.name != "$") m[b.name] = b.value
        return m
    }

    private fun stop(reason: StopReason, node: NodeInfo, frame: DebugFrame, input: Value?) {
        mode = Mode.RUN
        stoppedNode = node
        stoppedFrame = frame
        stoppedInput = input
        events.stopped(reason, node)
        val ctx = (frame as FrameView).frame.ctx
        val action = ctx.paused {
            lock.withLock {
                while (command == null) commandArrived.await()
                val a = command!!
                command = null
                a
            }
        }
        // the command reads the stop (step out needs its frame) before it is cleared
        action()
        stoppedNode = null
        stoppedFrame = null
        stoppedInput = null
        if (terminating) throw JsonataException("X0009")
    }

    private companion object {
        val NOOP: () -> Unit = { }
    }
}
