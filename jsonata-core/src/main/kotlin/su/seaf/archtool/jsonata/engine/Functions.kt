package su.seaf.archtool.jsonata.engine

import su.seaf.archtool.jsonata.CallContext
import su.seaf.archtool.jsonata.FunctionDefinition
import su.seaf.archtool.jsonata.FunctionSource
import su.seaf.archtool.jsonata.NativeImplementation
import su.seaf.archtool.jsonata.parser.JsonataException
import su.seaf.archtool.jsonata.parser.Signature
import su.seaf.archtool.jsonata.value.ArrayValue
import su.seaf.archtool.jsonata.value.BooleanValue
import su.seaf.archtool.jsonata.value.FunctionValue
import su.seaf.archtool.jsonata.value.NullValue
import su.seaf.archtool.jsonata.value.NumberValue
import su.seaf.archtool.jsonata.value.ObjectValue
import su.seaf.archtool.jsonata.value.StringValue
import su.seaf.archtool.jsonata.value.Value

/** Function values created by the engine. */
internal abstract class EngineFunction : FunctionValue() {
    open val signature: Signature? get() = null

    /**
     * True for what jsonata-js represents as an object (lambdas, `defineFunction` results): call sites record their
     * token and position on those, and errors raised inside take them over. Plain JS functions (regex matchers) don't.
     */
    open val jsObject: Boolean get() = true
}

/** A function registered with a signature (jsonata.js: defineFunction). */
internal class NativeFunction(
    val name: String,
    override val signature: Signature?,
    override val arity: Int,
    val implementation: NativeImplementation,
    /** Number of declared JS parameters, used by partial application (jsonata.js: getNativeFunctionArguments). */
    val paramCount: Int = maxOf(arity, signature?.params?.size ?: 0),
    /** The registration, with documentation and flags; null for the standard library and unregistered values. */
    val definition: FunctionDefinition? = null,
    /** Where the function comes from (the standard library registers with [FunctionSource.STDLIB]). */
    val source: FunctionSource = definition?.source ?: FunctionSource.HOST,
) : EngineFunction() {
    /** Must not run in a debugger watch expression. */
    val sideEffects: Boolean get() = definition?.sideEffects ?: false
}

/** A lambda closure (jsonata.js: evaluateLambda, partialApplyProcedure). */
internal class Lambda(
    @JvmField val input: Value?,
    @JvmField val env: Frame,
    @JvmField val params: Array<String>,
    override val signature: Signature?,
    @JvmField val body: Node,
    /** A tail call wrapped by the parser (tailCallOptimize): unwrapped by the trampoline in [Evaluator.apply]. */
    @JvmField val thunk: Boolean,
    @JvmField val source: Node?,
) : EngineFunction() {
    override val arity: Int get() = params.size
}

/** A native function with some arguments bound (jsonata.js: partialApplyNativeFunction). */
internal class PartialNative(
    val native: NativeFunction,
    /** Bound arguments by position; [PLACEHOLDER] marks a parameter left open. */
    val bound: Array<Any?>,
) : EngineFunction() {
    override val arity: Int = bound.count { it === PLACEHOLDER }

    companion object {
        val PLACEHOLDER: Any = Any()
    }
}

/** An engine function implemented in Kotlin that behaves as a plain JS function (no signature, no call-site token). */
internal open class InternalFunction(
    override val arity: Int,
    open val body: (CallContextImpl, List<Value?>) -> Value?,
) : EngineFunction() {
    override val jsObject: Boolean get() = false
}

/** The transformer created by `| pattern | update, delete |` (jsonata.js: evaluateTransformExpression). */
internal class Transformer(val node: TransformNode, val env: Frame) : EngineFunction() {
    override val arity: Int get() = 1
    override val signature: Signature get() = TRANSFORM_SIGNATURE

    companion object {
        val TRANSFORM_SIGNATURE: Signature = Signature.parse("<(oa):o>")
    }
}

/** Implementation of [CallContext] for one native call. */
internal class CallContextImpl(
    @JvmField val frame: Frame,
    override val input: Value?,
) : CallContext {
    override val timestamp: Long get() = frame.ctx.timestamp

    override val cancellation: su.seaf.archtool.jsonata.CancellationSignal = object : su.seaf.archtool.jsonata.CancellationSignal {
        override val isCancelled: Boolean get() = frame.ctx.isCancelled
        override fun throwIfCancelled() {
            if (frame.ctx.isCancelled) throw frame.ctx.cancelled(null)
        }
    }

    override fun remainingNanos(): Long = frame.ctx.remainingNanos()

    override val root: Value? get() = frame.ctx.rootInput

    override fun <T : Any> local(key: Any, init: () -> T): T = frame.ctx.local(key, init)

    override fun attribute(name: String): Any? = frame.ctx.options.attributes[name]

    override fun evaluate(expression: su.seaf.archtool.jsonata.Jsonata, input: Value?, bindings: Map<String, Value?>, frameName: String): Value? {
        val ctx = frame.ctx
        require(ctx.registry.descendsFrom(expression.options.functions)) {
            "The expression must be compiled with a registry of this evaluation's chain"
        }
        val root = if (ctx.listener != null) expression.debugRoot() else expression.compiledRoot
        val env = Frame(null, ctx)
        env.lambdaName = frameName
        env.caller = frame
        for ((k, v) in bindings) env.bind(k, v)
        env.bind("$", input)
        var focus = input
        if (input is ArrayValue && !input.isSequence) focus = ArrayValue.sequence(input).also { it.outerWrapper = true }
        ctx.listener?.let { l -> if (ctx.announce(expression)) l.source(expression.mainSource) }
        // `$$` of the nested expression is its own input, as in a separate jsonata-js evaluation
        val outerRoot = ctx.rootInput
        ctx.rootInput = input
        try {
            return root.evaluate(focus, env)
        } catch (e: su.seaf.archtool.jsonata.parser.JsonataException) {
            if (e.functionId == null) e.functionId = frameName
            throw e
        } finally {
            ctx.rootInput = outerRoot
        }
    }

    /**
     * jsonata.js wraps function arguments in a closure that calls `apply(fn, args, null, environment)`: the context of
     * such a call is JS `null` (it reaches signature checks and tail calls), not undefined.
     */
    override fun call(function: FunctionValue, args: List<Value?>): Value? = Evaluator.apply(function, args, NullValue, frame, null)

    /** jsonata.js: createSequence. */
    fun sequence(): ArrayValue = ArrayValue.sequence()

    /** The `sequence` limit of the options for a sequence a native function built. */
    fun checked(v: Value?): Value? {
        if (v is ArrayValue && v.isSequence) frame.ctx.checkSequence(v.size)
        return v
    }
}

/** jsonata.js/signature.js: argument validation against a function signature. */
internal object SignatureValidator {
    private val arraySignatureMapping = mapOf("a" to "arrays", "b" to "booleans", "f" to "functions", "n" to "numbers", "o" to "objects", "s" to "strings")

    fun symbol(value: Value?): Char = when (value) {
        null -> 'm'
        is FunctionValue -> 'f'
        is StringValue -> 's'
        is NumberValue -> 'n'
        is BooleanValue -> 'b'
        is NullValue -> 'l'
        is ArrayValue -> 'a'
        is ObjectValue -> 'o'
    }

    fun validate(signature: Signature, args: List<Value?>, context: Value?): List<Value?> {
        val supplied = StringBuilder(args.size)
        for (a in args) supplied.append(symbol(a))
        val sig = supplied.toString()
        val groups = matchGroups(signature, sig) ?: throwValidationError(signature, args, sig)
        val validated = ArrayList<Value?>(signature.params.size)
        var argIndex = 0
        signature.params.forEachIndexed { index, param ->
            var arg = args.getOrNull(argIndex)
            val match = groups[index]
            if (match.isEmpty()) {
                val ctxPattern = signature.contextPatterns[index]
                if (param.context && ctxPattern != null) {
                    if (ctxPattern.matcher(symbol(context).toString()).find()) {
                        validated += context
                    } else {
                        throw JsonataException("T0411", value = context, index = argIndex + 1)
                    }
                } else {
                    validated += arg
                    argIndex++
                }
            } else {
                for (single in match) {
                    arg = args.getOrNull(argIndex)
                    if (param.type == "a") {
                        if (single == 'm') {
                            arg = null
                        } else {
                            var arrayOK = true
                            val subtype = param.subtype
                            if (subtype != null) {
                                if (single != 'a' && match != subtype) {
                                    arrayOK = false
                                } else if (single == 'a') {
                                    val arr = arg as ArrayValue
                                    if (arr.size > 0) {
                                        val itemType = symbol(arr.raw(0))
                                        arrayOK = if (itemType != subtype[0]) {
                                            false
                                        } else {
                                            (0 until arr.size).all { symbol(arr.raw(it)) == itemType }
                                        }
                                    }
                                }
                            }
                            if (!arrayOK) {
                                throw JsonataException("T0412", value = arg, index = argIndex + 1, details = mapOf("type" to arraySignatureMapping[subtype]))
                            }
                            if (single != 'a') arg = ArrayValue(1).also { it.addNullable(arg) }
                        }
                        validated += arg
                        argIndex++
                    } else {
                        validated += arg
                        argIndex++
                    }
                }
            }
        }
        return validated
    }

    /**
     * The text matched by each parameter of the signature regex for a string of argument symbols, or null when it
     * does not match. Memoized per signature: a function is called with few distinct symbol strings, and running the
     * regex on every call was the main cost of native calls.
     */
    private fun matchGroups(signature: Signature, sig: String): Array<String>? {
        val memo = signature.matchMemo
        memo[sig]?.let { return if (it === NO_MATCH) null else it }
        val m = signature.pattern.matcher(sig)
        val groups = if (m.matches()) Array(signature.params.size) { m.group(it + 1) ?: "" } else null
        if (memo.size < 256) memo[sig] = groups ?: NO_MATCH
        return groups
    }

    private val NO_MATCH = arrayOf<String>()

    private fun throwValidationError(signature: Signature, badArgs: List<Value?>, badSig: String): Nothing {
        val partial = StringBuilder("^")
        var goodTo = 0
        for (param in signature.params) {
            partial.append(param.regex)
            val m = java.util.regex.Pattern.compile(partial.toString()).matcher(badSig)
            if (!m.lookingAt()) {
                throw JsonataException("T0410", value = badArgs.getOrNull(goodTo), index = goodTo + 1)
            }
            goodTo = m.end()
        }
        throw JsonataException("T0410", value = badArgs.getOrNull(goodTo), index = goodTo + 1)
    }
}
