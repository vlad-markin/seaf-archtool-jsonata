package su.seaf.archtool.jsonata.bench

import su.seaf.archtool.jsonata.json.JsonataJson
import su.seaf.archtool.jsonata.value.NumberValue
import su.seaf.archtool.jsonata.value.ObjectValue
import su.seaf.archtool.jsonata.value.StringValue
import su.seaf.archtool.jsonata.value.Value
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The performance stand (story 6, CAP-14; method in `docs/perf-method.md`): generates the models, runs one process per
 * engine × size (the engine through [StandRunner], jsonata-js 2.2.2 through `compat/node/stand.mjs`), compares the
 * results and writes `jsonata-bench/build/stand/summary.md`. With `--heap=true` it also finds the smallest heap each
 * query passes with (`-Xmx` / `--max-old-space-size` bisection).
 *
 * `./gradlew :jsonata-bench:stand -Psizes=10000 -Pqueries=path_filter,sort_fn -Pengines=jvm,js -Pheap=true`
 */
fun main(args: Array<String>) {
    val a = args.associate { it.removePrefix("--").substringBefore('=') to it.substringAfter('=', "") }
    val root = File(".").absoluteFile
    val dir = File(root, "jsonata-bench/build/stand").also { it.mkdirs() }
    val queriesDir = File(root, "jsonata-bench/queries")
    val sizes = (a["sizes"] ?: "10000,50000,200000").split(',').map { it.trim().toInt() }
    val engines = (a["engines"] ?: "jvm,js").split(',').map { it.trim() }.toSet()
    val only = a["queries"]
    val xmx = a["xmx"] ?: "3g"
    val queries = Queries.load(queriesDir).filter { only == null || it.name in only.split(',') }
    val results = File(dir, "results.jsonl").also { it.delete() }
    val dumps = File(dir, "results").also { d -> d.deleteRecursively(); d.mkdirs() }
    val cp = System.getProperty("java.class.path")
    val java = File(System.getProperty("java.home"), "bin/java").path
    val heapRows = ArrayList<String>()
    for (size in sizes) {
        val data = Generator.file(dir, size)
        val common = listOf("--data=${data.path}", "--size=$size", "--queries=${queriesDir.path}", "--out=${results.path}", "--dump=${dumps.path}") +
            listOfNotNull(only?.let { "--only=$it" }, a["warmup"]?.let { "--warmup=$it" }, a["iters"]?.let { "--iters=$it" })
        if ("jvm" in engines) {
            run(listOf(java, "-Xms$xmx", "-Xmx$xmx", "-XX:+UseG1GC", "-Xss16m", "-cp", cp, "su.seaf.archtool.jsonata.bench.StandRunner") + common, root)
        }
        if ("js" in engines) {
            val jsOnly = queries.filter { it.jsRunsAt(size) }.joinToString(",") { it.name }
            if (jsOnly.isNotEmpty()) {
                run(listOf("node", "--expose-gc", "--stack-size=65500", "--max-old-space-size=${mb(xmx)}", "compat/node/stand.mjs") + common.filter { !it.startsWith("--only=") } + "--only=$jsOnly", root)
            }
        }
        if (a["heap"] == "true") {
            fun jvmMin(name: String) = bisect(mb(xmx)) { m -> run(listOf(java, "-Xmx${m}m", "-XX:+UseG1GC", "-Xss16m", "-cp", cp, "su.seaf.archtool.jsonata.bench.StandRunner", "--data=${data.path}", "--size=$size", "--queries=${queriesDir.path}", "--once=$name"), root, quiet = true) == 0 }
            fun jsMin(name: String) = bisect(mb(xmx)) { m -> run(listOf("node", "--stack-size=65500", "--max-old-space-size=$m", "compat/node/stand.mjs", "--data=${data.path}", "--size=$size", "--queries=${queriesDir.path}", "--once=$name"), root, quiet = true) == 0 }
            // both sides parse from a string: the minimum of parsing alone is the floor of every query (review RV3-21)
            val parse = HeapMin(if ("jvm" in engines) jvmMin(StandRunner.PARSE_ONLY) else null, if ("js" in engines) jsMin(StandRunner.PARSE_ONLY) else null)
            heapRows += heapRow(PARSE_ROW, size, parse, null)
            for (q in queries.filter { it.runsAt(size) }) {
                val min = HeapMin(if ("jvm" in engines) jvmMin(q.name) else null, if ("js" in engines && q.jsRunsAt(size)) jsMin(q.name) else null)
                heapRows += heapRow(q.name, size, min, parse)
                System.err.println("[heap] ${q.name} n=$size jvm=${min.jvm}MB js=${min.js}MB")
            }
        }
    }
    File(dir, "summary.md").writeText(summary(results, dumps, queries, sizes, heapRows))
    System.err.println("summary: ${File(dir, "summary.md").path}")
}

/** The smallest heaps (MB) of the two engines for one query, null when not run or failing at the maximum. */
internal class HeapMin(val jvm: Int?, val js: Int?)

internal const val PARSE_ROW = "(только разбор)"

/** Whether [query] needs no more than parsing: equal to [parse] within the bisection resolution (5 %, at least 4 MB). */
internal fun atParseFloor(query: Int?, parse: Int?): Boolean =
    query != null && parse != null && kotlin.math.abs(query - parse) <= maxOf(4, parse / 20)

/**
 * A row of the heap table; a minimum at the parse floor is marked "= разбор": that engine's minimum is set by parsing
 * the model, not by the query, and says nothing about the query.
 */
internal fun heapRow(name: String, size: Int, min: HeapMin, parse: HeapMin?): String {
    fun cell(v: Int?, floor: Int?) = when {
        v == null -> "—"
        parse != null && atParseFloor(v, floor) -> "$v (= разбор)"
        else -> "$v"
    }
    val ratio = if (min.jvm != null && min.js != null) "%.2f".format(min.jvm.toDouble() / min.js) else "—"
    return "| $name | $size | ${cell(min.jvm, parse?.jvm)} | ${cell(min.js, parse?.js)} | $ratio |"
}

private fun mb(xmx: String): Int = when {
    xmx.endsWith("g", true) -> xmx.dropLast(1).toInt() * 1024
    xmx.endsWith("m", true) -> xmx.dropLast(1).toInt()
    else -> (xmx.toLong() / 1048576).toInt()
}

/** The smallest heap in MB (to 5 %, at least 4 MB) with which [passes] succeeds, or null when it fails at [max]. */
private fun bisect(max: Int, passes: (Int) -> Boolean): Int? {
    if (!passes(max)) return null
    var lo = 4
    var hi = max
    while (hi - lo > maxOf(4, hi / 20)) {
        val mid = (lo + hi) / 2
        if (passes(mid)) hi = mid else lo = mid
    }
    return hi
}

private fun run(cmd: List<String>, dir: File, quiet: Boolean = false): Int {
    val pb = ProcessBuilder(cmd).directory(dir)
    if (quiet) pb.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD) else pb.inheritIO()
    val p = pb.start()
    if (!p.waitFor(60, TimeUnit.MINUTES)) {
        p.destroyForcibly()
        return -1
    }
    return p.exitValue()
}

private fun summary(results: File, dumps: File, queries: List<Query>, sizes: List<Int>, heapRows: List<String>): String {
    val recs = if (results.isFile) results.readLines().filter { it.isNotBlank() }.map { JsonataJson.parse(it) as ObjectValue } else emptyList()
    fun rec(engine: String, q: String, size: Int) = recs.lastOrNull { (it["engine"] as StringValue).value.startsWith(engine) && (it["query"] as StringValue).value == q && (it["size"] as NumberValue).value.toInt() == size }
    fun num(r: ObjectValue?, k: String) = (r?.get(k) as? NumberValue)?.value
    fun f(d: Double?) = d?.let { "%.2f".format(it) } ?: "—"
    val sb = StringBuilder()
    sb.append("# Стенд: движок против jsonata-js 2.2.2\n\n")
    val any = recs.firstOrNull { (it["engine"] as StringValue).value == "jsonata-kotlin" }
    sb.append("JVM ${(any?.get("jvm") as? StringValue)?.value ?: "?"}, сборщик ${(any?.get("gc") as? StringValue)?.value ?: "?"}, куча ${num(any, "xmx_mb")?.toInt() ?: "?"} МБ. Числа облака — ориентир; приёмка — на выделенной машине (`docs/perf-method.md`).\n\n")
    sb.append("| запрос | размер | движок, мс (медиана / p95) | jsonata-js, мс | ускорение | аллокации движка, МБ | пик кучи, МБ | результат | load avg |\n|---|---:|---:|---:|---:|---:|---:|---|---:|\n")
    for (size in sizes) for (q in queries) {
        if (!q.runsAt(size)) continue
        val j = rec("jsonata-kotlin", q.name, size)
        val s = rec("jsonata-js", q.name, size)
        val jm = num(j, "eval_ms_median")
        val sm = num(s, "eval_ms_median")
        val speed = if (jm != null && sm != null && jm > 0) "%.1f×".format(sm / jm) else "—"
        val jsNote = when {
            !q.jsRunsAt(size) -> "не запускался (${q.note ?: "квадратичная сортировка jsonata-js"})"
            s == null -> "JS не завершился"
            (s["error"] as? StringValue) != null -> "JS: " + (s["error"] as StringValue).value
            else -> "${f(sm)} / ${f(num(s, "eval_ms_p95"))}"
        }
        val same = compare(File(dumps, "${q.name}-$size.jvm.json"), File(dumps, "${q.name}-$size.js.json"))
        sb.append("| ${q.name} | $size | ${f(jm)} / ${f(num(j, "eval_ms_p95"))} | $jsNote | $speed | ${f(num(j, "alloc_mb_median"))} | ${f(num(j, "heap_peak_mb"))} | $same | ${f(num(j, "loadavg1"))} |\n")
    }
    if (heapRows.isNotEmpty()) {
        sb.append("\n## Минимальная куча, при которой запрос проходит (бисекция, точность 5 %)\n\nОба движка читают модель из строки. «= разбор» — минимум равен минимуму одного разбора: его задаёт разбор, а не запрос.\n\n| запрос | размер | JVM `-Xmx`, МБ | V8 `--max-old-space-size`, МБ | JVM / V8 |\n|---|---:|---:|---:|---:|\n")
        heapRows.forEach { sb.append(it).append('\n') }
    }
    return sb.toString()
}

/** Results compared as values (key order aside: the order is checked separately by the test-suite). */
private fun compare(jvm: File, js: File): String {
    if (!jvm.isFile) return "—"
    if (!js.isFile) return "нет результата JS"
    val a: Value = JsonataJson.parse(jvm.readText())
    val b: Value = JsonataJson.parse(js.readText())
    return if (a == b) (if (jvm.readText() == js.readText()) "равны" else "равны (порядок ключей другой)") else "**различаются**"
}
