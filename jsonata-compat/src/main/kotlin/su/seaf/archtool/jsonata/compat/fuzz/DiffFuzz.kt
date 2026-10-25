package su.seaf.archtool.jsonata.compat.fuzz

import com.fasterxml.jackson.databind.JsonNode
import su.seaf.archtool.jsonata.EvaluationOptions
import su.seaf.archtool.jsonata.deviation.Deviations
import su.seaf.archtool.jsonata.Jsonata
import su.seaf.archtool.jsonata.compat.mapper
import su.seaf.archtool.jsonata.compat.toValue
import su.seaf.archtool.jsonata.parser.JsonataException
import su.seaf.archtool.jsonata.value.ArrayValue
import su.seaf.archtool.jsonata.value.FunctionValue
import su.seaf.archtool.jsonata.value.JsonWriter
import su.seaf.archtool.jsonata.value.ObjectValue
import su.seaf.archtool.jsonata.value.StringValue
import su.seaf.archtool.jsonata.value.Value
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The reference side: compat/node/sidecar.mjs (jsonata@2.2.2) in a child process, JSONL in and out. A writer thread
 * feeds the requests while the caller reads the outcomes, so full pipes cannot block either side.
 */
private fun posText(n: JsonNode?): String = if (n == null || n.isNull) "null" else n.asInt().toString()

private fun tokenText(n: JsonNode?): String = when {
    n == null || n.isNull -> "null"
    n.isNumber -> su.seaf.archtool.jsonata.value.JsNumber.toString(n.asDouble())
    else -> n.asText()
}

private fun engineToken(t: Any?): String = when (t) {
    null -> "null"
    is Double -> su.seaf.archtool.jsonata.value.JsNumber.toString(t)
    else -> t.toString()
}

/** `error CODE @pos token` → `error CODE`. */
internal fun codeOnly(outcome: String): String = if (outcome.startsWith("error ")) outcome.substringBefore(" @") else outcome

class Sidecar(nodeDir: File) : AutoCloseable {
    private val process: Process = ProcessBuilder("node", "sidecar.mjs").directory(nodeDir).redirectError(ProcessBuilder.Redirect.INHERIT).start()
    private val out = OutputStreamWriter(process.outputStream, Charsets.UTF_8)
    private val input = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))

    /** Outcomes in the canonical form of [outcomeOf], by case id. */
    fun evaluate(cases: List<FuzzCase>): Map<String, String> {
        val writer = Thread {
            for (c in cases) {
                val req = mapper.createObjectNode().put("id", c.id).put("expr", c.expr)
                if (c.input != null) req.set<JsonNode>("input", c.input)
                out.write(mapper.writeValueAsString(req))
                out.write("\n")
            }
            out.flush()
        }
        writer.start()
        val result = HashMap<String, String>()
        repeat(cases.size) {
            val line = input.readLine() ?: error("sidecar ended")
            val n = mapper.readTree(line)
            result[n.get("id").asText()] = when {
                n.has("json") -> "json " + n.get("json").asText()
                n.has("undefined") -> "undefined"
                n.has("error") -> "error " + n.get("error").asText() + " @" + posText(n.get("position")) + " " + tokenText(n.get("token"))
                else -> "crash " + n.get("crash").asText()
            }
        }
        writer.join()
        return result
    }

    override fun close() {
        out.close()
        process.waitFor(10, TimeUnit.SECONDS)
        process.destroy()
    }

    companion object {
        /** Node and the installed jsonata package are needed; tests skip fuzzing without them. */
        fun available(nodeDir: File): Boolean =
            File(nodeDir, "node_modules/jsonata").isDirectory && runCatching { ProcessBuilder("node", "--version").start().waitFor() == 0 }.getOrDefault(false)
    }
}

/** A difference between the engine and the reference on one case. */
data class Mismatch(val case: FuzzCase, val expected: String, val actual: String, val category: String, val known: String?)

/** A library function used as a value (`$map(x, $string)`, `~> $sum`), not called directly. */
private val functionAsValue = Regex("\\$[a-zA-Z]+\\s*(\\)|,|~>|$)")

/** Runs generated cases on both sides and classifies the differences (phase 8). */
class DiffFuzz(private val nodeDir: File) {
    private val executor = Executors.newVirtualThreadPerTaskExecutor()

    /** The engine outcome: `json <JSON.stringify text>` (functions as "<function>"), `undefined`, `error <code>`. */
    fun outcomeOf(c: FuzzCase): String {
        val hits = java.util.Collections.synchronizedSet(java.util.TreeSet<String>())
        probeHits[c.id] = hits
        val future = executor.submit<String> {
            try {
                val expr = Jsonata.compile(c.expr)
                val options = EvaluationOptions(timeoutMillis = 5000, deviations = { id, _ -> hits.add(id) })
                val r = expr.evaluate(c.input?.let { toValue(it) }, emptyMap(), options)
                if (r == null) "undefined" else "json " + JsonWriter.stringify(markFunctions(r))
            } catch (e: JsonataException) {
                "error " + e.code + " @" + (e.position?.toString() ?: "null") + " " + engineToken(e.token)
            } catch (e: Throwable) {
                "engine-crash ${e.javaClass.simpleName}: ${e.message}"
            }
        }
        return try {
            future.get(20, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            "timeout"
        }
    }

    private fun markFunctions(v: Value): Value = when (v) {
        is FunctionValue -> StringValue.of("<function>")
        is ArrayValue -> if ((0 until v.size).none { v.getOrNull(it).let { x -> x is FunctionValue || x is ArrayValue || x is ObjectValue } }) v
        else ArrayValue(v.size).also { a -> for (i in 0 until v.size) a.add(v.getOrNull(i)?.let { markFunctions(it) } ?: su.seaf.archtool.jsonata.value.NullValue) }
        is ObjectValue -> ObjectValue().also { o -> v.forEach { k, x -> o.put(k, markFunctions(x)) } }
        else -> v
    }

    /** Deviation probes that fired per case id: a difference is explained by a deviation only when its probe fired. */
    val probeHits = HashMap<String, Set<String>>()

    /** Engine time per case id (ms), for the report's slowest cases. */
    val engineMillis = HashMap<String, Double>()
    var referenceSeconds = 0.0

    fun run(cases: List<FuzzCase>, batch: Int = 500): List<Mismatch> {
        val mismatches = ArrayList<Mismatch>()
        Sidecar(nodeDir).use { sidecar ->
            for (chunk in cases.chunked(batch)) {
                val r0 = System.nanoTime()
                val expected = sidecar.evaluate(chunk)
                referenceSeconds += (System.nanoTime() - r0) / 1e9
                for (c in chunk) {
                    val want = expected.getValue(c.id)
                    val t0 = System.nanoTime()
                    val got = outcomeOf(c)
                    engineMillis[c.id] = (System.nanoTime() - t0) / 1e6
                    if (want != got) {
                        val category = if (codeOnly(want) == codeOnly(got)) "error-position-token" else category(want, got)
                        // DEV-10 changes only the position of an error
                        val fired = probeHits[c.id].orEmpty().filter { it != Deviations.POSITION_IN_LAMBDA.id || category == "error-position-token" }
                        val known = if (fired.isNotEmpty()) {
                            fired.joinToString(", ") { id -> "$id: " + (Deviations.byId(id)?.title ?: "deviation") } + " (probe fired)"
                        } else if (category == "error-position-token" && want.substringAfter(" @").substringAfter(' ') == got.substringAfter(" @").substringAfter(' ')) {
                            "reference instability: position kept on a shared function object from an earlier call"
                        } else if (category == "error-position-token" && functionAsValue.containsMatchIn(c.expr)) {
                            "reference instability: token of a library function passed as a value depends on earlier calls in the process"
                        } else {
                            KnownDifferences.explain(c, codeOnly(want), codeOnly(got))
                        }
                        mismatches += Mismatch(c, want, got, category, known)
                    }
                }
            }
        }
        return mismatches
    }

    private fun category(want: String, got: String): String = when {
        got.startsWith("engine-crash") -> "engine-crash"
        got == "timeout" -> "timeout"
        want.startsWith("crash") -> "reference-crash"
        want.startsWith("error") && got.startsWith("error") -> "error-code"
        want.startsWith("error") || got.startsWith("error") -> "error-vs-value"
        want == "undefined" || got == "undefined" -> "undefined-vs-value"
        else -> "value"
    }
}

/**
 * Differences that are documented, not bugs: deviations in docs/deviations.md and reference instabilities. Each
 * returns the reason, or null when the difference is unexplained.
 */
object KnownDifferences {
    private val foldingChars = Regex("[ſKİıßẞ]")

    fun explain(c: FuzzCase, want: String, got: String): String? {
        if (want.startsWith("crash")) return "reference crashes with a JS exception (not a JSONata error)"
        if (c.expr.contains("\$power") && want.startsWith("json ") && got.startsWith("json ") && lastBitsOnly(want, got)) {
            return "NUM-11: last bits of \$power (V8 Math.pow)"
        }
        if (Regex("/[a-z]*i[a-z]*").containsMatchIn(c.expr) && (foldingChars.containsMatchIn(c.expr) || foldingChars.containsMatchIn(c.input?.toString() ?: ""))) {
            return "DEV-06: case folding with the i flag"
        }
        if (Regex("\\(\\s*\\?|,\\s*\\?\\s*[,)]").containsMatchIn(c.expr) && got.startsWith("error T0410") && !want.startsWith("error")) {
            return "DEV-03: partial application of a native function checks its signature"
        }
        if (want.startsWith("error ") && got.startsWith("error ") && Regex("[\\[{][^\\]}]*,").containsMatchIn(c.expr)) {
            return "DEV-09: several failing values of a constructor (jsonata-js reports the first to fail in its async evaluation)"
        }
        if (c.expr.contains("{") && want.replace("[null]", "[]") == got) {
            return "DEV-08: object constructor over an empty array does not push undefined into it"
        }
        return null
    }

    private fun lastBitsOnly(want: String, got: String): Boolean {
        val a = want.removePrefix("json ").toDoubleOrNull() ?: return false
        val b = got.removePrefix("json ").toDoubleOrNull() ?: return false
        return Math.abs(a - b) <= 4 * Math.ulp(a)
    }
}

/** Replay: cases from a JSONL file of `{"id"?, "expr", "input"?}` (e.g. real expressions with their documents). */
fun readCases(file: File): List<FuzzCase> = file.readLines().filter { it.isNotBlank() }.mapIndexed { i, line ->
    val n = mapper.readTree(line)
    FuzzCase(n.get("id")?.asText() ?: "${file.nameWithoutExtension}-$i", n.get("zone")?.asText() ?: "replay", n.get("expr").asText(), n.get("input"))
}

/**
 * `./gradlew :jsonata-compat:fuzz -Pseed=1 -Pcount=20000`: generates cases, compares with jsonata@2.2.2 and writes
 * build/fuzz/report.json (counts by zone and category) and build/fuzz/mismatches.jsonl.
 * `-Pcases=path/to/cases.jsonl` replays a file of cases instead of generating them.
 */
fun main(args: Array<String>) {
    val root = File(args.getOrElse(0) { "compat" })
    val seed = args.getOrElse(1) { "1" }.toLong()
    val count = args.getOrElse(2) { "5000" }.toInt()
    val outDir = File(args.getOrElse(3) { "build/fuzz" }).apply { mkdirs() }
    val replay = args.getOrNull(4)?.takeIf { it.isNotBlank() }?.let { File(it) }
    val cases = if (replay != null) readCases(replay) else Generator(seed).let { gen -> List(count) { gen.next(it, "s$seed") } }
    val t0 = System.nanoTime()
    val fuzz = DiffFuzz(File(root, "node"))
    val mismatches = fuzz.run(cases)
    val seconds = (System.nanoTime() - t0) / 1e9

    val report = mapper.createObjectNode()
    report.put("seed", seed).put("cases", cases.size).put("seconds", seconds)
    if (replay != null) report.put("replay", replay.path)
    report.put("referenceSeconds", fuzz.referenceSeconds)
    report.put("engineSeconds", fuzz.engineMillis.values.sum() / 1000)
    val slow = report.putArray("slowestEngineCases")
    val byId = cases.associateBy { it.id }
    fuzz.engineMillis.entries.sortedByDescending { it.value }.take(10).forEach { (id, ms) ->
        slow.addObject().put("id", id).put("ms", ms).put("expr", byId.getValue(id).expr)
    }
    report.put("mismatches", mismatches.size)
    report.put("unexplained", mismatches.count { it.known == null })
    val byZone = report.putObject("casesByZone")
    cases.groupingBy { it.zone }.eachCount().toSortedMap().forEach { (z, n) -> byZone.put(z, n) }
    val byCategory = report.putObject("unexplainedByCategory")
    mismatches.filter { it.known == null }.groupingBy { it.category }.eachCount().toSortedMap().forEach { (k, n) -> byCategory.put(k, n) }
    val known = report.putObject("explained")
    mismatches.mapNotNull { it.known }.groupingBy { it }.eachCount().toSortedMap().forEach { (k, n) -> known.put(k, n) }
    File(outDir, "report.json").writeText(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report))
    File(outDir, "mismatches.jsonl").bufferedWriter().use { w ->
        for (m in mismatches.sortedBy { it.known != null }) {
            val n = mapper.createObjectNode().put("id", m.case.id).put("zone", m.case.zone).put("category", m.category)
                .put("expr", m.case.expr).put("expected", m.expected).put("actual", m.actual)
            if (m.known != null) n.put("known", m.known)
            if (m.case.input != null) n.set<JsonNode>("input", m.case.input)
            w.write(mapper.writeValueAsString(n))
            w.write("\n")
        }
    }
    println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report))
    System.exit(0)
}
