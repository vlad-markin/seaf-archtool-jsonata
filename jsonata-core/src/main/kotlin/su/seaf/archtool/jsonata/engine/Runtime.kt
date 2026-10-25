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
    @JvmField val hook: DebugHook?,
) {
    /** jsonata.js: environment.base.depth — the number of nested `evaluate` calls. */
    @JvmField var depth: Int = 0
    private val maxDepth = options.maxDepth
    private val deadline = if (options.timeoutMillis > 0) System.nanoTime() + options.timeoutMillis * 1_000_000 else 0L
    @JvmField val guarded: Boolean = options.maxDepth > 0 || options.timeoutMillis > 0

    /** True when the evaluation has a deadline: long native operations (regex matching) then check it too. */
    @JvmField val hasDeadline: Boolean = options.timeoutMillis > 0
    @JvmField val maxSequence: Int = options.maxSequence

    /**
     * The single checkpoint every evaluation step passes through (jsonata.js: evaluate → guardrails). Phase 7 adds
     * cancellation and budgets here; without limits it costs a counter increment and a field test.
     */
    fun enter() {
        depth++
        if (guarded) checkpoint()
    }

    private var ticks = 0

    /**
     * The checkpoint for loops inside native code (ranges, higher-order functions, `**`, `$clone`, `$string`,
     * sorting): a counter, with the full [checkpoint] every 1024 iterations (review RV-6).
     */
    fun tick() {
        if (guarded && (++ticks and 1023) == 0) checkpoint()
    }

    fun checkpoint() {
        if (maxDepth > 0 && depth > maxDepth) throw JsonataException("D1011", value = maxDepth.toDouble())
        if (deadline != 0L && System.nanoTime() - deadline > 0) throw JsonataException("D1012", value = options.timeoutMillis.toDouble())
    }

    /** jsonata.js: createSequence with the `sequence` limit of the options. */
    fun checkSequence(size: Int) {
        if (maxSequence > 0 && size > maxSequence) throw JsonataException("D2015", value = maxSequence.toDouble())
    }

    /** [depth] at which the current stack segment (thread) started. */
    @JvmField var segmentBase: Int = 0

    /** Nesting allowed on the current segment before the evaluation continues on a new one. */
    @JvmField var segmentLimit: Int = FIRST_SEGMENT_LIMIT

    /**
     * Segmented stack (review RV-4): jsonata-js 2.2.2 recurses as deep as its heap allows (100 000 non-tail levels
     * work), while one JVM thread stack holds a few hundred to a few thousand levels. When the nesting on the current
     * thread exceeds [segmentLimit], [block] continues on a new platform thread with a large stack and the current
     * thread waits for it. The evaluation stays sequential: one thread runs at a time and `join` orders memory.
     */
    fun <T> onNewSegment(block: () -> T): T {
        val savedBase = segmentBase
        val savedLimit = segmentLimit
        var result: Any? = null
        var failure: Throwable? = null
        val thread = Thread.ofPlatform().name("jsonata-stack-segment").daemon(true).stackSize(SEGMENT_STACK_BYTES).unstarted {
            segmentBase = depth
            segmentLimit = SEGMENT_LIMIT
            try {
                result = block()
            } catch (e: Throwable) {
                failure = e
            }
        }
        thread.start()
        var interrupted = false
        while (true) {
            try {
                thread.join()
                break
            } catch (_: InterruptedException) {
                // cancellation reaches the thread that does the work (phase 7 checks it at the checkpoint)
                interrupted = true
                thread.interrupt()
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
        segmentBase = savedBase
        segmentLimit = savedLimit
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    companion object {
        /** On the caller's thread, whose stack size is unknown (a virtual thread, a 512 KB worker): conservative. */
        const val FIRST_SEGMENT_LIMIT: Int = 120
        /** Segment threads: a 256 MB stack reservation (committed only as used) for 20 000 nested evaluations. */
        const val SEGMENT_LIMIT: Int = 20_000
        const val SEGMENT_STACK_BYTES: Long = 256L shl 20
    }
}

/**
 * Debugger entry points, called around every node when the expression is compiled with debugging enabled; when it is
 * not, no node calls them. The contract is internal until the debugger specification is settled.
 */
internal interface DebugHook {
    fun enter(node: Node, input: Value?, frame: Frame)
    fun exit(node: Node, input: Value?, frame: Frame, result: Value?)
    fun error(node: Node, input: Value?, frame: Frame, error: Throwable) {}
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
