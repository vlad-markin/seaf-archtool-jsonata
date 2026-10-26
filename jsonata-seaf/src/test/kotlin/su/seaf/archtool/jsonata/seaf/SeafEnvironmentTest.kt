package su.seaf.archtool.jsonata.seaf

import su.seaf.archtool.jsonata.CallContext
import su.seaf.archtool.jsonata.EvaluationOptions
import su.seaf.archtool.jsonata.FunctionRegistry
import su.seaf.archtool.jsonata.json.JsonataJson
import su.seaf.archtool.jsonata.parser.JsonataException
import su.seaf.archtool.jsonata.value.JsonWriter
import su.seaf.archtool.jsonata.value.StringValue
import su.seaf.archtool.jsonata.value.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The SEAF environment: bindings of a request, the temporary limits (story 3 step 3.1). */
class SeafEnvironmentTest {
    internal object NoHost : SeafHost {
        override fun loadSource(manifest: Value?, source: Value?, request: SeafRequest?, context: CallContext): Value? = null
        override fun manifestSchema(): Value? = null
        override fun versions(): Value? = null
        override fun log(content: Value?, tag: Value?) {}
    }

    private fun json(text: String): Value? = JsonataJson.parse(text)
    private fun str(v: Value?) = if (v == null) "undefined" else JsonWriter.stringify(v)

    @Test
    fun `self and params are bound for the expression`() {
        val env = SeafEnvironment.create(NoHost)
        val e = env.compile("[\$self.id, \$params.q, a]")
        assertEquals("[\"s\",\"p\",1]", str(env.evaluate(e, json("{\"a\":1}"), json("{\"id\":\"s\"}"), json("{\"q\":\"p\"}"))))
        assertEquals("[1]", str(env.evaluate(e, json("{\"a\":1}"))))
        assertTrue(env.registry.isFrozen)
    }

    @Test
    fun `the request reaches host functions`() {
        val env = SeafEnvironment.create(NoHost)
        val request = SeafRequest(mapOf("user" to "u1"))
        val seen = arrayOfNulls<Any>(1)
        val functions = env.registry.extend().register("who", "<:s>", { c, _ ->
            seen[0] = c.attribute(SeafEnvironment.REQUEST)
            StringValue.of(((seen[0] as SeafRequest).attributes["user"]).toString())
        }).freeze()
        val e = env.compile("\$who()")
        val options = EvaluationOptions(timeoutMillis = 1000, functions = functions)
        assertEquals("\"u1\"", str(env.evaluate(e, null, request = request, options = options)))
        assertSame(request, seen[0])
    }

    @Test
    fun `temporary limits`() {
        val t = SeafLimits.TEMPORARY
        assertEquals(60_000, t.timeoutMillis)
        assertEquals(1 shl 26, t.budgets.maxStringLength)
        assertEquals(1L shl 28, t.budgets.maxResultSize)
        assertEquals(0, t.budgets.maxSteps)
        assertEquals(EvaluationOptions.DEFAULT_MAX_DEPTH, t.options().maxDepth)
        assertEquals(EvaluationOptions.DEFAULT_MAX_SEQUENCE, t.options().maxSequence)
        val env = SeafEnvironment.create(NoHost)
        // the string budget: 2^27 characters fail before they are built
        val big = env.compile("\$pad('', 134217728)")
        val error = assertFailsWith<JsonataException> { env.evaluate(big, null) }
        assertEquals("X0010", error.code)
        // a manifest-size recursion passes: 100 000 levels under the default depth
        val rec = env.compile("(\$f := function(\$n){ \$n = 0 ? 0 : 1 + \$f(\$n - 1) }; \$f(100000))")
        assertEquals("100000", str(env.evaluate(rec, null)))
        val loop = env.compile("(\$f := function(\$n){ 1 + \$f(\$n) }; \$f(0))")
        assertEquals("D1011", assertFailsWith<JsonataException> { env.evaluate(loop, null) }.code)
        assertTrue(FunctionRegistry.standard().isFrozen)
    }
}
