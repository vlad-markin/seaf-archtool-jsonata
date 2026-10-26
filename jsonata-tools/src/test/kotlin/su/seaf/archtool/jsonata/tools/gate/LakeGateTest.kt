package su.seaf.archtool.jsonata.tools.gate

import su.seaf.archtool.jsonata.json.JsonataJson
import su.seaf.archtool.jsonata.tools.Contexts
import su.seaf.archtool.jsonata.tools.FoundExpression
import su.seaf.archtool.jsonata.value.JsonWriter
import su.seaf.archtool.jsonata.value.ObjectValue
import su.seaf.archtool.jsonata.value.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The lake gate with a fake reference and fake backend data (step 1.9; the real run is local, TC-05, TC-22). */
class LakeGateTest {
    private val manifest = JsonataJson.parse("""{"docs": {"a": {"x": 1}}, "src": "s1"}""")

    private object Data : GateData {
        override fun parseSource(source: Value?): Value? = JsonataJson.parse("""{"loaded": ${JsonWriter.stringify(source)}}""")
        override fun manifestSchema(): Value? = null
        override fun versions(): Value? = null
    }

    /** jsonata-js outcomes as the core would give them on this manifest. */
    private val reference = Reference { cases, timing ->
        cases.associate { c ->
            c.id to when (c.expr) {
                "docs.a.x" -> ReferenceOutcome("1", if (timing > 0) 10.0 else null)
                "\$parsesource(src).loaded" -> ReferenceOutcome("\"s1\"", null)
                // DEV-01: jsonata-js updates the input in place
                "(\$r := \$ ~> |\$\$.docs.a|{\"x\": 2}|; \$\$.docs.a.x)" -> ReferenceOutcome("2", null)
                // a real difference: no probe
                "\$string(1)" -> ReferenceOutcome("\"one\"", null)
                else -> ReferenceOutcome("#undefined", null)
            }
        }
    }

    @Test
    fun `differences are explained only by a fired probe`() {
        val found = listOf(
            FoundExpression("q/a.jsonata", "docs.a.x"),
            FoundExpression("q/b.jsonata", "\$parsesource(src).loaded"),
            FoundExpression("q/c.jsonata", "(\$r := \$ ~> |\$\$.docs.a|{\"x\": 2}|; \$\$.docs.a.x)"),
            FoundExpression("q/d.jsonata", "\$string(1)"),
            FoundExpression("q/e.jsonata", "\$self.id"),
            FoundExpression("functions.f.code", "1"),
        )
        val gate = LakeGate(manifest, Data, reference, Contexts.NONE, timing = 3).run(found)
        val s = gate.summary()
        assertEquals(4, (s["evaluations"] as su.seaf.archtool.jsonata.value.NumberValue).value.toInt())
        assertEquals(2, (s["equal"] as su.seaf.archtool.jsonata.value.NumberValue).value.toInt())
        assertEquals("{\"DEV-01\":1}", JsonWriter.stringify(s["explained"]))
        assertEquals(1, (s["unexplained"] as su.seaf.archtool.jsonata.value.NumberValue).value.toInt())
        assertEquals(
            "{\"manifest function code (evaluated through its calls)\":1,\"needs \$self/\$params and has no context\":1}",
            JsonWriter.stringify(s["notEvaluated"]),
        )
        // the summary has no places or texts
        val text = JsonWriter.stringify(s)
        assertTrue(!text.contains("q/") && !text.contains("docs.a"), text)
        assertTrue((s["timing"] as ObjectValue)["worst"] != null)
        // details keep the place for the owner
        assertTrue(JsonWriter.stringify(gate.details()).contains("q/d.jsonata"))
    }

    @Test
    fun `evaluations that would hit the temporary limits are counted`() {
        // a string just above the temporary budget of 2^26 characters: fine for the gate (no limits but the deadline),
        // counted as an evaluation the temporary limits would stop
        val big = FoundExpression("q/big.jsonata", "\$length(\$pad(\"\", 67108865))")
        val small = FoundExpression("q/small.jsonata", "\$length(\$pad(\"\", 10))")
        val gate = LakeGate(manifest, Data, { cases, _ -> cases.associate { it.id to ReferenceOutcome(if (it.place == "q/big.jsonata") "67108865" else "10", null) } }).run(listOf(big, small))
        assertEquals("{\"X0010\":1}", JsonWriter.stringify(gate.summary()["temporaryLimitsHit"]))
        assertEquals(2, (gate.summary()["equal"] as su.seaf.archtool.jsonata.value.NumberValue).value.toInt())
    }
}
