package su.seaf.archtool.jsonata.bench

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Every pair of the stand runs at every size; a failure of jsonata-js is recorded, the engine's ratio is kept (RV3-45). */
class StandPairsTest {
    private val root = File(System.getProperty("jsonata.root"))
    private val queries = Queries.load(File(root, "jsonata-bench/queries"))

    @Test
    fun `no size limits on the sorting and grouping pairs`() {
        // TC-21: 200 000 / 50 000 for sorting and grouping; fib and other queries that do not read the model stay at one size
        for (q in queries.filter { it.name in setOf("group_agg", "group_nested", "sort_fn", "orderby") }) {
            assertEquals(null, q.sizes, q.name)
            assertEquals(null, q.jsSizes, q.name)
        }
    }

    @Test
    fun `the summary shows a failure of the reference and the engine ratio`() {
        val dir = kotlin.io.path.createTempDirectory("stand").toFile()
        val results = File(dir, "results.jsonl")
        results.writeText(
            """{"engine":"jsonata-kotlin","query":"sort_fn","size":50000,"eval_ms_median":10,"eval_ms_p95":11}
              |{"engine":"jsonata-kotlin","query":"sort_fn","size":200000,"eval_ms_median":70,"eval_ms_p95":71}
              |{"engine":"jsonata-kotlin","query":"group_agg","size":50000,"eval_ms_median":30,"eval_ms_p95":31}
              |{"engine":"jsonata-kotlin","query":"group_agg","size":200000,"eval_ms_median":150,"eval_ms_p95":151}
              |""".trimMargin(),
        )
        recordFailure(results, "jsonata-js", "sort_fn", 200_000, "OOM")
        recordFailure(results, "jsonata-js", "group_agg", 200_000, "таймаут")
        // a pair the runner already wrote is not overwritten
        recordFailure(results, "jsonata-kotlin", "sort_fn", 200_000, "OOM")
        val text = summary(results, File(dir, "dumps"), queries.filter { it.name in setOf("sort_fn", "group_agg") }, listOf(50_000, 200_000), emptyList())
        assertTrue(text.contains("| sort_fn | 200000 | 70.00 / 71.00 | JS: OOM |"), text)
        assertTrue(text.contains("| group_agg | 200000 | 150.00 / 151.00 | JS: таймаут |"), text)
        assertTrue(!text.contains("не запускался"), text)
        assertTrue(text.contains("| sort_fn | 10.00 | 70.00 | **7.00** |"), text)
        assertTrue(text.contains("| group_agg | 30.00 | 150.00 | 5.00 |"), text)
    }

    @Test
    fun `an OOM and a timeout of jsonata-js are told apart`() {
        assertEquals("таймаут", jsFailure(ProcessOutcome(-1, true, "")))
        assertEquals(null, jsFailure(ProcessOutcome(0, false, "")))
        assertEquals("ошибка (код 1)", jsFailure(ProcessOutcome(1, false, "TypeError")))
        val nodeDir = File(root, "compat/node")
        val node = runCatching { ProcessBuilder("node", "--version").start().waitFor() == 0 }.getOrDefault(false)
        assumeTrue(node && File(nodeDir, "node_modules/jsonata").isDirectory, "Node with compat/node/node_modules is needed")
        val dir = kotlin.io.path.createTempDirectory("stand").toFile()
        val model = File(dir, "model.json").also { f -> f.bufferedWriter().use { Generator.write(10_000, it) } }
        val cmd = { heap: Int -> listOf("node", "--expose-gc", "--max-old-space-size=$heap", "compat/node/stand.mjs", "--data=${model.path}", "--size=10000",
            "--queries=${File(root, "jsonata-bench/queries").path}", "--out=${File(dir, "r.jsonl").path}", "--only=sort_fn", "--warmup=1", "--iters=1") }
        // an artificially small heap: the reference runs out of memory
        assertEquals("OOM", jsFailure(runLogged(cmd(8), root, 120)))
        // an artificially short time
        assertEquals("таймаут", jsFailure(runLogged(cmd(512), root, 0)))
    }
}
