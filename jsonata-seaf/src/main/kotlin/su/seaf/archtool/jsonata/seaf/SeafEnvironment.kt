package su.seaf.archtool.jsonata.seaf

import su.seaf.archtool.jsonata.CompileOptions
import su.seaf.archtool.jsonata.EvaluationOptions
import su.seaf.archtool.jsonata.FunctionRegistry
import su.seaf.archtool.jsonata.Jsonata
import su.seaf.archtool.jsonata.seaf.core.CoreFunctions
import su.seaf.archtool.jsonata.value.Value

/**
 * Functions of one manifest version (and, in the role mode of v1, one role): the standard library, the SEAF core
 * functions and the manifest `functions`, in a frozen registry. Rights are not part of the key: they come with the
 * [SeafRequest] of each evaluation. Create once per manifest version and share between threads.
 */
public class SeafEnvironment private constructor(
    public val host: SeafHost,
    public val limits: SeafLimits,
    /** Standard library + core functions + manifest functions, frozen. */
    public val registry: FunctionRegistry,
) {
    /** Compiles an expression with the functions of this environment. */
    @JvmOverloads
    public fun compile(expression: String, debug: Boolean = false): Jsonata = Jsonata.compile(expression, CompileOptions(registry, debug))

    /**
     * Evaluates [expression] (compiled by [compile]) with `$self` and `$params` bound for the whole expression and
     * [request] visible to the core functions. [options] default to the [limits]; the host may pass its own (a listener,
     * a cancellation token).
     */
    @JvmOverloads
    public fun evaluate(
        expression: Jsonata,
        input: Value?,
        self: Value? = null,
        params: Value? = null,
        request: SeafRequest? = null,
        options: EvaluationOptions = limits.options(),
    ): Value? {
        val bindings = HashMap<String, Value?>(2)
        bindings["self"] = self
        bindings["params"] = params
        val o = if (request == null) options else options.withAttribute(REQUEST, request)
        return expression.evaluate(input, bindings, o)
    }

    public companion object {
        /** Key of the [SeafRequest] in [EvaluationOptions.attributes]. */
        public const val REQUEST: String = "seaf.request"

        /** Creates the environment of one manifest version: the standard library and the core functions; manifest `functions` come with step 3.4. */
        @JvmStatic
        @JvmOverloads
        public fun create(host: SeafHost, limits: SeafLimits = SeafLimits.TEMPORARY): SeafEnvironment {
            val registry = FunctionRegistry.create()
            CoreFunctions(host).registerAll(registry)
            return SeafEnvironment(host, limits, registry.freeze())
        }
    }
}

/** A copy of the options with one more host attribute. */
internal fun EvaluationOptions.withAttribute(name: String, value: Any?): EvaluationOptions = EvaluationOptions(
    maxDepth, timeoutMillis, maxSequence, clock, functions, hostErrors, cancellation, budgets, listener, deviations,
    attributes + (name to value),
)
