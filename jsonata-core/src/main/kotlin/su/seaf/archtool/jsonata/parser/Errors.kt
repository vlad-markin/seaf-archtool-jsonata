package su.seaf.archtool.jsonata.parser

/**
 * A JSONata error with the reference code (`S0201`, `T2001`, ...), [position] (offset after the offending token, as in
 * jsonata-js), [token] and [value]; the message is built from the reference templates (jsonata.js: errorCodes,
 * populateMessage). Like the JS error object, position and token are filled in on the way up (by the operator or the
 * function call that the error passes through) when the code that raised it did not know them.
 */
public class JsonataException(
    public val code: String,
    position: Int? = null,
    token: Any? = null,
    public val value: Any? = null,
    /** Second value of T2007/T2009. */
    public val value2: Any? = null,
    /** 1-based argument index of signature errors (T0410, T0411, T0412) and some function errors. */
    public val index: Int? = null,
    /** Extra fields some templates use: `type` (T0412), `exp` (D3061), `message` (D3137, D3141), `functionName` (D3140). */
    public val details: Map<String, Any?> = emptyMap(),
    cause: Throwable? = null,
) : RuntimeException(null as String?, cause) {
    public var position: Int? = position
        internal set
    public var token: Any? = token
        internal set

    /** Offset inside a signature for S0401/S0402 before it is turned into a position in the expression. */
    internal var offset: Int? = null

    override val message: String
        get() = Messages.format(this)
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
        "T0410" to "Argument {{index}} of function {{token}} does not match function signature",
        "T0411" to "Context value is not a compatible type with argument {{index}} of function {{token}}",
        "T0412" to "Argument {{index}} of function {{token}} must be an array of {{type}}",
        "D1001" to "Number out of range: {{value}}",
        "D1002" to "Cannot negate a non-numeric value: {{value}}",
        "T1003" to "Key in object structure must evaluate to a string; got: {{value}}",
        "D1004" to "Regular expression matches zero length string",
        "T1005" to "Attempted to invoke a non-function. Did you mean \${{{token}}}?",
        "T1006" to "Attempted to invoke a non-function",
        "T1007" to "Attempted to partially apply a non-function. Did you mean \${{{token}}}?",
        "T1008" to "Attempted to partially apply a non-function",
        "D1009" to "Multiple key definitions evaluate to same key: {{value}}",
        "T1010" to "The matcher function argument passed to function {{token}} does not return the correct object structure",
        "D1011" to "Stack overflow. Check for non-terminating recursive function.  Consider rewriting as tail-recursive",
        "D1012" to "Evaluation timeout after {{value}} milliseconds. Check for infinite loop",
        "D1013" to "Object property names starting with _jsonata_ are reserved for internal use: {{value}}",
        "T2001" to "The left side of the {{token}} operator must evaluate to a number",
        "T2002" to "The right side of the {{token}} operator must evaluate to a number",
        "T2003" to "The left side of the range operator (..) must evaluate to an integer",
        "T2004" to "The right side of the range operator (..) must evaluate to an integer",
        "D2005" to "The left side of := must be a variable name (start with $)",
        "T2006" to "The right side of the function application operator ~> must be a function",
        "T2007" to "Type mismatch when comparing values {{value}} and {{value2}} in order-by clause",
        "T2008" to "The expressions within an order-by clause must evaluate to numeric or string values",
        "T2009" to "The values {{value}} and {{value2}} either side of operator {{token}} must be of the same data type",
        "T2010" to "The expressions either side of operator {{token}} must evaluate to numeric or string values",
        "T2011" to "The insert/update clause of the transform expression must evaluate to an object: {{value}}",
        "T2012" to "The delete clause of the transform expression must evaluate to a string or array of strings: {{value}}",
        "T2013" to "The transform expression clones the input object using the \$clone() function.  This has been overridden in the current scope by a non-function.",
        "D2014" to "The size of the sequence allocated by the range operator (..) must not exceed 1e7.  Attempted to allocate {{value}}.",
        "D2015" to "The maximum sequence length of {{value}} was exceeded.",
        "D3001" to "Attempting to invoke string function on Infinity or NaN",
        "D3010" to "Second argument of replace function cannot be an empty string",
        "D3011" to "Fourth argument of replace function must evaluate to a positive number",
        "D3012" to "Attempted to replace a matched string with a non-string value",
        "D3020" to "Third argument of split function must evaluate to a positive number",
        "D3030" to "Unable to cast value to a number: {{value}}",
        "D3040" to "Third argument of match function must evaluate to a positive number",
        "D3050" to "The second argument of reduce function must be a function with at least two arguments",
        "D3060" to "The sqrt function cannot be applied to a negative number: {{value}}",
        "D3061" to "The power function has resulted in a value that cannot be represented as a JSON number: base={{value}}, exponent={{exp}}",
        "D3070" to "The single argument form of the sort function can only be applied to an array of strings or an array of numbers.  Use the second argument to specify a comparison function",
        "D3080" to "The picture string must only contain a maximum of two sub-pictures",
        "D3081" to "The sub-picture must not contain more than one instance of the 'decimal-separator' character",
        "D3082" to "The sub-picture must not contain more than one instance of the 'percent' character",
        "D3083" to "The sub-picture must not contain more than one instance of the 'per-mille' character",
        "D3084" to "The sub-picture must not contain both a 'percent' and a 'per-mille' character",
        "D3085" to "The mantissa part of a sub-picture must contain at least one character that is either an 'optional digit character' or a member of the 'decimal digit family'",
        "D3086" to "The sub-picture must not contain a passive character that is preceded by an active character and that is followed by another active character",
        "D3087" to "The sub-picture must not contain a 'grouping-separator' character that appears adjacent to a 'decimal-separator' character",
        "D3088" to "The sub-picture must not contain a 'grouping-separator' at the end of the integer part",
        "D3089" to "The sub-picture must not contain two adjacent instances of the 'grouping-separator' character",
        "D3090" to "The integer part of the sub-picture must not contain a member of the 'decimal digit family' that is followed by an instance of the 'optional digit character'",
        "D3091" to "The fractional part of the sub-picture must not contain an instance of the 'optional digit character' that is followed by a member of the 'decimal digit family'",
        "D3092" to "A sub-picture that contains a 'percent' or 'per-mille' character must not contain a character treated as an 'exponent-separator'",
        "D3093" to "The exponent part of the sub-picture must comprise only of one or more characters that are members of the 'decimal digit family'",
        "D3100" to "The radix of the formatBase function must be between 2 and 36.  It was given {{value}}",
        "D3110" to "The argument of the toMillis function must be an ISO 8601 formatted timestamp. Given {{value}}",
        "D3120" to "Syntax error in expression passed to function eval: {{value}}",
        "D3121" to "Dynamic error evaluating the expression passed to function eval: {{value}}",
        "D3130" to "Formatting or parsing an integer as a sequence starting with {{value}} is not supported by this implementation",
        "D3131" to "In a decimal digit pattern, all digits must be from the same decimal group",
        "D3132" to "Unknown component specifier {{value}} in date/time picture string",
        "D3133" to "The 'name' modifier can only be applied to months and days in the date/time picture string, not {{value}}",
        "D3134" to "The timezone integer format specifier cannot have more than four digits",
        "D3135" to "No matching closing bracket ']' in date/time picture string",
        "D3136" to "The date/time picture string is missing specifiers required to parse the timestamp",
        "D3137" to "{{{message}}}",
        "D3138" to "The \$single() function expected exactly 1 matching result.  Instead it matched more.",
        "D3139" to "The \$single() function expected exactly 1 matching result.  Instead it matched 0.",
        "D3140" to "Malformed URL passed to \${{{functionName}}}(): {{value}}",
        "D3141" to "{{{message}}}",
    )

    /**
     * Errors of this engine that jsonata-js does not have (decision 18): same code format, letter X. Reference codes
     * (S, T, D, U) are never reused for them.
     */
    private val engineTemplates = mapOf(
        "X0001" to "A function named {{value}} is already registered; registered names cannot be replaced",
        "X0002" to "The evaluation exhausted the JVM stack. Check for non-terminating recursion or rewrite it as tail-recursive",
        "X0003" to "Invalid regular expression {{value}}: {{{message}}}",
        "X0004" to "The standard library registry is read-only; register {{value}} in FunctionRegistry.create() instead",
    )

    // populateMessage: {{{field}}} is replaced with the raw field, {{field}} with JSON.stringify(err[field]).
    fun format(e: JsonataException): String {
        val template = templates[e.code] ?: engineTemplates[e.code] ?: return e.code
        fun field(name: String): Any? = when (name) {
            "token" -> e.token
            "value" -> e.value
            "value2" -> e.value2
            "index" -> e.index?.toDouble()
            else -> e.details[name]
        }
        val raw = Regex("\\{\\{\\{([^}]+)}}}").replace(template) { m -> rawString(field(m.groupValues[1])) }
        return Regex("\\{\\{([^}]+)}}").replace(raw) { m -> stringify(field(m.groupValues[1])) }
    }

    private fun rawString(v: Any?): String = when (v) {
        null -> "undefined"
        is su.seaf.archtool.jsonata.value.StringValue -> v.value
        is su.seaf.archtool.jsonata.value.Value -> v.toString()
        is Double -> JsNumbers.toString(v)
        else -> v.toString()
    }

    private fun stringify(v: Any?): String = when (v) {
        null -> "undefined"
        is String -> su.seaf.archtool.jsonata.value.JsonWriter.quote(v)
        is Double -> JsNumbers.toString(v)
        is su.seaf.archtool.jsonata.value.Value -> su.seaf.archtool.jsonata.value.JsonWriter.stringify(v)
        else -> v.toString()
    }
}

/** The JS `Number.prototype.toString` subset the parser needs (integers and plain decimals). */
internal object JsNumbers {
    fun toString(d: Double): String = su.seaf.archtool.jsonata.value.JsNumber.toString(d)
}
