package su.seaf.archtool.jsonata

import su.seaf.archtool.jsonata.engine.Compiler
import su.seaf.archtool.jsonata.engine.Evaluator
import su.seaf.archtool.jsonata.engine.NativeFunction
import su.seaf.archtool.jsonata.engine.Node
import su.seaf.archtool.jsonata.engine.StandardLibrary
import su.seaf.archtool.jsonata.parser.AstNode
import su.seaf.archtool.jsonata.parser.JsonataParser
import su.seaf.archtool.jsonata.parser.JsonataException
import su.seaf.archtool.jsonata.parser.Signature
import su.seaf.archtool.jsonata.value.StringValue
import su.seaf.archtool.jsonata.value.FunctionValue
import su.seaf.archtool.jsonata.value.Value

/**
 * A compiled JSONata expression (jsonata-js 2.2.2 dialect). Immutable and thread-safe: every [evaluate] call has its
 * own state, and the input is never modified, so one parsed document can be shared by many evaluations.
 * The API is unstable until `jsonata-host`.
 */
public class Jsonata private constructor(
    /** The source text. */
    public val expression: String,
    private val ast: AstNode,
    private val root: Node,
    internal val options: CompileOptions,
    recursive: Boolean,
    /** Source ranges of the AST nodes (errors, the debugger). */
    internal val ranges: su.seaf.archtool.jsonata.parser.SourceRanges,
) {
    /**
     * Set when the expression binds a recursive lambda, or once an evaluation of it has needed a stack segment:
     * evaluations then start on a segment thread, so that a recursion just deeper than the caller's first segment pays one hand-over per evaluation
     * instead of one per crossing (condition U1).
     */
    @Volatile private var deep = recursive

    private val markDeep: () -> Unit = { deep = true }

    /** jsonata-js `ast()`. */
    public fun ast(): AstNode = ast

    /**
     * Evaluates against [input] (undefined when null) with variable [bindings] (`$name`, without the `$`).
     * Throws [su.seaf.archtool.jsonata.parser.JsonataException] with the reference error code.
     */
    @JvmOverloads
    public fun evaluate(
        input: Value?,
        bindings: Map<String, Value?> = emptyMap(),
        evaluation: EvaluationOptions = EvaluationOptions.DEFAULT,
    ): Value? = if (evaluation.listener == null) {
        Evaluator.run(root, input, bindings, options, evaluation, deep, markDeep)
    } else {
        Evaluator.run(debugRoot(), input, bindings, options, evaluation, deep, markDeep, mainSource)
    }

    /** The debugging tree: the same expression with listener calls at every node, compiled once (CAS, no monitor). */
    private val debugTree = java.util.concurrent.atomic.AtomicReference<DebugTree?>(null)

    internal class DebugTree(val root: Node, val infos: List<su.seaf.archtool.jsonata.debug.NodeInfo>)

    internal fun debugTree(): DebugTree {
        debugTree.get()?.let { return it }
        val compiler = Compiler(true, ranges)
        val tree = compiler.compile(ast)
        debugTree.compareAndSet(null, DebugTree(tree, compiler.infos.toList()))
        return debugTree.get()!!
    }

    internal fun debugRoot(): Node = if (options.debug) root else debugTree().root

    private val mainSource = su.seaf.archtool.jsonata.debug.SourceInfo("main", expression, su.seaf.archtool.jsonata.debug.SourceInfo.Kind.MAIN)

    internal val compiledRoot: Node get() = root

    public companion object {
        /** Parses and compiles; throws [su.seaf.archtool.jsonata.parser.JsonataException] on a syntax error. */
        @JvmStatic
        @JvmOverloads
        public fun compile(expression: String, options: CompileOptions = CompileOptions.DEFAULT): Jsonata {
            val parsed = JsonataParser.parseWithRanges(expression)
            val compiler = Compiler(options.debug, parsed.ranges)
            val root = compiler.compile(parsed.ast)
            return Jsonata(expression, parsed.ast, root, options, compiler.recursive, parsed.ranges)
        }
    }
}

/**
 * Process-wide stack segments of deep evaluations (review RV-4, condition U1). A recursion deeper than the caller's
 * thread can hold continues on pooled platform threads with a 256 MB stack reservation each; every segment holds up to
 * 20 000 nested evaluation steps (about 4 000 levels of non-tail recursion) and commits the stack memory it touches,
 * outside the Java heap: about 0.9 GB for 100 000 levels. See `docs/limits.md`.
 */
public object StackSegments {
    /** Default [budget]: enough for one evaluation to reach [EvaluationOptions.DEFAULT_MAX_DEPTH] (D1011 first). */
    public const val DEFAULT_BUDGET: Int = 64

    /**
     * The most segments running at once in the process, over all evaluations; one more fails the evaluation that
     * needs it with X0005. A host setting: lower it to bound the memory outside the heap (an IDE, a small pod).
     */
    @Volatile
    @JvmStatic
    public var budget: Int = DEFAULT_BUDGET
        set(value) {
            require(value >= 0) { "budget must not be negative" }
            field = value
        }

    /** Segments running now. */
    @JvmStatic
    public val active: Int get() = su.seaf.archtool.jsonata.engine.SegmentPool.activeSegments

    /** Segment threads alive now (running or idle in the pool). */
    @JvmStatic
    public val threads: Int get() = su.seaf.archtool.jsonata.engine.SegmentPool.liveThreads

    /** Segment threads started since the process began (reuse keeps it far below [entered]). */
    @JvmStatic
    public val threadsCreated: Long get() = su.seaf.archtool.jsonata.engine.SegmentPool.createdThreads

    /** Segments entered since the process began. */
    @JvmStatic
    public val entered: Long get() = su.seaf.archtool.jsonata.engine.SegmentPool.segmentsRun
}

/** Compile-time options. */
public class CompileOptions @JvmOverloads constructor(
    /** Functions visible to the expression: the standard library plus the caller's own. */
    public val functions: FunctionRegistry = FunctionRegistry.standard(),
    /**
     * Compile with debugger entry points around every node. Off by default: then the compiled tree contains no hook
     * calls at all.
     */
    public val debug: Boolean = false,
) {
    public companion object {
        @JvmField public val DEFAULT: CompileOptions = CompileOptions()
    }
}

/**
 * Limits of one evaluation, as the jsonata-js 2.2.2 options: [maxDepth] (`stack`, error D1011) counts nested
 * evaluation steps, [timeoutMillis] (`timeout`, D1012), [maxSequence] (`sequence`, D2015). Zero means no limit.
 *
 * The defaults are temporary conservative values (owner's decision on review RV-4; exact values after measurements on
 * real architecture lakes): jsonata-js has no limits by default, but without one a runaway recursion would consume
 * the memory of the process. [DEFAULT_MAX_DEPTH] allows about 200 000 levels of non-tail recursion (jsonata-js reaches
 * 100 000), [DEFAULT_MAX_SEQUENCE] equals the size limit jsonata-js puts on ranges (D2014).
 */
public class EvaluationOptions @JvmOverloads constructor(
    public val maxDepth: Int = DEFAULT_MAX_DEPTH,
    public val timeoutMillis: Long = 0,
    public val maxSequence: Int = DEFAULT_MAX_SEQUENCE,
    /** Clock for `$now()`/`$millis()` (milliseconds since the epoch), read once per evaluation. */
    public val clock: () -> Long = { System.currentTimeMillis() },
    /**
     * Functions for this evaluation only (CAP-4 p. 5): a descendant of the registry the expression was compiled with
     * (`compileRegistry.extend().register(...).freeze()`), else `evaluate` fails with IllegalArgumentException.
     * Null: the compile registry.
     */
    public val functions: FunctionRegistry? = null,
    /**
     * Told where in the expression an exception of a host function happened (CAP-9 p. 4): the exception itself
     * reaches the caller unchanged, so the place comes this way, once per exception object, from the innermost node.
     */
    public val hostErrors: HostErrorListener? = null,
    /** Cancels the evaluation from another thread (X0009); `Thread.interrupt()` of the evaluating thread does too. */
    public val cancellation: CancellationToken? = null,
    /** Budgets of steps, built strings and the result size (X0010); [Budgets.NONE] by default. */
    public val budgets: Budgets = Budgets.NONE,
    /**
     * Observes the evaluation node by node (the debugger, the profiler, the value tracer): the expression is then
     * evaluated by its debugging tree. Null: no listener calls at all.
     */
    public val listener: su.seaf.archtool.jsonata.debug.EvaluationListener? = null,
    /**
     * Receives probe hits of deliberate differences from jsonata-js 2.2.2 (`docs/deviations.md`): the evaluation went
     * through a branch where the result may differ from the reference. Null: probes cost one null check.
     */
    public val deviations: su.seaf.archtool.jsonata.deviation.DeviationSink? = null,
    /** Values the host attaches to the evaluation for its own functions ([CallContext.attribute]). */
    public val attributes: Map<String, Any?> = emptyMap(),
) {
    public companion object {
        public const val DEFAULT_MAX_DEPTH: Int = 1_000_000
        public const val DEFAULT_MAX_SEQUENCE: Int = 10_000_000

        @JvmField public val DEFAULT: EvaluationOptions = EvaluationOptions()
    }

    /** A debugger watch expression: functions with side effects fail with X0011. */
    internal var watch: Boolean = false
}

/** Where an exception of a host function happened: the node's range `[start, end)` and its jsonata-js position. */
public class ErrorLocation(public val start: Int, public val end: Int, public val position: Int?) {
    override fun toString(): String = "$start..$end@${position ?: "?"}"
}

/** Receives the place of host exceptions; see [EvaluationOptions.hostErrors]. Should not throw: its exceptions are ignored. */
public fun interface HostErrorListener {
    public fun located(error: Throwable, location: ErrorLocation)
}

/** A function implemented in Kotlin. Arguments are already checked and coerced by the signature, if any. */
public fun interface NativeImplementation {
    public fun invoke(context: CallContext, args: List<Value?>): Value?
}

/** What a native function sees of the evaluation (jsonata.js: the `this` of a native function). */
public interface CallContext {
    /** The context value at the call (`$`), undefined when called from a higher-order function. */
    public val input: Value?

    /** Evaluation timestamp (milliseconds since the epoch), the same for the whole evaluation. */
    public val timestamp: Long

    /** `$$`: the input of the evaluation (of the nested [evaluate] when called inside one). */
    public val root: Value?

    /** Invokes a function value (a lambda, a native, a partial application) synchronously. */
    public fun call(function: FunctionValue, args: List<Value?>): Value?

    /** Cancellation of the evaluation: a long host function checks it; a blocking one is interrupted instead. */
    public val cancellation: CancellationSignal

    /** Nanoseconds left before the deadline of the evaluation (timeouts of host calls), [Long.MAX_VALUE] without one. */
    public fun remainingNanos(): Long

    /**
     * State kept for the whole evaluation under [key] (created by [init] on first use): the store of `$set`/`$get`,
     * caches of a function. Nested [evaluate] calls share it; another evaluation has its own.
     */
    public fun <T : Any> local(key: Any, init: () -> T): T

    /** A value the host attached to the evaluation ([EvaluationOptions.attributes]), e.g. the request of a user. */
    public fun attribute(name: String): Any?

    /**
     * Evaluates [expression] (compiled with a registry of this evaluation's chain) inside this evaluation: depth,
     * deadline, budgets, cancellation, listener and [local] state are shared; the variables are only [bindings] plus
     * `$`/`$$` = [input]. The debugger shows it as a frame named [frameName] (a manifest function: its id).
     */
    public fun evaluate(expression: Jsonata, input: Value?, bindings: Map<String, Value?>, frameName: String): Value?
}

/**
 * The functions an expression can call, by name without `$` (CAP-4, CAP-7). Registries form a chain: [standard] holds
 * the jsonata-js 2.2.2 library, [extend] creates a child for the caller's functions (decision 14: the main way the
 * language grows). There is no silent replacement (decision 18):
 * - a name must be callable from an expression (`$` + name is one variable token), else X0006;
 * - a standard library name needs [FunctionDefinition.overridesStandard], else X0007;
 * - a name already registered in the registry or in a parent (other than a standard name overridden as above) fails
 *   with X0001.
 *
 * A local variable shadows a function of the same name. [freeze] makes an immutable snapshot, which is what a service
 * or a shared environment should hand to evaluations; a registry that is not frozen may be configured before compiling
 * expressions and is read concurrently afterwards. [EvaluationOptions.functions] adds functions for one evaluation: it
 * must be a descendant of the registry the expression was compiled with.
 */
public class FunctionRegistry private constructor(
    private val parent: FunctionRegistry?,
    snapshot: Map<String, FunctionValue>?,
) {
    /** Own functions: a concurrent map while open, an immutable copy in a frozen snapshot. */
    private val functions: MutableMap<String, FunctionValue>? = if (snapshot == null) java.util.concurrent.ConcurrentHashMap() else null
    private val frozenFunctions: Map<String, FunctionValue>? = snapshot

    /** Set on the shared standard registry once the library is registered: it can never change afterwards. */
    @Volatile private var standardFrozen = false

    /** True for a [freeze] snapshot and for [standard]. */
    public val isFrozen: Boolean get() = frozenFunctions != null || standardFrozen

    private fun own(): Map<String, FunctionValue> = frozenFunctions ?: functions!!

    /** Creates a registry whose own functions are looked up before this one's. */
    public fun extend(): FunctionRegistry = FunctionRegistry(this, null)

    /**
     * Registers a native function. [signature] uses the JSONata syntax (`<s-n?:s>`), or null for no checks. [arity]
     * is what higher-order functions see (how many of value/index/array they pass); by default the number of
     * parameters in the signature. Without documentation: prefer [register] with a [FunctionDefinition].
     */
    @JvmOverloads
    public fun register(name: String, signature: String?, implementation: NativeImplementation, arity: Int? = null): FunctionRegistry =
        register(FunctionDefinition(name, signature, implementation, FunctionDoc(LocalizedText(emptyMap())), arity = arity))

    /** Registers a documented function; the rules are those of the class. */
    public fun register(definition: FunctionDefinition): FunctionRegistry {
        val sig = definition.signature?.let { Signature.parse(it) }
        return register(NativeFunction(definition.name, sig, definition.arity ?: sig?.params?.size ?: 0, definition.implementation, definition = definition))
    }

    internal fun register(function: NativeFunction): FunctionRegistry {
        val name = function.name
        if (standardFrozen) throw JsonataException("X0004", value = StringValue.of(name))
        check(frozenFunctions == null) { "the registry is frozen: register \$$name in a registry made by extend()" }
        if (function.source != FunctionSource.STDLIB && !isCallableName(name)) throw JsonataException("X0006", value = StringValue.of(name))
        if (functions!!.containsKey(name)) throw JsonataException("X0001", value = StringValue.of(name))
        val existing = parent?.lookup(name) as? NativeFunction
        val overrides = function.definition?.overridesStandard == true
        when {
            existing == null && parent?.lookup(name) != null -> throw JsonataException("X0001", value = StringValue.of(name))
            existing == null -> require(!overrides) { "\$$name is not a standard library function: overridesStandard does not apply" }
            existing.source == FunctionSource.STDLIB && !overrides -> throw JsonataException("X0007", value = StringValue.of(name))
            existing.source != FunctionSource.STDLIB -> throw JsonataException("X0001", value = StringValue.of(name))
        }
        functions[name] = function
        return this
    }

    /**
     * An immutable snapshot of this registry: later registrations here do not reach it. The parent must be frozen
     * too ([standard] is), so that the whole chain is fixed.
     */
    public fun freeze(): FunctionRegistry {
        if (isFrozen) return this
        check(parent == null || parent.isFrozen) { "freeze the parent registry first" }
        return FunctionRegistry(parent, java.util.Collections.unmodifiableMap(LinkedHashMap(functions!!)))
    }

    /** The function registered under [name] here or in a parent, or null. */
    public fun lookup(name: String): FunctionValue? = own()[name] ?: parent?.lookup(name)

    /** The functions an expression compiled with this registry can call, with their documentation (CAP-8). */
    public fun catalog(): su.seaf.archtool.jsonata.catalog.FunctionCatalog = su.seaf.archtool.jsonata.catalog.FunctionCatalog.of(this)

    /** Names defined more than once along the chain (an overridden standard function), CAP-7 p. 5. */
    public fun conflicts(): List<NameConflict> {
        val chain = generateSequence(this) { it.parent }.toList().asReversed()
        val byName = LinkedHashMap<String, MutableList<FunctionSource>>()
        for (r in chain) for ((name, f) in r.own()) byName.getOrPut(name) { ArrayList() } += (f as? NativeFunction)?.source ?: FunctionSource.HOST
        return byName.filterValues { it.size > 1 }.map { (name, sources) -> NameConflict(name, sources, sources.last()) }
    }

    /** Whether this registry is [other] or extends it. */
    internal fun descendsFrom(other: FunctionRegistry): Boolean = generateSequence(this) { it.parent }.any { it === other }

    /** Names registered in this registry itself, without the parents (tests, the future catalog). */
    internal fun ownNames(): Set<String> = own().keys.toSet()

    /** The registries of the chain from the outermost (the standard library) to this one. */
    internal fun chain(): List<FunctionRegistry> = generateSequence(this) { it.parent }.toList().asReversed()

    internal fun ownFunctions(): Map<String, FunctionValue> = own()

    public companion object {
        /**
         * A function value implemented in Kotlin that is not registered under a name, e.g. the result of a registered
         * function that returns a function.
         */
        @JvmStatic
        @JvmOverloads
        public fun function(signature: String?, implementation: NativeImplementation, arity: Int? = null): FunctionValue {
            val sig = signature?.let { Signature.parse(it) }
            return NativeFunction("", sig, arity ?: sig?.params?.size ?: 0, implementation)
        }

        /** `$` + name must be exactly one variable token whose value is the name (the tokenizer of expressions). */
        internal fun isCallableName(name: String): Boolean {
            if (name.isEmpty()) return false
            return try {
                val lexer = su.seaf.archtool.jsonata.parser.Tokenizer("\$" + name)
                val t = lexer.next(false)
                t != null && t.type == su.seaf.archtool.jsonata.parser.TokenType.VARIABLE && t.value == name && lexer.next(true) == null
            } catch (_: JsonataException) {
                false
            }
        }

        private val STANDARD: FunctionRegistry = FunctionRegistry(null, null).also { StandardLibrary.registerAll(it); it.standardFrozen = true }

        /** The jsonata-js 2.2.2 standard library: shared and read-only (registering into it fails with X0004). */
        @JvmStatic
        public fun standard(): FunctionRegistry = STANDARD

        /** A new registry on top of the standard library. */
        @JvmStatic
        public fun create(): FunctionRegistry = STANDARD.extend()
    }
}
