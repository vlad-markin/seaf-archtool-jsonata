package su.seaf.archtool.jsonata.engine

import su.seaf.archtool.jsonata.Jsonata
import su.seaf.archtool.jsonata.value.JsonWriter
import su.seaf.archtool.jsonata.value.Value
import kotlin.test.Test
import kotlin.test.assertEquals

/** Regression tests of review round 2 (`docs/review/round-2.md`); the test JVM runs with -Xmx1g. */
class ReviewRound2Test {
    private fun eval(expr: String, input: Value? = null): String = Jsonata.compile(expr).evaluate(input).let { if (it == null) "undefined" else JsonWriter.stringify(it) }

    @Test
    fun `RV2-1 local functions called in every iteration are not kept until the end`() {
        assertEquals("600010000", eval("\$sum(\$map([1..20000], function(\$v){ (\$t := [1..20000]; \$f := function(\$x){ \$x + \$count(\$t) }; \$f(\$v)) }))"))
        assertEquals("1000000", eval("\$count(\$map([1..1000000], function(\$v){ (\$f := function(\$x){ \$x }; \$f(\$v)) }))"))
    }
}
