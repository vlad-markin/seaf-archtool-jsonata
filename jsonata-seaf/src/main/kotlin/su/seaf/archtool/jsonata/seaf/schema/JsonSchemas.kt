package su.seaf.archtool.jsonata.seaf.schema

import com.networknt.schema.Error
import com.networknt.schema.ExecutionContext
import com.networknt.schema.Schema
import com.networknt.schema.SchemaContext
import com.networknt.schema.SchemaException
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SchemaRegistryConfig
import com.networknt.schema.dialect.Dialect
import com.networknt.schema.dialect.Draft7
import com.networknt.schema.keyword.AbstractKeywordValidator
import com.networknt.schema.keyword.Keyword
import com.networknt.schema.keyword.KeywordValidator
import com.networknt.schema.keyword.NonValidationKeyword
import com.networknt.schema.path.NodePath
import com.networknt.schema.path.PathType
import com.networknt.schema.regex.RegularExpression
import com.networknt.schema.regex.RegularExpressionFactory
import su.seaf.archtool.jsonata.JsRegexes
import su.seaf.archtool.jsonata.value.ArrayValue
import su.seaf.archtool.jsonata.value.NumberValue
import su.seaf.archtool.jsonata.value.ObjectValue
import su.seaf.archtool.jsonata.value.StringValue
import su.seaf.archtool.jsonata.value.Value
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.POJONode
import java.util.Locale

/** One violation in the form of an ajv 8 error object; [message] is the text of networknt (DEV record). */
internal class SchemaViolation(
    val instancePath: String,
    val schemaPath: String,
    val keyword: String,
    val params: ObjectValue,
    val message: String,
) {
    /** `{instancePath, schemaPath, keyword, params, message}` as ajv reports it. */
    fun toValue(): ObjectValue = ObjectValue().also {
        it.put("instancePath", StringValue.of(instancePath))
        it.put("schemaPath", StringValue.of(schemaPath))
        it.put("keyword", StringValue.of(keyword))
        it.put("params", params)
        it.put("message", StringValue.of(message))
    }

    override fun toString(): String = "$instancePath $keyword $params"
}

/** A schema compiled for one [SchemaMode]; immutable, shared between threads and evaluations. */
internal class CompiledSchema(private val schema: Schema, private val mode: SchemaMode) {
    /** Validates [instance]; [poll] is called while converting and matching (cancellation, deadline). */
    fun validate(instance: Value?, poll: (() -> Unit)? = null): List<SchemaViolation> {
        poll?.invoke()
        val node = Nodes.of(instance, poll)
        val errors = JsonSchemas.POLL.withValue(poll) { schema.validate(node) }
        poll?.invoke()
        return errors.map { Violations.of(it, mode) }
    }
}

/** JSON Schema of the SEAF core on networknt (design §2.3, variant A of the IT lead). */
internal object JsonSchemas {
    private val factory = JsonNodeFactory.instance

    private class Setup(val registry: SchemaRegistry, val meta: Schema)

    private val setups: Map<SchemaMode, Setup> = SchemaMode.entries.associateWith { setup(it) }

    /**
     * Compiles [schema] as ajv of the core would. Failures ajv throws at `compile` (the meta-schema, strict mode,
     * an invalid regex, an unresolvable `$ref`) fail with [SchemaCompileException].
     */
    fun compile(schema: Value?, mode: SchemaMode): CompiledSchema {
        val setup = setups.getValue(mode)
        // a schema deeper than the adapter checks is an error of the schema, not of the host (review RV3-37)
        val node = try {
            Nodes.of(schema)
        } catch (_: SchemaInstanceException) {
            throw SchemaCompileException("schema is nested deeper than ${Nodes.MAX_DEPTH} levels")
        }
        val metaErrors = setup.meta.validate(node)
        if (metaErrors.isNotEmpty()) {
            throw SchemaCompileException("schema is invalid: " + metaErrors.joinToString(", ") { e ->
                val v = Violations.of(e, mode)
                "data${v.instancePath} ${e.message}"
            })
        }
        Strictness(mode).check(node)
        val prepared = Nullable.rewrite(node)
        try {
            val compiled = setup.registry.getSchema(prepared)
            compiled.initializeValidators()
            return CompiledSchema(compiled, mode)
        } catch (e: SchemaException) {
            throw SchemaCompileException(e.message ?: e.toString())
        } catch (e: IllegalArgumentException) {
            throw SchemaCompileException(e.message ?: e.toString())
        }
    }

    private fun setup(mode: SchemaMode): Setup {
        val keywords = ArrayList<Keyword>()
        keywords += AdapterKeyword("contains") { loc, node, parent, ctx -> ContainsValidator(loc, node, parent, ctx) }
        keywords += AdapterKeyword("propertyNames") { loc, node, parent, ctx -> PropertyNamesValidator(loc, node, parent, ctx) }
        keywords += AdapterKeyword("anyOf") { loc, node, parent, ctx -> AnyOfValidator(loc, node, parent, ctx) }
        keywords += AdapterKeyword("if") { loc, node, parent, ctx -> IfValidator(loc, node, parent, ctx) }
        keywords += AdapterKeyword("oneOf") { loc, node, parent, ctx -> OneOfValidator(loc, node, parent, ctx) }
        keywords += AdapterKeyword("dependencies") { loc, node, parent, ctx -> DependenciesValidator(loc, node, parent, ctx) }
        keywords += AdapterKeyword("uniqueItems") { loc, node, parent, _ -> UniqueItemsValidator(loc, node, parent) }
        keywords += AdapterKeyword("multipleOf") { loc, node, _, _ -> MultipleOfValidator(loc, node) }
        for (k in ANNOTATIONS) keywords += NonValidationKeyword(k)
        when (mode) {
            SchemaMode.MANIFEST_FUNCTION -> {
                keywords += AdapterKeyword("callback") { loc, node, _, _ -> FunctionValidator("callback", loc, node) }
                keywords += AdapterKeyword("params") { loc, node, _, _ -> FunctionValidator("params", loc, node) }
            }
            SchemaMode.MANIFEST_RESULT -> {}
            SchemaMode.JSONSCHEMA -> {
                keywords += NonValidationKeyword("\$rels")
                for (k in FormatLimitValidator.KEYWORDS.keys) keywords += AdapterKeyword(k) { loc, node, parent, _ -> FormatLimitValidator(k, loc, node, parent) }
            }
        }
        val dialect = Dialect.builder(Draft7.getInstance()).keywords(keywords)
            .formatKeywordFactory { _ -> AdapterFormatKeyword(mode) }
            .build()
        val config = SchemaRegistryConfig.builder()
            .regularExpressionFactory(JsRegexFactory(mode.regexFlags))
            .pathType(PathType.JSON_POINTER)
            .locale(Locale.ROOT)
            .formatAssertionsEnabled(true)
            .losslessNarrowing(true)
            .build()
        val registry = SchemaRegistry.withDefaultDialect(dialect) { b -> b.schemaRegistryConfig(config).schemaCacheEnabled(false) }
        val metaRegistry = SchemaRegistry.withDefaultDialect(Draft7.getInstance()) { b -> b.schemaRegistryConfig(config) }
        val meta = metaRegistry.getSchema(SchemaLocation.of("http://json-schema.org/draft-07/schema#"))
        return Setup(registry, meta)
    }

    /** The poll of the validation running on this thread (regex matching inside networknt). */
    internal val POLL = ThreadLocalPoll()

    internal class ThreadLocalPoll {
        private val current = ThreadLocal<(() -> Unit)?>()
        fun get(): (() -> Unit)? = current.get()
        fun <T> withValue(poll: (() -> Unit)?, block: () -> T): T {
            if (poll == null) return block()
            val previous = get()
            set(poll)
            try {
                return block()
            } finally {
                set(previous)
            }
        }
        fun set(poll: (() -> Unit)?) = if (poll == null) current.remove() else current.set(poll)
    }

    internal fun nodeOf(map: Map<String, Value?>): ObjectValue = ObjectValue().also { o -> for ((k, v) in map) if (v != null) o.put(k, v) }

    internal val jsonFactory: JsonNodeFactory get() = factory
}

/** `pattern` and `patternProperties` through the engine's JS regex translator, with the flags of the mode. */
private class JsRegexFactory(private val flags: String) : RegularExpressionFactory {
    override fun getRegularExpression(regex: String): RegularExpression {
        val compiled = JsRegexes.compile(regex, flags)
        return RegularExpression { value -> compiled.test(value, JsonSchemas.POLL.get()) }
    }
}

/** A keyword of the adapter: networknt calls [factory] for each schema object that has it. */
private class AdapterKeyword(
    private val name: String,
    private val factory: (SchemaLocation, JsonNode, Schema?, SchemaContext) -> KeywordValidator,
) : Keyword {
    override fun getValue(): String = name
    override fun newValidator(schemaLocation: SchemaLocation, schemaNode: JsonNode, parentSchema: Schema?, schemaContext: SchemaContext): KeywordValidator =
        factory(schemaLocation, schemaNode, parentSchema, schemaContext)
}

/** networknt allows replacing `format` only through a FormatKeyword. */
private class AdapterFormatKeyword(private val mode: SchemaMode) : com.networknt.schema.keyword.FormatKeyword("format", emptyMap()) {
    override fun newValidator(schemaLocation: SchemaLocation, schemaNode: JsonNode, parentSchema: Schema?, schemaContext: SchemaContext): KeywordValidator =
        FormatValidator(schemaLocation, schemaNode, parentSchema, schemaContext, mode)
}

/** Reports an ajv-shaped error; the params travel in the details of the networknt error. */
private fun ExecutionContext.report(keyword: String, location: SchemaLocation, schemaNode: JsonNode, node: JsonNode, instanceLocation: NodePath, params: Map<String, Value?>, message: String) {
    addError(
        Error.builder().keyword(keyword).schemaLocation(location).schemaNode(schemaNode).instanceNode(node)
            .instanceLocation(instanceLocation).evaluationPath(evaluationPath).message(message)
            .details(mapOf(Violations.PARAMS to JsonSchemas.nodeOf(params))).build(),
    )
}

/** `callback` and `params` of manifest functions: the value must be a function. */
private class FunctionValidator(private val name: String, location: SchemaLocation, schemaNode: JsonNode) :
    AbstractKeywordValidator(name, schemaNode, location) {
    override fun validate(executionContext: ExecutionContext, node: JsonNode, rootNode: JsonNode, instanceLocation: NodePath) {
        if (node is POJONode && node.pojo is su.seaf.archtool.jsonata.value.FunctionValue) return
        executionContext.report(name, schemaLocation, schemaNode, node, instanceLocation, emptyMap(), "must be a function")
    }
}

/** `format` as ajv with (JSONSCHEMA) or without (MANIFEST_FUNCTION, rejected by [Strictness]) ajv-formats. */
private class FormatValidator(location: SchemaLocation, schemaNode: JsonNode, parent: Schema?, ctx: SchemaContext, private val mode: SchemaMode) :
    AbstractKeywordValidator("format", schemaNode, location) {
    private val format = schemaNode.asString()

    override fun validate(executionContext: ExecutionContext, node: JsonNode, rootNode: JsonNode, instanceLocation: NodePath) {
        if (format !in mode.formats) return
        val ok = when {
            AjvFormats.appliesToNumbers(format) -> !node.isNumber || AjvFormats.matches(format, node.asDouble())
            else -> !node.isString || AjvFormats.matches(format, node.asString(), JsonSchemas.POLL.get())
        }
        if (!ok) {
            executionContext.report("format", schemaLocation, schemaNode, node, instanceLocation, mapOf("format" to StringValue.of(format)), "does not match the $format pattern")
        }
    }
}

/** formatMinimum, formatMaximum, formatExclusiveMinimum, formatExclusiveMaximum of ajv-formats. */
private class FormatLimitValidator(private val name: String, location: SchemaLocation, schemaNode: JsonNode, parent: Schema?) :
    AbstractKeywordValidator(name, schemaNode, location) {
    private val format: String? = parent?.schemaNode?.get("format")?.takeIf { it.isString }?.asString()
    private val limit = schemaNode.asString()

    override fun validate(executionContext: ExecutionContext, node: JsonNode, rootNode: JsonNode, instanceLocation: NodePath) {
        val f = format ?: return
        if (!node.isString || f !in AjvFormats.COMPARABLE) return
        val c = AjvFormats.compare(f, node.asString(), limit) ?: return
        val (op, fails) = KEYWORDS.getValue(name)
        if (fails(c)) {
            executionContext.report(name, schemaLocation, schemaNode, node, instanceLocation, mapOf("comparison" to StringValue.of(op), "limit" to StringValue.of(limit)), "should be $op $limit")
        }
    }

    companion object {
        val KEYWORDS: Map<String, Pair<String, (Int) -> Boolean>> = mapOf(
            "formatMaximum" to ("<=" to { c: Int -> c > 0 }),
            "formatMinimum" to (">=" to { c: Int -> c < 0 }),
            "formatExclusiveMaximum" to ("<" to { c: Int -> c >= 0 }),
            "formatExclusiveMinimum" to (">" to { c: Int -> c <= 0 }),
        )
    }
}

/** `nullable: true` of ajv: `null` joins the types. */
private object Nullable {
    fun rewrite(node: JsonNode): JsonNode {
        if (!hasNullable(node)) return node
        return copy(node)
    }

    private fun hasNullable(n: JsonNode): Boolean = when {
        n.isObject -> n.has("nullable") || n.values().any { hasNullable(it) }
        n.isArray -> n.values().any { hasNullable(it) }
        else -> false
    }

    private fun copy(n: JsonNode): JsonNode = when {
        n.isObject -> {
            val o = JsonSchemas.jsonFactory.objectNode()
            for (e in n.properties()) if (e.key != "nullable") o.set(e.key, copy(e.value))
            if (n.get("nullable")?.asBoolean() == true) {
                val t = n.get("type")
                if (t != null && t.isString && t.asString() != "null") o.set("type", JsonSchemas.jsonFactory.arrayNode().add(t.asString()).add("null"))
                else if (t != null && t.isArray && t.values().none { it.asString() == "null" }) o.set("type", (copy(t) as tools.jackson.databind.node.ArrayNode).add("null"))
            }
            o
        }
        n.isArray -> JsonSchemas.jsonFactory.arrayNode().also { a -> for (x in n.values()) a.add(copy(x)) }
        else -> n
    }
}
