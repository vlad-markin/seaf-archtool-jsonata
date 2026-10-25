package su.seaf.archtool.jsonata.seaf.schema

import com.networknt.schema.Error
import com.networknt.schema.ExecutionContext
import com.networknt.schema.Schema
import com.networknt.schema.SchemaContext
import com.networknt.schema.SchemaLocation
import com.networknt.schema.keyword.AbstractKeywordValidator
import com.networknt.schema.path.NodePath
import su.seaf.archtool.jsonata.value.NumberValue
import su.seaf.archtool.jsonata.value.StringValue
import su.seaf.archtool.jsonata.value.Value
import tools.jackson.databind.JsonNode

/**
 * Applicators whose errors networknt reports differently from ajv 8 with `allErrors`: ajv keeps the errors of the
 * subschemas and adds one error of the applicator itself (`contains`, `propertyNames`, `anyOf`, `if`). The outcome
 * (valid or not) is the same; these make the list of violations the same too.
 */
internal object AjvApplicators {
    /** Runs [block] with its own error list and returns the errors it produced. */
    fun collect(ctx: ExecutionContext, block: () -> Unit): List<Error> {
        val saved = ctx.errors
        val mine = ArrayList<Error>()
        ctx.errors = mine
        val failFast = ctx.isFailFast
        ctx.isFailFast = false
        try {
            block()
        } finally {
            ctx.errors = saved
            ctx.isFailFast = failFast
        }
        return mine
    }

    fun report(ctx: ExecutionContext, keyword: String, location: SchemaLocation, schemaNode: JsonNode, node: JsonNode, instanceLocation: NodePath, params: Map<String, Value?>, message: String) {
        ctx.addError(
            Error.builder().keyword(keyword).schemaLocation(location).schemaNode(schemaNode).instanceNode(node)
                .instanceLocation(instanceLocation).evaluationPath(ctx.evaluationPath).message(message)
                .details(mapOf(Violations.PARAMS to JsonSchemas.nodeOf(params))).build(),
        )
    }
}

/** `contains`: the errors of every item, then `{minContains: 1}`. */
internal class ContainsValidator(location: SchemaLocation, schemaNode: JsonNode, parent: Schema?, ctx: SchemaContext) :
    AbstractKeywordValidator("contains", schemaNode, location) {
    private val schema: Schema = ctx.newSchema(location, schemaNode, parent)

    override fun validate(executionContext: ExecutionContext, node: JsonNode, rootNode: JsonNode, instanceLocation: NodePath) {
        if (!node.isArray) return
        val errors = ArrayList<Error>()
        for ((i, item) in node.values().withIndex()) {
            val e = AjvApplicators.collect(executionContext) { schema.validate(executionContext, item, rootNode, instanceLocation.append(i)) }
            if (e.isEmpty()) return
            errors += e
        }
        for (e in errors) executionContext.addError(e)
        AjvApplicators.report(executionContext, "contains", schemaLocation, schemaNode, node, instanceLocation, mapOf("minContains" to NumberValue.of(1)), "must contain at least 1 valid item(s)")
    }
}

/** `propertyNames`: the errors of each invalid name at the object, then `{propertyName}` per name. */
internal class PropertyNamesValidator(location: SchemaLocation, schemaNode: JsonNode, parent: Schema?, ctx: SchemaContext) :
    AbstractKeywordValidator("propertyNames", schemaNode, location) {
    private val schema: Schema = ctx.newSchema(location, schemaNode, parent)

    override fun validate(executionContext: ExecutionContext, node: JsonNode, rootNode: JsonNode, instanceLocation: NodePath) {
        if (!node.isObject) return
        for (name in node.propertyNames()) {
            val nameNode = JsonSchemas.jsonFactory.stringNode(name)
            val e = AjvApplicators.collect(executionContext) { schema.validate(executionContext, nameNode, rootNode, instanceLocation) }
            if (e.isEmpty()) continue
            for (x in e) executionContext.addError(x)
            AjvApplicators.report(executionContext, "propertyNames", schemaLocation, schemaNode, node, instanceLocation, mapOf("propertyName" to StringValue.of(name)), "property name must be valid")
        }
    }
}

/** `anyOf`: when no branch is valid, the errors of all branches, then `{}`. */
internal class AnyOfValidator(location: SchemaLocation, schemaNode: JsonNode, parent: Schema?, ctx: SchemaContext) :
    AbstractKeywordValidator("anyOf", schemaNode, location) {
    private val schemas: List<Schema> = schemaNode.values().mapIndexed { i, s -> ctx.newSchema(location.append(i), s, parent) }

    override fun validate(executionContext: ExecutionContext, node: JsonNode, rootNode: JsonNode, instanceLocation: NodePath) {
        val errors = ArrayList<Error>()
        for ((i, s) in schemas.withIndex()) {
            executionContext.evaluationPathAddLast(i)
            val e = try {
                AjvApplicators.collect(executionContext) { s.validate(executionContext, node, rootNode, instanceLocation) }
            } finally {
                executionContext.evaluationPathRemoveLast()
            }
            if (e.isEmpty()) return
            errors += e
        }
        for (e in errors) executionContext.addError(e)
        AjvApplicators.report(executionContext, "anyOf", schemaLocation, schemaNode, node, instanceLocation, emptyMap(), "must match a schema in anyOf")
    }
}

/** `if`/`then`/`else`: the errors of the applied branch, then `{failingKeyword}`. */
internal class IfValidator(location: SchemaLocation, schemaNode: JsonNode, parent: Schema?, ctx: SchemaContext) :
    AbstractKeywordValidator("if", schemaNode, location) {
    private val condition: Schema = ctx.newSchema(location, schemaNode, parent)
    private val parentNode: JsonNode? = parent?.schemaNode
    private val thenSchema: Schema? = parentNode?.get("then")?.let { ctx.newSchema(location.parent().append("then"), it, parent) }
    private val elseSchema: Schema? = parentNode?.get("else")?.let { ctx.newSchema(location.parent().append("else"), it, parent) }

    private fun SchemaLocation.parent(): SchemaLocation = SchemaLocation(absoluteIri, fragment.parent)

    override fun validate(executionContext: ExecutionContext, node: JsonNode, rootNode: JsonNode, instanceLocation: NodePath) {
        val passes = AjvApplicators.collect(executionContext) { condition.validate(executionContext, node, rootNode, instanceLocation) }.isEmpty()
        val branch = if (passes) thenSchema else elseSchema
        val name = if (passes) "then" else "else"
        if (branch == null) return
        val e = AjvApplicators.collect(executionContext) { branch.validate(executionContext, node, rootNode, instanceLocation) }
        if (e.isEmpty()) return
        for (x in e) executionContext.addError(x)
        AjvApplicators.report(executionContext, "if", schemaLocation, schemaNode, node, instanceLocation, mapOf("failingKeyword" to StringValue.of(name)), "must match \"$name\" schema")
    }
}

/** Keywords ajv 8 only annotates in draft-07. */
internal val ANNOTATIONS: List<String> = listOf("contentEncoding", "contentMediaType", "then", "else")


/** `oneOf`: errors of the failing branches and `{passingSchemas}`: null when none passes, the first two passing indices otherwise. */
internal class OneOfValidator(location: SchemaLocation, schemaNode: JsonNode, parent: Schema?, ctx: SchemaContext) :
    AbstractKeywordValidator("oneOf", schemaNode, location) {
    private val schemas: List<Schema> = schemaNode.values().mapIndexed { i, s -> ctx.newSchema(location.append(i), s, parent) }

    override fun validate(executionContext: ExecutionContext, node: JsonNode, rootNode: JsonNode, instanceLocation: NodePath) {
        val errors = ArrayList<Error>()
        var passing = -1
        var second = -1
        for ((i, s) in schemas.withIndex()) {
            executionContext.evaluationPathAddLast(i)
            val e = try {
                AjvApplicators.collect(executionContext) { s.validate(executionContext, node, rootNode, instanceLocation) }
            } finally {
                executionContext.evaluationPathRemoveLast()
            }
            if (e.isNotEmpty()) {
                errors += e
            } else if (passing < 0) {
                passing = i
            } else {
                second = i
                break
            }
        }
        if (passing >= 0 && second < 0) return
        for (e in errors) executionContext.addError(e)
        val params: Value? = if (second < 0) su.seaf.archtool.jsonata.value.NullValue
        else su.seaf.archtool.jsonata.value.ArrayValue.of(NumberValue.of(passing), NumberValue.of(second))
        AjvApplicators.report(executionContext, "oneOf", schemaLocation, schemaNode, node, instanceLocation, mapOf("passingSchemas" to params), "must match exactly one schema in oneOf")
    }
}

/** `dependencies` of draft-07: per missing property `{property, missingProperty, depsCount, deps}`; a schema applies as is. */
internal class DependenciesValidator(location: SchemaLocation, schemaNode: JsonNode, parent: Schema?, ctx: SchemaContext) :
    AbstractKeywordValidator("dependencies", schemaNode, location) {
    private val schemas: Map<String, Schema> = schemaNode.properties().filter { !it.value.isArray }
        .associate { it.key to ctx.newSchema(location.append(it.key), it.value, parent) }

    override fun validate(executionContext: ExecutionContext, node: JsonNode, rootNode: JsonNode, instanceLocation: NodePath) {
        if (!node.isObject) return
        for (e in schemaNode.properties()) {
            if (!node.has(e.key)) continue
            val deps = e.value
            if (deps.isArray) {
                val names = deps.values().map { it.asString() }
                for (missing in names) if (!node.has(missing)) {
                    AjvApplicators.report(
                        executionContext, "dependencies", schemaLocation, schemaNode, node, instanceLocation,
                        mapOf(
                            "property" to StringValue.of(e.key), "missingProperty" to StringValue.of(missing),
                            "depsCount" to NumberValue.of(names.size), "deps" to StringValue.of(names.joinToString(", ")),
                        ),
                        "must have property $missing when property ${e.key} is present",
                    )
                }
            } else {
                schemas[e.key]?.validate(executionContext, node, rootNode, instanceLocation)
            }
        }
    }
}

/** `uniqueItems` as ajv: one error with the first duplicate pair it finds. */
internal class UniqueItemsValidator(location: SchemaLocation, schemaNode: JsonNode, parent: Schema?) :
    AbstractKeywordValidator("uniqueItems", schemaNode, location) {
    /** ajv takes a map of scalars when `items.type` names only scalar types. */
    private val scalarItems: Boolean = parent?.schemaNode?.get("items")?.takeIf { it.isObject }?.get("type")?.let { t ->
        val types = if (t.isArray) t.values().map { it.asString() } else listOf(t.asString())
        types.isNotEmpty() && types.none { it == "object" || it == "array" }
    } ?: false

    override fun validate(executionContext: ExecutionContext, node: JsonNode, rootNode: JsonNode, instanceLocation: NodePath) {
        if (!schemaNode.asBoolean() || !node.isArray) return
        val items = node.values().toList()
        var pair: Pair<Int, Int>? = null
        if (scalarItems) {
            val indices = HashMap<String, Int>()
            for (i in items.indices.reversed()) {
                val item = items[i]
                if (item.isObject || item.isArray) continue
                val key = (if (item.isString) "\"" else "") + item.toString()
                val j = indices[key]
                if (j != null) {
                    pair = i to j
                    break
                }
                indices[key] = i
            }
        } else {
            outer@ for (i in items.indices.reversed()) for (j in (i - 1) downTo 0) {
                if (items[i] == items[j]) {
                    pair = i to j
                    break@outer
                }
            }
        }
        val (i, j) = pair ?: return
        AjvApplicators.report(executionContext, "uniqueItems", schemaLocation, schemaNode, node, instanceLocation, mapOf("i" to NumberValue.of(i), "j" to NumberValue.of(j)), "must have only unique items in the array")
    }
}

/** `multipleOf` in JS floating point, as ajv: `data / m` must be an integer (so 0.07 is not a multiple of 0.01). */
internal class MultipleOfValidator(location: SchemaLocation, schemaNode: JsonNode) :
    AbstractKeywordValidator("multipleOf", schemaNode, location) {
    private val m = schemaNode.asDouble()

    override fun validate(executionContext: ExecutionContext, node: JsonNode, rootNode: JsonNode, instanceLocation: NodePath) {
        if (!node.isNumber) return
        val div = node.asDouble() / m
        // `div !== parseInt(div)`: parseInt reads the shortest JS text, which has an exponent from 1e21 on
        val integral = !div.isNaN() && !div.isInfinite() && Math.abs(div) < 1e21 && div == (if (div < 0) Math.ceil(div) else Math.floor(div))
        if (integral) return
        AjvApplicators.report(executionContext, "multipleOf", schemaLocation, schemaNode, node, instanceLocation, mapOf("multipleOf" to Nodes.toValue(schemaNode)), "must be multiple of ${schemaNode}")
    }
}
