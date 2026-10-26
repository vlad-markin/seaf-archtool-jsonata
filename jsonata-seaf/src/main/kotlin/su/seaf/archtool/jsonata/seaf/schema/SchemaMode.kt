package su.seaf.archtool.jsonata.seaf.schema

/** How the SEAF core configures ajv for a schema (`functions.mjs` and `driver.mjs` of seaf-archtool-core). */
internal enum class SchemaMode(val regexFlags: String, val extraKeywords: Set<String>, val formats: Set<String>) {
    /** `params` of manifest functions: ajv 8 defaults (regex flag `u`), no formats, `callback`/`params`. */
    MANIFEST_FUNCTION("u", setOf("callback", "params"), emptySet()),

    /** `result` of manifest functions: as [MANIFEST_FUNCTION] without `callback`/`params` (review RV3-36). */
    MANIFEST_RESULT("u", emptySet(), emptySet()),

    /** `$jsonschema`: `unicodeRegExp: false`, the full formats of ajv-formats 2.1.1 and their keywords, `$rels`. */
    JSONSCHEMA("", setOf("\$rels", "formatMinimum", "formatMaximum", "formatExclusiveMinimum", "formatExclusiveMaximum"), AjvFormats.NAMES),
}
