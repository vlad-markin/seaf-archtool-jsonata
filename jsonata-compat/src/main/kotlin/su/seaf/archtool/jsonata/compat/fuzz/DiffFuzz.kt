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

class Sidecar(private val nodeDir: File, private val caseTimeoutMillis: Long = 10_000) : AutoCloseable {
    private class Proc(val process: Process) {
        val out = OutputStreamWriter(process.outputStream, Charsets.UTF_8)
        /** Lines of the sidecar, read by a daemon thread; [EOF] when it ends. */
        val lines = java.util.concurrent.LinkedBlockingQueue<String>()
        init {
            Thread {
                BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { r ->
                    while (true) lines.put(r.readLine() ?: break)
                }
                lines.put(EOF)
            }.apply { isDaemon = true }.start()
        }
    }

    private var proc = start()

    private fun start() = Proc(ProcessBuilder("node", "sidecar.mjs").directory(nodeDir).redirectError(ProcessBuilder.Redirect.INHERIT).start())

    private fun restart() {
        proc.process.destroyForcibly()
        proc = start()
    }

    /**
     * Outcomes in the canonical form of [outcomeOf], by case id. A case the reference does not finish within
     * [caseTimeoutMillis] (an endless recursion of jsonata-js) gets `reference-timeout`; the sidecar is restarted and
     * the run goes on with the next case (review RV2-5).
     */
    fun evaluate(cases: List<FuzzCase>): Map<String, String> {
        val result = HashMap<String, String>()
        var pending = cases
        while (pending.isNotEmpty()) {
            val p = proc
            val batch = pending
            val writer = Thread {
                try {
                    for (c in batch) {
                        val req = mapper.createObjectNode().put("id", c.id).put("expr", c.expr)
                        if (c.input != null) req.set<JsonNode>("input", c.input)
                        if (c.bindings != null) req.set<JsonNode>("bindings", c.bindings)
                        p.out.write(mapper.writeValueAsString(req))
                        p.out.write("\n")
                    }
                    p.out.flush()
                } catch (_: java.io.IOException) {
                    // the sidecar was restarted after a timeout
                }
            }.apply { isDaemon = true }
            writer.start()
            var next: List<FuzzCase> = emptyList()
            for ((i, c) in batch.withIndex()) {
                val line = p.lines.poll(caseTimeoutMillis, TimeUnit.MILLISECONDS)
                if (line == null || line === EOF) {
                    result[c.id] = if (line == null) "reference-timeout" else "crash sidecar ended"
                    restart()
                    next = batch.drop(i + 1)
                    break
                }
                val n = mapper.readTree(line)
                result[n.get("id").asText()] = when {
                    n.has("json") -> "json " + n.get("json").asText()
                    n.has("undefined") -> "undefined"
                    n.has("error") -> "error " + n.get("error").asText() + " @" + posText(n.get("position")) + " " + tokenText(n.get("token"))
                    else -> "crash " + n.get("crash").asText()
                }
            }
            pending = next
        }
        return result
    }

    override fun close() {
        runCatching { proc.out.close() }
        proc.process.waitFor(10, TimeUnit.SECONDS)
        proc.process.destroy()
    }

    companion object {
        private const val EOF = "\u0000EOF"

        /** The Node major version of compat/node/package.json (`engines`). */
        fun requiredNodeMajor(nodeDir: File): Int? =
            Regex(">=\\s*(\\d+)").find(mapper.readTree(File(nodeDir, "package.json")).path("engines").path("node").asText())?.groupValues?.get(1)?.toInt()

        /** The major version of `node` on the path, or null without Node. */
        fun nodeMajor(): Int? = runCatching {
            val p = ProcessBuilder("node", "--version").start()
            val v = p.inputStream.bufferedReader().readText().trim()
            if (p.waitFor() == 0) v.removePrefix("v").substringBefore('.').toInt() else null
        }.getOrNull()

        /** Why fuzzing cannot run here, or null when it can: the installed jsonata package and the Node major of package.json (review RV2-7). */
        fun unavailable(nodeDir: File): String? {
            if (!File(nodeDir, "node_modules/jsonata").isDirectory) return "compat/node/node_modules is missing (npm ci)"
            return nodeVersionProblem(nodeMajor(), requiredNodeMajor(nodeDir))
        }

        /** Why Node [have] cannot be the reference when [need] is required (null: no requirement), or null when it can. */
        fun nodeVersionProblem(have: Int?, need: Int?): String? = when {
            have == null -> "node is not on the path"
            need != null && have != need -> "Node $need is needed (compat/node/package.json), found Node $have"
            else -> null
        }

        fun available(nodeDir: File): Boolean = unavailable(nodeDir) == null

        /** One case in a fresh sidecar process: what jsonata-js gives without the history of earlier calls. */
        fun fresh(nodeDir: File, c: FuzzCase): String = Sidecar(nodeDir).use { it.evaluate(listOf(c)).getValue(c.id) }
    }
}

/** A difference between the engine and the reference on one case. */
data class Mismatch(val case: FuzzCase, val expected: String, val actual: String, val category: String, val known: String?)

/**
 * The gate of a fuzzing run (review RV3-30): no unexplained difference, and cases where the reference did not finish
 * (`reference-timeout`, never explained) within their own threshold, one per thousand cases by default.
 */
object FuzzGate {
    fun referenceTimeouts(mismatches: List<Mismatch>): Int = mismatches.count { it.category == "reference-timeout" }

    fun unexplained(mismatches: List<Mismatch>): List<Mismatch> = mismatches.filter { it.known == null && it.category != "reference-timeout" }

    fun problems(mismatches: List<Mismatch>, cases: Int, maxReferenceTimeouts: Int = maxOf(1, cases / 1000)): List<String> {
        val out = ArrayList<String>()
        unexplained(mismatches).size.let { if (it > 0) out += "$it unexplained differences" }
        referenceTimeouts(mismatches).let { if (it > maxReferenceTimeouts) out += "$it cases where the reference did not finish (at most $maxReferenceTimeouts)" }
        return out
    }
}

/** Runs generated cases on both sides and classifies the differences (phase 8). */
open class DiffFuzz(private val nodeDir: File) {
    private val executor = Executors.newVirtualThreadPerTaskExecutor()

    /** The engine outcome: `json <JSON.stringify text>` (functions as "<function>"), `undefined`, `error <code>`. */
    open fun outcomeOf(c: FuzzCase): String {
        val hits = java.util.Collections.synchronizedSet(java.util.TreeSet<String>())
        probeHits[c.id] = hits
        val future = executor.submit<String> {
            try {
                val expr = Jsonata.compile(c.expr)
                val options = EvaluationOptions(timeoutMillis = 5000, deviations = { id, _ -> hits.add(id) })
                val bindings = HashMap<String, Value?>()
                c.bindings?.fields()?.forEach { (k, v) -> bindings[k] = toValue(v) }
                val r = expr.evaluate(c.input?.let { toValue(it) }, bindings, options)
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
                    if (want != got) mismatches += classify(c, want, got)
                }
            }
        }
        return mismatches
    }

    /** Differences explained by the position or token of an error, with the rule that explained them (report sample). */
    val explainedByPosition = ArrayList<Mismatch>()

    /**
     * Explains a difference only on a confirming sign (review RV2-4): a deviation probe fired, the reference in a
     * fresh process agrees with the engine, or a narrow documented rule matches.
     */
    fun classify(c: FuzzCase, want: String, got: String): Mismatch {
        val category = if (codeOnly(want) == codeOnly(got) && want.startsWith("error ")) "error-position-token" else category(want, got)
        // a reference that did not finish explains nothing: it is counted apart and has a threshold of its own (RV3-30)
        if (category == "reference-timeout") return Mismatch(c, want, got, category, null)
        // DEV-03 fires on any coercion in the expression: it explains only when the partial calls rewritten into direct
        // ones make the reference agree with the engine (review RV3-8)
        val partials by lazy { confirmedByRewrite(c, got, KnownDifferences.rewritePartials(c.expr)) }
        // DEV-10 changes only the position of an error
        val fired = probeHits[c.id].orEmpty()
            .filter { it != Deviations.POSITION_IN_LAMBDA.id || category == "error-position-token" }
            .filter { it != Deviations.PARTIAL_SIGNATURE.id || partials }
        val known = when {
            fired.isNotEmpty() -> fired.joinToString(", ") { id -> "$id: " + (Deviations.byId(id)?.title ?: "deviation") } + " (probe fired)"
            category == "error-position-token" -> explainPosition(c, want, got)
            else -> KnownDifferences.explain(c, codeOnly(want), codeOnly(got)) ?: when {
                KnownDifferences.hasPartialCall(c.expr) && partials ->
                    "DEV-03: a partially applied native function checks and coerces its arguments by the signature (a direct call agrees)"
                KnownDifferences.sharedRegexInConstructor(c.expr) && confirmedByRewrite(c, got, KnownDifferences.rewriteSharedRegex(c.expr)) ->
                    "DEV-09: a regex variable shared by several values of a constructor (lastIndex in the async evaluation of jsonata-js; separate literals agree)"
                else -> null
            }
        }
        val m = Mismatch(c, want, got, category, known)
        if (category == "error-position-token" && known != null) explainedByPosition += m
        return m
    }

    /**
     * True when [rewritten] (the case with one construct replaced by its equivalent) gives the engine's outcome on both
     * sides: the engine is unchanged by the rewrite and the reference, in a fresh process, now agrees. Then the
     * difference is that construct only; any other difference stays (review RV3-8).
     */
    private fun confirmedByRewrite(c: FuzzCase, got: String, rewritten: String?): Boolean {
        if (rewritten == null || rewritten == c.expr) return false
        val r = c.copy(id = c.id + "~rewritten", expr = rewritten)
        val engine = outcomeOf(r)
        probeHits.remove(r.id)
        if (codeOnly(engine) != codeOnly(got)) return false
        return codeOnly(Sidecar.fresh(nodeDir, r)) == codeOnly(got)
    }

    private fun explainPosition(c: FuzzCase, want: String, got: String): String? {
        val fresh = Sidecar.fresh(nodeDir, c)
        if (fresh == got) return "reference instability: position and token of a library function depend on earlier calls in the process (a fresh process agrees)"
        // DEV-17 (owner's decision): the token of a library function called through a higher-order function or ~> is
        // its name; a fresh jsonata-js process gives no token and no position
        val engineToken = got.substringAfter(" @").substringAfter(' ')
        val freshTail = fresh.substringAfter(" @")
        if (codeOnly(fresh) == codeOnly(got) && freshTail == "null null" && libraryFunctionAsValue(c.expr, engineToken)) {
            return "DEV-17: the token of a library function called through a higher-order function or ~> is its name"
        }
        return null
    }

    /** Whether [name] is a library function passed as a value in [expr] (`~> $name`, `$f(x, $name)`), not only called. */
    private fun libraryFunctionAsValue(expr: String, name: String): Boolean {
        if (name == "null" || su.seaf.archtool.jsonata.FunctionRegistry.standard().lookup(name) == null) return false
        return Regex("\\$" + Regex.escape(name) + "\\s*(\\)|,|~>|]|$)").containsMatchIn(expr) || Regex("~>\\s*\\$" + Regex.escape(name) + "\\b(?!\\s*\\()").containsMatchIn(expr)
    }

    private fun category(want: String, got: String): String = when {
        got.startsWith("engine-crash") -> "engine-crash"
        got == "timeout" -> "timeout"
        want == "reference-timeout" -> "reference-timeout"
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
        // DEV-03 and DEV-09 need the reference on a rewritten expression: DiffFuzz.classify (review RV3-8)
        if (onlyEmptyArrayItems(c, want, got)) {
            return "DEV-08: object constructor over an empty array does not push undefined into it"
        }
        return null
    }

    private val partialNative = Regex("\\$([a-zA-Z]+)\\(([^()]*\\?[^()]*)\\)")

    /** Whether [expr] has a partial application `$name(…, ?, …)` (simple arguments, no parentheses). */
    fun hasPartialCall(expr: String): Boolean = partialNative.findAll(expr).any { m -> splitArguments(m.groupValues[2]).any { it.trim() == "?" } }

    /**
     * [expr] with each partial application `$f(a, ?, b)` replaced by `(function($__p1){ $f(a, $__p1, b) })`: a direct
     * call, which checks and coerces its arguments in jsonata-js too. Null when there is none.
     */
    fun rewritePartials(expr: String): String? {
        var changed = false
        val out = partialNative.replace(expr) { m ->
            val args = splitArguments(m.groupValues[2])
            if (args.none { it.trim() == "?" }) return@replace m.value
            changed = true
            var n = 0
            val params = ArrayList<String>()
            val call = args.joinToString(",") { a -> if (a.trim() == "?") "\$__p${++n}".also { params += it } else a }
            "(function(${params.joinToString(", ")}){ \$${m.groupValues[1]}($call) })"
        }
        return if (changed) out else null
    }

    /** Splits an argument list at top-level commas (outside brackets, braces and strings). */
    private fun splitArguments(text: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var quote = 0.toChar()
        var start = 0
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                quote != 0.toChar() -> if (ch == '\\') i++ else if (ch == quote) quote = 0.toChar()
                ch == '"' || ch == '\'' -> quote = ch
                ch == '[' || ch == '{' -> depth++
                ch == ']' || ch == '}' -> depth--
                ch == ',' && depth == 0 -> { out += text.substring(start, i); start = i + 1 }
            }
            i++
        }
        out += text.substring(start)
        return out
    }

    /**
     * DEV-08: the only differences are `[null]` (reference) against `[]` (engine) in the structure of the results, and
     * the expression or the input has an empty array for jsonata-js to push undefined into (review RV3-8).
     */
    fun onlyEmptyArrayItems(c: FuzzCase, want: String, got: String): Boolean {
        if (!want.startsWith("json ") || !got.startsWith("json ") || !c.expr.contains("{")) return false
        val w = try { mapper.readTree(want.removePrefix("json ")) } catch (_: Exception) { return false }
        val g = try { mapper.readTree(got.removePrefix("json ")) } catch (_: Exception) { return false }
        var found = 0
        fun same(a: JsonNode, b: JsonNode): Boolean = when {
            a.isArray && b.isArray && a.size() == 1 && a[0].isNull && b.size() == 0 -> { found++; true }
            a.isArray && b.isArray -> a.size() == b.size() && (0 until a.size()).all { same(a[it], b[it]) }
            a.isObject && b.isObject -> a.fieldNames().asSequence().toList() == b.fieldNames().asSequence().toList() &&
                a.fieldNames().asSequence().all { same(a[it], b[it]) }
            else -> a == b
        }
        if (!same(w, g) || found == 0) return false
        return emptyArrayIn(c.input) || emptyArrayConstructor(c.expr)
    }

    private fun emptyArrayIn(n: JsonNode?): Boolean = when {
        n == null -> false
        n.isArray -> n.size() == 0 || n.any { emptyArrayIn(it) }
        n.isObject -> n.any { emptyArrayIn(it) }
        else -> false
    }

    private fun emptyArrayConstructor(expr: String): Boolean {
        val ast = try { Jsonata.compile(expr).ast() } catch (_: JsonataException) { return false }
        var found = false
        walk(ast) { n -> if (n.type == "unary" && n.value == "[" && (n["expressions"] as? List<*>).isNullOrEmpty()) found = true }
        return found
    }

    /** A variable bound to a regex literal and referenced inside two or more values of one array or object constructor. */
    fun sharedRegexInConstructor(expr: String): Boolean = sharedRegexVariables(expr).isNotEmpty()

    /** The shared regex variables of [sharedRegexInConstructor] and their literals. */
    private fun sharedRegexVariables(expr: String): Map<String, su.seaf.archtool.jsonata.parser.JsRegex> {
        val ast = try {
            Jsonata.compile(expr).ast()
        } catch (_: JsonataException) {
            return emptyMap()
        }
        val regexVars = HashMap<String, su.seaf.archtool.jsonata.parser.JsRegex>()
        walk(ast) { n ->
            val rhs = n["rhs"] as? su.seaf.archtool.jsonata.parser.AstNode
            if (n.type == "bind" && rhs?.type == "regex") {
                val name = (n["lhs"] as? su.seaf.archtool.jsonata.parser.AstNode)?.get("value") as? String
                val literal = rhs.value as? su.seaf.archtool.jsonata.parser.JsRegex
                if (name != null && literal != null) regexVars[name] = literal
            }
        }
        if (regexVars.isEmpty()) return emptyMap()
        val shared = HashMap<String, su.seaf.archtool.jsonata.parser.JsRegex>()
        walk(ast) { n ->
            val values: List<Any?> = when {
                n.type == "unary" && n.value == "[" -> (n["expressions"] as? List<*>).orEmpty()
                n.type == "unary" && n.value == "{" -> (n["lhs"] as? List<*>).orEmpty().flatMap { (it as? List<*>).orEmpty() }
                else -> emptyList()
            }
            for ((v, literal) in regexVars) if (values.count { uses(it, v) } >= 2) shared[v] = literal
        }
        return shared
    }

    /**
     * [expr] with each use of a shared regex variable replaced by its own copy of the literal: nothing is shared, so
     * jsonata-js has no `lastIndex` to carry over. Null when no variable is shared.
     */
    fun rewriteSharedRegex(expr: String): String? {
        val shared = sharedRegexVariables(expr)
        if (shared.isEmpty()) return null
        var out = expr
        for ((name, literal) in shared) {
            val text = "/" + literal.pattern + "/" + literal.flags.replace("g", "")
            out = Regex("\\$" + Regex.escape(name) + "(?![A-Za-z0-9_])(?!\\s*:=)").replace(out) { text }
        }
        return out
    }

    private fun uses(node: Any?, variable: String): Boolean {
        var found = false
        walk(node) { n -> if (n.type == "variable" && n["value"] == variable) found = true }
        return found
    }

    private fun walk(root: Any?, visit: (su.seaf.archtool.jsonata.parser.AstNode) -> Unit) {
        val stack = ArrayList<Any?>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            when (val x = stack.removeAt(stack.size - 1)) {
                is su.seaf.archtool.jsonata.parser.AstNode -> {
                    visit(x)
                    stack.addAll(x.values)
                }
                is List<*> -> stack.addAll(x)
            }
        }
    }

    private fun lastBitsOnly(want: String, got: String): Boolean {
        val a = want.removePrefix("json ").toDoubleOrNull() ?: return false
        val b = got.removePrefix("json ").toDoubleOrNull() ?: return false
        return Math.abs(a - b) <= 4 * Math.ulp(a)
    }
}

/**
 * Replay: cases from a JSONL file, one `{"expr", "data"?, "bindings"?, "id"?, "zone"?}` per line (`input` is accepted
 * for `data`, as mismatches.jsonl writes it). Other fields fail the run, so that nothing a case needs is silently
 * dropped (review RV2-5); the bindings reach both engines.
 */
fun readCases(file: File): List<FuzzCase> = file.readLines().withIndex().filter { it.value.isNotBlank() }.map { (i, line) ->
    val n = mapper.readTree(line)
    val unknown = n.fieldNames().asSequence().filter { it !in REPLAY_FIELDS }.toList()
    require(unknown.isEmpty()) { "${file.name}:${i + 1}: unsupported fields $unknown (allowed: $REPLAY_FIELDS)" }
    require(!(n.has("data") && n.has("input"))) { "${file.name}:${i + 1}: both data and input" }
    val bindings = n.get("bindings")
    require(bindings == null || bindings.isObject) { "${file.name}:${i + 1}: bindings must be an object" }
    FuzzCase(n.get("id")?.asText() ?: "${file.nameWithoutExtension}-${i + 1}", n.get("zone")?.asText() ?: "replay", n.get("expr").asText(), n.get("data") ?: n.get("input"), bindings)
}

private val REPLAY_FIELDS = setOf("id", "zone", "expr", "data", "input", "bindings")

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
    Sidecar.unavailable(File(root, "node"))?.let { reason ->
        System.err.println("fuzzing skipped: $reason")
        System.exit(2)
    }
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
    report.put("explainedByPosition", fuzz.explainedByPosition.size)
    val sample = report.putArray("explainedByPositionSample")
    fuzz.explainedByPosition.take(20).forEach { m ->
        sample.addObject().put("id", m.case.id).put("expr", m.case.expr).put("expected", m.expected).put("actual", m.actual).put("known", m.known)
    }
    report.put("unexplained", FuzzGate.unexplained(mismatches).size)
    report.put("referenceTimeouts", FuzzGate.referenceTimeouts(mismatches))
    val problems = FuzzGate.problems(mismatches, cases.size)
    report.put("gate", if (problems.isEmpty()) "pass" else "fail")
    problems.forEach { report.withArray("gateProblems").add(it) }
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
            if (m.case.bindings != null) n.set<JsonNode>("bindings", m.case.bindings)
            w.write(mapper.writeValueAsString(n))
            w.write("\n")
        }
    }
    println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report))
    System.exit(0)
}
