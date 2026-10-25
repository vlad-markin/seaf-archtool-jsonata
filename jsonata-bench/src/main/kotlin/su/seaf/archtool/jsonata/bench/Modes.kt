package su.seaf.archtool.jsonata.bench

import su.seaf.archtool.jsonata.EvaluationOptions
import su.seaf.archtool.jsonata.Jsonata
import su.seaf.archtool.jsonata.debug.EvaluationListener
import su.seaf.archtool.jsonata.debug.trace.Profiler
import su.seaf.archtool.jsonata.value.Value

/**
 * Evaluation modes for the price of debugging (CAP-12): `listener` — a listener with no breakpoints (what an IDE
 * attaches), `profiler` — the profiler of story 5. Kept apart from [StandRunner] so that an A/B against an engine
 * revision older than story 5 still runs the plain mode (this class is loaded only for these modes).
 */
internal object Modes {
    fun evaluator(mode: String, input: Value?): (Jsonata) -> Value? = when (mode) {
        "listener" -> {
            val options = EvaluationOptions(listener = object : EvaluationListener {})
            val run: (Jsonata) -> Value? = { e -> e.evaluate(input, emptyMap(), options) }
            run
        }
        "profiler" -> { e: Jsonata -> e.evaluate(input, emptyMap(), EvaluationOptions(listener = Profiler())) }
        else -> error("unknown mode $mode (plain, listener, profiler)")
    }
}
