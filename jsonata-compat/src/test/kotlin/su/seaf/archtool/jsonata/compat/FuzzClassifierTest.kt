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
    fun `DEV-08 only for an empty array item and the rewrites of DEV-03 and DEV-09 (RV3-8)`() {
        val clone = case("[[], \"x\"] ~> |\$|{}|")
        assertTrue(KnownDifferences.explain(clone, "json [[null],\"x\"]", "json [[],\"x\"]")!!.startsWith("DEV-08"))
        // inside a string, with another difference, or with no empty array to push into: not DEV-08
        assertNull(KnownDifferences.explain(case("{\"a\": \"[null]\"}"), "json {\"a\":\"[null]\"}", "json {\"a\":\"[]\"}"))
        assertNull(KnownDifferences.explain(case("[[], {\"k\": 1}]"), "json [[null],{\"k\":2}]", "json [[],{\"k\":1}]"))
        assertNull(KnownDifferences.explain(case("{\"a\": [1]}"), "json {\"a\":[null]}", "json {\"a\":[]}"))
        // DEV-03 and DEV-09 are not explained by the text alone
        assertNull(KnownDifferences.explain(case("\$count(?)(5)"), "undefined", "json 1"))
        assertEquals("(function(\$__p1){ \$count(\$__p1) })(5)", KnownDifferences.rewritePartials("\$count(?)(5)"))
        assertEquals("(function(\$__p1){ \$substring(\"abc\",\$__p1, 1) })(1)", KnownDifferences.rewritePartials("\$substring(\"abc\", ?, 1)(1)"))
        assertNull(KnownDifferences.rewritePartials("\$count([5])"))
        assertEquals("(\$r := /a/i; [\$contains(\"A\", /a/i), \$contains(\"b\", /a/i)])",
            KnownDifferences.rewriteSharedRegex("(\$r := /a/i; [\$contains(\"A\", \$r), \$contains(\"b\", \$r)])"))
    }

    /** The engine with a regression: every `1 + 1` gives 3, in the case and in its rewritten form alike. */
    private class Regressed(node: File) : DiffFuzz(node) {
        override fun outcomeOf(c: FuzzCase): String = super.outcomeOf(c).let { if (c.expr.contains("1 + 1")) it.replace("2]", "3]") else it }
    }

    @Test
    fun `DEV-03 and DEV-09 only when the rewritten case agrees (RV3-8)`() {
        assumeTrue(Sidecar.available(node), Sidecar.unavailable(node))
        fun known(fuzz: DiffFuzz, c: FuzzCase): String? {
            val got = fuzz.outcomeOf(c)
            val want = Sidecar.fresh(node, c)
            assertTrue(want != got, "no difference: $got")
            return fuzz.classify(c, want, got).known
        }
        val fuzz = DiffFuzz(node)
        val regressed = Regressed(node)
        // DEV-03: the probe fires, and a direct call makes the reference agree
        assertTrue(known(fuzz, case("\$count(?)(5)"))!!.startsWith("DEV-03"), known(fuzz, case("\$count(?)(5)")))
        // the probe fires too, but the other difference stays: unexplained
        assertNull(known(regressed, case("[\$count(?)(5), 1 + 1]")))
        // DEV-09: separate regex literals make the reference agree
        val shared = "(\$r := /a/; [\$match(\"abab\", \$r), \$replace(\"aa\", \$r, \"b\"), \$split(\"bab\", \$r), \$contains(\"xa\", \$r)])"
        assertTrue(known(fuzz, case(shared))!!.startsWith("DEV-09"))
        // a shared regex variable where the difference is elsewhere: unexplained
        assertNull(known(regressed, case("(\$r := /a/; [\$contains(\"a\", \$r), \$contains(\"b\", \$r), 1 + 1])")))
        assertNull(known(regressed, case("(\$r := /a/; [\$match(\"abab\", \$r), \$replace(\"aa\", \$r, \"b\"), 1 + 1])")))
        // DEV-08 still explained (its probe)
        assertTrue(known(fuzz, clone())!!.startsWith("DEV-08"))
    }

    private fun clone() = case("[[], \"x\"] ~> |\$|{}|")

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
    fun `the Node major is checked (RV2-7, RV3-29)`() {
        assertEquals(22, Sidecar.requiredNodeMajor(node))
        // the comparison itself, without the environment of this machine
        assertNull(Sidecar.nodeVersionProblem(22, 22))
        assertEquals("Node 22 is needed (compat/node/package.json), found Node 24", Sidecar.nodeVersionProblem(24, 22))
        assertEquals("node is not on the path", Sidecar.nodeVersionProblem(null, 22))
        assertNull(Sidecar.nodeVersionProblem(24, null))
    }
}
