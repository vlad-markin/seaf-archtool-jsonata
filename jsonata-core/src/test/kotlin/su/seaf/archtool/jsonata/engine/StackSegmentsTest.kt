package su.seaf.archtool.jsonata.engine

import su.seaf.archtool.jsonata.CompileOptions
import su.seaf.archtool.jsonata.FunctionRegistry
import su.seaf.archtool.jsonata.Jsonata
import su.seaf.archtool.jsonata.StackSegments
import su.seaf.archtool.jsonata.parser.JsonataException
import su.seaf.archtool.jsonata.value.JsonWriter
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Stack segments of deep evaluations (review RV-4, chief architect's condition U1). */
class StackSegmentsTest {
    private fun eval(expr: String): String = JsonWriter.stringify(Jsonata.compile(expr).evaluate(null))

    /** Recursion through a parameter: no `$f := function(){ $f() }` binding, so no static hint to start on a segment. */
    private val hiddenRecursion = "(\$g := function(\$h, \$n){ \$n = 0 ? 0 : 1 + \$h(\$h, \$n - 1) }; \$sum([1..2000].\$g(\$g, 40)))"

    @Test
    fun `crossing the first segment threshold reuses segment threads`() {
        val expr = Jsonata.compile(hiddenRecursion)
        val created0 = StackSegments.threadsCreated
        val entered0 = StackSegments.entered
        assertEquals("80000", JsonWriter.stringify(expr.evaluate(null)))
        assertTrue(StackSegments.entered - entered0 >= 2000, "every element crosses the threshold")
        assertTrue(StackSegments.threadsCreated - created0 <= 8, "${StackSegments.threadsCreated - created0} threads for ${StackSegments.entered - entered0} segments")
        // the expression is now known to go deep: the next evaluation starts on a segment, one hand-over in all
        val entered1 = StackSegments.entered
        assertEquals("80000", JsonWriter.stringify(expr.evaluate(null)))
        assertEquals(1, StackSegments.entered - entered1)
    }

    @Test
    fun `a recursive binding starts on a segment`() {
        val entered0 = StackSegments.entered
        assertEquals("80000", eval("(\$f := function(\$n){ \$n = 0 ? 0 : 1 + \$f(\$n - 1) }; \$sum([1..2000].\$f(40)))"))
        assertEquals(1, StackSegments.entered - entered0)
    }

    @Test
    fun `the process budget of segments stops a deeper evaluation with X0005`() {
        val saved = StackSegments.budget
        try {
            StackSegments.budget = 1
            // one segment holds about 4 000 levels of this recursion; 30 000 levels need several at once
            val e = assertFailsWith<JsonataException> { eval("(\$f := function(\$n){ \$n = 0 ? 0 : 1 + \$f(\$n - 1) }; \$f(30000))") }
            assertEquals("X0005", e.code)
            assertTrue(e.message!!.contains("1 running at once"), e.message)
            assertEquals("3000", eval("(\$f := function(\$n){ \$n = 0 ? 0 : 1 + \$f(\$n - 1) }; \$f(3000))"))
            StackSegments.budget = 0
            assertEquals("X0005", assertFailsWith<JsonataException> { eval(hiddenRecursion) }.code)
            assertEquals("10", eval("(\$g := function(\$h, \$n){ \$n = 0 ? 0 : 1 + \$h(\$h, \$n - 1) }; \$g(\$g, 10))"))
        } finally {
            StackSegments.budget = saved
        }
        assertEquals(0, StackSegments.active)
    }

    @Test
    fun `an exception from a host function crosses segments as the same object`() {
        val boom = IllegalStateException("host failure")
        val registry = FunctionRegistry.create().register("boom", "<n:n>", { _, _ -> throw boom })
        val expr = Jsonata.compile("(\$f := function(\$n){ \$n = 0 ? \$boom(0) : 1 + \$f(\$n - 1) }; \$f(20000))", CompileOptions(registry))
        assertSame(boom, assertFailsWith<IllegalStateException> { expr.evaluate(null) })
    }

    @Test
    fun `deep recursion from a virtual thread`() {
        var result: String? = null
        var failure: Throwable? = null
        val vt = Thread.ofVirtual().start {
            try {
                result = eval("(\$f := function(\$n){ \$n = 0 ? 0 : 1 + \$f(\$n - 1) }; \$f(100000))")
            } catch (e: Throwable) {
                failure = e
            }
        }
        vt.join()
        failure?.let { throw it }
        assertEquals("100000", result)
    }

    @Test
    fun `many evaluations go deep at once`() {
        val expr = Jsonata.compile(hiddenRecursion)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        try {
            val results = (1..16).map { pool.submit<String> { JsonWriter.stringify(expr.evaluate(null)) } }.map { it.get() }
            assertTrue(results.all { it == "80000" }, results.toString())
        } finally {
            pool.shutdown()
        }
    }

    /** §2.0 p. 7 of the design: waiting at the internal boundary must not pin a virtual thread's carrier. */
    @Test
    fun `no synchronized or Thread join in the engine`() {
        val sources = File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(sources.size > 10, "sources not found from ${File(".").absolutePath}")
        val forbidden = Regex("""synchronized\s*\(|@Synchronized|\.join\(\s*\)|Thread\.join|\.wait\(\s*\)""")
        val hits = sources.flatMap { f -> f.readLines().mapIndexedNotNull { i, line -> if (!isComment(line) && forbidden.containsMatchIn(line)) "${f.name}:${i + 1}: ${line.trim()}" else null } }
        assertEquals(emptyList(), hits)
    }

    private fun isComment(line: String): Boolean = line.trimStart().let { it.startsWith("*") || it.startsWith("//") || it.startsWith("/*") }
}
