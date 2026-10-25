package su.seaf.archtool.jsonata

import su.seaf.archtool.jsonata.engine.Compiler
import su.seaf.archtool.jsonata.engine.Evaluator
import su.seaf.archtool.jsonata.engine.NativeFunction
import su.seaf.archtool.jsonata.engine.Node
import su.seaf.archtool.jsonata.engine.StandardLibrary
import su.seaf.archtool.jsonata.parser.AstNode
import su.seaf.archtool.jsonata.parser.JsonataParser
import su.seaf.archtool.jsonata.parser.Signature
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
    private val options: CompileOptions,
) {
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
    ): Value? = Evaluator.run(root, input, bindings, options, evaluation)

    public companion object {
        /** Parses and compiles; throws [su.seaf.archtool.jsonata.parser.JsonataException] on a syntax error. */
        @JvmStatic
        @JvmOverloads
        public fun compile(expression: String, options: CompileOptions = CompileOptions.DEFAULT): Jsonata {
            val ast = JsonataParser.ast(expression)
            return Jsonata(expression, ast, Compiler(options.debug).compile(ast), options)
        }
    }
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
 */
public class EvaluationOptions @JvmOverloads constructor(
    public val maxDepth: Int = 0,
    public val timeoutMillis: Long = 0,
    public val maxSequence: Int = 0,
    /** Clock for `$now()`/`$millis()` (milliseconds since the epoch), read once per evaluation. */
    public val clock: () -> Long = { System.currentTimeMillis() },
) {
    public companion object {
        @JvmField public val DEFAULT: EvaluationOptions = EvaluationOptions()
    }
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

    /** Invokes a function value (a lambda, a native, a partial application) synchronously. */
    public fun call(function: FunctionValue, args: List<Value?>): Value?
}

/**
 * The functions an expression can call, by name without `$`. Registries form a chain: [standard] holds the jsonata-js
 * 2.2.2 library, [extend] creates a child for the caller's functions (decision 14: the main way the language grows).
 * A child cannot replace a standard function unless created with `allowOverride`. Configure a registry before
 * compiling expressions with it; it is read concurrently afterwards.
 */
public class FunctionRegistry private constructor(
    private val parent: FunctionRegistry?,
    private val allowOverride: Boolean,
) {
    private val functions = HashMap<String, FunctionValue>()

    /** Creates a registry whose own functions are looked up before this one's. */
    @JvmOverloads
    public fun extend(allowOverride: Boolean = false): FunctionRegistry = FunctionRegistry(this, allowOverride)

    /**
     * Registers a native function. [signature] uses the JSONata syntax (`<s-n?:s>`), or null for no checks. [arity]
     * is what higher-order functions see (how many of value/index/array they pass); by default the number of
     * parameters in the signature.
     */
    @JvmOverloads
    public fun register(name: String, signature: String?, implementation: NativeImplementation, arity: Int? = null): FunctionRegistry {
        val sig = signature?.let { Signature.parse(it) }
        return register(NativeFunction(name, sig, arity ?: sig?.params?.size ?: 0, implementation))
    }

    internal fun register(function: NativeFunction): FunctionRegistry {
        if (!allowOverride && parent?.lookup(function.name) != null) {
            throw IllegalArgumentException("function \$${function.name} is already defined; use extend(allowOverride = true) to replace it")
        }
        functions[function.name] = function
        return this
    }

    /** The function registered under [name] here or in a parent, or null. */
    public fun lookup(name: String): FunctionValue? = functions[name] ?: parent?.lookup(name)

    public companion object {
        private val STANDARD: FunctionRegistry by lazy { FunctionRegistry(null, false).also { StandardLibrary.registerAll(it) } }

        /** The jsonata-js 2.2.2 standard library (shared, do not register into it). */
        @JvmStatic
        public fun standard(): FunctionRegistry = STANDARD

        /** A new registry on top of the standard library. */
        @JvmStatic
        public fun create(): FunctionRegistry = STANDARD.extend()
    }
}
