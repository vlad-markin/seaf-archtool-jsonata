package su.seaf.archtool.jsonata.tools

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The console debugger as a scripted scenario (story 5 step 5.6, TC-12). */
class DebugCommandTest {
    private val expression = """(
  ${'$'}rate := 2;
  ${'$'}total := function(${'$'}p){ ${'$'}p.Price * ${'$'}p.Quantity };
  Account.Order.{
    "id": OrderID,
    "sum": ${'$'}sum(Product.(${'$'}total(${'$'}) * ${'$'}rate))
  }
)"""

    private val input = """{"Account":{"Order":[
        {"OrderID":"o1","Product":[{"Name":"Hat","Price":10,"Quantity":2},{"Name":"Cap","Price":5,"Quantity":1}]},
        {"OrderID":"o2","Product":[{"Name":"Coat","Price":12,"Quantity":3}]}]}}"""

    private fun session(script: String): Pair<Int, String> {
        val buffer = ByteArrayOutputStream()
        val code = DebugCommand(expression, input, script.reader().buffered(), PrintStream(buffer, true)).run()
        return code to buffer.toString()
    }

    @Test
    fun `breakpoints stack variables evaluate and step out`() {
        val star = expression.lines()[2].indexOf("*") + 1
        val (code, out) = session("""
            b 5:12
            b 3:$star if ${'$'}p.Price > 5
            r
            c
            bt
            vars
            e ${'$'}p.Name & ": " & ${'$'}p.Price * ${'$'}p.Quantity
            o
            c
            c
            c
        """.trimIndent())
        assertEquals(0, code, out)
        assertTrue(out.contains("breakpoint 1 at 5:11-5:18 OrderID"), out)
        assertTrue(out.contains("stopped (breakpoint) at 5:11-5:18 OrderID"), out)
        assertTrue(out.contains("#0 total at 3:"), out)
        assertTrue(out.contains("#1 <main> at 6:"), out)
        assertTrue(out.contains("Arguments:\n  p = {\"Name\":\"Hat\",\"Price\":10,\"Quantity\":2}"), out)
        assertTrue(out.contains("\"Hat: 20\""), out)
        assertTrue(out.contains("=> 20"), out)
        assertTrue(out.contains("result: [{\"id\":\"o1\",\"sum\":50},{\"id\":\"o2\",\"sum\":72}]"), out)
    }

    @Test
    fun `stop on an error and quit`() {
        val (code, out) = session("catch\nr\nq\n")
        assertEquals(0, code, out) // no error in this expression: it runs to the end
        val buf = ByteArrayOutputStream()
        val code2 = DebugCommand("[1, 2].(\$ + \"x\")", null, "catch\nr\nbt\nc\n".reader().buffered(), PrintStream(buf, true)).run()
        val out2 = buf.toString()
        assertEquals(1, code2)
        assertTrue(out2.contains("stopped (exception) at 1:9-1:16 \$ + \"x\""), out2)
        assertTrue(out2.contains("error: "), out2)
    }
}
