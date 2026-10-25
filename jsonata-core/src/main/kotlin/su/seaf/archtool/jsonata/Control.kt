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

/**
 * Budgets of one evaluation (CAP-13 p. 5), zero meaning no limit; exceeding one fails with X0010 naming it
 * (`details["budget"]`: `steps`, `string`, `result`). There is no budget of allocated memory (owner's decision K5):
 * these bound what an expression can build, the host's heap limit bounds the rest (`docs/limits.md`).
 *
 * @property maxSteps evaluation steps (nodes entered)
 * @property maxStringLength length (UTF-16 units) of a string the evaluation builds: checked before the allocation
 *   where the length is known in advance (`&`, `$join`, `$pad`) and while building (`$replace`, `$string`)
 * @property maxResultSize size of the final result: one per value, plus key and string lengths
 */
public class Budgets @JvmOverloads constructor(
    public val maxSteps: Long = 0,
    public val maxStringLength: Int = 0,
    public val maxResultSize: Long = 0,
) {
    public companion object {
        @JvmField public val NONE: Budgets = Budgets()
    }
}
