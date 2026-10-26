package su.seaf.archtool.jsonata.tools.gate

import su.seaf.archtool.jsonata.CallContext
import su.seaf.archtool.jsonata.EvaluationOptions
import su.seaf.archtool.jsonata.Jsonata
import su.seaf.archtool.jsonata.deviation.Deviations
import su.seaf.archtool.jsonata.parser.AstNode
import su.seaf.archtool.jsonata.parser.JsonataException
import su.seaf.archtool.jsonata.seaf.SeafEnvironment
import su.seaf.archtool.jsonata.seaf.SeafHost
import su.seaf.archtool.jsonata.seaf.SeafLimits
import su.seaf.archtool.jsonata.seaf.SeafRequest
import su.seaf.archtool.jsonata.tools.Contexts
import su.seaf.archtool.jsonata.tools.EvalContextSpec
import su.seaf.archtool.jsonata.tools.FoundExpression
import su.seaf.archtool.jsonata.value.ArrayValue
import su.seaf.archtool.jsonata.value.NumberValue
import su.seaf.archtool.jsonata.value.ObjectValue
import su.seaf.archtool.jsonata.value.StringValue
import su.seaf.archtool.jsonata.value.Value
import java.util.TreeMap

/** One evaluation of the gate: an expression of a place on one context. */
class GateCase(val id: String, val place: String, val expr: String, val context: EvalContextSpec)

/** What the reference gave: the canonical outcome and, with timing, the median time in ms. */
class ReferenceOutcome(val outcome: String, val millis: Double?)

/** The reference side of the gate: the core's driver on jsonata-js 2.2.2 ([NodeReference]), or a fake in tests. */
fun interface Reference {
    fun evaluate(cases: List<GateCase>, timing: Int): Map<String, ReferenceOutcome>
}

/** The data the engine side gets from the core: `$parsesource`, `$manifestschema()`, `$versions()`. */
interface GateData {
    fun parseSource(source: Value?): Value?
    fun manifestSchema(): Value?
    fun versions(): Value?
}

/**
 * The lake gate (design §2.1, step 1.9; TC-05, TC-22): every expression of a lake on the manifest, on the engine and
 * on the core's reference, compared canonically. A difference counts as explained only when a deviation probe fired
 * on that evaluation. The engine runs without limits except a deadline; how many evaluations would hit the temporary
 * limits is counted separately (input for step 6.5). With timing both sides repeat each evaluation in their own
 * process; the report keeps only aggregates (anonymization rule of `docs/compat-policy.md`).
 */
class LakeGate(
    private val manifest: Value?,
    private val data: GateData,
    private val reference: Reference,
    private val contexts: Contexts = Contexts.NONE,
    private val timing: Int = 0,
) {
    private inner class GateHost : SeafHost {
        override fun loadSource(manifest: Value?, source: Value?, request: SeafRequest?, context: CallContext): Value? = data.parseSource(source)
        override fun manifestSchema(): Value? = data.manifestSchema()
        override fun versions(): Value? = data.versions()
        override fun log(tag: String?, content: Value?) {}
    }

    class Result(val case: GateCase, val engine: String, val reference: String, val explainedBy: List<String>, val engineMillis: Double?, val referenceMillis: Double?) {
        val equal: Boolean get() = engine == reference
    }

    val results = ArrayList<Result>()
    private val notEvaluated = TreeMap<String, Int>()
    private var limitHits = TreeMap<String, Int>()
    private val functions: Value? = (manifest as? ObjectValue)?.get("functions")
    private val env = SeafEnvironment.create(GateHost(), functions, SeafLimits.TEMPORARY)

    fun run(found: List<FoundExpression>): LakeGate {
        val cases = ArrayList<GateCase>()
        for (e in found) {
            if (e.place.startsWith("functions.")) {
                notEvaluated.merge("manifest function code (evaluated through its calls)", 1, Int::plus)
                continue
            }
            val ast = try {
                Jsonata.compile(e.text).ast()
            } catch (_: JsonataException) {
                null
            }
            val specs = contexts.of(e.place).ifEmpty {
                if (ast != null && (uses(ast, "self") || uses(ast, "params"))) {
                    notEvaluated.merge("needs \$self/\$params and has no context", 1, Int::plus)
                    emptyList()
                } else {
                    listOf(EvalContextSpec(null, null, null))
                }
            }
            specs.forEach { spec -> cases += GateCase("c${cases.size + 1}", e.place, e.text, spec) }
        }
        val ref = reference.evaluate(cases, timing)
        for (c in cases) {
            val hits = LinkedHashSet<String>()
            val (outcome, millis) = engine(c, hits)
            val r = ref[c.id] ?: ReferenceOutcome("crash no outcome", null)
            val explained = if (outcome == r.outcome) emptyList() else hits.toList()
            results += Result(c, outcome, r.outcome, explained, millis, r.millis)
        }
        return this
    }

    private fun engine(c: GateCase, hits: MutableSet<String>): Pair<String, Double?> {
        val compiled = try {
            env.compile(c.expr)
        } catch (e: JsonataException) {
            return Canonical.error(e) to null
        }
        val sink = EvaluationOptions(timeoutMillis = 60_000, deviations = { id, _ -> hits.add(id) })
        fun eval(options: EvaluationOptions) = env.evaluate(compiled, manifest, c.context.self, c.context.params, c.context.role?.let { SeafRequest(mapOf("role" to it)) }, options)
        val outcome = try {
            Canonical.of(eval(sink))
        } catch (e: JsonataException) {
            Canonical.error(e)
        } catch (e: RuntimeException) {
            "crash ${e.javaClass.simpleName}: ${e.message}"
        }
        if (!outcome.startsWith("error ") && !outcome.startsWith("crash ")) {
            // would the temporary limits stop it? (step 6.5)
            try {
                eval(SeafLimits.TEMPORARY.options())
            } catch (e: JsonataException) {
                if (e.code == "D1012" || e.code == "X0010" || e.code == "D1011" || e.code == "D2015") limitHits.merge(e.code, 1, Int::plus)
            }
        }
        var millis: Double? = null
        if (timing > 0 && !outcome.startsWith("crash ")) {
            val times = DoubleArray(timing) {
                val t0 = System.nanoTime()
                runCatching { eval(EvaluationOptions(timeoutMillis = 60_000)) }
                (System.nanoTime() - t0) / 1e6
            }
            times.sort()
            millis = times[times.size / 2]
        }
        return outcome to millis
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

    /** Aggregates only: no places, expression texts or lake names (anonymization rule). */
    fun summary(): ObjectValue {
        val differ = results.filter { !it.equal }
        val ratios = results.mapNotNull { r -> if (r.engineMillis != null && r.referenceMillis != null && r.engineMillis > 0) r.referenceMillis / r.engineMillis else null }.sorted()
        fun pct(p: Double) = if (ratios.isEmpty()) null else NumberValue.of(ratios[minOf(ratios.size - 1, (p * ratios.size).toInt())])
        return obj(
            "evaluations" to NumberValue.of(results.size),
            "equal" to NumberValue.of(results.count { it.equal }),
            "explained" to counts(differ.filter { it.explainedBy.isNotEmpty() }.flatMap { it.explainedBy }.groupingBy { it }.eachCount().toSortedMap()),
            "unexplained" to NumberValue.of(differ.count { it.explainedBy.isEmpty() }),
            "notEvaluated" to counts(notEvaluated),
            "temporaryLimitsHit" to counts(limitHits),
            "timing" to if (ratios.isEmpty()) null else obj(
                "speedupMedian" to pct(0.5), "speedupP5" to pct(0.05),
                // the worst ten by ratio: rank and ratio only
                "worst" to ArrayValue(minOf(10, ratios.size)).also { a -> ratios.take(10).forEach { a.add(NumberValue.of(it)) } },
            ),
        )
    }

    /** Every difference with its place and both outcomes — for the owner only, never committed. */
    fun details(): ArrayValue = ArrayValue().also { a ->
        for (r in results.filter { !it.equal }) a.add(obj(
            "place" to StringValue.of(r.case.place), "engine" to StringValue.of(r.engine), "reference" to StringValue.of(r.reference),
            "explainedBy" to ArrayValue(r.explainedBy.size).also { l -> r.explainedBy.forEach { l.add(StringValue.of("$it: " + (Deviations.byId(it)?.title ?: ""))) } },
        ))
    }
}
