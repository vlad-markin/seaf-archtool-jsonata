package su.seaf.archtool.jsonata.tools.gate

import su.seaf.archtool.jsonata.json.JsonataJson
import su.seaf.archtool.jsonata.value.JsonWriter
import su.seaf.archtool.jsonata.value.NumberValue
import su.seaf.archtool.jsonata.value.ObjectValue
import su.seaf.archtool.jsonata.value.StringValue
import su.seaf.archtool.jsonata.value.Value
import java.io.File

/**
 * The reference of the lake gate: `compat/node/lake-reference.mjs` run by `npx tsx` in a checkout of the core, with
 * `TZ=UTC`. [coreCheckout] must be at the commit of `compat/seaf/core-ref.json` unless [allowDrift] (exploration only).
 */
class NodeReference(
    private val engineRoot: File,
    private val coreCheckout: File,
    private val manifestFile: File,
    private val backendUrl: String,
    private val backendBody: String,
    allowDrift: Boolean,
) : Reference {
    init {
        val ref = File(engineRoot, "compat/seaf/core-ref.json")
        if (!allowDrift) {
            require(ref.isFile) { "compat/seaf/core-ref.json is missing (step 3.0); use --allow-drift only to explore" }
            val want = ((JsonataJson.parse(ref.readText()) as ObjectValue)["commit"] as StringValue).value
            val head = ProcessBuilder("git", "rev-parse", "HEAD").directory(coreCheckout).start().inputStream.bufferedReader().readText().trim()
            require(head == want) { "the core checkout is at $head, the reference values are of $want (compat/seaf/core-ref.json); use --allow-drift only to explore" }
        }
    }

    override fun evaluate(cases: List<GateCase>, timing: Int): Map<String, ReferenceOutcome> {
        val input = File.createTempFile("gate-cases", ".jsonl").apply { deleteOnExit() }
        input.bufferedWriter(Charsets.UTF_8).use { w ->
            for (c in cases) {
                val o = ObjectValue()
                o.put("id", StringValue.of(c.id))
                o.put("expr", StringValue.of(c.expr))
                c.context.self?.let { o.put("self", it) }
                c.context.params?.let { o.put("params", it) }
                w.write(JsonWriter.stringify(o))
                w.write("\n")
            }
        }
        val pb = ProcessBuilder("npx", "tsx", File(engineRoot, "compat/node/lake-reference.mjs").absolutePath)
            .directory(coreCheckout).redirectInput(input).redirectError(ProcessBuilder.Redirect.INHERIT)
        pb.environment().putAll(mapOf("TZ" to "UTC", "MANIFEST" to manifestFile.absolutePath, "BACKEND_URL" to backendUrl, "BACKEND_BODY" to backendBody, "TIMING" to timing.toString()))
        val p = pb.start()
        val out = HashMap<String, ReferenceOutcome>()
        p.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val o = JsonataJson.parse(line) as ObjectValue
            out[(o["id"] as StringValue).value] = ReferenceOutcome((o["outcome"] as StringValue).value, (o["ms"] as? NumberValue)?.value)
        }
        p.waitFor()
        return out
    }
}

/** [GateData] from the local backend of the core. */
class BackendData(private val backend: BackendClient) : GateData {
    override fun parseSource(source: Value?): Value? = backend.parseSource(source)
    override fun manifestSchema(): Value? = backend.query("\$manifestschema()")
    override fun versions(): Value? = backend.query("\$versions()")
}
