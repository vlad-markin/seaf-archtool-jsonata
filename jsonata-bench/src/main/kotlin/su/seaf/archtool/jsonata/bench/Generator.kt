package su.seaf.archtool.jsonata.bench

import java.io.File
import java.io.Writer

/**
 * Deterministic "architecture as code" model of the stand (CAP-14): `systems → containers → components` with tags,
 * properties and relations, 1 system = 5 containers × 20 components. A port of `compat-eval/bench/gen.js` of the
 * dashjoin evaluation (the same mulberry32 generator and draw order), so the files are byte for byte the same and the
 * numbers stay comparable with that report.
 */
object Generator {
    private val TYPES = listOf("service", "library", "database", "queue", "ui")
    private val TECH = listOf("java", "kotlin", "go", "node", "python", "postgres", "kafka", "react")
    private val TAGS = listOf("pci", "gdpr", "internal", "external", "legacy", "critical", "batch", "realtime", "beta", "core")
    private val CRIT = listOf("low", "medium", "high")
    private val KINDS = listOf("uses", "calls", "reads", "writes")
    private val WORDS = listOf(
        "handles", "payment", "requests", "from", "customer", "accounts", "billing", "storage",
        "gateway", "routing", "messages", "between", "internal", "services", "reporting", "analytics",
        "catalog", "inventory", "shipping", "notifications", "identity", "session", "tokens", "pricing",
    )
    private val DOMAINS = listOf("billing", "orders", "catalog", "identity", "shipping", "payments", "reporting", "search")

    /** mulberry32 with JS 32-bit integer arithmetic (`Math.imul`, `>>>`). */
    private class Mulberry32(seed: Int) {
        private var a = seed
        fun next(): Double {
            a += 0x6D2B79F5
            var t = (a xor (a ushr 15)) * (1 or a)
            t = (t + (t xor (t ushr 7)) * (61 or t)) xor t
            return ((t xor (t ushr 14)).toLong() and 0xFFFFFFFFL) / 4294967296.0
        }
    }

    /** Writes the model with [components] components as JSON (as `JSON.stringify` of gen.js) to [out]. */
    fun write(components: Int, out: Writer, seed: Int = 42) {
        val rnd = Mulberry32(seed)
        fun <T> pick(list: List<T>): T = list[Math.floor(rnd.next() * list.size).toInt()]
        fun int(lo: Int, hi: Int): Int = lo + Math.floor(rnd.next() * (hi - lo + 1)).toInt()
        fun pad(n: Int, w: Int) = n.toString().padStart(w, '0')
        fun str(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        val systemsCount = (components + 99) / 100
        var c = 0
        out.write("{\"generated\":{\"components\":$components,\"seed\":42},\"systems\":[")
        var s = 0
        while (s < systemsCount && c < components) {
            if (s > 0) out.write(",")
            val sysId = "s" + pad(s, 5)
            out.write("{\"id\":${str(sysId)},\"name\":${str("system-$s-" + pick(DOMAINS))},\"owner\":${str("team-" + int(1, 20))},\"containers\":[")
            var k = 0
            while (k < 5 && c < components) {
                if (k > 0) out.write(",")
                out.write("{\"id\":${str("$sysId.k$k")},\"name\":${str("container-$s-$k")},\"technology\":${str(pick(TECH))},\"components\":[")
                var m = 0
                while (m < 20 && c < components) {
                    if (m > 0) out.write(",")
                    val tags = ArrayList<String>()
                    val nt = int(1, 3)
                    repeat(nt) { val tg = pick(TAGS); if (tg !in tags) tags += tg }
                    val words = ArrayList<String>()
                    val nw = int(5, 12)
                    repeat(nw) { words += pick(WORDS) }
                    val rels = ArrayList<String>()
                    val nr = int(0, 3)
                    repeat(nr) { rels += "{\"target\":${str("c" + pad(int(0, components - 1), 6))},\"kind\":${str(pick(KINDS))}}" }
                    val id = "c" + pad(c, 6)
                    val name = "svc-$c-" + pick(DOMAINS)
                    val type = pick(TYPES)
                    val tech = pick(TECH)
                    val owner = "team-" + int(1, 20)
                    val cost = int(1, 1000)
                    val crit = pick(CRIT)
                    val version = "" + int(1, 5) + "." + int(0, 20) + "." + int(0, 9)
                    out.write(
                        "{\"id\":${str(id)},\"name\":${str(name)},\"type\":${str(type)},\"technology\":${str(tech)}," +
                            "\"tags\":[${tags.joinToString(",") { str(it) }}],\"description\":${str(words.joinToString(" "))}," +
                            "\"properties\":{\"owner\":${str(owner)},\"cost\":$cost,\"criticality\":${str(crit)},\"version\":${str(version)}}," +
                            "\"relations\":[${rels.joinToString(",")}]}",
                    )
                    m++
                    c++
                }
                out.write("]}")
                k++
            }
            out.write("]}")
            s++
        }
        out.write("]}")
    }

    /** The model file for [components] under [dir] (`model-<n>.json`), generated when missing. */
    fun file(dir: File, components: Int): File {
        val f = File(dir, "model-$components.json")
        if (!f.isFile) {
            dir.mkdirs()
            val tmp = File(dir, f.name + ".tmp")
            tmp.bufferedWriter().use { write(components, it) }
            tmp.renameTo(f)
        }
        return f
    }
}
