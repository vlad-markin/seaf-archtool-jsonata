package su.seaf.archtool.jsonata.seaf.schema

import com.networknt.schema.Error
import com.networknt.schema.path.NodePath
import su.seaf.archtool.jsonata.value.ArrayValue
import su.seaf.archtool.jsonata.value.NullValue
import su.seaf.archtool.jsonata.value.NumberValue
import su.seaf.archtool.jsonata.value.ObjectValue
import su.seaf.archtool.jsonata.value.StringValue
import su.seaf.archtool.jsonata.value.Value
import tools.jackson.databind.JsonNode

/** networknt errors as ajv 8 error objects: `instancePath`, `schemaPath`, `keyword` and `params` as ajv builds them. */
internal object Violations {
    const val PARAMS: String = "ajv.params"

    fun of(e: Error, mode: SchemaMode): SchemaViolation {
        val keyword = when (val k = e.keyword ?: "unknown") {
            "false" -> "false schema"
            else -> k
        }
        val schemaPath = "#" + pointer(e.schemaLocation?.fragment)
        val params = (e.details?.get(PARAMS) as? ObjectValue) ?: params(keyword, e)
        return SchemaViolation(pointer(e.instanceLocation), schemaPath, keyword, params, e.message ?: "")
    }

    /** A JSON pointer as ajv writes `instancePath` (`""` for the root, `~0`/`~1` escapes). */
    fun pointer(path: NodePath?): String {
        if (path == null) return ""
        val sb = StringBuilder()
        for (i in 0 until path.nameCount) {
            sb.append('/')
            sb.append(path.getElement(i).toString().replace("~", "~0").replace("/", "~1"))
        }
        return sb.toString()
    }

    private fun obj(vararg pairs: Pair<String, Value?>): ObjectValue = ObjectValue().also { o -> for ((k, v) in pairs) if (v != null) o.put(k, v) }

    private fun num(n: JsonNode?): Value? = n?.let { Nodes.toValue(it) }

    private fun params(keyword: String, e: Error): ObjectValue {
        val schema = e.schemaNode
        val instance = e.instanceNode
        return when (keyword) {
            "type" -> obj("type" to (num(schema) ?: StringValue.of("")))
            "minimum" -> obj("comparison" to StringValue.of(">="), "limit" to num(schema))
            "maximum" -> obj("comparison" to StringValue.of("<="), "limit" to num(schema))
            "exclusiveMinimum" -> obj("comparison" to StringValue.of(">"), "limit" to num(schema))
            "exclusiveMaximum" -> obj("comparison" to StringValue.of("<"), "limit" to num(schema))
            "multipleOf" -> obj("multipleOf" to num(schema))
            "minLength", "maxLength", "minItems", "maxItems", "minProperties", "maxProperties" -> obj("limit" to num(schema))
            "pattern" -> obj("pattern" to num(schema))
            "required" -> obj("missingProperty" to StringValue.of(e.property ?: e.arguments?.firstOrNull()?.toString() ?: ""))
            "additionalProperties" -> obj("additionalProperty" to StringValue.of(e.property ?: e.arguments?.firstOrNull()?.toString() ?: ""))
            "additionalItems", "items" -> obj("limit" to NumberValue.of(e.index?.toDouble() ?: 0.0))
            "enum" -> obj("allowedValues" to num(schema))
            "const" -> obj("allowedValue" to (num(schema) ?: NullValue))
            "uniqueItems" -> uniqueItems(instance)
            "contains" -> obj("minContains" to NumberValue.of(1))
            "propertyNames" -> obj("propertyName" to StringValue.of(e.arguments?.firstOrNull()?.toString() ?: ""))
            "oneOf" -> obj("passingSchemas" to NullValue)
            else -> ObjectValue()
        }
    }

    /** ajv's uniqueItems reports the first pair found scanning from the end: `{i, j}` with `j < i`. */
    private fun uniqueItems(instance: JsonNode?): ObjectValue {
        if (instance == null || !instance.isArray) return ObjectValue()
        val items = instance.values().toList()
        for (i in items.indices.reversed()) for (j in (i - 1) downTo 0) {
            if (items[i] == items[j]) return obj("i" to NumberValue.of(i), "j" to NumberValue.of(j))
        }
        return ObjectValue()
    }
}
