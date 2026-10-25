package su.seaf.archtool.jsonata

import su.seaf.archtool.jsonata.parser.JsonataException
import su.seaf.archtool.jsonata.value.JsonWriter
import su.seaf.archtool.jsonata.value.NumberValue
import su.seaf.archtool.jsonata.value.StringValue
import su.seaf.archtool.jsonata.value.Value
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The function registry: definitions, name rules, freezing, scopes (story 2 step 2.2; TC-09, TC-24). */
class RegistryTest {
    private val upper2 = FunctionDefinition(
        "upper2", "<s-:s>",
        { _, a -> (a[0] as? StringValue)?.let { StringValue.of(it.value.uppercase()) } },
        FunctionDoc(
            LocalizedText.ru("Строка в верхнем регистре"),
            listOf(ParamDoc("str", "s", LocalizedText.ru("Исходная строка"))),
            ResultDoc("s"),
            listOf(Example("\$upper2(\"a\")", "\"A\"")),
        ),
    )

    private fun eval(expr: String, registry: FunctionRegistry, input: Value? = null, evaluation: EvaluationOptions = EvaluationOptions.DEFAULT): String =
        Jsonata.compile(expr, CompileOptions(registry)).evaluate(input, emptyMap(), evaluation).let { if (it == null) "undefined" else JsonWriter.stringify(it) }

    private fun code(block: () -> Unit): String = assertFailsWith<JsonataException> { block() }.code

    @Test
    fun `a documented native function with a signature (TC-09)`() {
        val r = FunctionRegistry.create().register(upper2)
        assertEquals("\"A\"", eval("\$upper2(\"a\")", r))
        assertEquals("\"A\"", eval("\"a\" ~> \$upper2", r))
        assertEquals("\"A\"", eval("\$upper2()", r, StringValue.of("a"))) // context argument of `s-`
        assertEquals("[\"A\",\"B\"]", eval("\$map([\"a\",\"b\"], \$upper2)", r))
        val e = assertFailsWith<JsonataException> { Jsonata.compile("\$upper2(1)", CompileOptions(r)).evaluate(null) }
        assertEquals("T0410", e.code)
        assertEquals(8, e.position)
        assertEquals("upper2", e.token)
    }

    @Test
    fun `names must be callable from an expression`() {
        val r = FunctionRegistry.create()
        for (bad in listOf("a.b", "", "a b", "a-b", "a(", "a:b")) {
            assertEquals("X0006", code { r.register(bad, null, { _, _ -> null }) }, bad)
        }
        for (good in listOf("a1", "_x", "привет", "camelCase", "and")) r.register(good, null, { _, _ -> NumberValue.of(1) })
        assertEquals("1", eval("\$привет()", r))
        assertTrue(assertFailsWith<JsonataException> { r.register("a.b", null, { _, _ -> null }) }.message!!.contains("\"a.b\" cannot be called"))
    }

    @Test
    fun `a standard name needs overridesStandard (TC-24)`() {
        val sum2 = FunctionDefinition("sum", "<a<n>:n>", { _, a -> NumberValue.of(-1.0) }, FunctionDoc(LocalizedText.ru("Своя сумма")))
        val e = assertFailsWith<JsonataException> { FunctionRegistry.create().register(sum2) }
        assertEquals("X0007", e.code)
        assertEquals("\"sum\" is a standard library function; register it with overridesStandard to replace it", e.message)
        val r = FunctionRegistry.create().register(FunctionDefinition("sum", "<a<n>:n>", { _, _ -> NumberValue.of(-1.0) }, FunctionDoc(LocalizedText.ru("Своя сумма")), overridesStandard = true))
        assertEquals("-1", eval("\$sum([1,2])", r))
        assertEquals(listOf("sum: STDLIB -> HOST (effective HOST)"), r.conflicts().map { it.toString() })
        // a local variable shadows the function, as in jsonata-js
        assertEquals("6", eval("(\$sum := function(\$a){\$a*2}; \$sum(3))", r))
        assertEquals("6", eval("(\$sum := function(\$a){\$a*2}; \$sum(3))", FunctionRegistry.standard()))
        // the flag applies to standard names only
        assertFailsWith<IllegalArgumentException> {
            FunctionRegistry.create().register(FunctionDefinition("mine", null, { _, _ -> null }, FunctionDoc(LocalizedText.ru("x")), overridesStandard = true))
        }
        assertTrue(FunctionRegistry.create().conflicts().isEmpty())
    }

    @Test
    fun `duplicates in the registry or along the chain`() {
        val r = FunctionRegistry.create().register(upper2)
        assertEquals("X0001", code { r.register(upper2) })
        assertEquals("X0001", code { r.extend().register(upper2) })
        val over = FunctionDefinition("lookup", null, { _, _ -> null }, FunctionDoc(LocalizedText.ru("x")), source = FunctionSource.CORE, overridesStandard = true)
        val core = FunctionRegistry.create().register(over)
        // an override is a registered function now: a second one fails like any duplicate
        assertEquals("X0001", code { core.extend().register(over) })
        assertEquals("X0004", code { FunctionRegistry.standard().register(upper2) })
    }

    @Test
    fun `a frozen snapshot does not change`() {
        val r = FunctionRegistry.create().register(upper2)
        val frozen = r.freeze()
        assertTrue(frozen.isFrozen)
        assertFalse(r.isFrozen)
        r.register("later", null, { _, _ -> NumberValue.of(1) })
        assertNotNull(r.lookup("later"))
        assertNull(frozen.lookup("later"))
        assertNotNull(frozen.lookup("upper2"))
        assertFailsWith<IllegalStateException> { frozen.register("x", null, { _, _ -> null }) }
        assertSame(frozen, frozen.freeze())
        assertSame(FunctionRegistry.standard(), FunctionRegistry.standard().freeze())
        // the parent must be frozen first
        assertFailsWith<IllegalStateException> { r.extend().freeze() }
        assertTrue(frozen.extend().freeze().isFrozen)
        // the snapshot keeps its functions in final fields
        val field = FunctionRegistry::class.java.getDeclaredField("frozenFunctions")
        assertTrue(java.lang.reflect.Modifier.isFinal(field.modifiers))
    }

    @Test
    fun `functions for one evaluation`() {
        val compileRegistry = FunctionRegistry.create().freeze()
        val expr = Jsonata.compile("\$exists(\$upper2) ? \$upper2(\"x\") : \"none\"", CompileOptions(compileRegistry))
        val scoped = compileRegistry.extend().register(upper2).freeze()
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = (0 until 200).map { i ->
                pool.submit<String> {
                    val r = if (i % 2 == 0) expr.evaluate(null, emptyMap(), EvaluationOptions(functions = scoped)) else expr.evaluate(null)
                    JsonWriter.stringify(r!!)
                }
            }.map { it.get(10, TimeUnit.SECONDS) }
            results.forEachIndexed { i, r -> assertEquals(if (i % 2 == 0) "\"X\"" else "\"none\"", r) }
        } finally {
            pool.shutdown()
        }
        // the evaluation registry must extend the compile registry
        assertFailsWith<IllegalArgumentException> {
            expr.evaluate(null, emptyMap(), EvaluationOptions(functions = FunctionRegistry.create().register(upper2)))
        }
    }
}
