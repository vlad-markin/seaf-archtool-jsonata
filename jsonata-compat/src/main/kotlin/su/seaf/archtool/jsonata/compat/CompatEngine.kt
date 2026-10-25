package su.seaf.archtool.jsonata.compat

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import su.seaf.archtool.jsonata.EvaluationOptions
import su.seaf.archtool.jsonata.Jsonata
import su.seaf.archtool.jsonata.parser.AstNode
import su.seaf.archtool.jsonata.value.ArrayValue
import su.seaf.archtool.jsonata.value.BooleanValue
import su.seaf.archtool.jsonata.value.FunctionValue
import su.seaf.archtool.jsonata.value.NullValue
import su.seaf.archtool.jsonata.value.NumberValue
import su.seaf.archtool.jsonata.value.ObjectValue
import su.seaf.archtool.jsonata.value.StringValue
import su.seaf.archtool.jsonata.value.Value
import su.seaf.archtool.jsonata.parser.JsRegex
import su.seaf.archtool.jsonata.parser.JsonataException
import su.seaf.archtool.jsonata.parser.JsonataParser
import su.seaf.archtool.jsonata.parser.Signature

/** A JSONata error as the test-suite sees it: code (`S0201`, `T2001`, ...), position, token. */
class EngineError(val code: String, val position: Int? = null, val token: String? = null, message: String? = null) :
    RuntimeException(message ?: code)

/** Outcome of evaluating one case: a value, `undefined`, or an [EngineError]. */
sealed interface Outcome {
    data class Value(val value: JsonNode) : Outcome
    data object Undefined : Outcome
    data class Failed(val error: EngineError) : Outcome
}

/**
 * The only place where the harness touches jsonata-core. Phase 3 implements [ast] (the jsonata-js `ast()` shape,
 * see compat/node/dump-ast.mjs for the serialization of regex and signature nodes); phase 4 implements [evaluate].
 */
interface CompatEngine {
    fun ast(expr: String): JsonNode
    fun evaluate(case: SuiteCase): Outcome
}

object KotlinEngine : CompatEngine {
    override fun ast(expr: String): JsonNode = try {
        toJson(JsonataParser.ast(expr))
    } catch (e: JsonataException) {
        throw EngineError(e.code, e.position, e.token?.toString(), e.message)
    }
    override fun evaluate(case: SuiteCase): Outcome {
        val expr = try {
            Jsonata.compile(case.expr)
        } catch (e: JsonataException) {
            return Outcome.Failed(EngineError(e.code, e.position, e.token?.toString(), e.message))
        }
        // run-test-suite.js timeboxExpression: depth counted per evaluate() entry and a time limit, both reported as
        // U1001; the jsonata-js 2.2.2 options `stack`/`timeout` count the same way (D1011/D1012)
        val limits = if (case.timelimit != null && case.depth != null) {
            EvaluationOptions(maxDepth = case.depth, timeoutMillis = case.timelimit)
        } else {
            EvaluationOptions.DEFAULT
        }
        return try {
            val result = expr.evaluate(case.input?.let { toValue(it) }, case.bindings.mapValues { toValue(it.value) }, limits)
            if (result == null) Outcome.Undefined else Outcome.Value(fromValue(result))
        } catch (e: JsonataException) {
            val code = if (limits !== EvaluationOptions.DEFAULT && (e.code == "D1011" || e.code == "D1012")) "U1001" else e.code
            Outcome.Failed(EngineError(code, e.position, tokenText(e.token), e.message))
        }
    }

    private fun tokenText(token: Any?): String? = when (token) {
        null -> null
        is Double -> su.seaf.archtool.jsonata.value.JsNumber.toString(token)
        else -> token.toString()
    }
}

/** Jackson tree (test-suite JSON) → engine value, with JS object key order. */
internal fun toValue(node: JsonNode): Value = when {
    node.isNull -> NullValue
    node.isBoolean -> BooleanValue.of(node.booleanValue())
    node.isNumber -> NumberValue.of(node.asDouble())
    node.isTextual -> StringValue.of(node.textValue())
    node.isArray -> ArrayValue(node.size()).also { a -> node.forEach { a.add(toValue(it)) } }
    else -> ObjectValue().also { o -> node.properties().forEach { (k, v) -> o.put(k, toValue(v)) } }
}

/** Engine value → Jackson tree, as `JSON.stringify` sees it (undefined array items become null). */
internal fun fromValue(value: Value?): JsonNode = when (value) {
    null, is NullValue -> json.nullNode()
    is BooleanValue -> json.booleanNode(value.value)
    is NumberValue -> if (value.value.isNaN() || value.value.isInfinite()) json.nullNode() else json.numberNode(value.value)
    is StringValue -> json.textNode(value.value)
    is ArrayValue -> json.arrayNode().also { a -> for (i in 0 until value.size) a.add(fromValue(value.getOrNull(i))) }
    is ObjectValue -> json.objectNode().also { o -> value.forEach { k, v -> o.set<JsonNode>(k, fromValue(v)) } }
    is FunctionValue -> json.textNode("")
}

private val json = JsonNodeFactory.instance

/** The snapshot format of compat/node/dump-ast.mjs: own properties, RegExp and signature functions as markers. */
internal fun toJson(value: Any?): JsonNode = when (value) {
    null -> json.nullNode()
    is AstNode -> json.objectNode().also { o -> value.forEach { (k, v) -> o.set<JsonNode>(k, toJson(v)) } }
    is List<*> -> json.arrayNode().also { a -> value.forEach { a.add(toJson(it)) } }
    is String -> json.textNode(value)
    is Double -> json.numberNode(value)
    is Number -> json.numberNode(value.toDouble())
    is Boolean -> json.booleanNode(value)
    is JsRegex -> json.objectNode().put("@regex", value.source).put("flags", value.canonicalFlags)
    is Signature -> json.objectNode().put("definition", value.definition).also { it.set<JsonNode>("validate", json.objectNode().put("@function", "validate")) }
    else -> json.textNode(value.toString())
}
