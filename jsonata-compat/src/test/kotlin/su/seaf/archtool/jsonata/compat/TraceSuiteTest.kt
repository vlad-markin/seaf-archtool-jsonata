package su.seaf.archtool.jsonata.compat

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** With a profiler and a value tracer the whole test-suite gives the same outcomes (CAP-11 p. 4, TC-16). */
class TraceSuiteTest {
    private val root = File(System.getProperty("jsonata.compat"))

    @Test
    fun `tracing does not change any outcome of the test-suite`() {
        val suite = TestSuite(root)
        val cases = suite.cases()
        val plain = Harness(KotlinEngine, suite, AstSnapshots(root))
        val traced = Harness(TracedKotlinEngine, suite, AstSnapshots(root))
        val nondeterministic = listOf("\$random", "\$shuffle", "\$now", "\$millis")
        val diffs = cases.filter { c -> nondeterministic.none { c.expr.contains(it) } && c.timelimit == null }.mapNotNull { c ->
            val a = plain.runEval(c)
            val b = traced.runEval(c)
            if (a.status != b.status || a.detail != b.detail) "${c.id}: ${a.status} ${a.detail ?: ""} / traced ${b.status} ${b.detail ?: ""}" else null
        }
        assertTrue(diffs.isEmpty(), diffs.joinToString("\n"))
    }
}
