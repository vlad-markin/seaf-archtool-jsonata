package su.seaf.archtool.jsonata.compat

import com.fasterxml.jackson.databind.JsonNode
import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

enum class Status { PASS, FAIL, NOT_IMPLEMENTED, TIMEOUT, CRASH }

data class CaseResult(val id: String, val status: Status, val detail: String? = null)

/** Default per-case time budget; cases with `timelimit` get twice their limit, as in jsonata-js run-test-suite.js. */
private const val DEFAULT_BUDGET_MS = 10_000L

class Harness(private val engine: CompatEngine, private val suite: TestSuite, private val snapshots: AstSnapshots) {
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

    fun runEval(case: SuiteCase): CaseResult = guarded(case.id, case.timelimit?.times(2) ?: DEFAULT_BUDGET_MS) {
        when (val outcome = engine.evaluate(case)) {
            is Outcome.Failed -> when {
                case.code == null -> CaseResult(case.id, Status.FAIL, "error ${outcome.error.code}: ${outcome.error.message}")
                case.code != outcome.error.code -> CaseResult(case.id, Status.FAIL, "expected ${case.code}, got ${outcome.error.code}")
                case.token != null && case.token != outcome.error.token -> CaseResult(case.id, Status.FAIL, "token ${outcome.error.token}")
                else -> CaseResult(case.id, Status.PASS)
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

/** Ratchet list: ids of cases that must keep passing (one per line, `#` comments). */
class PassList(val file: File) {
    val ids: Set<String> = file.readLines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.toSet()

    fun add(newIds: Collection<String>) {
        val all = (ids + newIds).sorted()
        file.writeText(all.joinToString("\n", postfix = if (all.isEmpty()) "" else "\n"))
    }
}
