package su.seaf.archtool.jsonata.engine

import su.seaf.archtool.jsonata.StackSegments
import su.seaf.archtool.jsonata.parser.JsonataException
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

/**
 * Platform threads with large stacks that run the stack segments of deep evaluations (review RV-4, condition U1 of
 * the chief architect). Threads are reused: creating one per segment made every crossing of the first segment
 * threshold cost a thread start (`$sum([1..20000].$f(40))` started 80 000 threads). Handing a segment over and back is
 * a [LockSupport] park/unpark pair; nothing on the evaluation path blocks in `synchronized` or `Thread.join`, so a
 * virtual thread waiting for its segment does not pin its carrier.
 *
 * The number of segments running at once in the process is bounded by [StackSegments.budget]: each one reserves
 * [SEGMENT_STACK_BYTES] of address space and commits what its recursion touches, outside the Java heap.
 */
internal object SegmentPool {
    /** A segment thread: [SEGMENT_LIMIT] nested evaluations fit its stack with a wide margin. */
    const val SEGMENT_STACK_BYTES: Long = 256L shl 20

    /** Idle threads kept for reuse; a thread idle for [KEEP_ALIVE_NANOS] exits and frees its stack. */
    private const val MAX_IDLE = 8
    private val KEEP_ALIVE_NANOS = TimeUnit.SECONDS.toNanos(30)

    private val idle = ConcurrentLinkedDeque<Worker>()
    private val idleCount = AtomicInteger()
    private val busy = AtomicInteger()
    private val threads = AtomicInteger()
    private val created = AtomicLong()
    private val handoffs = AtomicLong()
    private val threadIds = AtomicLong()

    val activeSegments: Int get() = busy.get()
    val liveThreads: Int get() = threads.get()
    val createdThreads: Long get() = created.get()
    val segmentsRun: Long get() = handoffs.get()

    /** True on a segment thread: an evaluation started there may use a full segment from the start. */
    fun onSegmentThread(): Boolean = Thread.currentThread() is SegmentThread

    /** A unit of work handed to a segment thread; the caller parks until [done]. */
    class Task(val caller: Thread, val block: (Task) -> Any?) {
        @Volatile @JvmField var done: Boolean = false
        @JvmField var result: Any? = null
        @JvmField var failure: Throwable? = null
        /** Set by the block when it needed a further segment: its thread has touched a deep stack. */
        @JvmField var wentDeep: Boolean = false
    }

    /**
     * Runs [block] on a segment thread and returns its result (or rethrows its exception, the same object) once it has
     * finished. An interrupt of the waiting thread is passed on to the segment thread and restored afterwards.
     */
    fun run(block: (Task) -> Any?): Task {
        if (busy.incrementAndGet() > StackSegments.budget) {
            busy.decrementAndGet()
            throw JsonataException("X0005", value = StackSegments.budget.toDouble())
        }
        val task = Task(Thread.currentThread(), block)
        try {
            handoffs.incrementAndGet()
            val worker = acquire()
            worker.task = task
            LockSupport.unpark(worker.thread)
            var interrupted = false
            while (!task.done) {
                LockSupport.park(task)
                if (Thread.interrupted()) {
                    // cancellation reaches the thread that does the work (story 4 checks it at the checkpoint)
                    interrupted = true
                    worker.thread.interrupt()
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
        } finally {
            busy.decrementAndGet()
        }
        return task
    }

    private fun acquire(): Worker {
        while (true) {
            val w = idle.pollFirst() ?: break
            idleCount.decrementAndGet()
            if (w.claim()) return w
        }
        val w = Worker()
        threads.incrementAndGet()
        created.incrementAndGet()
        w.claim()
        w.thread.start()
        return w
    }

    private class SegmentThread(runnable: Runnable, name: String) :
        Thread(null, runnable, name, SEGMENT_STACK_BYTES) {
        init {
            isDaemon = true
        }
    }

    private class Worker {
        /** IDLE → CLAIMED by a caller (or EXITED by the keep-alive timeout); whoever moves it first wins. */
        private val state = AtomicInteger(IDLE)
        @Volatile @JvmField var task: Task? = null
        val thread: Thread = SegmentThread({ loop() }, "jsonata-stack-segment-" + threadIds.incrementAndGet())

        fun claim(): Boolean = state.compareAndSet(IDLE, CLAIMED)

        private fun loop() {
            try {
                while (true) {
                    val t = awaitTask() ?: return
                    try {
                        t.result = t.block(t)
                    } catch (e: Throwable) {
                        t.failure = e
                    }
                    task = null
                    Thread.interrupted() // an interrupt meant for this task must not reach the next one
                    val keep = !t.wentDeep && idleCount.get() < MAX_IDLE
                    if (keep) {
                        state.set(IDLE)
                        idleCount.incrementAndGet()
                        idle.addFirst(this)
                    }
                    t.done = true
                    LockSupport.unpark(t.caller)
                    // a thread whose stack grew past a segment keeps that memory committed: it is not reused
                    if (!keep) return
                }
            } finally {
                threads.decrementAndGet()
            }
        }

        /** Waits for the next task; null when the thread retires after [KEEP_ALIVE_NANOS] without one. */
        private fun awaitTask(): Task? {
            var deadline = System.nanoTime() + KEEP_ALIVE_NANOS
            while (true) {
                task?.let { return it }
                val left = deadline - System.nanoTime()
                if (left <= 0) {
                    if (state.compareAndSet(IDLE, EXITED)) {
                        if (idle.remove(this)) idleCount.decrementAndGet()
                        return null
                    }
                    // claimed meanwhile: the task is on its way
                    deadline = System.nanoTime() + KEEP_ALIVE_NANOS
                }
                LockSupport.parkNanos(this, maxOf(left, 1L))
            }
        }
    }

    private const val IDLE = 0
    private const val CLAIMED = 1
    private const val EXITED = 2
}
