package su.seaf.archtool.jsonata.json

import su.seaf.archtool.jsonata.value.ArrayValue
import su.seaf.archtool.jsonata.value.JsonWriter
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The string pool of a parsed document: small for small documents, shared values after growth (review RV3-20). */
class StringPoolTest {
    private val mx = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    @Test
    fun `small documents allocate a few KB and parse in microseconds`() {
        for (text in listOf("{\"a\":\"b\"}", "[1]")) {
            repeat(50_000) { JsonataJson.parse(text) }
            val n = 100_000
            val bytes0 = mx.currentThreadAllocatedBytes
            val t0 = System.nanoTime()
            repeat(n) { JsonataJson.parse(text) }
            val nanos = (System.nanoTime() - t0) / n
            val bytes = (mx.currentThreadAllocatedBytes - bytes0) / n
            assertTrue(bytes <= 4096, "$text: $bytes bytes per parse")
            assertTrue(nanos <= 2000, "$text: $nanos ns per parse")
        }
    }

    @Test
    fun `repeated values are shared after the table grows`() {
        val distinct = (0 until 20_000).map { "v$it" }
        val text = (distinct + distinct).joinToString(",", "[", "]") { JsonWriter.quote(it) }
        val a = JsonataJson.parse(text) as ArrayValue
        assertEquals(40_000, a.size)
        for (i in distinct.indices) {
            assertEquals(distinct[i], (a[i] as su.seaf.archtool.jsonata.value.StringValue).value)
            assertSame(a[i], a[i + distinct.size])
        }
    }
}
