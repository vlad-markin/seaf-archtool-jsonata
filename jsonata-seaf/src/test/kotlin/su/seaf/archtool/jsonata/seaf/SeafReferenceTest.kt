package su.seaf.archtool.jsonata.seaf

import org.junit.jupiter.api.Assumptions.assumeTrue
import su.seaf.archtool.jsonata.json.JsonataJson
import su.seaf.archtool.jsonata.value.ObjectValue
import su.seaf.archtool.jsonata.value.StringValue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The reference values of the core functions are taken from the core at the commit of `compat/seaf/core-ref.json`
 * (step 3.0, run locally by the owner). Skipped until those files are in the repository.
 */
class SeafReferenceTest {
    private val dir = File(System.getProperty("jsonata.compat"), "seaf")

    /** The 12 core functions of design §2.3. */
    private val functions = setOf(
        "lookup", "tryexecute", "jsonschema", "wcard", "mergedeep", "sourcetype",
        "set", "get", "log", "parsesource", "manifestschema", "versions",
    )

    @Test
    fun `reference values match the recorded core commit and cover every function`() {
        val expected = File(dir, "expected.jsonl")
        val ref = File(dir, "core-ref.json")
        assumeTrue(expected.isFile && ref.isFile, "compat/seaf/expected.jsonl is produced locally (step 3.0)")
        val lines = expected.readLines().filter { it.isNotBlank() }
        val header = JsonataJson.parse(lines.first()) as ObjectValue
        val commit = ((JsonataJson.parse(ref.readText()) as ObjectValue)["commit"] as StringValue).value
        assertEquals(commit, (header["commit"] as StringValue).value, "expected.jsonl was produced on another core commit")
        val covered = lines.drop(1).map { ((JsonataJson.parse(it) as ObjectValue)["function"] as StringValue).value }.toSet()
        assertTrue(covered.containsAll(functions), "no reference cases for ${functions - covered}")
    }
}
