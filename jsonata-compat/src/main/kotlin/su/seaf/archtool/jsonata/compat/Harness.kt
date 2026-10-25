package su.seaf.archtool.jsonata.compat

import com.fasterxml.jackson.databind.JsonNode
import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** DEVIATION: the outcome differs from jsonata-js 2.2.2 by a deviation listed for the case in compat/pass/deviations.txt. */
enum class Status { PASS, FAIL, NOT_IMPLEMENTED, TIMEOUT, CRASH, DEVIATION }

data class CaseResult(val id: String, val status: Status, val detail: String? = null)

/** Default per-case time budget; cases with `timelimit` get twice their limit, as in jsonata-js run-test-suite.js. */
private const val DEFAULT_BUDGET_MS = 10_000L

class Harness(
    private val engine: CompatEngine,
    private val suite: TestSuite,
    private val snapshots: AstSnapshots,
    private val errors: ErrorSnapshots = ErrorSnapshots(suite.root),
    private val deviations: DeviationList = DeviationList(File(suite.root, "pass/deviations.txt")),
) {
    private val executor = Executors.newVirtualThreadPerTaskExecutor()

    fun runAst(case: SuiteCase): CaseResult = guarded(case.id, DEFAULT_BUDGET_MS) {
        val expected = snapshots.entries[case.id] ?: return@guarded CaseResult(case.id, Status.FAIL, "no snapshot")
        val error = expected.get("error")
        try {
            val actual = engine.ast(case.expr)
            if (error != null) CaseResult(case.id, Status.FAIL, "expected ${error.get("code").asText()}, got AST")
            else if (normalize(actual) == normalize(expected.get("ast"))) CaseResult(case.id, Status.PASS)
            else CaseResult(case.id, Status.FAIL, "AST differs")
        } catch (e: EngineError) {
            if (error != null && error.get("code").asText() == e.code &&
                (error.get("position") == null || error.get("position").asInt() == e.position)) CaseResult(case.id, Status.PASS)
            else CaseResult(case.id, Status.FAIL, "unexpected error ${e.code} at ${e.position}")
        }
    }

    fun runEval(case: SuiteCase): CaseResult = deviation(runEvalAgainstReference(case))

    /** A failing case listed in compat/pass/deviations.txt counts as that deviation (docs/compat-policy.md). */
    private fun deviation(r: CaseResult): CaseResult {
        val dev = deviations.ids[r.id] ?: return r
        return if (r.status == Status.FAIL) CaseResult(r.id, Status.DEVIATION, "$dev: ${r.detail ?: ""}".trim()) else r
    }

    private fun runEvalAgainstReference(case: SuiteCase): CaseResult = guarded(case.id, case.timelimit?.times(2) ?: DEFAULT_BUDGET_MS) {
        when (val outcome = engine.evaluate(case)) {
            is Outcome.Failed -> when {
                case.code == null -> CaseResult(case.id, Status.FAIL, "error ${outcome.error.code}: ${outcome.error.message}")
                case.code != outcome.error.code -> CaseResult(case.id, Status.FAIL, "expected ${case.code}, got ${outcome.error.code}")
                case.token != null && case.token != outcome.error.token -> CaseResult(case.id, Status.FAIL, "token ${outcome.error.token}")
                else -> errors.check(case.id, outcome.error)?.let { CaseResult(case.id, Status.FAIL, it) } ?: CaseResult(case.id, Status.PASS)
            }
            Outcome.Undefined ->
                if (case.undefinedResult) CaseResult(case.id, Status.PASS) else CaseResult(case.id, Status.FAIL, "got undefined")
            is Outcome.Value -> when {
                case.code != null -> CaseResult(case.id, Status.FAIL, "expected ${case.code}, got a value")
                case.undefinedResult -> CaseResult(case.id, Status.FAIL, "expected undefined")
                same(case, outcome.value) -> CaseResult(case.id, Status.PASS)
                else -> CaseResult(case.id, Status.FAIL, "result differs")
            }
        }
    }

    private fun same(case: SuiteCase, actual: JsonNode): Boolean {
        val a = normalize(actual)
        val e = normalize(case.result)
        if (!case.unordered || a == null || e == null || !a.isArray || !e.isArray) return a == e
        return a.map { it.toString() }.sorted() == e.map { it.toString() }.sorted()
    }

    /** Runs one case on its own virtual thread so that a hung evaluator cannot stall the suite. */
    private fun guarded(id: String, budgetMs: Long, body: () -> CaseResult): CaseResult {
        val future = executor.submit<CaseResult> { body() }
        return try {
            future.get(budgetMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            CaseResult(id, Status.TIMEOUT)
        } catch (e: ExecutionException) {
            when (val cause = e.cause) {
                is NotImplementedError -> CaseResult(id, Status.NOT_IMPLEMENTED, cause.message)
                else -> CaseResult(id, Status.CRASH, "${cause?.javaClass?.simpleName}: ${cause?.message}")
            }
        }
    }
}

/**
 * Position and token of the reference error for each test-suite case that expects one, recorded by
 * compat/node/dump-eval-errors.mjs (compat/pass/eval-errors.jsonl); the test-suite itself checks only the code.
 */
class ErrorSnapshots(root: File) {
    val entries: Map<String, JsonNode> = File(root, "pass/eval-errors.jsonl").let { f ->
        if (!f.exists()) emptyMap() else f.readLines().filter { it.isNotBlank() }.map { mapper.readTree(it) }.associateBy { it.get("id").asText() }
    }

    /** Null when the error matches the reference (or the reference is unstable for the case), else what differs. */
    fun check(id: String, error: EngineError): String? {
        val e = entries[id] ?: return null
        if (e.get("unstable")?.asBoolean() == true) return null
        val position = e.get("position")?.takeIf { !it.isNull }?.asInt()
        val token = e.get("token")?.takeIf { !it.isNull }?.asText()
        val diffs = ArrayList<String>()
        if (position != error.position) diffs += "position ${error.position}, expected $position"
        if (token != error.token) diffs += "token ${error.token}, expected $token"
        return if (diffs.isEmpty()) null else "${error.code}: " + diffs.joinToString(", ")
    }
}

/** compat/pass/deviations.txt: `<case-id> <DEV-id>` per line (`#` comments), cases that differ by a deviation. */
class DeviationList(val file: File) {
    val ids: Map<String, String> = if (!file.exists()) emptyMap() else file.readLines()
        .map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }
        .associate { line -> line.substringBefore(' ') to line.substringAfter(' ').trim() }
}

/** Ratchet list: ids of cases that must keep passing (one per line, `#` comments). */
class PassList(val file: File) {
    val ids: Set<String> = file.readLines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.toSet()

    fun add(newIds: Collection<String>) {
        val all = (ids + newIds).sorted()
        file.writeText(all.joinToString("\n", postfix = if (all.isEmpty()) "" else "\n"))
    }
}
