package su.seaf.archtool.jsonata.tools

import java.io.File
import kotlin.system.exitProcess

/** `jsonata-tools <command> …`: `debug <expression file> [input.json]`. */
fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "debug" -> {
            if (args.size < 2) usage()
            val input = args.getOrNull(2)?.let { File(it).readText() }
            val code = DebugCommand(File(args[1]).readText(), input, System.`in`.bufferedReader(), System.out).run()
            exitProcess(code)
        }
        else -> usage()
    }
}

private fun usage(): Nothing {
    System.err.println("usage: jsonata-tools debug <expression file> [input.json]")
    exitProcess(2)
}
