package su.seaf.archtool.jsonata.compat

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import su.seaf.archtool.jsonata.Jsonata
import su.seaf.archtool.jsonata.value.JsonWriter
import java.io.File

/**
 * Rough timing (not JMH: orders of magnitude only) of compat/bench/cases.json, the same cases and scaling as
 * compat/node/bench.mjs runs on jsonata@2.2.2. `./gradlew :jsonata-compat:bench` (`-PbenchFilter=text` runs the cases whose name contains it).
 */
fun main(args: Array<String>) {
    val root = File(args.getOrElse(0) { "compat" })
    val iterations = args.getOrElse(1) { "10" }.toInt()
    val filter = args.getOrElse(2) { "" }
    val cases = mapper.readTree(File(root, "bench/cases.json"))
    println(String.format("%-36s %10s %10s  %s", "case", "median ms", "min ms", "result"))
    for (c in cases) {
        if (filter.isNotEmpty() && !c.get("name").asText().contains(filter)) continue
        val input = load(root, c)?.let { toValue(it) }
        val expr = Jsonata.compile(c.get("expr").asText())
        val times = ArrayList<Double>()
        var result = ""
        repeat(iterations + 5) { i ->
            val t0 = System.nanoTime()
            val r = expr.evaluate(input)
            val ms = (System.nanoTime() - t0) / 1e6
            if (i >= 5) times += ms
            result = JsonWriter.stringify(r).take(60)
        }
        times.sort()
        println(String.format("%-36s %10.2f %10.2f  %s", c.get("name").asText(), times[times.size / 2], times[0], result))
    }
}

private fun load(root: File, c: JsonNode): JsonNode? {
    val name = c.get("dataset")?.takeIf { !it.isNull }?.asText() ?: return null
    val data = mapper.readTree(File(root, "jsonata-js/test-suite/datasets/$name.json"))
    val scale = c.get("scale")
    if (scale != null && !scale.isNull) {
        val path = scale.get("path").map { it.asText() }
        var parent = data
        for (k in path.dropLast(1)) parent = parent.get(k)
        val items = parent.get(path.last()) as ArrayNode
        val out = mapper.createArrayNode()
        repeat(scale.get("times").asInt()) { items.forEach { out.add(it.deepCopy<JsonNode>()) } }
        (parent as ObjectNode).set<JsonNode>(path.last(), out)
    }
    return data
}
