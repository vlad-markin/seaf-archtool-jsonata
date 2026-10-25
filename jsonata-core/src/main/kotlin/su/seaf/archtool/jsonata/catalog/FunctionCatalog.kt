package su.seaf.archtool.jsonata.catalog

import su.seaf.archtool.jsonata.Example
import su.seaf.archtool.jsonata.FunctionDoc
import su.seaf.archtool.jsonata.FunctionRegistry
import su.seaf.archtool.jsonata.FunctionSource
import su.seaf.archtool.jsonata.LocalizedText
import su.seaf.archtool.jsonata.ParamDoc
import su.seaf.archtool.jsonata.ResultDoc
import su.seaf.archtool.jsonata.engine.NativeFunction
import su.seaf.archtool.jsonata.parser.Signature
import su.seaf.archtool.jsonata.value.ArrayValue
import su.seaf.archtool.jsonata.value.BooleanValue
import su.seaf.archtool.jsonata.value.JsonWriter
import su.seaf.archtool.jsonata.value.ObjectValue
import su.seaf.archtool.jsonata.value.StringValue

/**
 * One function as an expression sees it: the effective registration of [name] along the registry chain, with its
 * documentation (CAP-8). [overridesStandard] marks a function that replaces the standard one of the same name.
 */
public class CatalogEntry internal constructor(
    public val name: String,
    public val source: FunctionSource,
    public val signature: String?,
    public val doc: FunctionDoc,
    public val overridesStandard: Boolean,
    public val sideEffects: Boolean,
)

/**
 * The functions an expression compiled with a registry can call, for IDEs, the portal and agents (CAP-8, TC-25).
 * [toJson] writes it without any JSON library: `{"functions": [{"name", "source", "signature", "summary", "params",
 * "result", "examples", "overridesStandard", ...}]}`, entries sorted by name.
 */
public class FunctionCatalog internal constructor(public val entries: List<CatalogEntry>) {
    /** The entry for [name], or null. */
    public fun entry(name: String): CatalogEntry? = entries.firstOrNull { it.name == name }

    /** The catalog as JSON; [lang] keeps only that language in texts (null: every language). */
    @JvmOverloads
    public fun toJson(lang: String? = null, indent: Int = 0): String = JsonWriter.stringify(toValue(lang), indent)

    /** The catalog as a JSONata value (the same structure as [toJson]). */
    @JvmOverloads
    public fun toValue(lang: String? = null): ObjectValue {
        val list = ArrayValue(entries.size)
        for (e in entries) list.add(entryValue(e, lang))
        return ObjectValue().also { it.put("functions", list) }
    }

    private fun text(t: LocalizedText, lang: String?): ObjectValue {
        val o = ObjectValue()
        if (lang != null) t.texts[lang]?.let { o.put(lang, StringValue.of(it)) } else t.texts.forEach { (k, v) -> o.put(k, StringValue.of(v)) }
        return o
    }

    private fun entryValue(e: CatalogEntry, lang: String?): ObjectValue {
        val o = ObjectValue()
        o.put("name", StringValue.of(e.name))
        o.put("source", StringValue.of(e.source.name.lowercase()))
        e.doc.manifestId?.let { o.put("id", StringValue.of(it)) }
        e.doc.declaredAt?.let { o.put("declaredAt", StringValue.of(it)) }
        e.signature?.let { o.put("signature", StringValue.of(it)) }
        o.put("summary", text(e.doc.summary, lang))
        val params = ArrayValue(e.doc.params.size)
        for (p in e.doc.params) {
            params.add(
                ObjectValue().also {
                    it.put("name", StringValue.of(p.name))
                    it.put("type", StringValue.of(p.type))
                    it.put("optional", BooleanValue.of(p.optional))
                    it.put("description", text(p.description, lang))
                },
            )
        }
        o.put("params", params)
        e.doc.result?.let { r ->
            o.put("result", ObjectValue().also { it.put("type", StringValue.of(r.type)); r.description?.let { d -> it.put("description", text(d, lang)) } })
        }
        val examples = ArrayValue(e.doc.examples.size)
        for (x in e.doc.examples) examples.add(ObjectValue().also { it.put("expr", StringValue.of(x.expr)); it.put("result", StringValue.of(x.result)) })
        o.put("examples", examples)
        o.put("overridesStandard", BooleanValue.of(e.overridesStandard))
        o.put("sideEffects", BooleanValue.of(e.sideEffects))
        return o
    }

    internal companion object {
        fun of(registry: FunctionRegistry): FunctionCatalog {
            val effective = LinkedHashMap<String, NativeFunction>()
            for (r in registry.chain()) for ((name, f) in r.ownFunctions()) if (f is NativeFunction) effective[name] = f
            val entries = effective.values.sortedBy { it.name }.map { f ->
                val def = f.definition
                val sig = f.signature
                val doc = if (f.source == FunctionSource.STDLIB) StdlibDocs.doc(f.name, sig) else withSignatureParams(def!!.doc, sig)
                CatalogEntry(f.name, f.source, sig?.definition, doc, def?.overridesStandard == true, f.sideEffects)
            }
            return FunctionCatalog(entries)
        }

        /** Host documentation without parameters gets them from the signature (unnamed, without descriptions). */
        private fun withSignatureParams(doc: FunctionDoc, sig: Signature?): FunctionDoc {
            if (doc.params.isNotEmpty() || sig == null || sig.params.isEmpty()) return doc
            val params = sig.params.mapIndexed { i, p -> ParamDoc("arg${i + 1}", paramType(p), LocalizedText(emptyMap()), optional(p)) }
            return FunctionDoc(doc.summary, params, doc.result ?: resultType(sig)?.let { ResultDoc(it) }, doc.examples, doc.manifestId, doc.declaredAt)
        }

        fun paramType(p: su.seaf.archtool.jsonata.parser.SignatureParam): String =
            (p.type ?: "") + (p.subtype?.let { "<$it>" } ?: "") + (if (p.regex.endsWith("+")) "+" else "")

        fun optional(p: su.seaf.archtool.jsonata.parser.SignatureParam): Boolean = p.context || p.regex.endsWith("?")

        /** The return type of a signature (after the top-level `:`), which signature.js parses but ignores. */
        fun resultType(sig: Signature): String? {
            val d = sig.definition
            var depth = 0
            for (i in d.indices) {
                when (d[i]) {
                    '<' -> depth++
                    '>' -> depth--
                    ':' -> if (depth == 1) return d.substring(i + 1, d.length - 1).ifEmpty { null }
                }
            }
            return null
        }
    }
}

/** Descriptions of the standard library: `stdlib-docs_<lang>.properties` beside this package (UTF-8). */
internal object StdlibDocs {
    private val languages = listOf("ru")

    private val bundles: Map<String, java.util.Properties> = languages.associateWith { lang ->
        java.util.Properties().also { p ->
            val stream = StdlibDocs::class.java.getResourceAsStream("/su/seaf/archtool/jsonata/stdlib-docs_$lang.properties")
                ?: error("stdlib-docs_$lang.properties is missing")
            stream.reader(Charsets.UTF_8).use { p.load(it) }
        }
    }

    private fun texts(key: String): LocalizedText = LocalizedText(bundles.mapNotNull { (lang, p) -> p.getProperty(key)?.let { lang to it } }.toMap())

    fun doc(name: String, sig: Signature?): FunctionDoc {
        val ru = bundles.getValue("ru")
        val names = ru.getProperty("$name.params")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val sigParams = sig?.params.orEmpty()
        val params = names.mapIndexed { i, pname ->
            val p = sigParams.getOrNull(i)
            ParamDoc(pname, p?.let { FunctionCatalog.paramType(it) } ?: "", texts("$name.param.$pname"), p?.let { FunctionCatalog.optional(it) } ?: false)
        }
        val result = sig?.let { FunctionCatalog.resultType(it) }?.let { ResultDoc(it, texts("$name.result").takeIf { t -> t.texts.isNotEmpty() }) }
            ?: texts("$name.result").takeIf { it.texts.isNotEmpty() }?.let { ResultDoc("x", it) }
        val examples = generateSequence(1) { it + 1 }.map { ru.getProperty("$name.example.$it") }.takeWhile { it != null }
            .map { line -> Example(line!!.substringBeforeLast(" => ").trim(), line.substringAfterLast(" => ").trim()) }.toList()
        return FunctionDoc(texts("$name.summary"), params, result, examples)
    }
}
