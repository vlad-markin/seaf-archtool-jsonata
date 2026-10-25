package su.seaf.archtool.jsonata.compat

import org.junit.jupiter.api.Assumptions.assumeTrue
import su.seaf.archtool.jsonata.compat.fuzz.DiffFuzz
import su.seaf.archtool.jsonata.compat.fuzz.Generator
import su.seaf.archtool.jsonata.compat.fuzz.Sidecar
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Phase 8 in the build: a fixed-seed differential fuzzing run against jsonata@2.2.2 must have no unexplained
 * differences (documented deviations and reference crashes are explained by KnownDifferences). Skipped when Node or
 * the installed jsonata package is missing (`cd compat/node && npm ci`). Larger runs: `./gradlew :jsonata-compat:fuzz`.
 */
class FuzzSmokeTest {
    private val node = File(System.getProperty("jsonata.compat"), "node")

    @Test
    fun `fixed-seed fuzzing run has no unexplained differences`() {
        assumeTrue(Sidecar.available(node), "Node 22 with compat/node/node_modules is needed")
        val gen = Generator(20261025)
        val cases = List(4000) { gen.next(it, "smoke") }
        val mismatches = DiffFuzz(node).run(cases)
        val unexplained = mismatches.filter { it.known == null }
        assertTrue(unexplained.isEmpty(), unexplained.joinToString("\n") { "${it.case.id} [${it.category}] ${it.case.expr}\n  input ${it.case.input}\n  expected ${it.expected}\n  actual   ${it.actual}" })
    }
}
