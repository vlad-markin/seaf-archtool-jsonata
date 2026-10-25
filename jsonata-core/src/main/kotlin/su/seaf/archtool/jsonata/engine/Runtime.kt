package su.seaf.archtool.jsonata.engine

import su.seaf.archtool.jsonata.EvaluationOptions
import su.seaf.archtool.jsonata.FunctionRegistry
import su.seaf.archtool.jsonata.parser.JsonataException
import su.seaf.archtool.jsonata.value.FunctionValue
import su.seaf.archtool.jsonata.value.Value

/**
 * A scope of variable bindings (jsonata.js: createFrame). A name bound to undefined shadows outer bindings, as
 * `hasOwnProperty` does in the reference. The outermost frame falls back to the function registry (the reference's
 * static frame with the standard library).
 *
 * [callSite] and [caller] are set on frames of lambda invocations: together with [parent] they give the JSONata call
 * stack (for error reports and the future debugger) without any cost on the evaluation path.
 */
internal class Frame(
    @JvmField val parent: Frame?,
    @JvmField val ctx: EvalContext,
) {
    private var names: Array<String?>? = null
    private var values: Array<Value?>? = null
    private var size = 0

    /** The node that invoked the lambda this frame belongs to (null for blocks and tuple frames). */
    @JvmField var callSite: Node? = null
    /** The frame active at the call site. */
    @JvmField var caller: Frame? = null

    /** Name of the lambda invocation this frame is for (`f`, `λ`, `λ via $map`); null for blocks and the root. */
    @JvmField var lambdaName: String? = null

    /** The invocation came from the tail-call trampoline. */
    @JvmField var tailCall: Boolean = false

    fun bind(name: String, value: Value?) {
        var ns = names
        if (ns == null) {
            ns = arrayOfNulls(4)
            names = ns
            values = arrayOfNulls(4)
        }
        for (i in 0 until size) {
            if (ns[i] == name) {
                values!![i] = value
                return
            }
        }
        if (size == ns.size) {
            ns = ns.copyOf(size * 2)
            names = ns
            values = values!!.copyOf(size * 2)
        }
        ns[size] = name
        values!![size] = value
        size++
    }

    fun lookup(name: String): Value? {
        var f: Frame? = this
        while (f != null) {
            val ns = f.names
            if (ns != null) {
                for (i in 0 until f.size) if (ns[i] == name) return f.values!![i]
            }
            f = f.parent
        }
        return ctx.registry.lookup(name)
    }

    /** Variables bound in this frame only, in binding order (for debugging and tests). */
    fun localBindings(): List<Pair<String, Value?>> = List(size) { names!![it]!! to values!![it] }
}

/**
 * State of one evaluation: options, the timestamp seen by `$now()`/`$millis()`, depth, registry. Created per
 * `evaluate` call, never shared between threads.
 */
internal class EvalContext(
    @JvmField val registry: FunctionRegistry,
    @JvmField val options: EvaluationOptions,
    @JvmField val timestamp: Long,
) {
    /** The listener of the evaluation (debugger, profiler, tracer); the debugging tree calls it at every node. */
    @JvmField val listener: su.seaf.archtool.jsonata.debug.EvaluationListener? = options.listener

    /** Item i of n that the next node entered (a path step) evaluates; set only with a listener. */
    @JvmField var iteration: su.seaf.archtool.jsonata.debug.Iteration? = null

    /** Names of the native functions being called, innermost last (a lambda they call is "λ via $name"). */
    @JvmField var nativeCalls: ArrayList<String>? = null

    /** The next lambda invocation comes from the tail-call trampoline (its frame replaces the caller's). */
    @JvmField var tailCallNext: Boolean = false

    /** `$$` of the evaluation. */
    @JvmField var rootInput: Value? = null

    /** Ids of the `$eval` sources reported to the listener. */
    @JvmField var evalSources: Int = 0

    /** jsonata.js: environment.base.depth — the number of nested `evaluate` calls. */
    @JvmField var depth: Int = 0
    private val maxDepth = options.maxDepth
    private val deadline = if (options.timeoutMillis > 0) System.nanoTime() + options.timeoutMillis * 1_000_000 else 0L

    /** Time the evaluation spent paused by a debugger: it does not count against the deadline (story 4 step 4.5). */
    @Volatile private var pausedNanos = 0L

    private val token = options.cancellation

    private val maxSteps = options.budgets.maxSteps
    private val maxStringLength = options.budgets.maxStringLength
    private val maxResultSize = options.budgets.maxResultSize

    private fun budget(name: String, limit: Long): JsonataException =
        JsonataException("X0010", value = limit.toDouble(), details = mapOf("budget" to name))

    /** The string budget, before building a string of [length] units (or while building it). */
    fun checkString(length: Long) {
        if (maxStringLength > 0 && length > maxStringLength) throw budget("string", maxStringLength.toLong())
    }

    /** The result budget over the final value: one per value, plus key and string lengths. */
    fun checkResult(result: Value?) {
        if (maxResultSize <= 0 || result == null) return
        var size = 0L
        val stack = ArrayList<Value>()
        stack.add(result)
        while (stack.isNotEmpty()) {
            tick()
            val v = stack.removeAt(stack.size - 1)
            size++
            when (v) {
                is su.seaf.archtool.jsonata.value.StringValue -> size += v.value.length
                is su.seaf.archtool.jsonata.value.ArrayValue -> for (i in 0 until v.size) v.raw(i)?.let { stack.add(it) }
                is su.seaf.archtool.jsonata.value.ObjectValue -> v.forEach { k, x -> size += 1 + k.length; stack.add(x) }
                else -> {}
            }
            if (size > maxResultSize) throw budget("result", maxResultSize)
        }
    }

    /** True when the evaluation has a deadline: long native operations (regex matching) then check it too. */
    @JvmField val hasDeadline: Boolean = options.timeoutMillis > 0
    @JvmField val maxSequence: Int = options.maxSequence

    private val deviationSink = options.deviations

    private val hostErrors = options.hostErrors

    /** Exceptions thrown by host functions in this evaluation: they leave it unchanged (CAP-9 p. 4). */
    private var hostExceptions: MutableSet<Throwable>? = null

    /** Host exceptions whose place was already reported. */
    private var locatedExceptions: MutableSet<Throwable>? = null

    fun markHost(e: Throwable) {
        (hostExceptions ?: java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>()).also { hostExceptions = it }).add(e)
    }

    fun isHost(e: Throwable): Boolean = hostExceptions?.contains(e) == true

    /**
     * Reports the place of a host exception at the innermost node it passes through that has a source range (the
     * nodes of a `$eval` text have none: the `$eval` call is reported). An exception of the listener is ignored.
     */
    fun locate(e: Throwable, node: Node) {
        val listener = hostErrors ?: return
        if (node.start < 0 || !isHost(e)) return
        val located = locatedExceptions ?: java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>()).also { locatedExceptions = it }
        if (!located.add(e)) return
        try {
            listener.located(e, su.seaf.archtool.jsonata.ErrorLocation(node.start, node.end, node.position))
        } catch (_: RuntimeException) {
            // the listener must not change the outcome of the evaluation
        }
    }

    /** Probe of a deliberate difference from jsonata-js 2.2.2 (`docs/deviations.md`): the result here may differ. */
    fun deviation(id: String, position: Int?) {
        deviationSink?.hit(id, position)
    }

    /**
     * The single checkpoint every evaluation step passes through (jsonata.js: evaluate → guardrails): the depth limit
     * (exact, as the `stack` option of jsonata-js) and a step counter; every [CHECK_INTERVAL] steps [slowPath] checks
     * cancellation and the deadline. One increment and two compares per node.
     */
    fun enter() {
        depth++
        if (maxDepth > 0 && depth > maxDepth) throw JsonataException("D1011", value = maxDepth.toDouble())
        if (++steps >= nextCheck) slowPath()
    }

    /** Evaluation steps so far (nodes entered). */
    @JvmField var steps: Long = 0
    private var nextCheck: Long = CHECK_INTERVAL

    private fun slowPath() {
        if (maxSteps > 0) {
            if (steps > maxSteps) throw budget("steps", maxSteps)
            nextCheck = minOf(steps + CHECK_INTERVAL, maxSteps + 1)
        } else {
            nextCheck = steps + CHECK_INTERVAL
        }
        checkpoint()
    }

    private var ticks = 0

    /**
     * The last call site of each lambda in this evaluation. jsonata-js records token and position on the lambda object
     * at every call (evaluateFunction, the trampoline) and errors raised inside take them over, so the latest call
     * wins. Kept per evaluation, not on the lambda, so that one lambda can run in several evaluations at once.
     */
    private var lastCalls: java.util.IdentityHashMap<Lambda, CallSite>? = null

    fun recordCall(lambda: Lambda, site: CallSite) {
        (lastCalls ?: java.util.IdentityHashMap<Lambda, CallSite>().also { lastCalls = it })[lambda] = site
    }

    fun lastCall(lambda: Lambda): CallSite? = lastCalls?.get(lambda)

    /**
     * The checkpoint for loops inside native code (ranges, higher-order functions, `**`, `$clone`, `$string`,
     * sorting, regex matching): a counter, with the full [checkpoint] every 1024 iterations (review RV-6).
     */
    fun tick() {
        if ((++ticks and 1023) == 0) checkpoint()
    }

    /** Cancellation (token or interrupt of the current thread) and the deadline. */
    fun checkpoint() {
        if (token != null && token.isCancelled) throw cancelled(null)
        // an interrupt the host set itself: X0009, the flag stays with the host
        if (Thread.currentThread().isInterrupted) throw cancelled(null)
        if (deadline != 0L && System.nanoTime() - pausedNanos - deadline > 0) throw JsonataException("D1012", value = options.timeoutMillis.toDouble())
    }

    // ---- cancellation (design §2.4): RUNNING -> IN_HOST -> RUNNING; IN_HOST -> INTERRUPTING -> INTERRUPTED; DONE

    private val state = java.util.concurrent.atomic.AtomicInteger(RUNNING)
    @Volatile private var hostThread: Thread? = null

    val isCancelled: Boolean get() = (token != null && token.isCancelled) || Thread.currentThread().isInterrupted

    fun cancelled(cause: Throwable?): JsonataException = JsonataException("X0009", cause = cause)

    fun isCancelledByToken(): Boolean = token != null && token.isCancelled

    /** Remaining time before the deadline in nanoseconds, [Long.MAX_VALUE] without one. */
    fun remainingNanos(): Long = if (deadline == 0L) Long.MAX_VALUE else maxOf(0L, deadline + pausedNanos - System.nanoTime())

    /** Before a host function runs: a cancellation now interrupts this thread. */
    fun enterHost() {
        hostThread = Thread.currentThread()
        if (!state.compareAndSet(RUNNING, IN_HOST)) return
        if (token != null && token.isCancelled) cancelFromToken()
    }

    /**
     * After a host function returned or threw: back to RUNNING, or, when a cancellation interrupted the call, wait
     * until the interrupt is delivered, clear this thread's flag and fail with X0009.
     */
    fun exitHost() {
        if (state.compareAndSet(IN_HOST, RUNNING)) return
        if (state.get() == RUNNING || state.get() == DONE) return
        while (state.get() == INTERRUPTING) Thread.onSpinWait()
        Thread.interrupted()
        state.compareAndSet(INTERRUPTED, RUNNING)
        throw cancelled(null)
    }

    /**
     * After a host function failed with InterruptedException: back to RUNNING; true when the interrupt came from the
     * token (then this thread's flag is cleared), false when the host interrupted the thread itself.
     */
    fun exitHostInterrupted(): Boolean {
        if (state.compareAndSet(IN_HOST, RUNNING)) return isCancelledByToken()
        while (state.get() == INTERRUPTING) Thread.onSpinWait()
        Thread.interrupted()
        state.compareAndSet(INTERRUPTED, RUNNING)
        return true
    }

    /** Called by the token: interrupts the thread of a running host call; JSONata code sees the token itself. */
    fun cancelFromToken() {
        if (!state.compareAndSet(IN_HOST, INTERRUPTING)) return
        hostThread?.interrupt()
        state.set(INTERRUPTED)
    }

    /** Registers with the token at the start of the evaluation. */
    fun start() {
        if (token == null) return
        token.register(this)
        if (token.isCancelled) {
            token.unregister(this)
            throw cancelled(null)
        }
    }

    /** At the end: later cancellations do nothing (no interrupt flag is left on any thread). */
    fun finish() {
        state.set(DONE)
        token?.unregister(this)
    }

    /** Runs [block] (a debugger pause) without counting its time against the deadline. */
    inline fun <T> paused(block: () -> T): T {
        val t0 = System.nanoTime()
        try {
            return block()
        } finally {
            addPaused(System.nanoTime() - t0)
        }
    }

    fun addPaused(nanos: Long) {
        pausedNanos += nanos
    }

    /** jsonata.js: createSequence with the `sequence` limit of the options. */
    fun checkSequence(size: Int) {
        if (maxSequence > 0 && size > maxSequence) throw JsonataException("D2015", value = maxSequence.toDouble())
    }

    /** [depth] at which the current stack segment (thread) started. */
    @JvmField var segmentBase: Int = 0

    /** Nesting allowed on the current segment before the evaluation continues on a new one. */
    @JvmField var segmentLimit: Int = if (SegmentPool.onSegmentThread()) SEGMENT_LIMIT else FIRST_SEGMENT_LIMIT

    /** The segment task this evaluation is running in now (null on the caller's thread). */
    private var currentTask: SegmentPool.Task? = null

    /** Segments this evaluation has entered (for the profile, story 5). */
    @JvmField var segmentsEntered: Int = 0

    /** Called when this evaluation enters its first segment (the compiled expression remembers it goes deep). */
    @JvmField var onDeep: (() -> Unit)? = null

    /**
     * Segmented stack (review RV-4, condition U1): jsonata-js 2.2.2 recurses as deep as its heap allows (100 000
     * non-tail levels work), while one JVM thread stack holds a few hundred to a few thousand levels. When the nesting
     * on the current thread exceeds [segmentLimit], [block] continues on a pooled platform thread with a large stack
     * ([SegmentPool]) and the current thread parks until it is done. The evaluation stays sequential: one thread runs
     * at a time, and the volatile completion flag of the task orders memory between them.
     */
    fun <T> onNewSegment(block: () -> T): T = onSegment(depth, block)

    /** Runs the whole evaluation on a segment thread from the start. */
    fun <T> fromSegment(block: () -> T): T = onSegment(depth, block)

    private fun <T> onSegment(base: Int, block: () -> T): T {
        val savedBase = segmentBase
        val savedLimit = segmentLimit
        val savedTask = currentTask
        savedTask?.wentDeep = true
        if (segmentsEntered++ == 0) onDeep?.invoke()
        val task = SegmentPool.run { t ->
            segmentBase = base
            segmentLimit = SEGMENT_LIMIT
            currentTask = t
            block()
        }
        segmentBase = savedBase
        segmentLimit = savedLimit
        currentTask = savedTask
        task.failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return task.result as T
    }

    companion object {
        /** Evaluation steps between two checks of cancellation and deadline (tens of microseconds). */
        const val CHECK_INTERVAL: Long = 1024

        private const val RUNNING = 0
        private const val IN_HOST = 1
        private const val INTERRUPTING = 2
        private const val INTERRUPTED = 3
        private const val DONE = 4

        /** On the caller's thread, whose stack size is unknown (a virtual thread, a 512 KB worker): conservative. */
        const val FIRST_SEGMENT_LIMIT: Int = 120
        /** Segment threads: a 256 MB stack reservation (committed only as used) for 20 000 nested evaluations. */
        const val SEGMENT_LIMIT: Int = 20_000
    }
}

/**
 * The function frame of [frame] as the debugger sees it: the innermost lambda invocation that [frame] belongs to (a
 * block or tuple frame belongs to the invocation around it), or the main expression.
 */
internal class FrameView private constructor(@JvmField val frame: Frame) : su.seaf.archtool.jsonata.debug.DebugFrame {
    override val name: String get() = frame.lambdaName ?: "<main>"
    override val callSite: su.seaf.archtool.jsonata.debug.NodeInfo? get() = frame.callSite?.info
    override val tailCall: Boolean get() = frame.tailCall
    override val parent: su.seaf.archtool.jsonata.debug.DebugFrame? get() = frame.caller?.let { of(it) }

    override fun root(): Value? = frame.ctx.rootInput

    override fun bindings(): List<su.seaf.archtool.jsonata.debug.Binding> {
        val seen = HashSet<String>()
        val out = ArrayList<su.seaf.archtool.jsonata.debug.Binding>()
        var f: Frame? = scope
        while (f != null) {
            for ((n, v) in f.localBindings()) if (seen.add(n)) out += su.seaf.archtool.jsonata.debug.Binding(n, v)
            f = f.parent
        }
        return out
    }

    /** The innermost frame of the event (its bindings are the visible variables). */
    private var scope: Frame = frame

    override fun equals(other: Any?): Boolean = other is FrameView && other.frame === frame
    override fun hashCode(): Int = System.identityHashCode(frame)
    override fun toString(): String = name

    companion object {
        fun of(env: Frame): FrameView {
            var f: Frame = env
            while (f.lambdaName == null && f.parent != null) f = f.parent!!
            return FrameView(f).also { it.scope = env }
        }
    }
}

/** Where a function is invoked from: the name used at the call and the position (jsonata.js: proc.token/position). */
internal class CallSite(@JvmField val token: String?, @JvmField val position: Int?)

/** JS truthiness of a looked-up value (used by the T1005/T1007 hints). */
internal fun jsTruthy(v: Value?): Boolean = when (v) {
    null, su.seaf.archtool.jsonata.value.NullValue -> false
    is su.seaf.archtool.jsonata.value.BooleanValue -> v.value
    is su.seaf.archtool.jsonata.value.NumberValue -> v.value != 0.0 && !v.value.isNaN()
    is su.seaf.archtool.jsonata.value.StringValue -> v.value.isNotEmpty()
    is FunctionValue -> true
    else -> true
}
