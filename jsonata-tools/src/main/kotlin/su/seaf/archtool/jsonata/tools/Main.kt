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
        "lake-gate" -> exitProcess(lakeGate(options(args)))
        else -> usage()
    }
}

private fun usage(): Nothing {
    System.err.println("usage: jsonata-tools debug <expression file> [input.json]")
    System.err.println("       jsonata-tools detect --lake <dir> [--manifest <export.json>] [--contexts <contexts.jsonl>] [--out <dir>]")
    System.err.println("       jsonata-tools detect [--functions <functions.json>] <expression file>...")
    System.err.println("       jsonata-tools lake-gate --lake <dir> --core <core checkout> [--backend <url>] [--backend-body <template>]")
    System.err.println("                               [--manifest <export.json>] [--contexts <contexts.jsonl>] [--timing <N>] [--allow-drift] [--out <dir>]")
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

private fun options(args: Array<String>): Map<String, String> {
    val opts = HashMap<String, String>()
    var i = 1
    while (i < args.size) {
        val k = args[i].removePrefix("--")
        if (k == "allow-drift") {
            opts[k] = "true"
            i++
        } else {
            opts[k] = args.getOrNull(i + 1) ?: usage()
            i += 2
        }
    }
    return opts
}

/** `lake-gate`: the engine against the core's reference on a lake (design §2.1; runs locally at the owner's). */
private fun lakeGate(opts: Map<String, String>): Int {
    val root = File(".").absoluteFile
    val backend = su.seaf.archtool.jsonata.tools.gate.BackendClient(
        opts["backend"] ?: ("http://localhost:3030" + su.seaf.archtool.jsonata.tools.gate.BackendClient.DEFAULT_PATH),
        opts["backend-body"] ?: su.seaf.archtool.jsonata.tools.gate.BackendClient.DEFAULT_BODY,
    )
    val out = File(opts["out"] ?: "build/lake-gate").apply { mkdirs() }
    val manifestFile = opts["manifest"]?.let { File(it) } ?: File(out, "manifest.json").also {
        it.writeText(su.seaf.archtool.jsonata.value.JsonWriter.stringify(backend.manifest() ?: su.seaf.archtool.jsonata.value.NullValue))
    }
    val manifest = su.seaf.archtool.jsonata.json.JsonataJson.parse(manifestFile.readText())
    val reference = su.seaf.archtool.jsonata.tools.gate.NodeReference(
        root, File(opts["core"] ?: usage()), manifestFile, opts["backend"] ?: ("http://localhost:3030" + su.seaf.archtool.jsonata.tools.gate.BackendClient.DEFAULT_PATH),
        opts["backend-body"] ?: su.seaf.archtool.jsonata.tools.gate.BackendClient.DEFAULT_BODY, opts["allow-drift"] == "true",
    )
    val found = (opts["lake"]?.let { LakeScan.files(File(it)) } ?: emptyList()) + LakeScan.manifest(manifest)
    val gate = su.seaf.archtool.jsonata.tools.gate.LakeGate(
        manifest, su.seaf.archtool.jsonata.tools.gate.BackendData(backend), reference,
        opts["contexts"]?.let { Contexts.read(File(it)) } ?: Contexts.NONE, opts["timing"]?.toInt() ?: 0,
    ).run(found)
    val summary = gate.summary()
    File(out, "summary.json").writeText(su.seaf.archtool.jsonata.value.JsonWriter.stringify(summary, 2))
    File(out, "details.json").writeText(su.seaf.archtool.jsonata.value.JsonWriter.stringify(gate.details(), 2))
    println(su.seaf.archtool.jsonata.value.JsonWriter.stringify(summary, 2))
    return if ((summary["unexplained"] as su.seaf.archtool.jsonata.value.NumberValue).value > 0) 1 else 0
}
