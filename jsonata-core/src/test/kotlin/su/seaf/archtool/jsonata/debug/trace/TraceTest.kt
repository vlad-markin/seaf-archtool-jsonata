package su.seaf.archtool.jsonata.debug.trace

import su.seaf.archtool.jsonata.EvaluationOptions
import su.seaf.archtool.jsonata.Jsonata
import su.seaf.archtool.jsonata.value.ArrayValue
import su.seaf.archtool.jsonata.value.JsonWriter
import su.seaf.archtool.jsonata.value.NumberValue
import su.seaf.archtool.jsonata.value.ObjectValue
import su.seaf.archtool.jsonata.value.StringValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Profile and value trace (story 5 step 5.5, CAP-11; TC-16). */
class TraceTest {
    /** 10 000 items in 10 groups with nested parts. */
    private val groups = ObjectValue.of("items" to ArrayValue(10_000).also { a ->
        repeat(10_000) { i ->
            a.add(ObjectValue.of(
                "group" to StringValue.of("g${i % 10}"),
                "value" to NumberValue.of(i),
                "parts" to ArrayValue(2).also { p -> p.add(NumberValue.of(1)); p.add(NumberValue.of(2)) },
                "text" to StringValue.of("x".repeat(if (i == 0) 5000 else 3)),
            ))
        }
    })

    private val expr = "items{group: \$sum(value)}"

    @Test
    fun `a profile counts the evaluations of every node (TC-16)`() {
        val profiler = Profiler(5)
        val compiled = Jsonata.compile(expr)
        val traced = compiled.evaluate(groups, emptyMap(), EvaluationOptions(listener = profiler))
        assertEquals(JsonWriter.stringify(compiled.evaluate(groups)!!), JsonWriter.stringify(traced!!))
        val step = profiler.nodeProfiles.first { it.node.type == "name" && it.node.value == "items" }
        assertEquals(1, step.count)
        assertEquals(10_000, profiler.nodeProfiles.first { it.node.type == "name" && it.node.value == "group" }.count)
        assertEquals(5, profiler.top().size)
        assertTrue(profiler.top().zipWithNext().all { (a, b) -> a.selfNanos >= b.selfNanos })
        assertEquals(10, profiler.functionProfiles.first { it.name == "sum" }.calls)
        val report = TraceReport(expr, profiler)
        assertTrue(report.toJson().contains("\"functions\":[{\"name\":\"sum\""), report.toJson())
        assertTrue(report.toText().contains("\$sum"))
    }

    @Test
    fun `a value trace keeps the first K pairs and cuts big values (TC-16)`() {
        val e = "items.text"
        val tracer = ValueTracer({ it.type == "name" && it.value == "text" }, k = 3, maxEntryBytes = 1024)
        Jsonata.compile(e).evaluate(groups, emptyMap(), EvaluationOptions(listener = tracer))
        assertEquals(3, tracer.trace.size)
        assertTrue(tracer.trace[0].truncated)
        assertEquals(1024, tracer.trace[0].input.length)
        assertEquals("\"xxx\"", tracer.trace[1].result)
        assertTrue(!tracer.truncated)
        val text = TraceReport(e, null, tracer).toText()
        assertTrue(text.contains("(truncated)"), text)
    }

    @Test
    fun `a trace over its limit is cut and the evaluation completes (TC-16)`() {
        val tracer = ValueTracer({ true }, k = 1_000_000, maxEntries = 100)
        val profiler = Profiler()
        val r = Jsonata.compile(expr).evaluate(groups, emptyMap(), EvaluationOptions(listener = CompositeListener(profiler, tracer)))
        assertTrue(tracer.truncated)
        assertEquals(100, tracer.trace.size)
        assertEquals(10, (r as ObjectValue).keys.size)
        assertTrue(TraceReport(expr, profiler, tracer).toJson().contains("\"traceTruncated\":true"))
    }

    @Test
    fun `recursion does not count the total time over and over`() {
        val profiler = Profiler()
        Jsonata.compile("(\$f := function(\$n){ \$n = 0 ? 0 : 1 + \$f(\$n - 1) }; \$f(20000))").evaluate(null, emptyMap(), EvaluationOptions(listener = profiler))
        val body = profiler.nodeProfiles.first { it.node.type == "condition" }
        val all = profiler.nodeProfiles.first { it.node.parentId == -1 }
        assertEquals(20_001, body.count)
        assertTrue(body.totalNanos <= all.totalNanos, "${body.totalNanos} > ${all.totalNanos}")
        assertTrue(profiler.stackSegments > 0)
    }
}
