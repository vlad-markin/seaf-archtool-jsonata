package su.seaf.archtool.jsonata.compat

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.File

val mapper: ObjectMapper = ObjectMapper()

/**
 * One case of the jsonata-js 2.2.2 test-suite (compat/jsonata-js/test-suite, format in its TESTSUITE.md).
 * [id] is `<group>/<file>` or `<group>/<file>[<index>]` for files that hold an array of cases.
 * [input] is null when the input is `undefined` (`"dataset": null`, or neither `data` nor `dataset`).
 */
data class SuiteCase(
    val id: String,
    val expr: String,
    val input: JsonNode?,
    val bindings: Map<String, JsonNode>,
    val result: JsonNode?,
    val undefinedResult: Boolean,
    val code: String?,
    val token: String?,
    val timelimit: Long?,
    val depth: Int?,
    val unordered: Boolean,
)

class TestSuite(val root: File) {
    private val groups = File(root, "jsonata-js/test-suite/groups")
    private val datasets = File(root, "jsonata-js/test-suite/datasets")
    private val datasetCache = HashMap<String, JsonNode>()

    fun cases(): List<SuiteCase> = groups.listFiles()!!.filter { it.isDirectory }.sortedBy { it.name }.flatMap { group ->
        group.listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }.flatMap { file ->
            val parsed = mapper.readTree(file)
            val name = "${group.name}/${file.name.removeSuffix(".json")}"
            if (parsed is ArrayNode) parsed.mapIndexed { i, c -> toCase("$name[$i]", group, c) } else listOf(toCase(name, group, parsed))
        }
    }

    private fun toCase(id: String, group: File, c: JsonNode): SuiteCase {
        val expr = c.get("expr-file")?.let { File(group, it.asText()).readText() } ?: c.get("expr").asText()
        val input = when {
            c.has("data") -> c.get("data")
            c.has("dataset") && !c.get("dataset").isNull -> dataset(c.get("dataset").asText())
            else -> null
        }
        val bindings = (c.get("bindings") as? ObjectNode)?.properties()?.associate { it.key to it.value } ?: emptyMap()
        return SuiteCase(
            id = id, expr = expr, input = input, bindings = bindings,
            result = c.get("result"),
            undefinedResult = c.get("undefinedResult")?.asBoolean() ?: false,
            code = c.get("code")?.asText() ?: c.get("error")?.get("code")?.asText(),
            token = c.get("token")?.asText(),
            timelimit = c.get("timelimit")?.asLong(),
            depth = c.get("depth")?.asInt(),
            unordered = c.get("unordered")?.asBoolean() ?: false,
        )
    }

    private fun dataset(name: String): JsonNode = datasetCache.getOrPut(name) { mapper.readTree(File(datasets, "$name.json")) }
}

/** Reference AST snapshots written by compat/node/dump-ast.mjs: case id -> {expr, ast} or {expr, error}. */
class AstSnapshots(root: File) {
    val entries: Map<String, JsonNode> = File(root, "snapshots/ast").listFiles { f -> f.name.endsWith(".json") }!!
        .sortedBy { it.name }
        .flatMap { file ->
            val group = file.name.removeSuffix(".json")
            mapper.readTree(file).properties().map { "$group/${it.key}" to it.value }
        }.toMap()
}

/**
 * Structural form used for every comparison with the reference: numbers become doubles (JS has one number type),
 * object key order is ignored (ObjectNode equality is map equality).
 */
fun normalize(node: JsonNode?): JsonNode? = when {
    node == null -> null
    node.isNumber -> JsonNodeFactory.instance.numberNode(node.asDouble())
    node is ArrayNode -> JsonNodeFactory.instance.arrayNode().also { a -> node.forEach { a.add(normalize(it)) } }
    node is ObjectNode -> JsonNodeFactory.instance.objectNode().also { o -> node.properties().forEach { (k, v) -> o.set<JsonNode>(k, normalize(v)) } }
    else -> node
}
