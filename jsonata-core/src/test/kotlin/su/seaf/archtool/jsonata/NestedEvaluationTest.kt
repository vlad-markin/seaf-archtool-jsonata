package su.seaf.archtool.jsonata

import su.seaf.archtool.jsonata.debug.DebugFrame
import su.seaf.archtool.jsonata.debug.EvaluationListener
import su.seaf.archtool.jsonata.debug.Iteration
import su.seaf.archtool.jsonata.debug.NodeInfo
import su.seaf.archtool.jsonata.parser.JsonataException
import su.seaf.archtool.jsonata.value.JsonWriter
import su.seaf.archtool.jsonata.value.NumberValue
import su.seaf.archtool.jsonata.value.StringValue
import su.seaf.archtool.jsonata.value.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** `CallContext.local`, `attribute` and `evaluate`: the means host functions build manifest functions with (story 3 step 3.1). */
class NestedEvaluationTest {
    private fun json(text: String): Value? = Jsonata.compile(text).evaluate(null)
    private fun str(v: Value?) = if (v == null) "undefined" else JsonWriter.stringify(v)

    /** `$run(code, input)`: evaluates a nested expression compiled lazily with [registry]. */
    private fun withRun(base: FunctionRegistry = FunctionRegistry.create()): FunctionRegistry {
        lateinit var frozen: FunctionRegistry
        val cache = HashMap<String, Jsonata>()
        val r = base.register("run", "<sx?o?:x>", { c, a ->
            val code = (a[0] as StringValue).value
            val expr = synchronized(cache) { cache.getOrPut(code) { Jsonata.compile(code, CompileOptions(frozen)) } }
            val bindings = (a.getOrNull(2) as? su.seaf.archtool.jsonata.value.ObjectValue)?.entries?.toMap() ?: emptyMap()
            c.evaluate(expr, a.getOrNull(1), bindings, "nested")
        })
        r.register("put", "<sx:x>", { c, a -> c.local("store") { HashMap<String, Value?>() }[(a[0] as StringValue).value] = a[1]; a[1] })
        r.register("take", "<s:x>", { c, a -> c.local("store") { HashMap<String, Value?>() }[(a[0] as StringValue).value] })
        r.register("attr", "<s:x>", { c, a -> c.attribute((a[0] as StringValue).value)?.let { StringValue.of(it.toString()) } })
        frozen = r.freeze()
        return frozen
    }

    private fun eval(expr: String, input: Value? = null, options: EvaluationOptions = EvaluationOptions.DEFAULT, registry: FunctionRegistry = withRun()): Value? =
        Jsonata.compile(expr, CompileOptions(registry)).evaluate(input, mapOf("self" to StringValue.of("outer")), options)

    @Test
    fun `a nested expression sees only its bindings and its own input`() {
        assertEquals("[2,4]", str(eval("\$run('\$.(\$ * 2)', [1, 2])")))
        // `$$` is the nested input; the outer bindings ($self) and variables are not inherited
        assertEquals("{\"a\":1}", str(eval("\$run('\$\$', {'a': 1})", json("{\"b\":2}"))))
        assertEquals("undefined", str(eval("(\$x := 1; \$run('\$x'))")))
        assertEquals("undefined", str(eval("\$run('\$self')")))
        assertEquals("3", str(eval("\$run('\$x + 1', null, {'x': 2})")))
        // after the nested call `$$` is the outer input again
        assertEquals("[{\"a\":1},2]", str(eval("[\$run('\$\$', {'a': 1}), \$\$.b]", json("{\"b\":2}"))))
        // the nested expression calls the functions of the registry, the nested evaluation nests further
        assertEquals("\"y\"", str(eval("\$run('\$run(\"\$.x\", \$)', {'x': 'y'})")))
    }

    @Test
    fun `local state is shared by nested calls and not by evaluations`() {
        assertEquals("[1,1]", str(eval("[\$put('k', 1), \$run('\$take(\"k\")')]")))
        assertEquals("undefined", str(eval("\$take('k')")))
        assertEquals("\"r1\"", str(eval("\$run('\$attr(\"request\")')", options = EvaluationOptions(attributes = mapOf("request" to "r1")))))
    }

    @Test
    fun `depth deadline and budgets are shared`() {
        val infinite = "\$run('(\$f := function(\$n){ 1 + \$f(\$n + 1) }; \$f(0))')"
        assertEquals("D1011", assertFailsWith<JsonataException> { eval(infinite, options = EvaluationOptions(maxDepth = 10_000)) }.code)
        // a nested call starts at the depth of the caller
        val deep = "(\$g := function(\$n){ \$n = 0 ? \$count(\$run('[1..30].\$')) : 1 + \$g(\$n - 1) }; \$g(\$d))"
        val depth = { n: Int -> runCatching { Jsonata.compile(deep, CompileOptions(withRun())).evaluate(null, mapOf("d" to NumberValue.of(n)), EvaluationOptions(maxDepth = 400)) } }
        depth(1).exceptionOrNull()?.let { throw it }
        assertEquals("D1011", (depth(200).exceptionOrNull() as JsonataException).code)
        val loop = "\$run('(\$f := function(){ \$f() }; \$f())')"
        assertEquals("D1012", assertFailsWith<JsonataException> { eval(loop, options = EvaluationOptions(timeoutMillis = 200)) }.code)
        assertEquals("X0010", assertFailsWith<JsonataException> { eval("\$run('[1..1000]')", options = EvaluationOptions(budgets = Budgets(maxResultSize = 100))) }.code)
        val token = CancellationToken()
        token.cancel()
        assertEquals("X0009", assertFailsWith<JsonataException> { eval(loop, options = EvaluationOptions(cancellation = token)) }.code)
    }

    @Test
    fun `the listener sees the nested frame by its name`() {
        val frames = HashSet<String>()
        val listener = object : EvaluationListener {
            override fun enter(node: NodeInfo, input: Value?, frame: DebugFrame, iteration: Iteration?) {
                if (node.type == "number" && node.value == "7") frames += generateSequence(frame) { it.parent }.joinToString("<") { it.name }
            }
        }
        assertEquals("7", str(eval("\$run('7')", options = EvaluationOptions(listener = listener))))
        assertEquals(setOf("nested<<main>"), frames)
    }

    @Test
    fun `an expression of a foreign registry is refused`() {
        val foreign = Jsonata.compile("1", CompileOptions(FunctionRegistry.create().freeze()))
        val r = FunctionRegistry.create().register("bad", "<:x>", { c, _ -> c.evaluate(foreign, null, emptyMap(), "f") }).freeze()
        assertFailsWith<IllegalArgumentException> { eval("\$bad()", registry = r) }
    }
}
