package su.seaf.archtool.jsonata

import jdk.jfr.Recording
import jdk.jfr.consumer.RecordingFile
import su.seaf.archtool.jsonata.value.JsonWriter
import su.seaf.archtool.jsonata.value.NumberValue
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Evaluations on virtual threads with a blocking host function do not pin their carriers (story 2 step 2.5, TC-10,
 * CAP-4 p. 4): JFR records no `jdk.VirtualThreadPinned` event. The control case makes sure the recording would see one.
 */
class PinningTest {
    private fun pinnedEvents(block: () -> Unit): List<String> {
        val file = Files.createTempFile("pinning", ".jfr")
        try {
            Recording().use { r ->
                r.enable("jdk.VirtualThreadPinned").withThreshold(Duration.ZERO).withStackTrace()
                r.start()
                block()
                r.stop()
                r.dump(file)
            }
            return RecordingFile.readAllEvents(file).filter { it.eventType.name == "jdk.VirtualThreadPinned" }
                .map { e -> e.stackTrace?.frames?.take(6)?.joinToString(" < ") { "${it.method.type.name}.${it.method.name}" } ?: "?" }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    private val lock = Object()

    @Test
    fun `the recording sees a pinned virtual thread`() {
        val events = pinnedEvents {
            Thread.ofVirtual().start {
                synchronized(lock) { Thread.sleep(5) }
            }.join()
        }
        assertTrue(events.isNotEmpty(), "control: sleeping inside synchronized must pin")
    }

    @Test
    fun `10 000 evaluations with a blocking host function pin no carrier (TC-10)`() {
        val registry = FunctionRegistry.create().register(
            FunctionDefinition(
                "slow", "<n:n>",
                { _, a -> Thread.sleep(10); NumberValue.of((a[0] as NumberValue).value * 2) },
                FunctionDoc(LocalizedText.ru("Ждёт 10 мс и удваивает число")),
            ),
        ).freeze()
        // a path, a regex (cache with a lock), a date picture (cache), the catalog of signatures, a host call
        val expr = Jsonata.compile(
            "{\"n\": \$slow(\$count(items[\$contains(name, /a/)])), \"d\": \$fromMillis(0, '[Y0001]'), \"s\": \$string(items.name)}",
            CompileOptions(registry),
        )
        // and recursion deep enough to need stack segments
        val deep = Jsonata.compile("(\$f := function(\$n){ \$n = 0 ? \$slow(0) : 1 + \$f(\$n - 1) }; \$f(5000))", CompileOptions(registry))
        val input = Jsonata.compile("{\"items\": [{\"name\": \"a\"}, {\"name\": \"b\"}, {\"name\": \"ab\"}]}").evaluate(null)
        val results = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val events = pinnedEvents {
            val executor = Executors.newVirtualThreadPerTaskExecutor()
            repeat(10_000) { i ->
                executor.submit {
                    results += if (i % 500 == 0) JsonWriter.stringify(deep.evaluate(null)!!) else JsonWriter.stringify(expr.evaluate(input)!!)
                }
            }
            executor.shutdown()
            assertTrue(executor.awaitTermination(5, TimeUnit.MINUTES))
        }
        assertEquals(10_000, results.size)
        assertEquals(20, results.count { it == "5000" })
        assertTrue(results.filter { it != "5000" }.all { it == """{"n":4,"d":"1970","s":"[\"a\",\"b\",\"ab\"]"}""" }, results.first())
        assertEquals(emptyList(), events, "pinned virtual threads")
    }
}
