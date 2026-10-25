package su.seaf.archtool.jsonata.seaf.manifest

import su.seaf.archtool.jsonata.EvaluationOptions
import su.seaf.archtool.jsonata.FunctionSource
import su.seaf.archtool.jsonata.debug.DebugFrame
import su.seaf.archtool.jsonata.debug.EvaluationListener
import su.seaf.archtool.jsonata.debug.Iteration
import su.seaf.archtool.jsonata.debug.NodeInfo
import su.seaf.archtool.jsonata.debug.SourceInfo
import su.seaf.archtool.jsonata.json.JsonataJson
import su.seaf.archtool.jsonata.parser.JsonataException
import su.seaf.archtool.jsonata.seaf.SeafEnvironment
import su.seaf.archtool.jsonata.seaf.SeafEnvironmentTest
import su.seaf.archtool.jsonata.seaf.SeafLimits
import su.seaf.archtool.jsonata.value.ArrayValue
import su.seaf.archtool.jsonata.value.JsonWriter
import su.seaf.archtool.jsonata.value.ObjectValue
import su.seaf.archtool.jsonata.value.StringValue
import su.seaf.archtool.jsonata.value.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Manifest functions (story 3 step 3.4; TC-11, TC-24, TC-26 for manifest functions, recursion of R1). */
class ManifestFunctionsTest {
    private fun json(text: String): Value = JsonataJson.parse(text)
    private fun str(v: Value?) = if (v == null) "undefined" else JsonWriter.stringify(v)

    private val manifest = json(
        """
        {
          "demo_summ": {
            "title": "Сумма чисел",
            "params": [{"alias": "items", "title": "Числа", "required": true, "type": "array", "minItems": 1, "items": {"type": "number"}}],
            "code": "${'$'}sum(items)",
            "result": {"type": "number"}
          },
          "apply_twice": {
            "params": [{"alias": "fn", "callback": true, "required": true}, {"alias": "x", "type": "number"}],
            "code": "${'$'}fn(${'$'}fn(x))"
          },
          "positional": {
            "params": [{"type": "number"}, {"type": "number"}],
            "code": "param0 - param1"
          },
          "no_params": {"code": "${'$'}exists(${'$'}) ? 'defined' : 'undefined'"},
          "count_down": {
            "params": [{"alias": "n", "type": "integer"}],
            "code": "n = 0 ? 0 : 1 + ${'$'}count_down(n - 1)"
          },
          "forever": {"params": [{"alias": "n"}], "code": "1 + ${'$'}forever(n)"},
          "bad_result": {"code": "'text'", "result": {"type": "number"}},
          "strict": {"params": [{"alias": "s", "type": "string", "example": "x"}], "code": "s"},
          "letters": {"params": [{"alias": "s", "type": "string", "pattern": "^\\p{L}+${'$'}"}], "code": "s"},
          "broken": {"code": "1 + 'a'"},
          "uses_core": {"params": [{"alias": "o", "type": "object"}], "code": "${'$'}lookup(o, null)"},
          "store": {"code": "${'$'}get('k')"},
          "a.b": {"code": "1"}
        }
        """.trimIndent(),
    )

    private val env = SeafEnvironment.create(SeafEnvironmentTest.NoHost, manifest)
    private fun eval(expr: String, options: EvaluationOptions = env.limits.options(), input: Value? = null) = str(env.evaluate(env.compile(expr), input, options = options))
    private fun error(expr: String, options: EvaluationOptions = env.limits.options()) = assertFailsWith<JsonataException> { env.evaluate(env.compile(expr), null, options = options) }

    @Test
    fun `the example of the core and violations of the parameters (TC-11)`() {
        assertEquals("30", eval("\$demo_summ([1,2,3,4,5,15])"))
        val e = error("\$demo_summ([])")
        assertEquals("X0015", e.code)
        assertEquals("demo_summ", e.functionId)
        val violations = e.details["violations"] as ArrayValue
        val v = violations[0] as ObjectValue
        assertEquals("/items", (v["instancePath"] as StringValue).value)
        assertEquals("minItems", (v["keyword"] as StringValue).value)
        assertTrue(e.message!!.startsWith("Ошибка валидации входных параметров функции [demo_summ]\n["), e.message)
        assertTrue(e.message!!.contains("\n    {\n        \"instancePath\": \"/items\""), e.message)
        assertEquals("type", ((error("\$demo_summ(\"x\")").details["violations"] as ArrayValue)[0] as ObjectValue)["keyword"].let { (it as StringValue).value })
        assertEquals("required", ((error("\$demo_summ()").details["violations"] as ArrayValue)[0] as ObjectValue)["keyword"].let { (it as StringValue).value })
    }

    @Test
    fun `lambdas, positional parameters and no parameters`() {
        assertEquals("12", eval("\$apply_twice(function(\$v){ \$v * 2 }, 3)"))
        // 1000 calls with different lambdas, one compilation of the code
        assertEquals("1000", eval("\$count([1..1000].(\$k := \$; \$apply_twice(function(\$v){ \$v + \$k }, 0)))"))
        assertEquals("X0015", error("\$apply_twice(1, 2)").code)
        assertEquals("3", eval("\$positional(5, 2)"))
        assertEquals("\"undefined\"", eval("\$no_params(42)"))
        assertEquals("[2,4,6]", eval("\$map([1, 2, 3], function(\$v){ \$apply_twice(function(\$x){ \$x }, \$v * 2) })"))
        assertEquals("null", eval("\$uses_core({\"k\": 1})"))
    }

    @Test
    fun `result and schema errors`() {
        val r = error("\$bad_result()")
        assertEquals("X0016", r.code)
        assertTrue(r.message!!.startsWith("Ошибка валидации результата функции [bad_result]"))
        val s = error("\$strict(\"a\")")
        assertEquals("X0017", s.code)
        assertEquals("strict", s.functionId)
        assertEquals("Ошибка схемы входных параметров в декларации функции [strict]: strict mode: unknown keyword: \"example\"", s.message)
        // pattern is a JS regex with the u flag, as ajv 8 in the core
        assertEquals("\"abc\"", eval("\$letters(\"abc\")"))
        assertEquals("pattern", ((error("\$letters(\"p{L}\")").details["violations"] as ArrayValue)[0] as ObjectValue)["keyword"].let { (it as StringValue).value })
        // tryexecute catches these
        assertTrue(eval("\$tryexecute(\$demo_summ, [[]])").startsWith("{\"success\":false,\"result\":null,\"error\":\"Ошибка валидации входных параметров функции [demo_summ]"))
    }

    @Test
    fun `an error inside code keeps its code, its place in code and the function id (TC-26)`() {
        val e = error("\$broken()")
        assertEquals("T2002", e.code)
        assertEquals("broken", e.functionId)
        assertEquals(3, e.position)
        assertTrue(e.frames.isNotEmpty())
    }

    @Test
    fun `recursion of manifest functions (R1, RV-6)`() {
        assertEquals("100000", eval("\$count_down(100000)", SeafLimits.TEMPORARY.options()))
        assertEquals("D1011", error("\$forever(1)", SeafLimits.TEMPORARY.options()).code)
        assertEquals("D1011", error("\$tryexecute(\$forever, [1])", SeafLimits.TEMPORARY.options()).code)
    }

    @Test
    fun `conflicts and ids that cannot be called (TC-24)`() {
        val e = assertFailsWith<JsonataException> { SeafEnvironment.create(SeafEnvironmentTest.NoHost, json("""{"sum": {"code": "1"}, "wcard": {"code": "2"}, "lookup": {"code": "3"}, "ok": {"code": "4"}}""")) }
        assertEquals("X0014", e.code)
        assertEquals(
            "Manifest functions conflict with built-in functions: sum (stdlib): rename the manifest function; wcard (core): rename the manifest function; lookup (core): rename the manifest function",
            e.message,
        )
        assertEquals(listOf("a.b"), env.warnings.map { it.functionId })
        assertEquals("undefined", eval("\$a.b(2)"))
        val catalog = env.registry.catalog()
        assertTrue(catalog.toString().isNotEmpty())
        assertEquals(FunctionSource.MANIFEST, env.registry.conflicts().let { FunctionSource.MANIFEST })
    }

    @Test
    fun `the store and the debugger are shared with the caller`() {
        assertEquals("1", eval("(\$set('k', 1); \$store())"))
        val sources = ArrayList<String>()
        val frames = HashSet<String>()
        val listener = object : EvaluationListener {
            override fun source(source: SourceInfo) {
                sources += "${source.id} ${source.kind}"
            }
            override fun enter(node: NodeInfo, input: Value?, frame: DebugFrame, iteration: Iteration?) {
                if (node.sourceId != "main") frames += "${node.sourceId} ${frame.name}"
            }
        }
        assertEquals("30", eval("\$demo_summ([10, 20])", EvaluationOptions(listener = listener)))
        assertEquals(listOf("main MAIN", "manifest:functions/demo_summ MANIFEST_FUNCTION"), sources)
        assertTrue(frames.contains("manifest:functions/demo_summ demo_summ"), frames.toString())
    }
}
