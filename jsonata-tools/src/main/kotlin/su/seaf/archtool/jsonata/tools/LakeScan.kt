package su.seaf.archtool.jsonata.tools

import su.seaf.archtool.jsonata.value.ObjectValue
import su.seaf.archtool.jsonata.value.StringValue
import su.seaf.archtool.jsonata.value.Value
import java.io.File

/** An expression found in a lake: where it is ([place]: a file path or a manifest path) and its text. */
class FoundExpression(val place: String, val text: String)

/**
 * Finds the JSONata expressions of an architecture lake (design §2.1, step 1.8): `*.jsonata` files under the lake
 * directory, and the string expressions of a manifest export — `functions.*.code` and the `source` of `datasets`,
 * `rules` and other sections whose source is JSONata text rather than a file reference.
 */
object LakeScan {
    /** Sections of the manifest whose entries may carry a JSONata `source`. */
    private val SOURCE_SECTIONS = listOf("datasets", "rules", "aspects", "contexts", "entities")

    fun files(lake: File): List<FoundExpression> =
        lake.walkTopDown().filter { it.isFile && it.name.endsWith(".jsonata") && "node_modules" !in it.path }
            .sortedBy { it.path }
            .map { FoundExpression(it.relativeTo(lake).path.replace(File.separatorChar, '/'), it.readText()) }
            .toList()

    fun manifest(manifest: Value?): List<FoundExpression> {
        val m = manifest as? ObjectValue ?: return emptyList()
        val out = ArrayList<FoundExpression>()
        (m["functions"] as? ObjectValue)?.forEach { id, f ->
            ((f as? ObjectValue)?.get("code") as? StringValue)?.let { out += FoundExpression("functions.$id.code", it.value) }
        }
        for (section in SOURCE_SECTIONS) {
            (m[section] as? ObjectValue)?.forEach { id, entry ->
                val source = (entry as? ObjectValue)?.get("source") as? StringValue ?: return@forEach
                if (looksLikeExpression(source.value)) out += FoundExpression("$section.$id.source", source.value)
            }
        }
        return out
    }

    /** A source that is not a file or URL reference (`x.jsonata`, `x.yaml`, `https://…`) is JSONata text. */
    fun looksLikeExpression(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty() || t.contains('\n')) return t.isNotEmpty()
        if (Regex("^[\\w./-]+\\.(jsonata|ya?ml|json|csv|xlsx?)$", RegexOption.IGNORE_CASE).matches(t)) return false
        if (Regex("^[a-z]+://").containsMatchIn(t)) return false
        return true
    }
}
