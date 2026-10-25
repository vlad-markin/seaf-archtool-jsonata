package su.seaf.archtool.jsonata.tools

import java.io.File
import kotlin.system.exitProcess

/** `jsonata-tools <command> …`: `debug <expression file> [input.json]`, `detect [--functions f.json] <expression file>…`. */
fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "debug" -> {
            if (args.size < 2) usage()
            val input = args.getOrNull(2)?.let { File(it).readText() }
            val code = DebugCommand(File(args[1]).readText(), input, System.`in`.bufferedReader(), System.out).run()
            exitProcess(code)
        }
        "detect" -> {
            var rest = args.drop(1)
            var functions: String? = null
            if (rest.firstOrNull() == "--functions") {
                functions = File(rest.getOrNull(1) ?: usage()).readText()
                rest = rest.drop(2)
            }
            if (rest.isEmpty() && functions == null) usage()
            val code = Detect(System.out).run(rest.associateWith { File(it).readText() }, Detect.parseFunctions(functions))
            exitProcess(code)
        }
        else -> usage()
    }
}

private fun usage(): Nothing {
    System.err.println("usage: jsonata-tools debug <expression file> [input.json]")
    System.err.println("       jsonata-tools detect [--functions <functions.json>] <expression file>...")
    exitProcess(2)
}
