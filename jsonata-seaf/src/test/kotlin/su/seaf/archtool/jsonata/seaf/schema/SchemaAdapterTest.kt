package su.seaf.archtool.jsonata.seaf.schema

import su.seaf.archtool.jsonata.FunctionRegistry
import su.seaf.archtool.jsonata.json.JsonataJson
import su.seaf.archtool.jsonata.value.ArrayValue
import su.seaf.archtool.jsonata.value.JsonWriter
import su.seaf.archtool.jsonata.value.NumberValue
import su.seaf.archtool.jsonata.value.ObjectValue
import su.seaf.archtool.jsonata.value.StringValue
import su.seaf.archtool.jsonata.value.Value
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The networknt adapter beyond the conformance file: examples of the spec, polling, limits, threads (story 3 step 3.2). */
class SchemaAdapterTest {
    private fun json(text: String): Value = JsonataJson.parse(text)
    private fun valid(schema: String, data: String, mode: SchemaMode) = JsonSchemas.compile(json(schema), mode).validate(json(data)).isEmpty()

    @Test
    fun `pattern with the u flag for manifest functions and without it for jsonschema (spec CAP-5 p 8)`() {
        val schema = """{"type":"string","pattern":"^\\p{L}+$"}"""
        assertTrue(valid(schema, "\"abc\"", SchemaMode.MANIFEST_FUNCTION))
        assertTrue(!valid(schema, "\"p{L}\"", SchemaMode.MANIFEST_FUNCTION))
        assertTrue(!valid(schema, "\"abc\"", SchemaMode.JSONSCHEMA))
        assertTrue(valid(schema, "\"p{L}\"", SchemaMode.JSONSCHEMA))
    }

    @Test
    fun `strict mode as ajv of the core`() {
        val e = assertFailsWith<SchemaCompileException> { JsonSchemas.compile(json("""{"type":"string","example":"x"}"""), SchemaMode.MANIFEST_FUNCTION) }
        assertEquals("strict mode: unknown keyword: \"example\"", e.message)
        assertFailsWith<SchemaCompileException> { JsonSchemas.compile(json("""{"format":"email"}"""), SchemaMode.MANIFEST_FUNCTION) }
        JsonSchemas.compile(json("""{"format":"email"}"""), SchemaMode.JSONSCHEMA)
        assertFailsWith<SchemaCompileException> { JsonSchemas.compile(json("""{"${'$'}ref":"http://example.com/remote.json"}"""), SchemaMode.JSONSCHEMA) }
        assertTrue(assertFailsWith<SchemaCompileException> { JsonSchemas.compile(json("""{"minimum":"x"}"""), SchemaMode.JSONSCHEMA) }.message!!.startsWith("schema is invalid: data/minimum"))
    }

    @Test
    fun `violations in the form of ajv`() {
        val schema = JsonSchemas.compile(json("""{"type":"object","properties":{"items":{"type":"array","items":{"type":"number"}},"fn":{"callback":true}},"required":["items"]}"""), SchemaMode.MANIFEST_FUNCTION)
        val f = FunctionRegistry.function(null, { _, _ -> null })
        val ok = ObjectValue().also { it.put("items", ArrayValue.of(NumberValue.of(1))); it.put("fn", f) }
        assertEquals(emptyList(), schema.validate(ok).map { it.toString() })
        val bad = ObjectValue().also { it.put("fn", StringValue.of("x")) }
        val v = schema.validate(bad).map { JsonWriter.stringify(it.toValue().also { o -> o.put("message", StringValue.of("")) }) }.sorted()
        assertEquals(
            listOf(
                """{"instancePath":"","schemaPath":"#/required","keyword":"required","params":{"missingProperty":"items"},"message":""}""",
                """{"instancePath":"/fn","schemaPath":"#/properties/fn/callback","keyword":"callback","params":{},"message":""}""",
            ),
            v,
        )
        assertTrue(schema.validate(bad).all { it.message.isNotEmpty() })
    }

    @Test
    fun `the poll runs while converting and matching`() {
        val schema = JsonSchemas.compile(json("""{"type":"array","items":{"type":"string","pattern":"^a+$"}}"""), SchemaMode.JSONSCHEMA)
        val many = ArrayValue(5000).also { a -> repeat(5000) { a.add(StringValue.of("a")) } }
        val polls = AtomicInteger()
        assertTrue(schema.validate(many) { polls.incrementAndGet() }.isEmpty())
        assertTrue(polls.get() >= 4, "conversion polls ${polls.get()}")
        // a long subject polls inside the regex engine
        val long = ArrayValue.of(StringValue.of("a".repeat(100_000)))
        polls.set(0)
        assertTrue(schema.validate(long) { polls.incrementAndGet() }.isEmpty())
        assertTrue(polls.get() >= 90, "regex polls ${polls.get()}")
        assertFailsWith<IllegalStateException> { schema.validate(long) { if (polls.incrementAndGet() > 120) throw IllegalStateException("cancelled") } }
    }

    @Test
    fun `deep instances are refused`() {
        val schema = JsonSchemas.compile(json("{}"), SchemaMode.JSONSCHEMA)
        var v: Value = NumberValue.of(1)
        repeat(Nodes.MAX_DEPTH + 1) { v = ArrayValue.of(v) }
        assertFailsWith<SchemaInstanceException> { schema.validate(v) }
        var ok: Value = NumberValue.of(1)
        repeat(Nodes.MAX_DEPTH) { ok = ArrayValue.of(ok) }
        assertTrue(schema.validate(ok).isEmpty())
    }

    @Test
    fun `one compiled schema serves many threads`() {
        val schema = JsonSchemas.compile(json("""{"type":"object","properties":{"n":{"type":"integer","minimum":0}},"required":["n"]}"""), SchemaMode.MANIFEST_FUNCTION)
        val pool = Executors.newFixedThreadPool(8)
        val wrong = AtomicInteger()
        repeat(8) { t ->
            pool.execute {
                repeat(2000) { i ->
                    val n = (t * 2000 + i) % 7 - 3
                    val errors = schema.validate(ObjectValue().also { it.put("n", NumberValue.of(n)) })
                    if (errors.isEmpty() != (n >= 0)) wrong.incrementAndGet()
                }
            }
        }
        pool.shutdown()
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS))
        assertEquals(0, wrong.get())
    }
}
