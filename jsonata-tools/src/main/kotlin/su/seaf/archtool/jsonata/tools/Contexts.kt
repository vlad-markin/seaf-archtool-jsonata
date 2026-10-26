package su.seaf.archtool.jsonata.tools

import su.seaf.archtool.jsonata.json.JsonataJson
import su.seaf.archtool.jsonata.value.ObjectValue
import su.seaf.archtool.jsonata.value.StringValue
import su.seaf.archtool.jsonata.value.Value
import java.io.File

/** One evaluation context of an expression: `$self`, `$params` and the role (role mode v1). */
class EvalContextSpec(val self: Value?, val params: Value?, val role: String?)

/**
 * Contexts of the owner (`contexts.jsonl`, never committed): one `{"place", "self"?, "params"?, "role"?}` per line,
 * several lines per place for several contexts. The same set as the lake gate uses (design §2.1).
 */
class Contexts(private val byPlace: Map<String, List<EvalContextSpec>>) {
    fun of(place: String): List<EvalContextSpec> = byPlace[place].orEmpty()

    val size: Int get() = byPlace.values.sumOf { it.size }

    companion object {
        val NONE = Contexts(emptyMap())

        fun read(file: File): Contexts {
            val map = LinkedHashMap<String, MutableList<EvalContextSpec>>()
            file.readLines().withIndex().filter { it.value.isNotBlank() }.forEach { (i, line) ->
                val o = JsonataJson.parse(line) as? ObjectValue ?: error("${file.name}:${i + 1}: an object is expected")
                val place = (o["place"] as? StringValue)?.value ?: error("${file.name}:${i + 1}: place is missing")
                map.getOrPut(place) { ArrayList() } += EvalContextSpec(o["self"], o["params"], (o["role"] as? StringValue)?.value)
            }
            return Contexts(map)
        }
    }
}
