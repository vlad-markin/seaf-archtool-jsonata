package su.seaf.archtool.jsonata.tools

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals

/** `jsonata-tools detect` with SEAF checks (story 3 step 3.5; TC-24 detector part, TC-27). */
class DetectTest {
    private fun detect(expressions: Map<String, String>, functions: String?): Pair<Int, List<String>> {
        val buffer = ByteArrayOutputStream()
        val code = Detect(PrintStream(buffer, true)).run(expressions, Detect.parseFunctions(functions))
        return code to buffer.toString().lines().filter { it.isNotBlank() }
    }

    @Test
    fun `manifest problems fail, expression rules are reported`() {
        val functions = """{"lookup": {"code": "1"}, "check": {"params": [{"alias": "s", "type": "string", "example": "x"}], "code": "s"}}"""
        val (code, lines) = detect(mapOf("q.jsonata" to "(\n  \$set('k', 1);\n  \$base64encode('é')\n)"), functions)
        assertEquals(1, code)
        assertEquals(
            listOf(
                "functions: X0014 lookup is a built-in function (core): rename the manifest function, e.g. to lookup_mf",
                "functions: X0017 manifest function check: the входных параметров: strict mode: unknown keyword: \"example\"",
                "q.jsonata:2:8 DEV-15 \$set: the store of \$set/\$get lives for one evaluation in the engine; in the core it outlives evaluations of a request",
            ),
            lines.take(3),
        )
        assertEquals("q.jsonata:3:17 DEV-11", lines[3].substringBefore(" characters"))
    }

    @Test
    fun `a clean expression gives nothing`() {
        assertEquals(0 to emptyList(), detect(mapOf("a.jsonata" to "\$sum([1, 2])"), null))
        assertEquals(1, detect(mapOf("b.jsonata" to "1 +"), null).first)
    }
}
