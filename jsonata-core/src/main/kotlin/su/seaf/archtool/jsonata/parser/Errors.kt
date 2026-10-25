package su.seaf.archtool.jsonata.parser

/**
 * A JSONata error with the reference code (`S0201`, `T2001`, ...), [position] (offset after the offending token, as in
 * jsonata-js), [token] and [value]; the message is built from the reference templates (jsonata.js: errorCodes,
 * populateMessage).
 */
public class JsonataException(
    public val code: String,
    public val position: Int? = null,
    public val token: Any? = null,
    public val value: Any? = null,
) : RuntimeException(null as String?) {
    /** Offset inside a signature for S0401/S0402 before it is turned into a position in the expression. */
    internal var offset: Int? = null

    override val message: String
        get() = Messages.format(code, token, value)
}

internal object Messages {
    // jsonata.js: errorCodes (static errors; runtime codes are added with the evaluator).
    private val templates = mapOf(
        "S0101" to "String literal must be terminated by a matching quote",
        "S0102" to "Number out of range: {{token}}",
        "S0103" to "Unsupported escape sequence: \\{{token}}",
        "S0104" to "The escape sequence \\u must be followed by 4 hex digits",
        "S0105" to "Quoted property name must be terminated with a backquote (`)",
        "S0106" to "Comment has no closing tag",
        "S0201" to "Syntax error: {{token}}",
        "S0202" to "Expected {{value}}, got {{token}}",
        "S0203" to "Expected {{value}} before end of expression",
        "S0204" to "Unknown operator: {{token}}",
        "S0205" to "Unexpected token: {{token}}",
        "S0206" to "Unknown expression type: {{token}}",
        "S0207" to "Unexpected end of expression",
        "S0208" to "Parameter {{value}} of function definition must be a variable name (start with $)",
        "S0209" to "A predicate cannot follow a grouping expression in a step",
        "S0210" to "Each step can only have one grouping expression",
        "S0211" to "The symbol {{token}} cannot be used as a unary operator",
        "S0212" to "The left side of := must be a variable name (start with $)",
        "S0213" to "The literal value {{value}} cannot be used as a step within a path expression",
        "S0214" to "The right side of {{token}} must be a variable name (start with $)",
        "S0215" to "A context variable binding must precede any predicates on a step",
        "S0216" to "A context variable binding must precede the 'order-by' clause on a step",
        "S0217" to "The object representing the 'parent' cannot be derived from this expression",
        "S0301" to "Empty regular expressions are not allowed",
        "S0302" to "No terminating / in regular expression",
        "S0402" to "Choice groups containing parameterized types are not supported",
        "S0401" to "Type parameters can only be applied to functions and arrays",
        "S0500" to "Attempted to evaluate an expression containing syntax error(s)",
    )

    // populateMessage: {{field}} is replaced with JSON.stringify(err[field]).
    fun format(code: String, token: Any?, value: Any?): String {
        val template = templates[code] ?: return code
        return Regex("\\{\\{([^}]+)}}").replace(template) { m ->
            when (m.groupValues[1]) {
                "token" -> stringify(token)
                "value" -> stringify(value)
                else -> "undefined"
            }
        }
    }

    private fun stringify(v: Any?): String = when (v) {
        null -> "undefined"
        is String -> "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
        is Double -> JsNumbers.toString(v)
        else -> v.toString()
    }
}

/** The JS `Number.prototype.toString` subset the parser needs (integers and plain decimals). */
internal object JsNumbers {
    fun toString(d: Double): String {
        if (d.isNaN()) return "NaN"
        if (d.isInfinite()) return if (d > 0) "Infinity" else "-Infinity"
        if (d == 0.0) return "0"
        if (d == Math.rint(d) && Math.abs(d) < 1e21) return java.math.BigDecimal(d).toPlainString()
        return d.toString()
    }
}
