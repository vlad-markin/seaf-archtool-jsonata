package su.seaf.archtool.jsonata.tools

import su.seaf.archtool.jsonata.Jsonata
import su.seaf.archtool.jsonata.debug.BreakpointSpec
import su.seaf.archtool.jsonata.debug.DebugEvents
import su.seaf.archtool.jsonata.debug.DebugSession
import su.seaf.archtool.jsonata.debug.EvalOutcome
import su.seaf.archtool.jsonata.debug.ExceptionMode
import su.seaf.archtool.jsonata.debug.Granularity
import su.seaf.archtool.jsonata.debug.NodeInfo
import su.seaf.archtool.jsonata.debug.SourceIndex
import su.seaf.archtool.jsonata.debug.StopReason
import su.seaf.archtool.jsonata.json.JsonataJson
import su.seaf.archtool.jsonata.value.Value
import java.io.BufferedReader
import java.io.PrintStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A console debugger over [DebugSession] (story 5 step 5.6): for demos with architects and as a scripted scenario of
 * the debugging API. Commands, one per line:
 * `b LINE[:COL] [if COND]` breakpoint, `bl` list, `catch` stop on errors, `r` run, `c` continue, `s` step in,
 * `si` step in by node, `n` step over, `o` step out, `bt` call stack, `vars [FRAME]` variables, `e EXPR` evaluate in
 * the innermost frame, `p` pause, `q` terminate.
 */
class DebugCommand(
    private val expression: String,
    private val inputJson: String?,
    private val commands: BufferedReader,
    private val out: PrintStream,
) {
    private sealed interface Event
    private class Stopped(val reason: StopReason, val node: NodeInfo) : Event
    private class Done(val outcome: EvalOutcome) : Event

    private val events = LinkedBlockingQueue<Event>()
    private val compiled = Jsonata.compile(expression)
    private val index = SourceIndex.of(compiled)
    private val breakpoints = ArrayList<BreakpointSpec>()

    private val session = DebugSession(compiled, object : DebugEvents {
        override fun stopped(reason: StopReason, at: NodeInfo) { events.put(Stopped(reason, at)) }
        override fun output(text: String) { out.print(text) }
        override fun terminated(outcome: EvalOutcome) { events.put(Done(outcome)) }
    })

    private fun where(n: NodeInfo): String {
        val text = if (n.sourceId == "main") expression.substring(n.start, n.end).replace('\n', ' ') else n.type
        return "${if (n.sourceId == "main") index.range(n) else n.sourceId} ${text.take(60)}"
    }

    /** Reads and executes commands until the evaluation ends or `q`; returns the exit code (0: a value, 1: an error). */
    fun run(): Int {
        val input: Value? = inputJson?.let { JsonataJson.parse(it) }
        var started = false
        var running = false
        while (true) {
            if (running) {
                when (val e = events.poll(1, TimeUnit.HOURS)) {
                    is Stopped -> {
                        running = false
                        out.println("stopped (${e.reason.name.lowercase()}) at ${where(e.node)}")
                    }
                    is Done -> {
                        out.println(e.outcome.error?.let { "error: ${it.message}" } ?: "result: ${e.outcome}")
                        return if (e.outcome.error == null) 0 else 1
                    }
                    null -> return 1
                }
            }
            out.print("(jsonata) ")
            out.flush()
            val line = commands.readLine()?.trim() ?: run {
                if (started) session.terminate()
                return 1
            }
            val cmd = line.substringBefore(' ')
            val arg = line.substringAfter(' ', "").trim()
            when (cmd) {
                "" -> {}
                "b" -> {
                    val cond = arg.substringAfter(" if ", "").ifEmpty { null }
                    val place = arg.substringBefore(" if ").trim()
                    val bp = BreakpointSpec(place.substringBefore(':').toInt(), place.substringAfter(':', "").toIntOrNull(), condition = cond)
                    breakpoints += bp
                    val resolved = session.setBreakpoints("main", breakpoints).last()
                    out.println(if (resolved.verified) "breakpoint ${resolved.id} at ${where(resolved.node!!)}" else "breakpoint not set: ${resolved.message}")
                }
                "bl" -> breakpoints.forEach { out.println("${it.line}${it.column?.let { c -> ":$c" } ?: ""}${it.condition?.let { c -> " if $c" } ?: ""}") }
                "catch" -> { session.setExceptionBreakpoints(ExceptionMode.ALL); out.println("stopping on errors") }
                "r" -> if (!started) {
                    started = true
                    running = true
                    Thread { runCatching { session.run(input) } }.apply { isDaemon = true }.start()
                }
                "c" -> { session.resume(); running = true }
                "s" -> { session.stepIn(); running = true }
                "si" -> { session.stepIn(Granularity.NODE); running = true }
                "n" -> { session.stepOver(); running = true }
                "o" -> {
                    session.stepOut()
                    running = true
                    when (val e = events.poll(1, TimeUnit.HOURS)) {
                        is Stopped -> {
                            running = false
                            out.println("stopped (${e.reason.name.lowercase()}) at ${where(e.node)}${session.returnValue?.let { " => " + su.seaf.archtool.jsonata.value.JsonWriter.stringify(it) } ?: ""}")
                        }
                        is Done -> {
                            out.println(e.outcome.error?.let { "error: ${it.message}" } ?: "result: ${e.outcome}")
                            return if (e.outcome.error == null) 0 else 1
                        }
                        null -> return 1
                    }
                }
                "p" -> session.pause()
                "bt" -> session.stackTrace().frames.forEach { f ->
                    val range = if (f.start >= 0 && f.sourceId == "main") index.range(NodeInfo(-1, "", null, "main", f.start, f.end, true, false, false, -1)).toString() else f.sourceId
                    out.println("#${f.id} ${f.name}${if (f.tailCall) " (tail call)" else ""} at $range")
                }
                "vars" -> {
                    val frame = arg.toIntOrNull() ?: 0
                    for (scope in session.scopes(frame)) {
                        out.println("${scope.name}:")
                        for (v in session.variables(scope.variablesReference)) out.println("  ${v.name} = ${v.value}")
                    }
                }
                "e" -> out.println(session.evaluate(0, arg))
                "q" -> {
                    session.terminate()
                    running = started
                    if (!started) return 1
                }
                else -> out.println("unknown command: $cmd")
            }
        }
    }
}
