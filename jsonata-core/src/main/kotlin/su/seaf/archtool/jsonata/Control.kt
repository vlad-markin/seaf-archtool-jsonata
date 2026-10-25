package su.seaf.archtool.jsonata

import su.seaf.archtool.jsonata.engine.EvalContext

/** What a native function can ask about the cancellation of the evaluation that calls it (CAP-13). */
public interface CancellationSignal {
    /** True once the evaluation was cancelled (by its token or by an interrupt of its thread). */
    public val isCancelled: Boolean

    /** Throws the cancellation error (X0009) when [isCancelled]; a long host function may call it in its loops. */
    public fun throwIfCancelled()
}

/**
 * Cancels evaluations (CAP-13 p. 1): pass it in [EvaluationOptions.cancellation], call [cancel] from any thread. An
 * evaluation that runs JSONata sees the cancellation within about a thousand evaluation steps (well under 10 ms) and
 * fails with X0009; a host function that blocks at that moment is interrupted. One token may serve many evaluations.
 * Cancelling after an evaluation finished does nothing, in particular it leaves no interrupt flag on any thread.
 */
public class CancellationToken {
    @Volatile private var cancelled = false
    private val running: MutableSet<EvalContext> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** True once [cancel] was called. */
    public val isCancelled: Boolean get() = cancelled

    /** Requests the cancellation of every evaluation using this token, now and later. */
    public fun cancel() {
        cancelled = true
        for (ctx in running) ctx.cancelFromToken()
    }

    internal fun register(ctx: EvalContext) {
        running.add(ctx)
    }

    internal fun unregister(ctx: EvalContext) {
        running.remove(ctx)
    }
}
