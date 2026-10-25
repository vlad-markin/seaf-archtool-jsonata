package su.seaf.archtool.jsonata.seaf

import su.seaf.archtool.jsonata.Budgets
import su.seaf.archtool.jsonata.EvaluationOptions

/**
 * Default limits of an evaluation on a SEAF host. Depth and sequence length keep the defaults of the core
 * ([EvaluationOptions.DEFAULT_MAX_DEPTH], [EvaluationOptions.DEFAULT_MAX_SEQUENCE]); see `docs/limits.md`.
 */
public class SeafLimits @JvmOverloads constructor(
    /** Absolute deadline of an evaluation (D1012), 0 for none. */
    public val timeoutMillis: Long,
    public val budgets: Budgets,
    public val maxDepth: Int = EvaluationOptions.DEFAULT_MAX_DEPTH,
    public val maxSequence: Int = EvaluationOptions.DEFAULT_MAX_SEQUENCE,
) {
    /** Evaluation options with these limits and nothing else. */
    public fun options(): EvaluationOptions = EvaluationOptions(maxDepth = maxDepth, timeoutMillis = timeoutMillis, maxSequence = maxSequence, budgets = budgets)

    public companion object {
        /**
         * The temporary profile (owner decision K4) for every host until profiles measured on the lakes replace it
         * (story 6, step 6.5): a 60 s deadline, built strings up to 2^26 characters, results up to 2^28 units,
         * no step budget.
         */
        @JvmField
        public val TEMPORARY: SeafLimits = SeafLimits(60_000, Budgets(maxSteps = 0, maxStringLength = 1 shl 26, maxResultSize = 1L shl 28))
    }
}
