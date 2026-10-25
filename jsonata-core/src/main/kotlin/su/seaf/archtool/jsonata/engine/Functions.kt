package su.seaf.archtool.jsonata.engine

import su.seaf.archtool.jsonata.CallContext
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
) : EngineFunction()

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

    /**
     * The last call site that invoked this lambda by name: jsonata-js records token and position on the lambda object
     * at every call (evaluateFunction, the trampoline) and errors raised inside take them over, so the innermost call
     * wins. A lambda belongs to one evaluation, so this state is never shared between threads.
     */
    @JvmField var lastSite: CallSite? = null
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
internal class InternalFunction(
    override val arity: Int,
    val body: (CallContextImpl, List<Value?>) -> Value?,
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

    override fun call(function: FunctionValue, args: List<Value?>): Value? = Evaluator.apply(function, args, null, frame, null)

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
        val m = signature.pattern.matcher(sig)
        if (!m.matches()) throwValidationError(signature, args, sig)
        val validated = ArrayList<Value?>(signature.params.size)
        var argIndex = 0
        signature.params.forEachIndexed { index, param ->
            var arg = args.getOrNull(argIndex)
            val match = m.group(index + 1) ?: ""
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
