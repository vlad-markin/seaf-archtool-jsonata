package su.seaf.archtool.jsonata.compat

import su.seaf.archtool.jsonata.deviation.Deviations
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The page for architects (`docs/user/differences.md`, CAP-15 p. 1) lists exactly the records of `docs/deviations.md`,
 * and every deviation with a probe or rule in the engine has a record (step 1.10; TC-27).
 */
class DifferencesPageTest {
    private val docs = File(System.getProperty("jsonata.compat"), "../docs")

    private fun ids(file: String, heading: String) =
        Regex("^$heading (DEV-\\d+)\\.", RegexOption.MULTILINE).findAll(File(docs, file).readText()).map { it.groupValues[1] }.toList()

    @Test
    fun `the page lists every record and nothing else`() {
        val records = ids("deviations.md", "##")
        val page = ids("user/differences.md", "###")
        assertEquals(records.sorted(), page.sorted())
        assertEquals(page.size, page.toSet().size, "a DEV listed twice")
    }

    @Test
    fun `every deviation known to the engine has a record`() {
        val records = ids("deviations.md", "##").toSet()
        val missing = Deviations.all.map { it.id }.filter { it !in records }
        assertTrue(missing.isEmpty(), "deviations without a record: $missing")
    }

    @Test
    fun `the switch page tells how to build and run the detector`() {
        val text = File(docs, "user/jvm-switch.md").readText()
        assertTrue(text.contains(":jsonata-tools:installDist") && text.contains("detect --lake"))
    }
}
