package su.seaf.archtool.jsonata.json

import su.seaf.archtool.jsonata.value.ArrayValue
import su.seaf.archtool.jsonata.value.NumberValue
import su.seaf.archtool.jsonata.value.ObjectValue
import su.seaf.archtool.jsonata.value.StringValue
import su.seaf.archtool.jsonata.value.Value
import su.seaf.archtool.jsonata.value.Values
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JsonataJsonTest {
    private val datasets = File(System.getProperty("jsonata.compat"), "jsonata-js/test-suite/datasets")

    @Test
    fun `every test-suite dataset reads and writes back equivalently`() {
        val files = datasets.listFiles { f -> f.name.endsWith(".json") }!!
        assertTrue(files.size > 20)
        for (file in files) {
            val value = JsonataJson.parse(file.readText())
            val text = JsonataJson.stringify(value)
            val again = JsonataJson.parse(text)
            assertTrue(Values.deepEquals(value, again), file.name)
            assertEquals(text, JsonataJson.stringify(again), file.name)
            // pretty output parses to the same value
            assertTrue(Values.deepEquals(value, JsonataJson.parse(JsonataJson.stringify(value, 2))), file.name)
        }
    }

    @Test
    fun `numbers are doubles with JS text`() {
        val v = JsonataJson.parse("[1e21, 1E-7, -0, 0.1, 9007199254740993, 1e400, 100]") as ArrayValue
        assertEquals("[1e+21,1e-7,0,0.1,9007199254740992,null,100]", JsonataJson.stringify(v))
        assertTrue(1.0 / (v[2] as NumberValue).value < 0)
    }

    @Test
    fun `key order, duplicates and missing fields`() {
        val o = JsonataJson.parse("""{"b":1,"2":2,"1":3,"01":4,"b":5,"n":null}""") as ObjectValue
        assertEquals(listOf("1", "2", "b", "01", "n"), o.keys)
        assertEquals(NumberValue.of(5.0), o["b"])
        assertEquals(null, o["missing"])
        assertEquals(su.seaf.archtool.jsonata.value.NullValue, o["n"])
    }

    @Test
    fun `pretty printing matches JSON stringify with indent`() {
        val v = JsonataJson.parse("""{"a":[1,{"b":[]}],"c":{}}""")
        assertEquals("{\n  \"a\": [\n    1,\n    {\n      \"b\": []\n    }\n  ],\n  \"c\": {}\n}", JsonataJson.stringify(v, 2))
        assertEquals(su.seaf.archtool.jsonata.value.JsonWriter.stringify(v, 2), JsonataJson.stringify(v, 2))
    }

    @Test
    fun `deep nesting does not overflow the stack`() {
        val depth = 200_000
        val text = "[".repeat(depth) + "\"x\"" + "]".repeat(depth)
        var v: Value = JsonataJson.parse(text)
        var d = 0
        while (v is ArrayValue) { v = v[0]; d++ }
        assertEquals(depth, d)
        assertEquals(StringValue.of("x"), v)
        val nested = JsonataJson.parse(text)
        assertEquals(text, JsonataJson.stringify(nested))
    }

    @Test
    fun `unicode and escapes`() {
        val v = JsonataJson.parse("\"\\u00e9\\ud83d\\ude00\\n\\\"\"") as StringValue
        assertEquals("é😀\n\"", v.value)
    }

    @Test
    fun `undefined items in results are written as null (RV-3)`() {
        val m = su.seaf.archtool.jsonata.Jsonata.compile("\$match('b', /(a)?b/)").evaluate(null)
        assertEquals("""{"match":"b","index":0,"groups":[null]}""", JsonataJson.stringify(m))
        // undefined inside a result array: optional groups of $match (sequences keep no undefined since DEV-12)
        val r = su.seaf.archtool.jsonata.Jsonata.compile("\$match('b', /(a)?(b)(c)?/).groups").evaluate(null) as ArrayValue
        assertEquals("[null,\"b\",null]", JsonataJson.stringify(r))
        assertEquals(su.seaf.archtool.jsonata.value.NullValue, r[0])
        assertEquals(3, r.toList().size)
        assertEquals(3, r.count())
        assertEquals(null, r.getOrNull(0))
        val groups = (m as ObjectValue)["groups"] as ArrayValue
        assertEquals(listOf<su.seaf.archtool.jsonata.value.Value>(su.seaf.archtool.jsonata.value.NullValue), groups.toList())
    }

    @Test
    fun `malformed input`() {
        assertFailsWith<Exception> { JsonataJson.parse("") }
        assertFailsWith<Exception> { JsonataJson.parse("[1,") }
        assertFailsWith<Exception> { JsonataJson.parse("1 2") }
    }
}
