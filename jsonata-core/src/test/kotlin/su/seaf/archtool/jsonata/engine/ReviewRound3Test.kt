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

    /** About 16 MB of JSON: 200 000 components with a few fields. */
    private fun bigModel(): su.seaf.archtool.jsonata.value.ObjectValue {
        val list = su.seaf.archtool.jsonata.value.ArrayValue()
        for (i in 0 until 200_000) {
            list.add(su.seaf.archtool.jsonata.value.ObjectValue().also { o ->
                o.put("id", su.seaf.archtool.jsonata.value.StringValue.of("component.$i"))
                o.put("title", su.seaf.archtool.jsonata.value.StringValue.of("Component number $i of the model"))
                o.put("kind", su.seaf.archtool.jsonata.value.StringValue.of("service"))
                o.put("size", su.seaf.archtool.jsonata.value.NumberValue.of(i.toDouble()))
            })
        }
        return su.seaf.archtool.jsonata.value.ObjectValue().also { it.put("components", list) }
    }

    private fun allocated(block: () -> Unit): Long {
        val mx = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val before = mx.currentThreadAllocatedBytes
        block()
        return mx.currentThreadAllocatedBytes - before
    }

    @Test
    fun `RV3-6 previews and trace entries of a big value cost O(limit)`() {
        val model = bigModel()
        val full = JsonWriter.stringify(model)
        assertTrue(full.length > 15_000_000, "${full.length}")
        // variable previews at a stop: the Context scope holds $$
        val stops = java.util.concurrent.LinkedBlockingQueue<Any>()
        val events = object : su.seaf.archtool.jsonata.debug.DebugEvents {
            override fun stopped(reason: su.seaf.archtool.jsonata.debug.StopReason, at: su.seaf.archtool.jsonata.debug.NodeInfo) { stops.put(at) }
        }
        val session = su.seaf.archtool.jsonata.debug.DebugSession(Jsonata.compile("[1, 2].(\$ * 2)"), events)
        session.setBreakpoints("main", listOf(su.seaf.archtool.jsonata.debug.BreakpointSpec(1, 11)))
        val run = Thread { runCatching { session.run(model) } }
        run.start()
        kotlin.test.assertNotNull(stops.poll(10, java.util.concurrent.TimeUnit.SECONDS))
        var root: su.seaf.archtool.jsonata.debug.VariableView? = null
        val previewBytes = allocated {
            val context = session.scopes(0).first { it.name == "Context" }
            root = session.variables(context.variablesReference).first { it.name == "\$\$" }
        }
        assertEquals(full.substring(0, 197) + "...", root!!.value)
        assertTrue(previewBytes < 1_000_000, "preview allocated $previewBytes bytes")
        session.terminate()
        run.join(5000)
        // a trace entry of the whole model
        val tracer = su.seaf.archtool.jsonata.debug.trace.ValueTracer({ true }, k = 3, maxEntryBytes = 1024)
        val compiled = Jsonata.compile("\$")
        val traceBytes = allocated { compiled.evaluate(model, emptyMap(), EvaluationOptions(listener = tracer)) }
        val entry = tracer.trace.single()
        assertEquals(full.substring(0, 1024), entry.input)
        assertEquals(full.substring(0, 1024), entry.result)
        assertTrue(entry.truncated)
        assertTrue(traceBytes < 1_000_000, "trace allocated $traceBytes bytes")
        // the bounded writer agrees with the full one on small values and cuts long strings by the limit
        val small = Jsonata.compile("{\"a\": [1, 2.5, null, \"x\\ny\"], \"2\": {\"b\": true}}").evaluate(null)
        assertEquals(JsonWriter.stringify(small) to false, JsonWriter.stringifyBounded(small, 1000))
        val long = su.seaf.archtool.jsonata.value.StringValue.of("\uD83D\uDE00".repeat(1_000_000))
        val (text, cut) = JsonWriter.stringifyBounded(long, 7)
        assertEquals(JsonWriter.stringify(long).substring(0, 7), text)
        assertTrue(cut)
    }

    @Test
    fun `RV3-7 the host cancellation token works under the debugger`() {
        class Session(expr: String, breakpoint: Int?) : su.seaf.archtool.jsonata.debug.DebugEvents {
            val stops = java.util.concurrent.LinkedBlockingQueue<Any>()
            val done = java.util.concurrent.CountDownLatch(1)
            @Volatile var endedAt = 0L
            @Volatile var error: Throwable? = null
            val token = su.seaf.archtool.jsonata.CancellationToken()
            val session = su.seaf.archtool.jsonata.debug.DebugSession(Jsonata.compile(expr), this)

            init {
                breakpoint?.let { session.setBreakpoints("main", listOf(su.seaf.archtool.jsonata.debug.BreakpointSpec(1, it))) }
                Thread { runCatching { session.run(null, emptyMap(), EvaluationOptions(cancellation = token)) } }.start()
            }

            override fun stopped(reason: su.seaf.archtool.jsonata.debug.StopReason, at: su.seaf.archtool.jsonata.debug.NodeInfo) { stops.put(at) }

            override fun terminated(outcome: su.seaf.archtool.jsonata.debug.EvalOutcome) {
                endedAt = System.nanoTime()
                error = outcome.error
                done.countDown()
            }

            /** Runs [cancel] and returns the milliseconds until the evaluation ended with X0009. */
            fun cancelled(cancel: () -> Unit): Long {
                val t0 = System.nanoTime()
                cancel()
                assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS), "the evaluation did not end")
                assertEquals("X0009", (error as JsonataException).code)
                return (endedAt - t0) / 1_000_000
            }
        }
        val endless = "\$reduce([1..10000000], function(\$a, \$b){ \$a + \$b })"
        // the host token on a running evaluation
        val running = Session(endless, null)
        Thread.sleep(100)
        assertTrue(running.done.count == 1L)
        assertTrue(running.cancelled { running.token.cancel() } < 50)
        // the host token on a stopped debugger
        val stopped = Session("[1, 2].(\$ * 2)", 11)
        kotlin.test.assertNotNull(stopped.stops.poll(10, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(stopped.cancelled { stopped.token.cancel() } < 50)
        // a token cancelled before the run ends it at once
        val early = su.seaf.archtool.jsonata.CancellationToken().also { it.cancel() }
        val e = kotlin.test.assertFailsWith<JsonataException> {
            su.seaf.archtool.jsonata.debug.DebugSession(Jsonata.compile(endless), object : su.seaf.archtool.jsonata.debug.DebugEvents {})
                .run(null, emptyMap(), EvaluationOptions(cancellation = early))
        }
        assertEquals("X0009", e.code)
        // terminate() still works, running and stopped
        val t1 = Session(endless, null)
        Thread.sleep(100)
        assertTrue(t1.cancelled { t1.session.terminate() } < 50)
        val t2 = Session("[1, 2].(\$ * 2)", 11)
        kotlin.test.assertNotNull(t2.stops.poll(10, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(t2.cancelled { t2.session.terminate() } < 50)
    }

    @Test
    fun `RV3-14 a class escape as a range bound`() {
        // without u (Annex B) the hyphen is a literal; values pinned from jsonata-js 2.2.2
        assertEquals("\"a-1\"", eval("\$match(\"a-1\", /[a-\\d]+/).match"))
        assertEquals("\"x.-a\"", eval("\$match(\"x.-a\", /[.-\\w]+/).match"))
        assertEquals("\" -1\"", eval("\$match(\"a -1\", /[\\s-\\d]+/).match"))
        assertEquals("\"5-z\"", eval("\$match(\"5-z\", /[\\d-z]+/).match"))
        for (p in listOf("[a-\\d]", "[.-\\w]", "[\\s-\\d]", "[\\d-z]", "^[\\w-.]+\$")) {
            assertTrue(su.seaf.archtool.jsonata.JsRegexes.compile(p).test("-"), p)
        }
        assertTrue(su.seaf.archtool.jsonata.JsRegexes.compile("^[\\w-.]+\$").test("a-b.c"))
        // with u: SyntaxError, as new RegExp(p, "u") and ajv
        for (p in listOf("[\\d-z]", "^[\\w-.]+\$", "[a-\\d]", "[\\s-\\d]", "[\\p{L}-z]")) {
            kotlin.test.assertFailsWith<IllegalArgumentException>(p) { su.seaf.archtool.jsonata.JsRegexes.compile(p, "u") }
        }
        // ordinary ranges stay ranges, also after a class escape
        assertTrue(su.seaf.archtool.jsonata.JsRegexes.compile("^[\\da-c]+\$", "u").test("1b"))
        assertTrue(!su.seaf.archtool.jsonata.JsRegexes.compile("^[a-c]\$").test("-"))
        assertEquals("\"-\"", eval("\$match(\"x-\", /[a-c-e]/).match"))
    }

    @Test
    fun `RV3-47 grouping with tuple bindings gives the reference results`() {
        val data = Jsonata.compile("""{"items":[{"t":"x","v":[1,2]},{"t":"x","v":[3]},{"t":"y","v":5},{"t":"x","v":[]},{"t":"2"},{"t":"1","v":[[7]]}]}""").evaluate(null)
        // outcomes of jsonata-js 2.2.2
        val expected = listOf(
            "items@\$o{\$o.t: \$o.v}" to """{"1":[[7]],"x":[1,2,3],"y":5}""",
            "items@\$o{\$o.t: \$count(\$o)}" to """{"1":1,"2":1,"x":3,"y":1}""",
            "items@\$o#\$i{\$o.t: [\$i]}" to """{"1":[5],"2":[4],"x":[0,1,3],"y":[2]}""",
            "items#\$i{t: \$i}" to """{"1":5,"2":4,"x":[0,1,3],"y":2}""",
            // the data is not changed by the grouping
            "(items@\$o{\$o.t: \$o.v}; items)" to """[{"t":"x","v":[1,2]},{"t":"x","v":[3]},{"t":"y","v":5},{"t":"x","v":[]},{"t":"2"},{"t":"1","v":[[7]]}]""",
        )
        for ((expr, want) in expected) assertEquals(want, eval(expr, data), expr)
        val items = Jsonata.compile("""{"items":[{"t":"a"},{"t":"b"},{"t":"a"},{"t":"a"}]}""").evaluate(null)
        assertEquals("""{"a":{"i":[0,2,3],"n":3},"b":{"i":1,"n":1}}""", eval("items@\$o#\$i{\$o.t: {\"i\": \$i, \"n\": \$count(\$o)}}", items))
        assertEquals("""{"b":1,"a":5}""", eval("items#\$i[\$i > 0]{t: \$sum(\$i)}", items))
    }

    @Test
    fun `RV3-47 grouping grows linearly`() {
        fun model(n: Int): Value {
            val list = su.seaf.archtool.jsonata.value.ArrayValue()
            for (i in 0 until n) list.add(su.seaf.archtool.jsonata.value.ObjectValue().also { o ->
                o.put("id", su.seaf.archtool.jsonata.value.StringValue.of("c$i"))
                o.put("type", su.seaf.archtool.jsonata.value.StringValue.of("t${i % 7}"))
                o.put("cost", su.seaf.archtool.jsonata.value.NumberValue.of((i % 100).toDouble()))
            })
            return su.seaf.archtool.jsonata.value.ObjectValue().also { it.put("items", list) }
        }
        // the work of the evaluation: nodes entered and iterations of native loops, read by a host function at the end
        var work = 0L
        val registry = su.seaf.archtool.jsonata.FunctionRegistry.create()
            .register("work", null, { c, _ -> (c as CallContextImpl).frame.ctx.let { work = it.steps + it.ticks }; null })
            .freeze()
        val mx = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        fun measure(expr: String, n: Int): Pair<Long, Long> {
            val compiled = Jsonata.compile("(\$r := $expr; \$work(); \$r)", su.seaf.archtool.jsonata.CompileOptions(registry))
            val input = model(n)
            repeat(3) { compiled.evaluate(input) }
            var bytes = Long.MAX_VALUE
            repeat(3) {
                val b0 = mx.currentThreadAllocatedBytes
                compiled.evaluate(input)
                bytes = minOf(bytes, mx.currentThreadAllocatedBytes - b0)
            }
            return bytes to work
        }
        for (expr in listOf(
            "items@\$c{\$c.type: \$count(\$c)}",
            "items#\$i{type: \$count(\$i)}",
            "items{type: {\"count\": \$count(id), \"cost\": \$sum(cost), \"max\": \$max(cost)}}",
        )) {
            val (bytes1, work1) = measure(expr, 10_000)
            val (bytes4, work4) = measure(expr, 40_000)
            assertTrue(bytes4 <= 5 * bytes1, "$expr: allocations $bytes1 -> $bytes4")
            assertTrue(work4 <= 5 * work1, "$expr: work $work1 -> $work4")
        }
    }

    @Test
    fun `RV3-24 a nested host call keeps the outer one cancellable`() {
        val registry = su.seaf.archtool.jsonata.FunctionRegistry.create()
            .register("outer", "<f:x>", { c, a ->
                c.call(a[0] as su.seaf.archtool.jsonata.value.FunctionValue, emptyList())
                Thread.sleep(3000)
                null
            })
            .register("inner", null, { _, _ -> null })
            .register("slowInner", null, { _, _ -> Thread.sleep(3000); null })
            .register("plain", null, { _, _ -> Thread.sleep(3000); null })
            .freeze()
        fun cancelledAfter(expr: String): Long {
            val compiled = Jsonata.compile(expr, su.seaf.archtool.jsonata.CompileOptions(registry))
            val token = su.seaf.archtool.jsonata.CancellationToken()
            Thread {
                Thread.sleep(200)
                token.cancel()
            }.start()
            val t0 = System.nanoTime()
            val e = kotlin.test.assertFailsWith<JsonataException>(expr) { compiled.evaluate(null, emptyMap(), EvaluationOptions(cancellation = token)) }
            assertEquals("X0009", e.code, expr)
            assertTrue(!Thread.currentThread().isInterrupted, "an interrupt flag is left")
            return (System.nanoTime() - t0) / 1_000_000
        }
        // the control: one blocking host call
        assertTrue(cancelledAfter("\$plain()") < 1000)
        // a nested host call returned, the outer one blocks
        assertTrue(cancelledAfter("\$outer(function(){ \$inner() })") < 1000)
        // the cancellation comes while the nested one blocks
        assertTrue(cancelledAfter("\$outer(function(){ \$slowInner() })") < 1000)
    }

    @Test
    fun `RV3-25 an evaluate queued before resume is answered`() {
        val registry = su.seaf.archtool.jsonata.FunctionRegistry.create()
            .register(su.seaf.archtool.jsonata.FunctionDefinition("slow", null, { _, _ -> Thread.sleep(500); su.seaf.archtool.jsonata.value.NumberValue.of(1.0) },
                su.seaf.archtool.jsonata.FunctionDoc(su.seaf.archtool.jsonata.LocalizedText.ru("Медленная")), sideEffects = false))
        val stops = java.util.concurrent.LinkedBlockingQueue<Any>()
        val events = object : su.seaf.archtool.jsonata.debug.DebugEvents {
            override fun stopped(reason: su.seaf.archtool.jsonata.debug.StopReason, at: su.seaf.archtool.jsonata.debug.NodeInfo) { stops.put(at) }
            override fun terminated(outcome: su.seaf.archtool.jsonata.debug.EvalOutcome) { stops.put(outcome) }
        }
        // one stop only: an evaluation left in the queue would never run
        val session = su.seaf.archtool.jsonata.debug.DebugSession(Jsonata.compile("(\$a := 5; [1].(\$ * 2))", su.seaf.archtool.jsonata.CompileOptions(registry)), events)
        session.setBreakpoints("main", listOf(su.seaf.archtool.jsonata.debug.BreakpointSpec(1, 18)))
        Thread { runCatching { session.run(null) } }.start()
        assertTrue(stops.poll(10, java.util.concurrent.TimeUnit.SECONDS) is su.seaf.archtool.jsonata.debug.NodeInfo)
        // B keeps the stop busy, A asks while it runs, then the client resumes
        val busy = Thread { session.evaluate(0, "\$slow()") }.apply { start() }
        Thread.sleep(100)
        var answer: Any? = null
        var took = 0L
        val a = Thread {
            val t0 = System.nanoTime()
            answer = runCatching { session.evaluate(0, "\$a") }.getOrElse { it }
            took = (System.nanoTime() - t0) / 1_000_000
        }.apply { start() }
        Thread.sleep(100)
        session.resume()
        a.join(15_000)
        busy.join(15_000)
        assertTrue(took < 1000, "answered after $took ms: $answer")
        assertEquals("5", answer.toString())
        assertTrue(stops.poll(10, java.util.concurrent.TimeUnit.SECONDS) is su.seaf.archtool.jsonata.debug.EvalOutcome)
        // after the run an evaluate is refused at once
        assertTrue(session.evaluate(0, "1").error is IllegalStateException)
    }

    @Test
    fun `RV3-26 a native call that fails ends its profile frame`() {
        val registry = su.seaf.archtool.jsonata.FunctionRegistry.create()
            .register("guard", "<f:x>", { c, a ->
                Thread.sleep(100)
                try {
                    c.call(a[0] as su.seaf.archtool.jsonata.value.FunctionValue, emptyList())
                } catch (_: JsonataException) {
                }
                null
            })
            .register("boom", null, { _, _ -> throw JsonataException("D3137", value = su.seaf.archtool.jsonata.value.StringValue.of("boom")) })
            .freeze()
        val profiler = su.seaf.archtool.jsonata.debug.trace.Profiler()
        val errors = ArrayList<String>()
        val watcher = object : su.seaf.archtool.jsonata.debug.EvaluationListener {
            override fun nativeError(name: String, error: Throwable, frame: su.seaf.archtool.jsonata.debug.DebugFrame) { errors += name }
        }
        Jsonata.compile("[\$guard(function(){ \$boom() }), \$guard(function(){ \$boom() })]", su.seaf.archtool.jsonata.CompileOptions(registry))
            .evaluate(null, emptyMap(), EvaluationOptions(listener = su.seaf.archtool.jsonata.debug.trace.CompositeListener(profiler, watcher)))
        val guard = profiler.functionProfiles.single { it.name == "guard" }
        assertEquals(2, guard.calls)
        assertTrue(guard.totalNanos >= 200_000_000, "guard total ${guard.totalNanos / 1_000_000} ms")
        assertEquals(2, profiler.functionProfiles.single { it.name == "boom" }.calls)
        assertEquals(listOf("boom", "boom"), errors)
    }

    @Test
    fun `RV3-27 library loops pass the checkpoint`() {
        val big = su.seaf.archtool.jsonata.value.ObjectValue()
        for (i in 0 until 1_000_000) big.put("k$i", su.seaf.archtool.jsonata.value.NumberValue.of(i.toDouble()))
        fun nums() = su.seaf.archtool.jsonata.value.ArrayValue(3_000_000).also { a -> for (i in 0 until 3_000_000) a.add(su.seaf.archtool.jsonata.value.NumberValue.of(i.toDouble())) }
        val text = su.seaf.archtool.jsonata.value.StringValue.of("ab,".repeat(1_000_000))
        val words = su.seaf.archtool.jsonata.value.StringValue.of("é b".repeat(1_000_000))
        val bindings: Map<String, Value?> = mapOf("big" to big, "nums" to nums(), "nums2" to nums(), "s" to text, "w" to words)
        for (expr in listOf(
            "\$spread(\$big)", "\$merge([\$big, \$big])", "\$shuffle(\$nums)", "\$split(\$s, \",\")", "\$nums = \$nums2",
            "\$replace(\$s, \",\", \";\")", "\$encodeUrlComponent(\$w)", "\$base64encode(\$w)", "-1 in \$nums",
        )) {
            val compiled = Jsonata.compile(expr)
            val t0 = System.nanoTime()
            val e = kotlin.test.assertFailsWith<JsonataException>(expr) { compiled.evaluate(null, bindings, EvaluationOptions(timeoutMillis = 5)) }
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertEquals("D1012", e.code, expr)
            assertTrue(ms < 200, "$expr: D1012 after $ms ms")
        }
    }
}
