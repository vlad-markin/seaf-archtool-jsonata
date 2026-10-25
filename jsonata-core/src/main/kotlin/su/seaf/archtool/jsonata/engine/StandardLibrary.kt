package su.seaf.archtool.jsonata.engine

import su.seaf.archtool.jsonata.FunctionRegistry
import su.seaf.archtool.jsonata.NativeImplementation
import su.seaf.archtool.jsonata.parser.Signature
import su.seaf.archtool.jsonata.value.NumberValue

/**
 * The jsonata-js 2.2.2 standard library, registered through the same [FunctionRegistry] as the caller's functions
 * (jsonata.js: the `staticFrame.bind(... defineFunction(...))` list). Arities are the JS `implementation.length`.
 */
internal object StandardLibrary {
    fun registerAll(r: FunctionRegistry) {
        def(r, "clone", "<(oa)-:o>", 1) { _, a -> Evaluator.clone(a[0]) }
        def(r, "millis", "<:n>", 0) { c, _ -> NumberValue.of(c.timestamp.toDouble()) }
    }

    fun def(r: FunctionRegistry, name: String, signature: String, arity: Int, paramCount: Int = arity, impl: NativeImplementation) {
        r.register(NativeFunction(name, Signature.parse(signature), arity, impl, maxOf(paramCount, arity)))
    }
}
