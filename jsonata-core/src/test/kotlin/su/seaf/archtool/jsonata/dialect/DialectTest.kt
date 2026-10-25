package su.seaf.archtool.jsonata.dialect

import su.seaf.archtool.jsonata.FunctionRegistry
import su.seaf.archtool.jsonata.Jsonata
import su.seaf.archtool.jsonata.engine.NativeFunction
import su.seaf.archtool.jsonata.parser.JsonataException
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The dialect is frozen on jsonata-js 2.2.2 (decision 13, CAP-3; TC-07, TC-08). */
class DialectTest {
    /** The vendored reference; the test runs with the module directory as working directory. */
    private val reference = File("../compat/jsonata-js/src/jsonata.js").readText()

    /** `staticFrame.bind(name, defineFunction(impl, signature))` and the `now`/`millis` bound per evaluation. */
    private fun referenceLibrary(): Map<String, String> {
        val static = Regex("""staticFrame\.bind\('(\w+)', defineFunction\([\w.]+, '([^']*)'\)\);""").findAll(reference)
            .associate { it.groupValues[1] to it.groupValues[2] }
        val perEvaluation = Regex("""environment\.bind\('(now|millis)', defineFunction\(function\([^)]*\) \{[^}]*}, '([^']*)'\)\);""")
            .findAll(reference).associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals(63, static.size, "staticFrame.bind entries of jsonata.js 2.2.2")
        assertEquals(setOf("now", "millis"), perEvaluation.keys)
        return static + perEvaluation
    }

    @Test
    fun `the standard library is exactly the 65 functions of jsonata-js 2_2_2 with their signatures`() {
        val expected = referenceLibrary()
        val standard = FunctionRegistry.standard()
        val names = standard.ownNames()
        assertEquals(65, expected.size)
        assertEquals(expected.keys.sorted(), names.sorted(), "names of the standard library")
        val wrong = expected.filter { (name, sig) -> (standard.lookup(name) as NativeFunction).signature?.definition != sig }
            .map { (name, sig) -> "$name: ${(standard.lookup(name) as NativeFunction).signature?.definition} instead of $sig" }
        assertTrue(wrong.isEmpty(), "signatures differ from jsonata.js 2.2.2: $wrong")
    }

    @Test
    fun `a function that jsonata-js 2_2_2 does not have is not callable`() {
        val e = assertFailsWith<JsonataException> { Jsonata.compile("\$foo(1)").evaluate(null) }
        assertEquals("T1006", e.code)
        assertEquals("foo", e.token)
        assertEquals("Attempted to invoke a non-function", e.message)
        // functions of later JSONata versions are not there either
        for (later in listOf("\$hash('a')", "\$distinctBy([1], function(\$v){\$v})", "\$uuid()")) {
            assertEquals("T1006", assertFailsWith<JsonataException>(later) { Jsonata.compile(later).evaluate(null) }.code, later)
        }
    }
}
