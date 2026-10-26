package su.seaf.archtool.jsonata.tools

import java.io.File
import kotlin.system.exitProcess

/** `jsonata-tools <command> …`: `debug`, `detect` (a lake or expression files); see [usage]. */
fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "debug" -> {
            if (args.size < 2) usage()
            val input = args.getOrNull(2)?.let { File(it).readText() }
            val code = DebugCommand(File(args[1]).readText(), input, System.`in`.bufferedReader(), System.out).run()
            exitProcess(code)
        }
        "detect" -> {
            val opts = HashMap<String, String>()
            val files = ArrayList<String>()
            var i = 1
            while (i < args.size) {
                if (args[i].startsWith("--")) {
                    opts[args[i].removePrefix("--")] = args.getOrNull(i + 1) ?: usage()
                    i += 2
                } else {
                    files += args[i++]
                }
            }
            val code = if (opts.containsKey("lake") || opts.containsKey("manifest")) {
                lakeDetect(opts)
            } else {
                if (files.isEmpty() && !opts.containsKey("functions")) usage()
                Detect(System.out).run(files.associateWith { File(it).readText() }, Detect.parseFunctions(opts["functions"]?.let { File(it).readText() }))
            }
            exitProcess(code)
        }
        else -> usage()
    }
}

private fun usage(): Nothing {
    System.err.println("usage: jsonata-tools debug <expression file> [input.json]")
    System.err.println("       jsonata-tools detect --lake <dir> [--manifest <export.json>] [--contexts <contexts.jsonl>] [--out <dir>]")
    System.err.println("       jsonata-tools detect [--functions <functions.json>] <expression file>...")
    exitProcess(2)
}

/** `detect --lake`: summary.json and details.json under --out (default build/detect), the text report on stdout. */
private fun lakeDetect(opts: Map<String, String>): Int {
    val manifest = opts["manifest"]?.let { su.seaf.archtool.jsonata.json.JsonataJson.parse(File(it).readText()) }
    val found = (opts["lake"]?.let { LakeScan.files(File(it)) } ?: emptyList()) + LakeScan.manifest(manifest)
    val detect = LakeDetect(manifest, opts["contexts"]?.let { Contexts.read(File(it)) } ?: Contexts.NONE).run(found)
    val out = File(opts["out"] ?: "build/detect").apply { mkdirs() }
    File(out, "summary.json").writeText(su.seaf.archtool.jsonata.value.JsonWriter.stringify(detect.summary(), 2))
    File(out, "details.json").writeText(su.seaf.archtool.jsonata.value.JsonWriter.stringify(detect.detailsValue(), 2))
    print(detect.text())
    println("summary: ${File(out, "summary.json").path}; details (for the installation owner only): ${File(out, "details.json").path}")
    return if (detect.problems > 0) 1 else 0
}
