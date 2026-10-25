package su.seaf.archtool.jsonata.compat

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import su.seaf.archtool.jsonata.parser.AstNode
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
 * Until then the methods throw [NotImplementedError], which the harness reports as "not implemented".
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
    override fun evaluate(case: SuiteCase): Outcome = throw NotImplementedError("evaluator: phase 4")
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
