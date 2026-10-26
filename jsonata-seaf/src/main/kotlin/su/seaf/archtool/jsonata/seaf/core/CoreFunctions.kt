package su.seaf.archtool.jsonata.seaf.core

import su.seaf.archtool.jsonata.CallContext
import su.seaf.archtool.jsonata.Example
import su.seaf.archtool.jsonata.FunctionDefinition
import su.seaf.archtool.jsonata.FunctionDoc
import su.seaf.archtool.jsonata.FunctionRegistry
import su.seaf.archtool.jsonata.FunctionSource
import su.seaf.archtool.jsonata.LocalizedText
import su.seaf.archtool.jsonata.NativeImplementation
import su.seaf.archtool.jsonata.ParamDoc
import su.seaf.archtool.jsonata.ResultDoc
import su.seaf.archtool.jsonata.parser.JsonataException
import su.seaf.archtool.jsonata.seaf.SeafEnvironment
import su.seaf.archtool.jsonata.seaf.SeafHost
import su.seaf.archtool.jsonata.seaf.SeafRequest
import su.seaf.archtool.jsonata.seaf.schema.CompiledSchema
import su.seaf.archtool.jsonata.seaf.schema.JsonSchemas
import su.seaf.archtool.jsonata.seaf.schema.Nodes
import su.seaf.archtool.jsonata.seaf.schema.SchemaCompileException
import su.seaf.archtool.jsonata.seaf.schema.SchemaInstanceException
import su.seaf.archtool.jsonata.seaf.schema.SchemaMode
import su.seaf.archtool.jsonata.value.ArrayValue
import su.seaf.archtool.jsonata.value.BooleanValue
import su.seaf.archtool.jsonata.value.FunctionValue
import su.seaf.archtool.jsonata.value.NullValue
import su.seaf.archtool.jsonata.value.NumberValue
import su.seaf.archtool.jsonata.value.ObjectValue
import su.seaf.archtool.jsonata.value.StringValue
import su.seaf.archtool.jsonata.value.Value
import java.util.IdentityHashMap
import java.util.Properties
import java.util.concurrent.atomic.AtomicReference

/**
 * The functions the SEAF core registers in its JSONata driver (`driver.mjs` of seaf-archtool-core), described in
 * `docs/seaf-functions.md`. `$self` and `$params` are bindings of [SeafEnvironment.evaluate], not functions.
 *
 * Not here yet: `$wcard`, `$mergedeep`, `$sourcetype` — their algorithms need the core's code or the reference values
 * of step 3.0 (`docs/progress.md`, story 3).
 */
internal class CoreFunctions(private val host: SeafHost) {
    /** `$manifestschema()` asks the host once per environment. */
    private val manifestSchema = AtomicReference<Any?>(UNSET)

    fun registerAll(registry: FunctionRegistry) {
        def(registry, "lookup", null, overrides = true) { c, a -> lookup(c, a) }
        def(registry, "tryexecute", null) { c, a -> tryExecute(c, a) }
        def(registry, "jsonschema", null) { c, a -> jsonSchema(c, a) }
        def(registry, "set", null, sideEffects = true) { c, a -> store(c)[key(a.getOrNull(0))] = a.getOrNull(1); a.getOrNull(1) }
        def(registry, "get", null) { c, a -> store(c)[key(a.getOrNull(0))] }
        def(registry, "log", null, sideEffects = true) { _, a -> log(a) }
        def(registry, "parsesource", null, sideEffects = true) { c, a -> host.loadSource(c.root, a.getOrNull(0), c.attribute(SeafEnvironment.REQUEST) as? SeafRequest, c) }
        def(registry, "manifestschema", null) { _, _ -> schema() }
        def(registry, "versions", null) { _, _ -> host.versions() }
    }

    private fun def(registry: FunctionRegistry, name: String, signature: String?, overrides: Boolean = false, sideEffects: Boolean = false, impl: NativeImplementation) {
        registry.register(FunctionDefinition(name, signature, impl, CoreDocs.doc(name), FunctionSource.CORE, overrides, sideEffects))
    }

    // ---- $lookup

    /**
     * The core's `$lookup` replaces the standard one: a `null` key gives `null` (the standard function fails with
     * T0410), one argument looks the key up in the context, an array collects and flattens the values.
     */
    private fun lookup(c: CallContext, a: List<Value?>): Value? {
        val (obj, key) = if (a.size <= 1) c.input to a.getOrNull(0) else a[0] to a[1]
        if (key == NullValue) return NullValue
        val name = keyText(key) ?: return null
        return lookupIn(obj, name)
    }

    private fun lookupIn(obj: Value?, key: String): Value? = when (obj) {
        is ArrayValue -> {
            val out = ArrayValue.sequence()
            for (i in 0 until obj.size) {
                when (val r = lookupIn(obj.getOrNull(i), key)) {
                    null -> {}
                    is ArrayValue -> for (x in r) out.add(x)
                    else -> out.add(r)
                }
            }
            if (out.size == 0) null else out
        }
        is ObjectValue -> obj[key]
        else -> null
    }

    /** A key as JS turns it into a property name; null for undefined and structured values. */
    private fun keyText(key: Value?): String? = when (key) {
        is StringValue -> key.value
        is NumberValue -> su.seaf.archtool.jsonata.value.JsonWriter.stringify(key)
        is BooleanValue -> key.value.toString()
        else -> null
    }

    // ---- $tryexecute

    /**
     * `$tryexecute(f, args)`: `{success: true, result}` or `{success: false, result: null, error}`. Limit errors
     * (depth, deadline, sequence, cancellation, budgets) and engine faults are not caught: an expression cannot
     * catch its own limits (CAP-13 p. 8).
     */
    private fun tryExecute(c: CallContext, a: List<Value?>): Value {
        val f = a.getOrNull(0) as? FunctionValue ?: return failure("The first argument of \$tryexecute must be a function")
        val args: List<Value?> = when (val x = a.getOrNull(1)) {
            null -> emptyList()
            is ArrayValue -> List(x.size) { x.getOrNull(it) }
            else -> listOf(x)
        }
        return try {
            val r = c.call(f, args)
            ObjectValue().also { o ->
                o.put("success", BooleanValue.TRUE)
                r?.let { o.put("result", it) }
            }
        } catch (e: JsonataException) {
            if (e.code in UNCATCHABLE) throw e
            failure(e.message)
        } catch (e: RuntimeException) {
            failure(e.message ?: "Unknown error")
        }
    }

    private fun failure(text: String): Value = ObjectValue().also { o ->
        o.put("success", BooleanValue.FALSE)
        o.put("result", NullValue)
        o.put("error", StringValue.of(text))
    }

    // ---- $jsonschema

    /**
     * `$jsonschema(schema)` gives a function: `true` for a valid value, otherwise the list of ajv errors. The compiled
     * schema is cached for the evaluation by the identity of the schema value.
     */
    private fun jsonSchema(c: CallContext, a: List<Value?>): Value {
        val schemaValue = a.getOrNull(0)
        val cache = c.local(SCHEMA_CACHE) { IdentityHashMap<Any, CompiledSchema>() }
        val compiled = cache[schemaValue ?: NO_SCHEMA] ?: try {
            JsonSchemas.compile(schemaValue, SchemaMode.JSONSCHEMA).also { cache[schemaValue ?: NO_SCHEMA] = it }
        } catch (e: SchemaCompileException) {
            throw JsonataException("X0012", details = mapOf("message" to e.message))
        }
        return FunctionRegistry.function(null, { cc, args -> validate(cc, compiled, args.getOrNull(0)) })
    }

    private fun validate(c: CallContext, schema: CompiledSchema, data: Value?): Value {
        val poll = { c.checkpoint() } // cancellation and deadline (review RV3-13)
        val violations = try {
            schema.validate(data, poll)
        } catch (_: SchemaInstanceException) {
            throw JsonataException("X0013", value = NumberValue.of(Nodes.MAX_DEPTH))
        }
        val errors = violations.filter { it.keyword != "if" }
        if (errors.isEmpty()) return BooleanValue.TRUE
        val out = ArrayValue(errors.size)
        for (v in errors) {
            val o = v.toValue()
            (v.params["allowedValues"])?.let { o.put("allowedValues", it) }
            out.add(o)
        }
        return out
    }

    // ---- $set, $get, $log, $manifestschema

    /** The store of `$set`/`$get` lives for one evaluation (decision B8 of the spec; a difference from the core). */
    private fun store(c: CallContext): HashMap<String, Value?> = c.local(STORE) { HashMap<String, Value?>() }

    private fun key(v: Value?): String = keyText(v) ?: "undefined"

    private fun log(a: List<Value?>): Value? {
        val tag = (a.getOrNull(0) as? StringValue)?.value
        val content = if (a.size > 1) a[1] else a.getOrNull(0)
        host.log(if (a.size > 1) tag else null, content)
        return content
    }

    private fun schema(): Value? {
        val cached = manifestSchema.get()
        if (cached !== UNSET) return cached as Value?
        val v = host.manifestSchema()
        manifestSchema.compareAndSet(UNSET, v)
        return manifestSchema.get() as Value?
    }

    companion object {
        private val UNSET = Any()
        private val NO_SCHEMA = Any()
        private val STORE = Any()
        private val SCHEMA_CACHE = Any()

        /** Errors `$tryexecute` lets through (design §2.3): limits, cancellation, budgets, engine faults. */
        val UNCATCHABLE: Set<String> = setOf("D1011", "D1012", "D2015", "X0002", "X0005", "X0008", "X0009", "X0010", "X0011")

        /** All 12 functions of the core, including the ones not implemented yet: manifest functions may not take these names. */
        val ALL_NAMES: Set<String> = setOf("lookup", "parsesource", "tryexecute", "manifestschema", "sourcetype", "wcard", "mergedeep", "jsonschema", "log", "set", "get", "versions")

        /** Names of the core functions this module registers. */
        val NAMES: List<String> = listOf("lookup", "tryexecute", "jsonschema", "set", "get", "log", "parsesource", "manifestschema", "versions")
    }
}

/** Descriptions of the core functions in the CAP-8 form: `core-docs_ru.properties` beside this package. */
internal object CoreDocs {
    private val ru: Properties = Properties().also { p ->
        val stream = CoreDocs::class.java.getResourceAsStream("/su/seaf/archtool/jsonata/seaf/core-docs_ru.properties")
            ?: error("core-docs_ru.properties is missing")
        stream.reader(Charsets.UTF_8).use { p.load(it) }
    }

    private fun text(key: String): LocalizedText? = ru.getProperty(key)?.let { LocalizedText.ru(it) }

    fun doc(name: String): FunctionDoc {
        val params = ru.getProperty("$name.params")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty().map { spec ->
            // name:type or name:type? (optional)
            val pname = spec.substringBefore(':')
            val type = spec.substringAfter(':', "x")
            ParamDoc(pname, type.removeSuffix("?"), text("$name.param.$pname") ?: LocalizedText.ru(""), type.endsWith("?"))
        }
        val result = ru.getProperty("$name.result.type")?.let { ResultDoc(it, text("$name.result")) }
        val examples = generateSequence(1) { it + 1 }.map { ru.getProperty("$name.example.$it") }.takeWhile { it != null }
            .map { line -> Example(line!!.substringBeforeLast(" => ").trim(), line.substringAfterLast(" => ").trim()) }.toList()
        return FunctionDoc(text("$name.summary") ?: LocalizedText.ru(name), params, result, examples)
    }
}
