package su.seaf.archtool.jsonata.value

import su.seaf.archtool.jsonata.parser.JsonataException

/**
 * `JSON.stringify` for [Value]s: JS number formatting, JS string escaping (well-formed: lone surrogates as `\uXXXX`),
 * JS key order, optional indentation. With [jsonataReplacer] it is `$string` (functions.js: string): numbers that are
 * not integers are written with 15 significant digits, functions as `""`, and an infinite number inside a structure
 * raises D1001.
 */
public object JsonWriter {
    /** Plain `JSON.stringify(value)`; undefined (null) gives "undefined". NaN and infinities are written as `null`. */
    @JvmStatic
    public fun stringify(value: Value?, indent: Int = 0): String {
        if (value == null) return "undefined"
        val sb = StringBuilder()
        write(sb, value, indent, 0, false)
        return sb.toString()
    }

    /** The JSON text `$string` produces for a non-string value (functions.js: string, after the type checks). */
    internal fun stringifyJsonata(value: Value, prettify: Boolean): String {
        val sb = StringBuilder()
        write(sb, value, if (prettify) 2 else 0, 0, true)
        return sb.toString()
    }

    private fun write(sb: StringBuilder, value: Value, indent: Int, level: Int, replacer: Boolean) {
        when (value) {
            is NullValue -> sb.append("null")
            is BooleanValue -> sb.append(if (value.value) "true" else "false")
            is NumberValue -> {
                var d = value.value
                if (replacer) {
                    if (d.isInfinite()) throw JsonataException("D1001", value = value)
                    if (!d.isNaN() && d != Math.floor(d)) d = JsNumber.toPrecision15(d)
                }
                if (d.isNaN() || d.isInfinite()) sb.append("null") else sb.append(JsNumber.toString(d))
            }
            is StringValue -> quote(sb, value.value)
            is FunctionValue -> if (replacer) sb.append("\"\"") else sb.append("{}")
            is ArrayValue -> {
                if (value.isEmpty()) {
                    sb.append("[]")
                    return
                }
                sb.append('[')
                var first = true
                for (item in value) {
                    if (!first) sb.append(',')
                    first = false
                    newline(sb, indent, level + 1)
                    write(sb, item, indent, level + 1, replacer)
                }
                newline(sb, indent, level)
                sb.append(']')
            }
            is ObjectValue -> {
                if (value.isEmpty()) {
                    sb.append("{}")
                    return
                }
                sb.append('{')
                var first = true
                for (key in value.keys) {
                    if (!first) sb.append(',')
                    first = false
                    newline(sb, indent, level + 1)
                    quote(sb, key)
                    sb.append(if (indent > 0) ": " else ":")
                    write(sb, value[key]!!, indent, level + 1, replacer)
                }
                newline(sb, indent, level)
                sb.append('}')
            }
        }
    }

    private fun newline(sb: StringBuilder, indent: Int, level: Int) {
        if (indent <= 0) return
        sb.append('\n')
        repeat(indent * level) { sb.append(' ') }
    }

    /** JSON.stringify string quoting (ECMA-262 QuoteJSONString). */
    @JvmStatic
    public fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\b' -> sb.append("\\b")
                c == '\u000C' -> sb.append("\\f")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> hex(sb, c)
                Character.isHighSurrogate(c) -> {
                    if (i + 1 < n && Character.isLowSurrogate(s[i + 1])) {
                        sb.append(c).append(s[i + 1])
                        i++
                    } else {
                        hex(sb, c)
                    }
                }
                Character.isLowSurrogate(c) -> hex(sb, c)
                else -> sb.append(c)
            }
            i++
        }
        sb.append('"')
    }

    @JvmStatic
    public fun quote(s: String): String = StringBuilder(s.length + 2).also { quote(it, s) }.toString()

    private fun hex(sb: StringBuilder, c: Char) {
        val h = Integer.toHexString(c.code)
        sb.append("\\u")
        repeat(4 - h.length) { sb.append('0') }
        sb.append(h)
    }
}
