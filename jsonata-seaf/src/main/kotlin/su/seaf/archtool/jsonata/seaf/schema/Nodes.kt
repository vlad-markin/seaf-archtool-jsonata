package su.seaf.archtool.jsonata.seaf.schema

import su.seaf.archtool.jsonata.value.ArrayValue
import su.seaf.archtool.jsonata.value.BooleanValue
import su.seaf.archtool.jsonata.value.FunctionValue
import su.seaf.archtool.jsonata.value.NullValue
import su.seaf.archtool.jsonata.value.NumberValue
import su.seaf.archtool.jsonata.value.ObjectValue
import su.seaf.archtool.jsonata.value.StringValue
import su.seaf.archtool.jsonata.value.Value
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.POJONode

/**
 * Engine values as Jackson 3 trees for networknt. A function (an argument of a manifest function) becomes a
 * [POJONode] holding it, which only the `callback`/`params` keywords accept. Undefined inside an array is JSON `null`,
 * an undefined field is left out (as `JSON.stringify`, and as ajv sees a JS value without the key).
 */
internal object Nodes {
    private val factory = JsonNodeFactory.instance

    /**
     * Instances nested deeper are refused: networknt validates recursively, so the depth bounds its stack use
     * (`docs/limits.md`). SEAF documents are a few dozen levels deep.
     */
    const val MAX_DEPTH: Int = 1000

    /** Converts [value]; [poll] is called every 1024 values (cancellation of a large instance). */
    fun of(value: Value?, poll: (() -> Unit)? = null): JsonNode = Converter(poll).conv(value, 0)

    private class Converter(private val poll: (() -> Unit)?) {
        private var count = 0

        fun conv(v: Value?, depth: Int): JsonNode {
            if (poll != null && ++count and 1023 == 0) poll.invoke()
            return when (v) {
                null, NullValue -> factory.nullNode()
                is StringValue -> factory.stringNode(v.value)
                is NumberValue -> number(v.value)
                is BooleanValue -> factory.booleanNode(v.value)
                is ArrayValue -> {
                    if (depth >= MAX_DEPTH) tooDeep()
                    val a = factory.arrayNode(v.size)
                    for (i in 0 until v.size) a.add(conv(v.getOrNull(i), depth + 1))
                    a
                }
                is ObjectValue -> {
                    if (depth >= MAX_DEPTH) tooDeep()
                    val o = factory.objectNode()
                    for ((k, x) in v.entries) o.set(k, conv(x, depth + 1))
                    o
                }
                is FunctionValue -> POJONode(v)
                else -> factory.nullNode()
            }
        }

        private fun tooDeep(): Nothing = throw SchemaInstanceException("The value is nested deeper than $MAX_DEPTH levels")
    }

    /** Integral numbers as long nodes (so `type: integer`, `const` and `enum` compare like JS numbers), others as doubles. */
    private fun number(d: Double): JsonNode =
        if (d == Math.rint(d) && d >= -9.007199254740992E15 && d <= 9.007199254740992E15 && !(d == 0.0 && 1 / d < 0)) factory.numberNode(d.toLong())
        else factory.numberNode(d)

    /** A Jackson tree back as an engine value (schemas of error reports). */
    fun toValue(node: JsonNode?): Value? = when {
        node == null || node.isMissingNode -> null
        node.isNull -> NullValue
        node.isString -> StringValue.of(node.asString())
        node.isNumber -> NumberValue.of(node.asDouble())
        node.isBoolean -> BooleanValue.of(node.asBoolean())
        node.isArray -> ArrayValue(node.size()).also { a -> for (x in node.values()) a.add(toValue(x) ?: NullValue) }
        node.isObject -> ObjectValue().also { o -> for (e in node.properties()) toValue(e.value)?.let { o.put(e.key, it) } }
        node is POJONode && node.pojo is Value -> node.pojo as Value
        else -> null
    }
}
