package su.seaf.archtool.jsonata.seaf.schema

import su.seaf.archtool.jsonata.JsRegexes
import tools.jackson.databind.JsonNode

/**
 * The strict mode of ajv 8 that the SEAF core relies on (`new Ajv({allErrors: true})`, strictSchema on), checked over
 * the schema tree before networknt compiles it: unknown keywords, unknown formats, keywords ajv ignores loudly, and
 * regular expressions that JS would not compile. networknt itself accepts all of these.
 */
internal class Strictness(private val mode: SchemaMode) {
    /** `$ref` values anywhere in the schema: ajv compiles (and checks) only the definitions that are referenced. */
    private val refs = HashSet<String>()

    fun check(schema: JsonNode) {
        collectRefs(schema)
        walk(schema, "#")
    }

    private fun collectRefs(n: JsonNode) {
        if (n.isObject) {
            n.get("\$ref")?.takeIf { it.isString }?.let { refs += it.asString() }
            for (x in n.values()) collectRefs(x)
        } else if (n.isArray) {
            for (x in n.values()) collectRefs(x)
        }
    }

    private fun referenced(path: String): Boolean = refs.any { it == path || it.startsWith("$path/") }

    private fun walk(s: JsonNode, path: String) {
        if (s.isBoolean) return
        if (!s.isObject) return // the meta-schema check reports it
        for (e in s.properties()) {
            val k = e.key
            if (k !in KNOWN && k !in mode.extraKeywords) fail("strict mode: unknown keyword: \"$k\"")
        }
        s.get("format")?.let { f ->
            if (f.isString && f.asString() !in mode.formats) fail("unknown format \"${f.asString()}\" ignored in schema at path \"$path\"")
        }
        val items = s.get("items")
        if (s.has("additionalItems") && (items == null || !items.isArray)) {
            fail("strict mode: \"additionalItems\" is ignored when \"items\" is not an array of schemas")
        }
        for (k in LIMIT_KEYWORDS) {
            val limit = s.get(k) ?: continue
            if (k !in mode.extraKeywords) continue
            val format = s.get("format")
            if (format == null) fail("parent schema must have dependencies of $k: format")
            if (!limit.isString) fail("$k value must be [\"string\"]")
            if (format.isString && format.asString() !in AjvFormats.COMPARABLE && format.asString() !in setOf("password", "binary")) {
                fail("\"$k\": format \"${format.asString()}\" does not define \"compare\" function")
            }
        }
        if (s.has("if") && !s.has("then") && !s.has("else")) fail("strict mode: \"if\" without \"then\" and \"else\" is ignored")
        s.get("pattern")?.let { p -> if (p.isString) regex(p.asString()) }
        s.get("patternProperties")?.let { pp -> if (pp.isObject) for (name in pp.propertyNames()) regex(name) }
        // subschemas
        for (k in listOf("properties", "patternProperties")) {
            s.get(k)?.let { m -> if (m.isObject) for (e in m.properties()) walk(e.value, "$path/$k/${escape(e.key)}") }
        }
        for (k in listOf("definitions", "\$defs")) {
            s.get(k)?.let { m ->
                if (m.isObject) for (e in m.properties()) {
                    val p = "$path/$k/${escape(e.key)}"
                    if (referenced(p)) walk(e.value, p)
                }
            }
        }
        s.get("dependencies")?.let { m -> if (m.isObject) for (e in m.properties()) if (e.value.isObject || e.value.isBoolean) walk(e.value, "$path/dependencies/${escape(e.key)}") }
        if (items != null) {
            if (items.isArray) items.values().forEachIndexed { i, x -> walk(x, "$path/items/$i") } else walk(items, "$path/items")
        }
        for (k in listOf("additionalItems", "additionalProperties", "contains", "propertyNames", "if", "then", "else", "not")) {
            s.get(k)?.let { walk(it, "$path/$k") }
        }
        for (k in listOf("allOf", "anyOf", "oneOf")) {
            s.get(k)?.let { a -> if (a.isArray) a.values().forEachIndexed { i, x -> walk(x, "$path/$k/$i") } }
        }
    }

    private fun regex(p: String) {
        try {
            JsRegexes.compile(p, mode.regexFlags)
        } catch (e: IllegalArgumentException) {
            fail(e.message ?: "Invalid regular expression: /$p/")
        }
    }

    private fun escape(key: String) = key.replace("~", "~0").replace("/", "~1")

    private fun fail(message: String): Nothing = throw SchemaCompileException(message)

    companion object {
        private val LIMIT_KEYWORDS = listOf("formatMinimum", "formatMaximum", "formatExclusiveMinimum", "formatExclusiveMaximum")

        /** Keywords of the default Ajv class (draft-07 with the vocabularies of ajv 8, without `next` and `discriminator`). */
        val KNOWN: Set<String> = setOf(
            "\$id", "\$schema", "\$ref", "\$comment", "definitions", "\$defs", "\$async",
            "type", "nullable", "const", "enum", "multipleOf", "maximum", "exclusiveMaximum", "minimum", "exclusiveMinimum",
            "maxLength", "minLength", "pattern", "maxItems", "minItems", "uniqueItems", "maxProperties", "minProperties",
            "required", "format",
            "additionalItems", "items", "contains", "dependencies", "propertyNames", "properties", "patternProperties",
            "additionalProperties", "if", "then", "else", "allOf", "anyOf", "oneOf", "not",
            "title", "description", "default", "deprecated", "readOnly", "writeOnly", "examples",
            "contentMediaType", "contentEncoding",
        )
    }
}

/** The schema cannot be compiled: ajv of the core throws at `compile` here. */
internal class SchemaCompileException(message: String) : RuntimeException(message)

/** The value cannot be validated (nested deeper than [Nodes.MAX_DEPTH]). */
internal class SchemaInstanceException(message: String) : RuntimeException(message)
