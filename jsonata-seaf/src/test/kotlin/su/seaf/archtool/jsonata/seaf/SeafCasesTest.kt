package su.seaf.archtool.jsonata.seaf

import su.seaf.archtool.jsonata.value.JsonWriter
import su.seaf.archtool.jsonata.value.ObjectValue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** `compat/seaf/cases.jsonl`: core functions of SEAF against the expected results (see the README there). */
class SeafCasesTest {
    private val file = File(System.getProperty("jsonata.compat"), "seaf/cases.jsonl")

    @Test
    fun `core functions give the expected results`() {
        val env = SeafEnvironment.create(SeafEnvironmentTest.NoHost)
        val lines = file.readLines().filter { it.isNotBlank() }
        val mismatches = ArrayList<String>()
        for (line in lines) {
            val c = su.seaf.archtool.jsonata.json.JsonataJson.parse(line) as ObjectValue
            fun text(key: String) = (c[key] as su.seaf.archtool.jsonata.value.StringValue).value
            val id = text("id")
            var result = env.evaluate(env.compile(text("expr")), c["input"])
            val ignore = (c["ignore"] as? su.seaf.archtool.jsonata.value.ArrayValue)?.map { (it as su.seaf.archtool.jsonata.value.StringValue).value }.orEmpty()
            if (ignore.isNotEmpty() && result is ObjectValue) {
                val copy = ObjectValue()
                result.forEach { k, v -> if (k !in ignore) copy.put(k, v) }
                result = copy
            }
            val got = if (result == null) "undefined" else JsonWriter.stringify(result)
            val want = text("json")
            if (got != want) mismatches += "$id: expected $want, got $got"
        }
        assertTrue(lines.isNotEmpty())
        assertTrue(mismatches.isEmpty(), mismatches.joinToString("\n"))
    }
}
