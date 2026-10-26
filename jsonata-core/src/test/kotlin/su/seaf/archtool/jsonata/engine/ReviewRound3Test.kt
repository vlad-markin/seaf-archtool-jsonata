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

    @Test
    fun `RV3-3 a transform result with deleted keys can be read by many threads`() {
        val make = Jsonata.compile("\$merge([0..39].{'k' & \$: \$}) ~> |\$|{}, [0..19].('k' & \$ * 2)|")
        val read = Jsonata.compile("\$string(\$x)")
        val expected = JsonWriter.stringify(read.evaluate(null, mapOf("x" to make.evaluate(null))))
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        val wrong = AtomicInteger()
        try {
            repeat(3000) {
                val x = make.evaluate(null) // not read before the threads start
                val start = java.util.concurrent.CountDownLatch(1)
                val futures = (1..8).map { pool.submit<String> { start.await(); runCatching { JsonWriter.stringify(read.evaluate(null, mapOf("x" to x))) }.getOrElse { it.toString() } } }
                start.countDown()
                futures.forEach { if (it.get() != expected) wrong.incrementAndGet() }
            }
        } finally {
            pool.shutdown()
        }
        assertEquals(0, wrong.get())
        assertTrue(expected.startsWith("\"{\\\"k1\\\":1,\\\"k3\\\":3"), expected)
    }

    private class HostFailure : IllegalStateException("boom")

    @Test
    fun `RV3-4 a partially applied host function takes the path of a direct call`() {
        val failure = HostFailure()
        val effects = AtomicInteger()
        val started = java.util.concurrent.CountDownLatch(1)
        val registry = su.seaf.archtool.jsonata.FunctionRegistry.create()
            .register("boom", "<n:n>", { _, _ -> throw failure })
            .register(su.seaf.archtool.jsonata.FunctionDefinition("fx", "<n:n>", { _, a -> effects.incrementAndGet(); a[0] },
                su.seaf.archtool.jsonata.FunctionDoc(su.seaf.archtool.jsonata.LocalizedText.ru("с побочным эффектом")), sideEffects = true))
            .register("block", "<n:n>", { _, a -> started.countDown(); Thread.sleep(5000); a[0] })
            .freeze()
        fun compile(e: String) = Jsonata.compile(e, su.seaf.archtool.jsonata.CompileOptions(registry))
        // a host exception leaves as the same object
        kotlin.test.assertSame(failure, kotlin.test.assertFailsWith<HostFailure> { compile("\$boom(?)(1)").evaluate(null) })
        // the listener sees the native call
        val names = ArrayList<String>()
        val listener = object : su.seaf.archtool.jsonata.debug.EvaluationListener {
            override fun nativeEnter(name: String, source: su.seaf.archtool.jsonata.FunctionSource, args: List<Value?>, frame: su.seaf.archtool.jsonata.debug.DebugFrame) { names += name }
        }
        assertEquals("5", JsonWriter.stringify(compile("\$fx(?)(5)").evaluate(null, emptyMap(), EvaluationOptions(listener = listener))))
        assertEquals(listOf("fx"), names)
        // cancellation interrupts a blocking host function called through a partial application
        val token = su.seaf.archtool.jsonata.CancellationToken()
        val result = arrayOfNulls<Throwable>(1)
        val t = Thread { result[0] = runCatching { compile("\$block(?)(1)").evaluate(null, emptyMap(), EvaluationOptions(cancellation = token)) }.exceptionOrNull() }
        t.start()
        started.await()
        val t0 = System.nanoTime()
        token.cancel()
        t.join(5000)
        assertEquals("X0009", (result[0] as JsonataException).code)
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 1000)
        // a watch expression may not call a function with side effects through a partial application
        val events = object : su.seaf.archtool.jsonata.debug.DebugEvents {
            val stops = java.util.concurrent.LinkedBlockingQueue<Any>()
            override fun stopped(reason: su.seaf.archtool.jsonata.debug.StopReason, at: su.seaf.archtool.jsonata.debug.NodeInfo) { stops.put(at) }
            override fun terminated(outcome: su.seaf.archtool.jsonata.debug.EvalOutcome) { stops.put(outcome) }
        }
        val session = su.seaf.archtool.jsonata.debug.DebugSession(compile("[1, 2].(\$ * 2)"), events)
        session.setBreakpoints("main", listOf(su.seaf.archtool.jsonata.debug.BreakpointSpec(1, 11)))
        val run = Thread { runCatching { session.run(null) } }
        run.start()
        events.stops.poll(10, java.util.concurrent.TimeUnit.SECONDS)
        val before = effects.get()
        assertEquals("X0011", (session.evaluate(0, "\$fx(?)(5)").error as JsonataException).code)
        assertEquals(before, effects.get())
        session.terminate()
        run.join(5000)
    }

    @Test
    fun `RV3-5 breakpoint conditions and logpoints go through the watch path`() {
        val fx = AtomicInteger()
        val registry = su.seaf.archtool.jsonata.FunctionRegistry.create()
            .register(su.seaf.archtool.jsonata.FunctionDefinition("pure", "<n:n>", { _, a -> su.seaf.archtool.jsonata.value.NumberValue.of((a[0] as su.seaf.archtool.jsonata.value.NumberValue).value + 1) },
                su.seaf.archtool.jsonata.FunctionDoc(su.seaf.archtool.jsonata.LocalizedText.ru("Чистая")), sideEffects = false))
            .register(su.seaf.archtool.jsonata.FunctionDefinition("fx", "<n:n>", { _, a -> fx.incrementAndGet(); a[0] },
                su.seaf.archtool.jsonata.FunctionDoc(su.seaf.archtool.jsonata.LocalizedText.ru("С эффектом")), sideEffects = true))
        class Run(condition: String? = null, log: String? = null) : su.seaf.archtool.jsonata.debug.DebugEvents {
            val stops = java.util.concurrent.LinkedBlockingQueue<Long>()
            val output = StringBuffer()
            val done = java.util.concurrent.CountDownLatch(1)
            var result: String? = null
            val session = su.seaf.archtool.jsonata.debug.DebugSession(Jsonata.compile("[1, 2].(\$ * 2)", su.seaf.archtool.jsonata.CompileOptions(registry)), this)
            private var t0 = System.nanoTime()

            init {
                session.setBreakpoints("main", listOf(su.seaf.archtool.jsonata.debug.BreakpointSpec(1, 11, condition = condition, logMessage = log)))
                Thread { runCatching { session.run(null) } }.start()
            }

            override fun stopped(reason: su.seaf.archtool.jsonata.debug.StopReason, at: su.seaf.archtool.jsonata.debug.NodeInfo) {
                stops.put((System.nanoTime() - t0) / 1_000_000)
            }

            override fun output(text: String) {
                output.append(text)
            }

            override fun terminated(outcome: su.seaf.archtool.jsonata.debug.EvalOutcome) {
                result = outcome.toString()
                done.countDown()
            }

            /** Waits for a stop and returns the milliseconds since the start or the last resume. */
            fun stop(): Long = stops.poll(10, java.util.concurrent.TimeUnit.SECONDS) ?: error("no stop; output: $output")

            fun resume() {
                t0 = System.nanoTime()
                session.resume()
            }

            fun finish(): String {
                assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS), "not finished; output: $output")
                assertTrue(stops.isEmpty())
                return result!!
            }
        }
        // a host function in a condition works: stops only where it holds
        val pure = Run(condition = "\$pure(\$) = 3")
        pure.stop()
        assertEquals("2", JsonWriter.stringify(pure.session.stoppedInput()!!))
        pure.resume()
        assertEquals("[2,4]", pure.finish())
        assertEquals("", pure.output.toString())
        // an endless condition fails within the watch limits and stops with the message
        val endless = Run(condition = "(\$f := function(\$i){ \$f(\$i + 1) }; \$f(0))")
        assertTrue(endless.stop() < 3000)
        endless.resume()
        assertTrue(endless.stop() < 3000)
        endless.resume()
        assertEquals("[2,4]", endless.finish())
        assertEquals(2, endless.output.split("breakpoint condition failed:").size - 1, endless.output.toString())
        // a function with side effects in a condition: X0011, and it is not called
        val effect = Run(condition = "\$fx(\$) = 2")
        effect.stop()
        assertTrue(effect.output.toString().startsWith("breakpoint condition failed: X0011"), effect.output.toString())
        effect.resume()
        effect.stop()
        effect.resume()
        effect.finish()
        assertEquals(0, fx.get())
        // a logpoint with a host function
        val log = Run(log = "next {\$pure(\$)}")
        assertEquals("[2,4]", log.finish())
        assertEquals("next 2\nnext 3\n", log.output.toString())
    }
}
