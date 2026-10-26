package su.seaf.archtool.jsonata.compat

import org.junit.jupiter.api.Assumptions.assumeTrue
import su.seaf.archtool.jsonata.compat.fuzz.DiffFuzz
import su.seaf.archtool.jsonata.compat.fuzz.FuzzCase
import su.seaf.archtool.jsonata.compat.fuzz.KnownDifferences
import su.seaf.archtool.jsonata.compat.fuzz.Sidecar
import su.seaf.archtool.jsonata.compat.fuzz.readCases
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The fuzzing classifier and replay (review round 2: RV2-4, RV2-5, RV2-7, RV2-9, RV2-10, owner's decision on the token). */
class FuzzClassifierTest {
    private val node = File(System.getProperty("jsonata.compat"), "node")

    private fun case(expr: String, input: String? = null, bindings: String? = null) =
        FuzzCase("t", "test", expr, input?.let { mapper.readTree(it) }, bindings?.let { mapper.readTree(it) })

    @Test
    fun `different codes in a constructor are not explained without the probe (RV2-4)`() {
        assertNull(KnownDifferences.explain(case("{\"a\":1,\"b\":x}"), "error T2001", "error T2002"))
        assertNull(KnownDifferences.explain(case("\$f([1,2])"), "error T1006", "error T2002"))
    }

    @Test
    fun `a shared regex variable in a constructor is DEV-09 (RV2-9)`() {
        assertTrue(KnownDifferences.sharedRegexInConstructor("(\$r := /a/; [\$match(\"abab\", \$r), \$replace(\"aa\", \$r, \"b\"), \$split(\"bab\", \$r), \$contains(\"xa\", \$r)])"))
        assertFalse(KnownDifferences.sharedRegexInConstructor("(\$r := /a/; [\$replace(\"aa\", \$r, \"b\"), 1])"))
        assertFalse(KnownDifferences.sharedRegexInConstructor("[\$replace(\"aa\", /a/, \"b\"), \$replace(\"aa\", /a/, \"b\")]"))
    }

    @Test
    fun `partial application giving another value is DEV-03 (RV2-10)`() {
        assertTrue(KnownDifferences.explain(case("\$count(?)(5)"), "undefined", "json 1")!!.startsWith("DEV-03"))
        assertNull(KnownDifferences.explain(case("\$count([5])"), "undefined", "json 1"))
    }

    @Test
    fun `positions are explained only on a confirming sign (RV2-4, DEV-17)`() {
        assumeTrue(Sidecar.available(node), Sidecar.unavailable(node))
        val fuzz = DiffFuzz(node)
        // a position the fresh reference does not give stays unexplained
        assertNull(fuzz.classify(case("1 + \"a\""), "error T2002 @9 +", "error T2002 @7 +").known)
        // a fresh reference agreeing with the engine: instability of the shared function object
        val agree = fuzz.classify(case("1 + \"a\""), "error T2002 @9 +", "error T2002 @3 +")
        assertTrue(agree.known!!.startsWith("reference instability"), agree.known)
        // the owner's decision: the token of a library function passed as a value is its name
        val got = fuzz.outcomeOf(case("null ~> \$uppercase ~> \$trim", "\"x\""))
        val dev = fuzz.classify(case("null ~> \$uppercase ~> \$trim", "\"x\""), "error T0410 @null null", got)
        assertTrue(dev.known!!.startsWith("DEV-17"), "$got: ${dev.known}")
        assertEquals(2, fuzz.explainedByPosition.size)
    }

    @Test
    fun `replay passes bindings to both engines and rejects unknown fields (RV2-5)`() {
        val file = File.createTempFile("replay", ".jsonl").apply { deleteOnExit() }
        file.writeText("{\"id\":\"b1\",\"expr\":\"\$x + 1\",\"bindings\":{\"x\":1}}\n{\"expr\":\"a\",\"data\":{\"a\":5}}\n")
        val cases = readCases(file)
        assertEquals("{\"x\":1}", cases[0].bindings.toString())
        assertEquals("{\"a\":5}", cases[1].input.toString())
        val bad = File.createTempFile("replay", ".jsonl").apply { deleteOnExit(); writeText("{\"expr\":\"1\",\"vars\":{}}\n") }
        assertFailsWith<IllegalArgumentException> { readCases(bad) }
        assumeTrue(Sidecar.available(node), Sidecar.unavailable(node))
        val fuzz = DiffFuzz(node)
        assertEquals("json 2", fuzz.outcomeOf(cases[0]))
        assertEquals("json 2", Sidecar(node).use { it.evaluate(listOf(cases[0])).getValue("b1") })
        assertTrue(fuzz.run(cases).isEmpty())
    }

    @Test
    fun `a case the reference does not finish does not stop the run (RV2-5)`() {
        assumeTrue(Sidecar.available(node), Sidecar.unavailable(node))
        val hang = FuzzCase("hang", "test", "(\$f := function(\$n){ \$f(\$n + 1) }; \$f(0))", null)
        val ok = FuzzCase("ok", "test", "1 + 1", null)
        val r = Sidecar(node, caseTimeoutMillis = 2000).use { it.evaluate(listOf(hang, ok)) }
        assertEquals("reference-timeout", r["hang"])
        assertEquals("json 2", r["ok"])
    }

    @Test
    fun `the Node major is checked (RV2-7)`() {
        assertEquals(22, Sidecar.requiredNodeMajor(node))
        val have = Sidecar.nodeMajor()
        if (have != null && have != 22) assertTrue(Sidecar.unavailable(node)!!.contains("Node 22 is needed"))
    }
}
