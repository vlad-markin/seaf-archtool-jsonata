package su.seaf.archtool.jsonata.seaf

import su.seaf.archtool.jsonata.Jsonata
import su.seaf.archtool.jsonata.json.JsonataJson
import su.seaf.archtool.jsonata.parser.JsonataException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** SEAF checks before loading and without evaluation (story 3 step 3.5; TC-24 detector part, TC-27 SEAF rules). */
class SeafDetectorTest {
    private val manifest = JsonataJson.parse(
        """{"sum": {"code": "1"}, "wcard": {"code": "2"}, "ok": {"params": [{"alias": "x", "type": "number"}], "code": "x"},
           "strict": {"params": [{"alias": "s", "type": "string", "example": "x"}], "code": "s"},
           "bad_result": {"code": "1", "result": {"type": "nmber"}}}""",
    )

    @Test
    fun `conflicts are the same list the loader fails with (TC-24)`() {
        val conflicts = SeafDetector.manifestConflicts(manifest)
        assertEquals(listOf("sum (stdlib)", "wcard (core)"), conflicts.map { "${it.id} (${it.builtIn})" })
        val e = assertFailsWith<JsonataException> { SeafEnvironment.create(SeafEnvironmentTest.NoHost, manifest) }
        assertEquals("Manifest functions conflict with built-in functions: " + conflicts.joinToString("; "), e.message)
    }

    @Test
    fun `declarations that fail at the first call`() {
        val findings = SeafDetector.invalidDeclarations(manifest)
        assertEquals(listOf("X0017", "X0017"), findings.map { it.id })
        assertEquals("manifest function strict: the входных параметров: strict mode: unknown keyword: \"example\"", findings[0].recommendation)
        assertEquals(true, findings[1].recommendation.startsWith("manifest function bad_result: the результата: schema is invalid: data/type"), findings[1].recommendation)
    }

    @Test
    fun `rules of an expression`() {
        val ast = Jsonata.compile("(\$set('k', 1); \$get('k') & \$jsonschema({})(1) & \$ok(1) & \$wcard('a', 'b') & ('x' ~> \$set('y')))").ast()
        val found = SeafDetector.scan(ast, manifest).map { it.id + "@" + it.position }
        assertEquals(listOf("DEV-15@6", "DEV-15@20", "DEV-14@39", "DEV-14@52", "T1006@64", "DEV-15@89"), found)
        assertEquals(3, SeafDetector.storeCalls(ast).size)
        assertEquals(emptyList(), SeafDetector.scan(Jsonata.compile("\$sum([1])").ast()))
    }
}
