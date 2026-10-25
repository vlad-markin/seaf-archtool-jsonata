package su.seaf.archtool.jsonata.compat

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SuiteIntegrityTest {
    private val root = File(System.getProperty("jsonata.compat"))
    private val cases = TestSuite(root).cases()

    @Test
    fun `vendored test-suite is jsonata-js 2_2_2 complete`() {
        assertEquals(1686, cases.size, "case count of jsonata-js v2.2.2 test-suite")
        assertEquals(cases.size, cases.map { it.id }.toSet().size, "case ids are unique")
    }

    @Test
    fun `every case has a reference AST snapshot for the same expression`() {
        val snapshots = AstSnapshots(root).entries
        val missing = cases.filter { snapshots[it.id]?.get("expr")?.asText() != it.expr }.map { it.id }
        assertTrue(missing.isEmpty(), "regenerate with compat/node/dump-ast.mjs: $missing")
    }

    @Test
    fun `the reference is jsonata-js 2_2_2`() {
        val version = File(root, "jsonata-js/VERSION").readText()
        assertTrue(version.startsWith("jsonata-js v2.2.2\n"), "compat/jsonata-js/VERSION: $version")
        assertTrue(version.contains("commit 6c7e95fdbf4405a1e741852a7cd8cd985b4305bb"), "compat/jsonata-js/VERSION: $version")
        val pkg = mapper.readTree(File(root, "jsonata-js/package.json"))
        assertEquals("2.2.2", pkg.get("version").asText())
        val node = mapper.readTree(File(root, "node/package.json"))
        assertEquals("2.2.2", node.get("dependencies").get("jsonata").asText().removePrefix("^").removePrefix("~").removePrefix("="))
    }

    /** CAP-1 p. 4: a case leaves the eval ratchet only together with the deviation that explains it. */
    @Test
    fun `every case is in the eval ratchet or listed with a deviation`() {
        val ids = cases.map { it.id }.toSet()
        val ratchet = PassList(File(root, "pass/eval.txt")).ids
        val deviations = DeviationList(File(root, "pass/deviations.txt")).ids
        val both = ratchet.intersect(deviations.keys)
        assertTrue(both.isEmpty(), "in both pass/eval.txt and pass/deviations.txt: $both")
        val unknown = deviations.keys - ids
        assertTrue(unknown.isEmpty(), "pass/deviations.txt has unknown ids: $unknown")
        val missing = ids - ratchet - deviations.keys
        assertTrue(missing.isEmpty(), "neither ratcheted nor explained by a deviation (add a DEV record or fix the engine): $missing")
        val recorded = Regex("^## (DEV-\\d+)\\.", RegexOption.MULTILINE).findAll(File(root, "../docs/deviations.md").readText()).map { it.groupValues[1] }.toSet()
        val undocumented = deviations.filterValues { it !in recorded }
        assertTrue(undocumented.isEmpty(), "DEV ids without a record in docs/deviations.md: $undocumented")
    }

    @Test
    fun `reference error positions cover the error cases`() {
        val errors = ErrorSnapshots(root).entries
        val expected = cases.filter { it.code != null }.map { it.id }.toSet()
        assertEquals(expected, errors.keys, "regenerate compat/pass/eval-errors.jsonl with compat/node/dump-eval-errors.mjs")
        val wrongCode = cases.filter { it.code != null && errors[it.id]?.get("code")?.asText() != it.code }.map { it.id }
        assertTrue(wrongCode.isEmpty(), "reference code differs from the test-suite: $wrongCode")
    }

    @Test
    fun `pass lists name existing cases only`() {
        val ids = cases.map { it.id }.toSet()
        for (name in listOf("ast", "eval")) {
            val unknown = PassList(File(root, "pass/$name.txt")).ids - ids
            assertTrue(unknown.isEmpty(), "pass/$name.txt has unknown ids: $unknown")
        }
    }
}
