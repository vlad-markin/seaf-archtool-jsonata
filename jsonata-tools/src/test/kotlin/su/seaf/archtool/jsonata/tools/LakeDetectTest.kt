package su.seaf.archtool.jsonata.tools

import su.seaf.archtool.jsonata.json.JsonataJson
import su.seaf.archtool.jsonata.value.JsonWriter
import su.seaf.archtool.jsonata.value.ObjectValue
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The detector over a lake: may be affected vs confirmed, always with the coverage (step 1.8; RV-22, TC-27). */
class LakeDetectTest {
    private fun lake(): File {
        val dir = Files.createTempDirectory("lake").toFile()
        File(dir, "queries").mkdirs()
        // a transform over $$: static DEV-01, and the probe fires when evaluated on the manifest
        File(dir, "queries/transform.jsonata").writeText("(\$r := \$ ~> |\$\$.docs|{\"x\": 1}|; \$r.docs.x)")
        // $toMillis with one argument: static DEV-07; the probe fires only on a date-time without an offset
        File(dir, "queries/dates.jsonata").writeText("\$toMillis(\"2024-01-01\")")
        File(dir, "queries/self.jsonata").writeText("\$self.id & \$params.p")
        File(dir, "queries/broken.jsonata").writeText("1 +")
        File(dir, "node_modules").mkdirs()
        File(dir, "node_modules/skip.jsonata").writeText("\$toMillis(x)")
        return dir
    }

    private val manifest = JsonataJson.parse(
        """{"docs": {"a": {"title": "A"}},
            "functions": {"twice": {"params": [{"alias": "n", "type": "number"}], "code": "n * 2"}},
            "datasets": {"d1": {"source": "${'$'}twice(2)"}, "d2": {"source": "data/file.yaml"}, "d3": {"source": "${'$'}toMillis('2024-01-01T10:00:00')"}}}""",
    )

    @Test
    fun `static findings, probe confirmations and coverage`() {
        val dir = lake()
        val found = LakeScan.files(dir) + LakeScan.manifest(manifest)
        assertEquals(
            listOf("queries/broken.jsonata", "queries/dates.jsonata", "queries/self.jsonata", "queries/transform.jsonata", "functions.twice.code", "datasets.d1.source", "datasets.d3.source"),
            found.map { it.place },
        )
        val contexts = File(dir, "contexts.jsonl").apply { writeText("{\"place\": \"queries/self.jsonata\", \"self\": {\"id\": \"s\"}, \"params\": {\"p\": \"1\"}}\n") }
        val d = LakeDetect(manifest, Contexts.read(contexts)).run(found)
        val s = d.summary()
        val may = s["mayBeAffected"] as ObjectValue
        assertEquals("{\"DEV-01\":1,\"DEV-07\":2,\"DEV-14\":1}", JsonWriter.stringify(may))
        val confirmed = s["confirmed"] as ObjectValue
        assertEquals("{\"DEV-01\":{\"expressions\":1,\"evaluations\":1},\"DEV-07\":{\"expressions\":1,\"evaluations\":1}}", JsonWriter.stringify(confirmed))
        val coverage = s["coverage"] as ObjectValue
        assertEquals(
            "{\"expressions\":7,\"evaluated\":5,\"evaluations\":5,\"contexts\":1,\"notEvaluated\":{\"manifest function code (evaluated through its calls)\":1},\"failed\":{\"syntax S0207\":1}}",
            JsonWriter.stringify(coverage),
        )
        assertEquals(1, d.problems)
        val text = d.text()
        assertTrue(text.contains("may be affected (static rules): DEV-01 in 1, DEV-07 in 2, DEV-14 in 1"), text)
        assertTrue(text.contains("confirmed (probe fired): DEV-01 in 1 (1 evaluations), DEV-07 in 1 (1 evaluations)"), text)
        assertTrue(d.details.any { it.place == "queries/transform.jsonata" && it.kind == "confirmed" && it.id == "DEV-01" })
    }

    @Test
    fun `without a context or a manifest nothing is called certain`() {
        val dir = lake()
        val noContexts = LakeDetect(manifest).run(LakeScan.files(dir))
        val c = noContexts.summary()["coverage"] as ObjectValue
        assertTrue(JsonWriter.stringify(c).contains("\"needs \$self/\$params and has no context (--contexts)\":1"), JsonWriter.stringify(c))
        val static = LakeDetect(null).run(LakeScan.files(dir))
        assertEquals("{}", JsonWriter.stringify(static.summary()["confirmed"]))
        assertTrue(JsonWriter.stringify(static.summary()["coverage"]).contains("\"evaluated\":0"))
    }

    @Test
    fun `manifest conflicts are problems`() {
        val bad = JsonataJson.parse("""{"functions": {"sum": {"code": "1"}}}""")
        val d = LakeDetect(bad).run(LakeScan.manifest(bad))
        assertEquals(1, d.problems)
        assertTrue(d.text().contains("manifest functions conflict (X0014): sum (stdlib)"))
        assertTrue(LakeScan.looksLikeExpression("\$sum(x)"))
        assertTrue(!LakeScan.looksLikeExpression("a/b/c.yaml"))
        assertTrue(!LakeScan.looksLikeExpression("https://example.com/x"))
    }

    @Test
    fun `summary json has no ids of manifest functions (RV3-46)`() {
        val bad = JsonataJson.parse("""{"functions": {"lookup": {"code": "1"}, "string": {"code": "2"}, "fine": {"code": "3", "params": {"a": 1}}}}""")
        val d = LakeDetect(bad).run(LakeScan.manifest(bad))
        val summary = su.seaf.archtool.jsonata.value.JsonWriter.stringify(d.summary())
        assertTrue(listOf("lookup", "\"string\"", "fine", "rename").none { summary.contains(it) }, summary)
        assertEquals("{\"core\":1,\"stdlib\":1}", su.seaf.archtool.jsonata.value.JsonWriter.stringify(d.summary()["manifestConflicts"]))
        // the places and recommendations are in details.json
        val details = su.seaf.archtool.jsonata.value.JsonWriter.stringify(d.detailsValue())
        assertTrue(details.contains("manifest:functions/lookup") && details.contains("e.g. to lookup_mf") && details.contains("manifest function fine"), details)
    }
}
