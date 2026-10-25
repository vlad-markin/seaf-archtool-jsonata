package su.seaf.archtool.jsonata.bench

import su.seaf.archtool.jsonata.json.JsonataJson
import su.seaf.archtool.jsonata.value.ArrayValue
import su.seaf.archtool.jsonata.value.NumberValue
import su.seaf.archtool.jsonata.value.ObjectValue
import su.seaf.archtool.jsonata.value.StringValue
import java.io.File

/** A query of the stand: `jsonata-bench/queries/<name>.jsonata`, sizes from `queries/index.json`. */
class Query(val name: String, val expr: String, val sizes: List<Int>?, val jsSizes: List<Int>?, val note: String?) {
    fun runsAt(size: Int) = sizes == null || size in sizes
    fun jsRunsAt(size: Int) = runsAt(size) && (jsSizes == null || size in jsSizes)
}

object Queries {
    fun load(dir: File): List<Query> {
        val index = JsonataJson.parse(File(dir, "index.json").readText()) as ArrayValue
        return index.map { e ->
            e as ObjectValue
            val name = (e["name"] as StringValue).value
            fun ints(k: String) = (e[k] as? ArrayValue)?.map { (it as NumberValue).value.toInt() }
            Query(name, File(dir, "$name.jsonata").readText().trim(), ints("sizes"), ints("js_sizes"), (e["note"] as? StringValue)?.value)
        }
    }
}
