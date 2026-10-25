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
    fun `pass lists name existing cases only`() {
        val ids = cases.map { it.id }.toSet()
        for (name in listOf("ast", "eval")) {
            val unknown = PassList(File(root, "pass/$name.txt")).ids - ids
            assertTrue(unknown.isEmpty(), "pass/$name.txt has unknown ids: $unknown")
        }
    }
}
