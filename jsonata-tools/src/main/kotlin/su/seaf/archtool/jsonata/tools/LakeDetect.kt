package su.seaf.archtool.jsonata.tools

import su.seaf.archtool.jsonata.CallContext
import su.seaf.archtool.jsonata.EvaluationOptions
import su.seaf.archtool.jsonata.Jsonata
import su.seaf.archtool.jsonata.deviation.Deviations
import su.seaf.archtool.jsonata.deviation.Finding
import su.seaf.archtool.jsonata.parser.AstNode
import su.seaf.archtool.jsonata.parser.JsonataException
import su.seaf.archtool.jsonata.parser.LineIndex
import su.seaf.archtool.jsonata.seaf.SeafDetector
import su.seaf.archtool.jsonata.seaf.SeafEnvironment
import su.seaf.archtool.jsonata.seaf.SeafHost
import su.seaf.archtool.jsonata.seaf.SeafLimits
import su.seaf.archtool.jsonata.seaf.SeafRequest
import su.seaf.archtool.jsonata.value.ArrayValue
import su.seaf.archtool.jsonata.value.NumberValue
import su.seaf.archtool.jsonata.value.ObjectValue
import su.seaf.archtool.jsonata.value.StringValue
import su.seaf.archtool.jsonata.value.Value
import java.util.TreeMap

/**
 * The detector over a lake (design §2.1, step 1.8; CAP-15 p. 2, RV-22). Findings are split into "may be affected"
 * (static rules, nothing evaluated) and "confirmed" (a deviation probe fired while the engine evaluated the expression
 * on the manifest), always next to the coverage: how many expressions were evaluated, on how many contexts, how many
 * failed and why, and which were not evaluated.
 */
class LakeDetect(private val manifest: Value?, private val contexts: Contexts = Contexts.NONE) {
    /** The detector does no I/O: `$parsesource` fails, the schema and versions are unknown. */
    private object DetectHost : SeafHost {
        override fun loadSource(manifest: Value?, source: Value?, request: SeafRequest?, context: CallContext): Value? =
            throw UnsupportedOperationException("\$parsesource is not available in the detector")
        override fun manifestSchema(): Value? = null
        override fun versions(): Value? = null
        override fun log(content: Value?, tag: Value?) {}
    }

    class Detail(val place: String, val line: Int?, val column: Int?, val id: String, val kind: String, val recommendation: String)

    val details = ArrayList<Detail>()
    private val staticByDev = TreeMap<String, MutableSet<String>>()
    private val confirmedExpr = TreeMap<String, MutableSet<String>>()
    private val confirmedEval = TreeMap<String, Int>()
    private val notEvaluated = TreeMap<String, Int>()
    private val failed = TreeMap<String, Int>()
    private var evaluatedExpressions = 0
    private var evaluations = 0
    private var expressions = 0
    var problems = 0
        private set

    private val functions: Value? = (manifest as? ObjectValue)?.get("functions")
    private val conflicts = SeafDetector.manifestConflicts(functions)
    private val invalid = SeafDetector.invalidDeclarations(functions)
    private val env: SeafEnvironment? = manifest?.let {
        SeafEnvironment.create(DetectHost, if (conflicts.isEmpty()) functions else null, SeafLimits.TEMPORARY)
    }

    fun run(found: List<FoundExpression>): LakeDetect {
        problems += conflicts.size + invalid.size
        // ids of manifest functions go to details.json only, summary.json counts them (review RV3-46)
        for (c in conflicts) details += Detail("manifest:functions/${c.id}", null, null, "X0014", "conflict", c.toString())
        for (f in invalid) details += Detail("manifest:functions", null, null, f.id, "declaration", f.recommendation)
        for (e in found) scan(e)
        return this
    }

    private fun count(map: TreeMap<String, Int>, key: String) {
        map[key] = (map[key] ?: 0) + 1
    }

    private fun scan(e: FoundExpression) {
        expressions++
        val compiled = try {
            env?.compile(e.text) ?: Jsonata.compile(e.text)
        } catch (ex: JsonataException) {
            count(failed, "syntax ${ex.code}")
            problems++
            details += Detail(e.place, null, null, ex.code, "error", ex.message ?: ex.code)
            return
        }
        val lines = LineIndex(e.text)
        val ast = compiled.ast()
        val findings: List<Finding> = Deviations.scan(ast) + SeafDetector.scan(ast, functions)
        for (f in findings) {
            staticByDev.getOrPut(f.id) { HashSet() } += e.place
            details += Detail(e.place, f.position?.let { lines.line(it) }, f.position?.let { lines.column(it) }, f.id, "static", f.recommendation)
        }
        val env = env
        if (env == null) {
            count(notEvaluated, "no manifest (--manifest)")
            return
        }
        if (e.place.startsWith("functions.")) {
            count(notEvaluated, "manifest function code (evaluated through its calls)")
            return
        }
        val specs = contexts.of(e.place).ifEmpty {
            if (uses(ast, "self") || uses(ast, "params")) {
                count(notEvaluated, "needs \$self/\$params and has no context (--contexts)")
                return
            }
            listOf(EvalContextSpec(null, null, null))
        }
        evaluatedExpressions++
        for (spec in specs) {
            evaluations++
            val hits = LinkedHashMap<String, Int?>()
            val options = EvaluationOptions(timeoutMillis = 10_000, budgets = SeafLimits.TEMPORARY.budgets, deviations = { id, pos -> hits.putIfAbsent(id, pos) })
            try {
                env.evaluate(compiled, manifest, spec.self, spec.params, spec.role?.let { SeafRequest(mapOf("role" to it)) }, options)
            } catch (ex: JsonataException) {
                count(failed, ex.code)
            } catch (ex: RuntimeException) {
                count(failed, ex.javaClass.simpleName)
            }
            for ((id, pos) in hits) {
                confirmedExpr.getOrPut(id) { HashSet() } += e.place
                confirmedEval[id] = (confirmedEval[id] ?: 0) + 1
                val title = Deviations.byId(id)?.title ?: id
                details += Detail(e.place, pos?.let { lines.line(it) }, pos?.let { lines.column(it) }, id, "confirmed", title)
            }
        }
    }

    private fun uses(ast: AstNode, variable: String): Boolean {
        val stack = ArrayList<Any?>()
        stack.add(ast)
        while (stack.isNotEmpty()) {
            when (val x = stack.removeAt(stack.size - 1)) {
                is AstNode -> {
                    if (x.type == "variable" && x["value"] == variable) return true
                    stack.addAll(x.values)
                }
                is List<*> -> stack.addAll(x)
            }
        }
        return false
    }

    private fun obj(vararg pairs: Pair<String, Value?>) = ObjectValue().also { o -> for ((k, v) in pairs) if (v != null) o.put(k, v) }
    private fun counts(m: Map<String, Int>) = ObjectValue().also { o -> m.forEach { (k, v) -> o.put(k, NumberValue.of(v)) } }

    /** `summary.json`: aggregates only, no expression texts (the anonymization rule allows places only in details). */
    fun summary(): ObjectValue = obj(
        "expressions" to NumberValue.of(expressions),
        "mayBeAffected" to counts(staticByDev.mapValues { it.value.size }),
        "confirmed" to ObjectValue().also { o ->
            confirmedExpr.forEach { (id, places) -> o.put(id, obj("expressions" to NumberValue.of(places.size), "evaluations" to NumberValue.of(confirmedEval[id] ?: 0))) }
        },
        "manifestConflicts" to counts(conflicts.groupingBy { it.builtIn }.eachCount().toSortedMap()),
        "invalidDeclarations" to NumberValue.of(invalid.size),
        "storeCalls" to NumberValue.of(staticByDev["DEV-15"]?.size ?: 0),
        "coverage" to obj(
            "expressions" to NumberValue.of(expressions),
            "evaluated" to NumberValue.of(evaluatedExpressions),
            "evaluations" to NumberValue.of(evaluations),
            "contexts" to NumberValue.of(contexts.size),
            "notEvaluated" to counts(notEvaluated),
            "failed" to counts(failed),
        ),
    )

    /** `details.json`: every finding with its place — for the owner of the installation only, never committed. */
    fun detailsValue(): ArrayValue = ArrayValue(details.size).also { a ->
        for (d in details) a.add(obj(
            "place" to StringValue.of(d.place), "line" to d.line?.let { NumberValue.of(it) }, "column" to d.column?.let { NumberValue.of(it) },
            "id" to StringValue.of(d.id), "kind" to StringValue.of(d.kind), "recommendation" to StringValue.of(d.recommendation),
        ))
    }

    /** A short text report: what may be affected, what is confirmed, and the coverage. */
    fun text(): String {
        val sb = StringBuilder()
        sb.append("expressions: $expressions\n")
        if (conflicts.isNotEmpty()) sb.append("manifest functions conflict (X0014): ${conflicts.joinToString("; ")}\n")
        for (f in invalid) sb.append("functions: ${f.id} ${f.recommendation}\n")
        sb.append("may be affected (static rules): ").append(if (staticByDev.isEmpty()) "none" else staticByDev.entries.joinToString(", ") { "${it.key} in ${it.value.size}" }).append('\n')
        sb.append("confirmed (probe fired): ").append(if (confirmedExpr.isEmpty()) "none" else confirmedExpr.entries.joinToString(", ") { "${it.key} in ${it.value.size} (${confirmedEval[it.key]} evaluations)" }).append('\n')
        sb.append("coverage: $evaluatedExpressions of $expressions expressions evaluated, $evaluations evaluations, ${contexts.size} contexts")
        if (notEvaluated.isNotEmpty()) sb.append("; not evaluated: ").append(notEvaluated.entries.joinToString(", ") { "${it.value} ${it.key}" })
        if (failed.isNotEmpty()) sb.append("; failed: ").append(failed.entries.joinToString(", ") { "${it.value} ${it.key}" })
        sb.append('\n')
        return sb.toString()
    }
}
