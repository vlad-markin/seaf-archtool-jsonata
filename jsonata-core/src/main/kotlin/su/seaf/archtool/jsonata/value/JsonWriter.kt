package su.seaf.archtool.jsonata.value

import su.seaf.archtool.jsonata.parser.JsonataException

/**
 * `JSON.stringify` for [Value]s: JS number formatting, JS string escaping (well-formed: lone surrogates as `\uXXXX`),
 * JS key order, optional indentation. With [jsonataReplacer] it is `$string` (functions.js: string): numbers that are
 * not integers are written with 15 significant digits, functions as `""`, and an infinite number inside a structure
 * raises D1001.
 */
public object JsonWriter {
    /**
     * Plain `JSON.stringify(value)`: undefined (null) and a function give "undefined"; inside a structure a function
     * is skipped in an object and written as `null` in an array, as JS does. NaN and infinities are written as `null`.
     */
    @JvmStatic
    public fun stringify(value: Value?, indent: Int = 0): String {
        if (value == null || value is FunctionValue) return "undefined"
        val sb = StringBuilder()
        write(sb, value, indent, 0, false)
        return sb.toString()
    }

    /** The JSON text `$string` produces for a non-string value (functions.js: string, after the type checks). */
    internal fun stringifyJsonata(value: Value, prettify: Boolean, tick: (() -> Unit)? = null): String {
        val sb = StringBuilder()
        write(sb, value, if (prettify) 2 else 0, 0, true, tick)
        return sb.toString()
    }

    /** A container being written: its items (array values or object keys), the next index, its level. */
    private class Open(val array: ArrayValue?, val obj: ObjectValue?, val keys: List<String>?, val level: Int) {
        var index = 0
        var first = true
    }

    /** Writes without recursion, so that nesting depth is limited only by memory (review RV-4). */
    private fun write(sb: StringBuilder, root: Value, indent: Int, level0: Int, replacer: Boolean, tick: (() -> Unit)? = null) {
        val stack = ArrayList<Open>()
        fun scalarOrOpen(value: Value?, level: Int) {
            tick?.invoke()
            when (value) {
                null, is NullValue -> sb.append("null")
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
                is FunctionValue -> if (replacer) sb.append("\"\"") else sb.append("null")
                is ArrayValue -> if (value.isEmpty()) sb.append("[]") else {
                    sb.append('[')
                    stack += Open(value, null, null, level)
                }
                is ObjectValue -> if (value.isEmpty()) sb.append("{}") else {
                    sb.append('{')
                    stack += Open(null, value, value.keys, level)
                }
            }
        }
        scalarOrOpen(root, level0)
        while (stack.isNotEmpty()) {
            val top = stack[stack.size - 1]
            val arr = top.array
            if (arr != null) {
                if (top.index < arr.size) {
                    if (!top.first) sb.append(',')
                    top.first = false
                    newline(sb, indent, top.level + 1)
                    // an undefined item (possible inside sequences) is written as null, as JSON.stringify does
                    scalarOrOpen(arr.getOrNull(top.index++), top.level + 1)
                } else {
                    stack.removeAt(stack.size - 1)
                    newline(sb, indent, top.level)
                    sb.append(']')
                }
                continue
            }
            val obj = top.obj!!
            val keys = top.keys!!
            var wrote = false
            while (top.index < keys.size) {
                val key = keys[top.index++]
                val v = obj[key]
                if (!replacer && v is FunctionValue) continue
                if (!top.first) sb.append(',')
                top.first = false
                newline(sb, indent, top.level + 1)
                quote(sb, key)
                sb.append(if (indent > 0) ": " else ":")
                scalarOrOpen(v, top.level + 1)
                wrote = true
                break
            }
            if (!wrote) {
                stack.removeAt(stack.size - 1)
                if (!top.first) newline(sb, indent, top.level)
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
