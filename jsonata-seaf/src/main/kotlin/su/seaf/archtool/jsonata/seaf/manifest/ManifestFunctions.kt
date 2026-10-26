package su.seaf.archtool.jsonata.seaf.manifest

import su.seaf.archtool.jsonata.CallContext
import su.seaf.archtool.jsonata.CompileOptions
import su.seaf.archtool.jsonata.FunctionDefinition
import su.seaf.archtool.jsonata.FunctionDoc
import su.seaf.archtool.jsonata.FunctionRegistry
import su.seaf.archtool.jsonata.FunctionSource
import su.seaf.archtool.jsonata.Jsonata
import su.seaf.archtool.jsonata.LocalizedText
import su.seaf.archtool.jsonata.ParamDoc
import su.seaf.archtool.jsonata.ResultDoc
import su.seaf.archtool.jsonata.parser.JsonataException
import su.seaf.archtool.jsonata.seaf.SeafWarning
import su.seaf.archtool.jsonata.seaf.core.CoreFunctions
import su.seaf.archtool.jsonata.seaf.schema.CompiledSchema
import su.seaf.archtool.jsonata.seaf.schema.JsonSchemas
import su.seaf.archtool.jsonata.seaf.schema.Nodes
import su.seaf.archtool.jsonata.seaf.schema.SchemaCompileException
import su.seaf.archtool.jsonata.seaf.schema.SchemaInstanceException
import su.seaf.archtool.jsonata.seaf.schema.SchemaMode
import su.seaf.archtool.jsonata.seaf.schema.SchemaViolation
import su.seaf.archtool.jsonata.value.ArrayValue
import su.seaf.archtool.jsonata.value.BooleanValue
import su.seaf.archtool.jsonata.value.FunctionValue
import su.seaf.archtool.jsonata.value.JsonWriter
import su.seaf.archtool.jsonata.value.NumberValue
import su.seaf.archtool.jsonata.value.ObjectValue
import su.seaf.archtool.jsonata.value.StringValue
import su.seaf.archtool.jsonata.value.Value
import java.util.concurrent.atomic.AtomicReference

/** A name of a manifest function that is also a built-in function (CAP-7 p. 3). */
public class NameConflictReport(public val id: String, public val builtIn: String) {
    /** `id (stdlib)`: rename the manifest function. */
    override fun toString(): String = "$id ($builtIn): rename the manifest function"
}

/** One declared parameter of a manifest function: its name in `code` and its JSON Schema. */
internal class ParamDeclaration(val name: String, val required: Boolean, val schema: ObjectValue)

/** A parsed `functions` entry. */
internal class ManifestFunction(
    val id: String,
    val title: String?,
    val params: List<ParamDeclaration>?,
    /** Null without a string `code`: the function returns undefined, as in the core. */
    val code: String?,
    val result: Value?,
    /** `params` is there but not an array: X0017 at the call, as ajv of the core fails then. */
    val paramsNotArray: Boolean = false,
) {
    /** The schema the arguments are checked against: `{type: object, properties, required}` of the declarations. */
    fun paramsSchema(): ObjectValue? {
        val ps = params ?: return null
        val properties = ObjectValue()
        val required = ArrayValue()
        for (p in ps) {
            properties.put(p.name, p.schema)
            if (p.required) required.add(StringValue.of(p.name))
        }
        return ObjectValue().also { o ->
            o.put("type", StringValue.of("object"))
            o.put("properties", properties)
            if (required.size > 0) o.put("required", required)
        }
    }
}

/**
 * The `functions` section of a manifest (CAP-5; `functions.mjs` of the core): each entry is `{title, params, code,
 * result}`; `params` is a list of parameter declarations — a JSON Schema plus `alias` (the field of the input `code`
 * sees, `param<N>` without one) and `required`.
 */
internal object ManifestFunctions {
    /** Keys of a declaration that are not part of the parameter's JSON Schema. */
    private val DECLARATION_KEYS = setOf("alias", "required")

    /**
     * Every entry is a function, as the core registers it: one without a string `code` returns undefined, and its id
     * counts for conflicts like any other (review RV3-36).
     */
    fun parse(functions: Value?): List<ManifestFunction> {
        val section = functions as? ObjectValue ?: return emptyList()
        return section.entries.map { (id, decl) ->
            val d = decl as? ObjectValue ?: ObjectValue()
            val code = (d["code"] as? StringValue)?.value
            val rawParams = d["params"]
            val params = (rawParams as? ArrayValue)?.let { list ->
                List(list.size) { i ->
                    val p = list[i] as? ObjectValue ?: ObjectValue()
                    val alias = (p["alias"] as? StringValue)?.value
                    val schema = ObjectValue().also { s -> for ((k, v) in p.entries) if (k !in DECLARATION_KEYS) s.put(k, v) }
                    ParamDeclaration(alias ?: "param$i", p["required"] == BooleanValue.TRUE, schema)
                }
            }
            val paramsNotArray = rawParams != null && rawParams != su.seaf.archtool.jsonata.value.NullValue && rawParams !is ArrayValue
            ManifestFunction(id, (d["title"] as? StringValue)?.value, params, code, d["result"], paramsNotArray)
        }
    }

    const val PARAMS_NOT_ARRAY: String = "params must be an array of parameter declarations"

    /** Ids that name a built-in function: one of the 12 core functions (checked first: `lookup` is both) or stdlib. */
    fun conflicts(functions: List<ManifestFunction>): List<NameConflictReport> = functions.mapNotNull { f ->
        when {
            f.id in CoreFunctions.ALL_NAMES -> NameConflictReport(f.id, "core")
            FunctionRegistry.standard().lookup(f.id) != null -> NameConflictReport(f.id, "stdlib")
            else -> null
        }
    }

    /** Schemas that fail at their first call (X0017): `params` and `result` compiled as ajv of the core would. */
    fun invalidDeclarations(functions: List<ManifestFunction>): List<Pair<ManifestFunction, String>> {
        val out = ArrayList<Pair<ManifestFunction, String>>()
        for (f in functions) {
            if (f.paramsNotArray) out += f to "входных параметров: $PARAMS_NOT_ARRAY"
            for ((part, schema, mode) in listOf(Triple("входных параметров", f.paramsSchema(), SchemaMode.MANIFEST_FUNCTION), Triple("результата", f.result, SchemaMode.MANIFEST_RESULT))) {
                if (schema == null) continue
                try {
                    JsonSchemas.compile(schema, mode)
                } catch (e: SchemaCompileException) {
                    out += f to "$part: ${e.message}"
                }
            }
        }
        return out
    }

    /**
     * Registers [functions] in [registry] (a child of the core registry). Conflicts fail all at once with X0014;
     * an id that cannot be called from an expression gives a warning and is skipped (`$a.b(2)` is undefined in 2.2.2
     * anyway). Returns the callables to finish with [Callable.compile] once the registry is frozen.
     */
    fun register(functions: List<ManifestFunction>, registry: FunctionRegistry, warnings: MutableList<SeafWarning>): List<Callable> {
        val conflicts = conflicts(functions)
        if (conflicts.isNotEmpty()) {
            throw JsonataException("X0014", details = mapOf("message" to conflicts.joinToString("; ")))
        }
        val out = ArrayList<Callable>()
        for (f in functions) {
            val callable = Callable(f)
            try {
                registry.register(FunctionDefinition(f.id, null, { c, a -> callable.call(c, a) }, doc(f), FunctionSource.MANIFEST, false, false, f.params?.size ?: 0))
                out += callable
            } catch (e: JsonataException) {
                if (e.code != "X0006") throw e
                warnings += SeafWarning(f.id, "The manifest function id \"${f.id}\" cannot be called from an expression; it is not registered")
            }
        }
        return out
    }

    private fun doc(f: ManifestFunction) = FunctionDoc(
        LocalizedText.ru(f.title ?: f.id),
        f.params.orEmpty().map { p ->
            ParamDoc(p.name, typeOf(p.schema), LocalizedText.ru((p.schema["title"] as? StringValue)?.value ?: (p.schema["description"] as? StringValue)?.value ?: ""), !p.required)
        },
        (f.result as? ObjectValue)?.let { ResultDoc(typeOf(it)) },
        manifestId = f.id,
    )

    /** The signature letter of a JSON Schema type, for the catalog. */
    private fun typeOf(schema: ObjectValue): String {
        if (schema["callback"] != null || schema["params"] != null) return "f"
        return when ((schema["type"] as? StringValue)?.value) {
            "string" -> "s"
            "number", "integer" -> "n"
            "boolean" -> "b"
            "array" -> "a"
            "object" -> "o"
            "null" -> "l"
            else -> "x"
        }
    }

    /** One manifest function: its code compiled once per environment, its schemas at the first call (as the core). */
    internal class Callable(val f: ManifestFunction) {
        private var compiled: Jsonata? = null
        private var compileError: JsonataException? = null
        private val paramsSchema = AtomicReference<Any?>(null)
        private val resultSchema = AtomicReference<Any?>(null)

        fun compile(registry: FunctionRegistry) {
            val text = f.code ?: return
            try {
                compiled = Jsonata.compile(text, CompileOptions(registry, false, "manifest:functions/${f.id}"))
            } catch (e: JsonataException) {
                compileError = e
            }
        }

        fun call(c: CallContext, args: List<Value?>): Value? {
            compileError?.let { throw JsonataException(it.code, it.position, it.token, it.value, it.value2, it.index, it.details, it, f.id) }
            if (f.paramsNotArray) {
                throw JsonataException("X0017", value = StringValue.of(f.id), details = mapOf("part" to "входных параметров", "message" to PARAMS_NOT_ARRAY), functionId = f.id)
            }
            val code = compiled
            if (code == null && f.code != null) error("manifest function ${f.id} is not compiled")
            val poll = { c.checkpoint() } // cancellation and deadline (review RV3-13)
            var input: Value? = null
            val bindings = HashMap<String, Value?>()
            val params = f.params
            if (params != null) {
                val fields = ObjectValue()
                val checked = ObjectValue()
                for ((i, p) in params.withIndex()) {
                    when (val v = args.getOrNull(i)) {
                        null -> {}
                        is FunctionValue -> {
                            bindings[p.name] = v
                            checked.put(p.name, v)
                        }
                        else -> {
                            fields.put(p.name, v)
                            checked.put(p.name, v)
                        }
                    }
                }
                val schema = schema(paramsSchema, f.paramsSchema()!!, "входных параметров", SchemaMode.MANIFEST_FUNCTION)
                val violations = check(schema, checked, poll)
                if (violations.isNotEmpty()) throw violation("X0015", violations)
                input = fields
            }
            // no code: undefined, as the core's function of an undefined code
            val result = if (code == null) null else c.evaluate(code, input, bindings, f.id)
            val resultDecl = f.result
            if (resultDecl != null) {
                val violations = check(schema(resultSchema, resultDecl, "результата", SchemaMode.MANIFEST_RESULT), result, poll)
                if (violations.isNotEmpty()) throw violation("X0016", violations)
            }
            return result
        }

        private fun check(schema: CompiledSchema, value: Value?, poll: () -> Unit): List<SchemaViolation> = try {
            schema.validate(value, poll)
        } catch (_: SchemaInstanceException) {
            throw JsonataException("X0013", value = NumberValue.of(Nodes.MAX_DEPTH), functionId = f.id)
        }

        /** Compiles a schema at the first call (as ajv of the core does), and remembers a failure too. */
        private fun schema(cache: AtomicReference<Any?>, schema: Value, part: String, mode: SchemaMode): CompiledSchema {
            val cached = cache.get() ?: run {
                val made: Any = try {
                    JsonSchemas.compile(schema, mode)
                } catch (e: SchemaCompileException) {
                    e
                }
                cache.compareAndSet(null, made)
                cache.get()
            }
            if (cached is SchemaCompileException) {
                throw JsonataException("X0017", value = StringValue.of(f.id), details = mapOf("part" to part, "message" to cached.message), functionId = f.id)
            }
            return cached as CompiledSchema
        }

        private fun violation(code: String, violations: List<SchemaViolation>): JsonataException {
            val list = ArrayValue(violations.size).also { a -> for (v in violations) a.add(v.toValue()) }
            return JsonataException(
                code, value = StringValue.of(f.id),
                details = mapOf("message" to JsonWriter.stringify(list, 4), "violations" to list),
                functionId = f.id,
            )
        }
    }
}
