package su.seaf.archtool.jsonata.debug

import su.seaf.archtool.jsonata.FunctionSource
import su.seaf.archtool.jsonata.value.Value

/**
 * Observes one evaluation node by node (CAP-10, CAP-11): the debugger session, the profiler and the value tracer are
 * listeners. Events come on the thread that evaluates the node, in the order of jsonata-js 2.2.2; a listener may block
 * (a debugger pause) and the evaluation waits. Pass it in [su.seaf.archtool.jsonata.EvaluationOptions.listener]: the
 * expression is then evaluated by its debugging tree (compiled once, on first use); without a listener the tree has no
 * listener calls at all.
 *
 * A listener must not change the outcome: its exceptions are not caught and end the evaluation.
 */
public interface EvaluationListener {
    /** Before a node is evaluated; [iteration] is set when a path step evaluates item i of n of its input. */
    public fun enter(node: NodeInfo, input: Value?, frame: DebugFrame, iteration: Iteration?) {}

    /** After a node was evaluated (undefined result: null). */
    public fun exit(node: NodeInfo, input: Value?, frame: DebugFrame, result: Value?) {}

    /** A node failed; called for every node the error passes through, innermost first. */
    public fun error(node: NodeInfo, input: Value?, frame: DebugFrame, error: Throwable) {}

    /** A native function (standard library, core, host) is called with checked [args]. */
    public fun nativeEnter(name: String, source: FunctionSource, args: List<Value?>, frame: DebugFrame) {}

    /** The native function returned. */
    public fun nativeExit(name: String, result: Value?, frame: DebugFrame) {}

    /**
     * The native function failed with [error]: every [nativeEnter] ends with [nativeExit] or this, also when the
     * error is caught further up (a host function with a callback, `$tryexecute`).
     */
    public fun nativeError(name: String, error: Throwable, frame: DebugFrame) {}

    /** A new source text takes part in the evaluation: the text of a `$eval`, the code of a manifest function. */
    public fun source(source: SourceInfo) {}
}

/**
 * A node of a compiled expression: [id] unique in its source, [type] the AST type (`path`, `function`, `binary`…),
 * range `[start, end)` in UTF-16 units of the source [sourceId], [parentId] (-1 for the root).
 *
 * @property executable the evaluator enters it (stages of a step, lambda parameters and tail-call thunks are not)
 * @property literal a literal value: no stop there
 * @property operatorStop a stop of the default step granularity: path steps, function calls, blocks, lambda bodies
 */
public class NodeInfo(
    public val id: Int,
    public val type: String,
    public val value: String?,
    public val sourceId: String,
    public val start: Int,
    public val end: Int,
    public val executable: Boolean,
    public val literal: Boolean,
    operatorStop: Boolean,
    public val parentId: Int,
) {
    public var operatorStop: Boolean = operatorStop
        internal set

    override fun toString(): String = "#$id $type${value?.let { " $it" } ?: ""} [$start,$end) in $sourceId"
}

/** A path step evaluating item [index] (from 0) of its [count] input items. */
public class Iteration(public val index: Int, public val count: Int) {
    override fun toString(): String = "${index + 1} of $count"
}

/** A variable visible in a frame (`name` without `$`); [value] null is undefined. */
public class Binding(public val name: String, public val value: Value?)

/**
 * A frame of the JSONata call stack: the main expression, or a lambda invocation (named after the call, `λ` for an
 * anonymous one, `λ via $map` when a higher-order function calls it). [tailCall] marks a frame that replaced its caller
 * through a tail call.
 */
public interface DebugFrame {
    public val name: String
    public val callSite: NodeInfo?
    public val tailCall: Boolean

    /** The caller's frame, null for the main expression. */
    public val parent: DebugFrame?

    /** `$$`: the input of the evaluation. */
    public fun root(): Value?

    /** Variables of the frame and of the scopes it closes over, innermost first, then the bindings of the evaluation. */
    public fun bindings(): List<Binding>
}

/** A source text of the evaluation: the main expression (`main`), a `$eval` text (`eval:N`), a manifest function. */
public class SourceInfo(public val id: String, public val text: String, public val kind: Kind) {
    public enum class Kind { MAIN, EVAL, MANIFEST_FUNCTION }

    override fun toString(): String = "$id ($kind)"
}
