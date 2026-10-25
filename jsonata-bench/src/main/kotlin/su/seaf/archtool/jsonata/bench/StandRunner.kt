package su.seaf.archtool.jsonata.bench

import su.seaf.archtool.jsonata.Jsonata
import su.seaf.archtool.jsonata.json.JsonataJson
import su.seaf.archtool.jsonata.parser.JsonataException
import su.seaf.archtool.jsonata.value.JsonWriter
import su.seaf.archtool.jsonata.value.Value
import java.io.File
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType

/**
 * One process of the stand for one model size (the JVM side; `compat/node/stand.mjs` is the jsonata-js side with the
 * same arguments and record format). Every query: median of 200 compilations, warm-up, then measured iterations with
 * a GC before each (outside the measurement); median, p95, allocations per evaluation (`ThreadMXBean`, all live threads:
 * deep evaluations run on stack segment threads), the peak of the heap pools during the query, the load average. The first result is written for comparison.
 *
 * `--once=<query>` evaluates one query once and exits 0, or 3 on OutOfMemoryError: the heap bisection runs it.
 */
object StandRunner {
    @JvmStatic
    fun main(args: Array<String>) {
        val a = args.associate { it.removePrefix("--").substringBefore('=') to it.substringAfter('=', "") }
        val data = File(a.getValue("data"))
        val size = a.getValue("size").toInt()
        val queries = Queries.load(File(a.getValue("queries")))
        val input = JsonataJson.parse(data.readText())
        a["once"]?.let { name ->
            val q = queries.first { it.name == name }
            try {
                Jsonata.compile(q.expr).evaluate(input)
            } catch (_: OutOfMemoryError) {
                System.exit(3)
            }
            System.exit(0)
        }
        val out = File(a.getValue("out"))
        val dump = a["dump"]?.let { File(it).also { d -> d.mkdirs() } }
        val only = a["only"]?.split(',')?.filter { it.isNotEmpty() }?.toSet()
        val warmup = a["warmup"]?.toInt() ?: when { size <= 10_000 -> 10; size <= 50_000 -> 5; else -> 3 }
        val iters = a["iters"]?.toInt() ?: when { size <= 10_000 -> 20; size <= 50_000 -> 10; else -> 5 }
        // CAP-12: the price of debugging — evaluation without a listener, with an empty one, with the profiler
        val mode = a["mode"] ?: "plain"
        val evaluate: (Jsonata) -> Value? = if (mode == "plain") { e -> e.evaluate(input) } else Modes.evaluator(mode, input)
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val pools = ManagementFactory.getMemoryPoolMXBeans().filter { it.type == MemoryType.HEAP }
        val os = ManagementFactory.getOperatingSystemMXBean()
        val gc = ManagementFactory.getGarbageCollectorMXBeans().joinToString("+") { it.name }
        System.gc()
        val dataHeap = pools.sumOf { it.usage.used }
        System.err.println("[jvm] size=$size dataHeap=${dataHeap / 1048576}MB gc=$gc")
        for (q in queries) {
            if (!q.runsAt(size) || (only != null && q.name !in only)) continue
            val compile = DoubleArray(200)
            var expr: Jsonata? = null
            for (i in compile.indices) {
                val s = System.nanoTime()
                expr = Jsonata.compile(q.expr)
                compile[i] = (System.nanoTime() - s) / 1e6
            }
            compile.sort()
            var result: Value? = null
            var error: String? = null
            val times = ArrayList<Double>()
            val allocs = ArrayList<Long>()
            var warmDone = 0
            var warmSpent = 0.0
            var measSpent = 0.0
            var peak = 0L
            try {
                var i = 0
                while (true) {
                    val measuring = warmDone >= warmup || (warmDone >= 1 && warmSpent > 15_000)
                    if (measuring && (times.size >= iters || (times.size >= 3 && measSpent > 20_000))) break
                    System.gc()
                    pools.forEach { it.resetPeakUsage() }
                    val a0 = threads.totalThreadAllocatedBytes
                    val s = System.nanoTime()
                    val r = evaluate(expr!!)
                    val e = (System.nanoTime() - s) / 1e6
                    val a1 = threads.totalThreadAllocatedBytes
                    peak = maxOf(peak, pools.sumOf { it.peakUsage.used })
                    if (i == 0) result = r
                    if (!measuring) {
                        warmDone++
                        warmSpent += e
                    } else {
                        measSpent += e
                        times += e
                        allocs += a1 - a0
                    }
                    i++
                }
            } catch (e: JsonataException) {
                error = e.code + " " + e.message
            } catch (e: OutOfMemoryError) {
                error = "OutOfMemoryError"
            }
            dump?.let { File(it, "${q.name}-$size.jvm.json").writeText(if (error != null) "{\"__error\":${JsonWriter.stringify(su.seaf.archtool.jsonata.value.StringValue.of(error))}}" else JsonWriter.stringify(result ?: su.seaf.archtool.jsonata.value.NullValue)) }
            val ts = times.sorted()
            val rec = linkedMapOf<String, Any?>(
                "engine" to "jsonata-kotlin", "mode" to mode, "jvm" to System.getProperty("java.version"), "gc" to gc, "xmx_mb" to Runtime.getRuntime().maxMemory() / 1048576,
                "query" to q.name, "size" to size, "warmup_done" to warmDone, "iters_done" to times.size,
                "compile_ms_median" to median(compile.toList()), "error" to error, "loadavg1" to os.systemLoadAverage,
                "eval_ms_median" to ts.takeIf { it.isNotEmpty() }?.let { median(it) }, "eval_ms_p95" to ts.takeIf { it.isNotEmpty() }?.let { pct(it, 0.95) },
                "eval_ms_min" to ts.firstOrNull(), "eval_ms_max" to ts.lastOrNull(),
                "alloc_mb_median" to allocs.takeIf { it.isNotEmpty() }?.let { median(it.map { x -> x.toDouble() }) / 1048576 },
                "heap_peak_mb" to peak / 1048576.0, "data_heap_mb" to dataHeap / 1048576.0,
                "times_ms" to times,
            )
            out.appendText(json(rec) + "\n")
            System.err.println("[jvm] ${q.name.padEnd(18)} n=$size median=${"%.2f".format(rec["eval_ms_median"] ?: Double.NaN)}ms p95=${"%.2f".format(rec["eval_ms_p95"] ?: Double.NaN)}ms${error?.let { " ERROR $it" } ?: ""}")
        }
    }

    fun median(s: List<Double>): Double = if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    fun pct(s: List<Double>, p: Double): Double = s[minOf(s.size - 1, Math.ceil(p * s.size).toInt() - 1)]

    /** A flat record as JSON (numbers, strings, null, lists of numbers). */
    fun json(rec: Map<String, Any?>): String = rec.entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":" + jsonValue(v) }

    private fun jsonValue(v: Any?): String = when (v) {
        null -> "null"
        is String -> JsonWriter.stringify(su.seaf.archtool.jsonata.value.StringValue.of(v))
        is Double -> if (v.isNaN() || v.isInfinite()) "null" else JsonWriter.stringify(su.seaf.archtool.jsonata.value.NumberValue.of(v))
        is Number -> v.toString()
        is List<*> -> v.joinToString(",", "[", "]") { jsonValue(it) }
        else -> jsonValue(v.toString())
    }
}
