package su.seaf.archtool.jsonata.value

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ValueTest {
    private fun n(d: Double) = NumberValue.of(d)
    private fun s(v: String) = StringValue.of(v)

    @Test
    fun `object keys follow JS order (ORD-01)`() {
        // jsonata-js 2.2.2: $keys({"b":1,"2":2,"1":3,"01":4}) = ["1","2","b","01"]
        val o = ObjectValue.of("b" to n(1.0), "2" to n(2.0), "1" to n(3.0), "01" to n(4.0))
        assertEquals(listOf("1", "2", "b", "01"), o.keys)
        o.put("b", n(5.0))
        o.put("0", n(6.0))
        assertEquals(listOf("0", "1", "2", "b", "01"), o.keys)
        assertTrue(ObjectValue.isArrayIndex("4294967294"))
        assertFalse(ObjectValue.isArrayIndex("4294967295"))
        assertFalse(ObjectValue.isArrayIndex("-1"))
        assertFalse(ObjectValue.isArrayIndex("1.0"))
        assertFalse(ObjectValue.isArrayIndex(""))
    }

    @Test
    fun `deep equality is JS isDeepEqual`() {
        assertEquals(n(0.0), n(-0.0))
        assertNotEquals(n(Double.NaN), n(Double.NaN))
        val a = ObjectValue.of("x" to ArrayValue.of(n(1.0), s("a")), "y" to NullValue)
        val b = ObjectValue.of("y" to NullValue, "x" to ArrayValue.of(n(1.0), s("a")))
        assertTrue(Values.deepEquals(a, b))
        assertFalse(Values.deepEquals(a, ObjectValue.of("x" to ArrayValue.of(n(1.0), s("a")))))
        assertFalse(Values.deepEquals(NullValue, null))
        assertTrue(Values.deepEquals(null, null))
    }

    @Test
    fun `negative zero keeps its sign`() {
        val z = NumberValue.of(-0.0)
        assertTrue(1.0 / z.value < 0)
        assertEquals("0", z.toString())
    }

    @Test
    fun `stringify follows JSON stringify`() {
        val o = ObjectValue.of("a" to ArrayValue.of(n(1e21), n(0.5), BooleanValue.TRUE), "b" to ObjectValue(), "c" to ArrayValue(), "d" to s("\u0001\"\\\n\uD800é"))
        assertEquals("{\"a\":[1e+21,0.5,true],\"b\":{},\"c\":[],\"d\":\"\\u0001\\\"\\\\\\n\\ud800é\"}", JsonWriter.stringify(o))
        assertEquals("{\n  \"a\": [\n    1\n  ],\n  \"b\": {}\n}", JsonWriter.stringify(ObjectValue.of("a" to ArrayValue.of(n(1.0)), "b" to ObjectValue()), 2))
        assertEquals("[0.333333333333333]", JsonWriter.stringifyJsonata(ArrayValue.of(n(1.0 / 3)), false))
    }

    @Test
    fun `sequences are arrays with flags`() {
        val seq = ArrayValue.sequence(n(1.0))
        assertTrue(seq.isSequence)
        assertEquals(ArrayValue.of(n(1.0)), seq)
        repeat(100) { seq.add(n(it.toDouble())) }
        assertEquals(101, seq.size)
        assertEquals(null, seq.getOrNull(101))
    }

    @Test
    fun `effective boolean`() {
        assertEquals(null, Values.toBoolean(null))
        assertEquals(false, Values.toBoolean(ArrayValue.of(n(0.0), s(""))))
        assertEquals(true, Values.toBoolean(ArrayValue.of(n(0.0), s("x"))))
        assertEquals(false, Values.toBoolean(ObjectValue()))
        assertEquals(false, Values.toBoolean(NullValue))
    }
}
