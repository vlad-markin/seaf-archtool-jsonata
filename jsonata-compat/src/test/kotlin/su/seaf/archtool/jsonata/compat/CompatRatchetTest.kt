package su.seaf.archtool.jsonata.compat

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Runs the whole test-suite twice (parser against AST snapshots, evaluator against expected results), writes
 * build/compat-report.json, and fails if any case listed in compat/pass/{ast,eval}.txt no longer passes.
 * `./gradlew :jsonata-compat:test -PcompatUpdate=true` appends newly passing cases to the pass lists;
 * removing a case from a list is a deliberate manual edit.
 */
class CompatRatchetTest {
    private val root = File(System.getProperty("jsonata.compat"))
    private val update = System.getProperty("jsonata.compat.update").toBoolean()

    @Test
    fun `ratcheted cases keep passing`() {
        val suite = TestSuite(root)
        val cases = suite.cases()
        val harness = Harness(KotlinEngine, suite, AstSnapshots(root))
        val runs = mapOf("ast" to cases.map(harness::runAst), "eval" to cases.map(harness::runEval))

        val regressions = mutableListOf<CaseResult>()
        val report = mapper.createObjectNode()
        for ((name, results) in runs) {
            val passList = PassList(File(root, "pass/$name.txt"))
            val passing = results.filter { it.status == Status.PASS }.map { it.id }
            regressions += results.filter { it.id in passList.ids && it.status != Status.PASS }
            val newlyPassing = passing - passList.ids
            if (update && newlyPassing.isNotEmpty()) passList.add(newlyPassing)
            report.putObject(name).apply {
                put("total", results.size)
                Status.entries.forEach { s -> put(s.name.lowercase(), results.count { it.status == s }) }
                put("ratcheted", passList.ids.size)
                putPOJO("newlyPassing", newlyPassing)
                putPOJO("failing", results.filter { it.status != Status.PASS && it.status != Status.NOT_IMPLEMENTED }
                    .map { "${it.id} ${it.status} ${it.detail ?: ""}".trim() })
            }
        }
        // decision 15: the debug compilation gives the same outcome on every case
        val debugHarness = Harness(DebugKotlinEngine, suite, AstSnapshots(root))
        val debugDiffs = runs.getValue("eval").zip(cases.map(debugHarness::runEval))
            .filter { (plain, debug) -> plain.status != debug.status || plain.detail != debug.detail }
            .filter { (plain, _) -> listOf("\$random", "\$shuffle", "\$now", "\$millis").none { cases.first { c -> c.id == plain.id }.expr.contains(it) } }
            .map { (plain, debug) -> "${plain.id}: ${plain.status} ${plain.detail ?: ""} / debug ${debug.status} ${debug.detail ?: ""}" }
        report.putPOJO("debugDiffs", debugDiffs)
        File(System.getProperty("jsonata.compat.report")).apply { parentFile.mkdirs() }
            .writeText(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report))
        assertTrue(regressions.isEmpty(), "regressions: ${regressions.joinToString("\n")}")
        assertTrue(debugDiffs.isEmpty(), "debug compilation differs:\n${debugDiffs.joinToString("\n")}")
    }
}
