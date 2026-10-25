package su.seaf.archtool.jsonata

import su.seaf.archtool.jsonata.parser.JsonataException
import su.seaf.archtool.jsonata.value.NumberValue
import su.seaf.archtool.jsonata.value.Value
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Cancellation and the deadline (story 4 step 4.1, CAP-13; TC-10, TC-18). */
class ControlTest {
    private val busy = Jsonata.compile("\$reduce([1..5000000], function(\$a, \$v){ \$a + \$v })")

    /** Starts [block] on a new thread; returns the thread and the outcome holder. */
    private fun async(block: () -> Unit): Pair<Thread, AtomicReference<Throwable?>> {
        val failure = AtomicReference<Throwable?>()
        val t = Thread {
            try {
                block()
            } catch (e: Throwable) {
                failure.set(e)
            }
        }
        t.start()
        return t to failure
    }

    /** Milliseconds from the request (after [warm] ms of running) to the end of the evaluation, which must fail with X0009. */
    private fun latency(warm: Long, evaluate: () -> Unit, request: (Thread) -> Unit): Double {
        val (t, failure) = async(evaluate)
        Thread.sleep(warm)
        assertTrue(t.isAlive, "the evaluation ended before the request")
        val t0 = System.nanoTime()
        request(t)
        t.join(5000)
        val ms = (System.nanoTime() - t0) / 1e6
        assertEquals("X0009", (failure.get() as? JsonataException)?.code, "outcome ${failure.get()}")
        return ms
    }

    @Test
    fun `cancellation by token and by interrupt within 10 ms (TC-18)`() {
        // warm up the JIT: the latency is about compiled code
        repeat(3) { runCatching { busy.evaluate(null, emptyMap(), EvaluationOptions(timeoutMillis = 200)) } }
        val byToken = (1..5).map {
            val token = CancellationToken()
            latency(100, { busy.evaluate(null, emptyMap(), EvaluationOptions(cancellation = token)) }) { token.cancel() }
        }
        val byInterrupt = (1..5).map {
            latency(100, { busy.evaluate(null) }) { it.interrupt() }
        }
        val all = (byToken + byInterrupt).sorted()
        assertTrue(all[all.size / 2] <= 10.0, "median cancellation latency ${all[all.size / 2]} ms: token $byToken, interrupt $byInterrupt")
        assertTrue(all.last() <= 100.0, "worst cancellation latency ${all.last()} ms: token $byToken, interrupt $byInterrupt")
    }

    @Test
    fun `a cancelled token stops the evaluation before it starts`() {
        val token = CancellationToken().also { it.cancel() }
        val e = runCatching { Jsonata.compile("1 + 1").evaluate(null, emptyMap(), EvaluationOptions(cancellation = token)) }.exceptionOrNull()
        assertEquals("X0009", (e as JsonataException).code)
        assertEquals("The evaluation was cancelled", e.message)
        assertFalse(Thread.interrupted())
    }

    @Test
    fun `the deadline on an endless recursion (TC-18)`() {
        val t0 = System.nanoTime()
        val e = runCatching {
            Jsonata.compile("(\$f := function(\$n){ 1 + \$f(\$n + 1) }; \$f(0))").evaluate(null, emptyMap(), EvaluationOptions(timeoutMillis = 200))
        }.exceptionOrNull() as JsonataException
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals("D1012", e.code)
        assertEquals(200.0, e.value)
        assertTrue(ms < 1500, "D1012 after $ms ms")
    }

    @Test
    fun `cancelling during a blocking host call interrupts it (TC-10)`() {
        val interrupted = AtomicBoolean(false)
        val sleeping = CountDownLatch(1)
        val registry = FunctionRegistry.create().register("sleep", "<n:n>", { _, a ->
            sleeping.countDown()
            try {
                Thread.sleep((a[0] as NumberValue).value.toLong())
            } catch (e: InterruptedException) {
                interrupted.set(true)
                throw e
            }
            a[0]
        })
        val expr = Jsonata.compile("[1, 2, 3].\$sleep(10000)", CompileOptions(registry))
        val token = CancellationToken()
        val flagAfter = AtomicBoolean(true)
        val (t, failure) = async {
            try {
                expr.evaluate(null, emptyMap(), EvaluationOptions(cancellation = token))
            } finally {
                flagAfter.set(Thread.currentThread().isInterrupted)
            }
        }
        assertTrue(sleeping.await(5, TimeUnit.SECONDS))
        val t0 = System.nanoTime()
        token.cancel()
        t.join(5000)
        assertTrue((System.nanoTime() - t0) / 1e6 < 100)
        assertEquals("X0009", (failure.get() as JsonataException).code)
        assertTrue(failure.get()!!.cause is InterruptedException)
        assertTrue(interrupted.get(), "the host function was interrupted")
        assertFalse(flagAfter.get(), "the token's interrupt does not stay on the thread")
    }

    @Test
    fun `an interrupt set by the host itself stays with the host`() {
        val registry = FunctionRegistry.create().register("sleep", "<n:n>", { _, a -> Thread.sleep((a[0] as NumberValue).value.toLong()); a[0] })
        val expr = Jsonata.compile("\$sleep(10000)", CompileOptions(registry))
        val flagAfter = AtomicBoolean(false)
        val (t, failure) = async {
            try {
                expr.evaluate(null)
            } finally {
                flagAfter.set(Thread.currentThread().isInterrupted)
            }
        }
        Thread.sleep(100)
        t.interrupt()
        t.join(5000)
        assertEquals("X0009", (failure.get() as JsonataException).code)
        assertTrue(flagAfter.get(), "the host's own interrupt flag is restored")
    }

    @Test
    fun `cancel right after the end leaves no interrupt flag (10 000 races)`() {
        val registry = FunctionRegistry.create().register("id", "<n:n>", { _, a -> a[0] }).freeze()
        val expr = Jsonata.compile("\$sum([1..20].\$id(\$))", CompileOptions(registry))
        var cancelled = 0
        repeat(10_000) { i ->
            val token = CancellationToken()
            val canceller = Thread { if (i % 3 != 0) Thread.onSpinWait(); token.cancel() }
            canceller.start()
            try {
                val r: Value? = expr.evaluate(null, emptyMap(), EvaluationOptions(cancellation = token))
                assertEquals(NumberValue.of(210), r)
            } catch (e: JsonataException) {
                assertEquals("X0009", e.code)
                cancelled++
            }
            canceller.join()
            assertFalse(Thread.interrupted(), "run $i left an interrupt flag")
        }
        assertTrue(cancelled < 10_000)
    }

    @Test
    fun `remaining time for host functions`() {
        val seen = AtomicReference<Long>()
        val registry = FunctionRegistry.create().register("left", "<:n>", { c, _ -> seen.set(c.remainingNanos()); NumberValue.of(1) })
        Jsonata.compile("\$left()", CompileOptions(registry)).evaluate(null, emptyMap(), EvaluationOptions(timeoutMillis = 1000))
        assertTrue(seen.get() in 1..1_000_000_000L, "${seen.get()}")
        Jsonata.compile("\$left()", CompileOptions(registry)).evaluate(null)
        assertEquals(Long.MAX_VALUE, seen.get())
    }

    @Test
    fun `a debugger pause does not count against the deadline (TC-18)`() {
        var paused = false
        val hook = object : su.seaf.archtool.jsonata.engine.DebugHook {
            override fun enter(node: su.seaf.archtool.jsonata.engine.Node, input: Value?, frame: su.seaf.archtool.jsonata.engine.Frame) {
                if (!paused && node.ast.type == "function") {
                    paused = true
                    frame.ctx.paused { Thread.sleep(1000) }
                }
            }
            override fun exit(node: su.seaf.archtool.jsonata.engine.Node, input: Value?, frame: su.seaf.archtool.jsonata.engine.Frame, result: Value?) {}
        }
        val expr = Jsonata.compile("\$sum([1..1000])", CompileOptions(debug = true))
        val r = expr.evaluate(null, emptyMap(), EvaluationOptions(timeoutMillis = 500), hook)
        assertTrue(paused)
        assertEquals(NumberValue.of(500500), r)
        // the same wait outside a pause does count
        val sleepy = FunctionRegistry.create().register("sleep", "<n:n>", { _, a -> Thread.sleep((a[0] as NumberValue).value.toLong()); a[0] })
        val e = runCatching {
            Jsonata.compile("\$sleep(1000) + \$sum([1..2000])", CompileOptions(sleepy)).evaluate(null, emptyMap(), EvaluationOptions(timeoutMillis = 500))
        }.exceptionOrNull() as JsonataException
        assertEquals("D1012", e.code)
    }
}
