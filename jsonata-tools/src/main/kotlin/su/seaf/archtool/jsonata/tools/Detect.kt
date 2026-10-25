package su.seaf.archtool.jsonata.tools

import su.seaf.archtool.jsonata.Jsonata
import su.seaf.archtool.jsonata.deviation.Deviations
import su.seaf.archtool.jsonata.deviation.Finding
import su.seaf.archtool.jsonata.json.JsonataJson
import su.seaf.archtool.jsonata.parser.JsonataException
import su.seaf.archtool.jsonata.parser.LineIndex
import su.seaf.archtool.jsonata.seaf.SeafDetector
import su.seaf.archtool.jsonata.value.Value
import java.io.PrintStream

/**
 * `jsonata-tools detect [--functions <functions.json>] <expression>…`: where an expression may give another result on
 * the engine than on jsonata-js 2.2.2 and the SEAF core (rules of `docs/deviations.md`), and what in the manifest
 * `functions` would fail to load (X0014) or at the first call (X0017). Nothing is evaluated. Exit code 1 when a
 * manifest problem or a syntax error is found, else 0.
 */
class Detect(private val out: PrintStream) {
    /** [expressions]: file name → text; [functions]: the `functions` section of the manifest, or null. */
    fun run(expressions: Map<String, String>, functions: Value?): Int {
        var failed = false
        if (functions != null) {
            for (c in SeafDetector.manifestConflicts(functions)) {
                out.println("functions: X0014 ${c.id} is a built-in function (${c.builtIn}): rename the manifest function")
                failed = true
            }
            for (f in SeafDetector.invalidDeclarations(functions)) {
                out.println("functions: ${f.id} ${f.recommendation}")
                failed = true
            }
        }
        for ((name, text) in expressions) {
            val ast = try {
                Jsonata.compile(text).ast()
            } catch (e: JsonataException) {
                out.println("$name: ${e.code} ${e.message}")
                failed = true
                continue
            }
            val lines = LineIndex(text)
            val findings: List<Finding> = (Deviations.scan(ast) + SeafDetector.scan(ast, functions)).sortedBy { it.position ?: -1 }
            for (f in findings) {
                val place = f.position?.let { p -> "${lines.line(p)}:${lines.column(p)}" } ?: "-"
                out.println("$name:$place ${f.id} ${f.recommendation}")
            }
        }
        return if (failed) 1 else 0
    }

    companion object {
        fun parseFunctions(text: String?): Value? = text?.let { JsonataJson.parse(it) }
    }
}
