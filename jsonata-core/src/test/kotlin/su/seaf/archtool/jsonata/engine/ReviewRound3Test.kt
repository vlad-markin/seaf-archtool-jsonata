package su.seaf.archtool.jsonata.engine

import su.seaf.archtool.jsonata.EvaluationOptions
import su.seaf.archtool.jsonata.Jsonata
import su.seaf.archtool.jsonata.parser.JsonataException
import su.seaf.archtool.jsonata.value.JsonWriter
import su.seaf.archtool.jsonata.value.Value
import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Regression tests of review round 3 (`docs/review/round-3.md`); the test JVM runs with -Xmx1g. */
class ReviewRound3Test {
    private fun eval(expr: String, input: Value? = null): String = Jsonata.compile(expr).evaluate(input).let { if (it == null) "undefined" else JsonWriter.stringify(it) }

    @Test
    fun `RV3-1 an interrupt of a caller does not reach other evaluations through pool threads`() {
        val attacked = Jsonata.compile("(\$f := function(\$n){ \$n = 0 ? 0 : \$f(\$n - 1) }; \$f(3))")
        val victim = Jsonata.compile("(\$f := function(\$n){ \$n = 0 ? 0 : 1 + \$f(\$n - 1) }; \$sum([1..2000].\$f(2)))")
        val stop = AtomicBoolean()
        val targets = List(4) {
            Thread {
                while (!stop.get()) {
                    runCatching { attacked.evaluate(null) }
                    Thread.interrupted()
                }
            }.apply { start() }
        }
        val attacker = Thread {
            while (!stop.get()) {
                targets.forEach { it.interrupt() }
                java.util.concurrent.locks.LockSupport.parkNanos(200_000)
            }
        }.apply { start() }
        val cancelled = AtomicInteger()
        val victims = List(2) {
            Thread {
                repeat(2500) {
                    try {
                        victim.evaluate(null)
                    } catch (e: JsonataException) {
                        if (e.code == "X0009") cancelled.incrementAndGet()
                    }
                }
            }.apply { start() }
        }
        victims.forEach { it.join() }
        stop.set(true)
        attacker.join()
        targets.forEach { it.join() }
        assertEquals(0, cancelled.get(), "evaluations of other threads were cancelled")
        // idle pool threads do not spin on a leftover interrupt flag
        Thread.sleep(200)
        val mx = ManagementFactory.getThreadMXBean()
        fun segmentCpu(): Long = Thread.getAllStackTraces().keys.filter { it.name.startsWith("jsonata-stack-segment") }
            .sumOf { mx.getThreadCpuTime(it.threadId()).coerceAtLeast(0) }
        val before = segmentCpu()
        Thread.sleep(1000)
        assertTrue((segmentCpu() - before) / 1_000_000 < 50, "idle segment threads used ${(segmentCpu() - before) / 1_000_000} ms of CPU")
    }

    @Test
    fun `RV3-1 an interrupt of the caller still cancels its own evaluation on a segment`() {
        // a recursive lambda: the evaluation runs on a segment thread from the start
        val loop = Jsonata.compile("(\$f := function(\$n){ \$n > 1e15 ? 0 : \$f(\$n + 1) }; \$f(0))")
        val result = arrayOfNulls<Any>(1)
        val t = Thread { result[0] = runCatching { loop.evaluate(null, emptyMap(), EvaluationOptions(maxDepth = 0)) }.exceptionOrNull() }
        t.start()
        Thread.sleep(300)
        val t0 = System.nanoTime()
        t.interrupt()
        t.join(5000)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals("X0009", (result[0] as JsonataException).code)
        assertTrue(ms < 50, "cancelled after $ms ms")
    }

    @Test
    fun `RV3-2 shallow recursive evaluations do not use the segment budget`() {
        val saved = su.seaf.archtool.jsonata.StackSegments.budget
        try {
            su.seaf.archtool.jsonata.StackSegments.budget = 4
            val registry = su.seaf.archtool.jsonata.FunctionRegistry.create()
                .register("wait", "<n:n>", { _, a -> Thread.sleep(200); a[0] }).freeze()
            val expr = Jsonata.compile("(\$f := function(\$n){ \$n = 0 ? \$wait(0) : \$f(\$n - 1) }; \$f(2))", su.seaf.archtool.jsonata.CompileOptions(registry))
            val failed = AtomicInteger()
            val threads = List(100) { Thread.ofVirtual().start { runCatching { expr.evaluate(null) }.onFailure { failed.incrementAndGet() } } }
            threads.forEach { it.join() }
            assertEquals(0, failed.get(), "shallow recursive evaluations failed")
            // budget 0: a shallow recursion works, a deep one fails with X0005
            su.seaf.archtool.jsonata.StackSegments.budget = 0
            assertEquals("0", eval("(\$f := function(\$n){ \$n = 0 ? 0 : \$f(\$n - 1) + 0 }; \$f(3))"))
            val deep = Jsonata.compile("(\$g := function(\$h, \$n){ \$n = 0 ? 0 : 1 + \$h(\$h, \$n - 1) }; \$g(\$g, \$))")
            assertEquals("X0005", kotlin.runCatching { deep.evaluate(su.seaf.archtool.jsonata.value.NumberValue.of(500)) }.exceptionOrNull().let { (it as JsonataException).code })
            // the refused evaluation does not mark the expression deep: the next shallow one runs on the caller's thread
            assertEquals("1", JsonWriter.stringify(deep.evaluate(su.seaf.archtool.jsonata.value.NumberValue.of(1))))
            su.seaf.archtool.jsonata.StackSegments.budget = saved
            assertEquals("500", JsonWriter.stringify(deep.evaluate(su.seaf.archtool.jsonata.value.NumberValue.of(500))))
            assertEquals(0, su.seaf.archtool.jsonata.StackSegments.active)
        } finally {
            su.seaf.archtool.jsonata.StackSegments.budget = saved
        }
    }
}
