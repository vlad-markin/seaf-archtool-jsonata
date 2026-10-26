package su.seaf.archtool.jsonata.tools.gate

import org.junit.jupiter.api.Assumptions.assumeTrue
import su.seaf.archtool.jsonata.Jsonata
import su.seaf.archtool.jsonata.parser.JsonataException
import su.seaf.archtool.jsonata.value.ArrayValue
import su.seaf.archtool.jsonata.value.NumberValue
import su.seaf.archtool.jsonata.value.ObjectValue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** The canonical form of the lake gate is the same on the JVM and in Node (step 1.9, design §2.1 p. 4). */
class CanonicalTest {
    private val node = File(System.getProperty("jsonata.root"), "compat/node")

    private val cases = listOf(
        "[1, 1e21, 0.1 + 0.2, -0, 1/3, 100, 1e-7]",
        "{\"b\": 1, \"2\": 2, \"a\": 3, \"1\": 4, \"01\": 5}",
        "\"é\\u2028\\\"x\"",
        "\$string",
        "[\$string, {\"f\": function(\$x){ \$x }}]",
        "\$lookup([{\"a\": 1}, {\"b\": 2}, {\"a\": [3, 4]}], \"a\")",
        "nothing",
        "null",
        "[true, false, null, \"\"]",
        "1 + \"a\"",
        "\$uppercase(1)",
    )

    private fun jvm(expr: String): String = try {
        Canonical.of(Jsonata.compile(expr).evaluate(null))
    } catch (e: JsonataException) {
        Canonical.error(e)
    }

    @Test
    fun `undefined at any depth and functions are marked`() {
        val a = ArrayValue(2).also { it.add(NumberValue.of(1)) }
        assertEquals("#undefined", Canonical.of(null))
        assertEquals("{\"x\":[1]}", Canonical.of(ObjectValue().also { it.put("x", a) }))
        assertEquals("[\"#function\"]".replace("\"", ""), Canonical.of(Jsonata.compile("[\$string]").evaluate(null)))
    }

    @Test
    fun `the same text as canonical mjs on jsonata 2 2 2`() {
        assumeTrue(File(node, "node_modules/jsonata").isDirectory, "compat/node/node_modules is needed")
        val script = """
            import jsonata from 'jsonata';
            import { canonical, canonicalError } from './canonical.mjs';
            const cases = JSON.parse((await import('node:fs')).readFileSync(process.argv[2], 'utf8'));
            for (const c of cases) {
              let out;
              try { out = canonical(await jsonata(c).evaluate(undefined)); } catch (e) { out = canonicalError(e); }
              console.log(out);
            }
        """.trimIndent()
        val file = File(node, "canonical-check.tmp.mjs").apply { writeText(script) }
        try {
            val json = cases.joinToString(",", "[", "]") { su.seaf.archtool.jsonata.value.JsonWriter.stringify(su.seaf.archtool.jsonata.value.StringValue.of(it)) }
            // the cases go through a UTF-8 file: process arguments use the platform encoding
            val input = File(node, "canonical-check.tmp.json").apply { writeText(json, Charsets.UTF_8) }
            val p = ProcessBuilder("node", file.name, input.name).directory(node).redirectErrorStream(true).start()
            val lines = p.inputStream.bufferedReader(Charsets.UTF_8).readLines()
            p.waitFor()
            assertEquals(cases.map { jvm(it) }, lines)
        } finally {
            file.delete()
            File(node, "canonical-check.tmp.json").delete()
        }
    }
}
