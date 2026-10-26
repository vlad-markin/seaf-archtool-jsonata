package su.seaf.archtool.jsonata.tools.gate

import su.seaf.archtool.jsonata.parser.JsonataException
import su.seaf.archtool.jsonata.value.ArrayValue
import su.seaf.archtool.jsonata.value.FunctionValue
import su.seaf.archtool.jsonata.value.JsonWriter
import su.seaf.archtool.jsonata.value.ObjectValue
import su.seaf.archtool.jsonata.value.StringValue
import su.seaf.archtool.jsonata.value.Value

/**
 * The canonical text of an outcome for the lake gate, the same on the JVM and in Node (`compat/node/canonical.mjs`;
 * design §2.1 p. 4): JSON with ECMAScript number lexemes and the object key order of the value, `undefined` marked at
 * any depth (`#undefined`), functions marked (`#function`), errors as `error CODE @position token`.
 */
object Canonical {
    fun of(value: Value?): String = StringBuilder().also { write(value, it) }.toString()

    fun error(e: JsonataException): String = "error ${e.code} @${e.position ?: "null"} ${token(e.token)}"

    private fun token(t: Any?): String = when (t) {
        null -> "null"
        is Double -> su.seaf.archtool.jsonata.value.JsNumber.toString(t)
        else -> t.toString()
    }

    private fun write(v: Value?, sb: StringBuilder) {
        when (v) {
            null -> sb.append("#undefined")
            is FunctionValue -> sb.append("#function")
            is ArrayValue -> {
                sb.append('[')
                for (i in 0 until v.size) {
                    if (i > 0) sb.append(',')
                    write(v.getOrNull(i), sb)
                }
                sb.append(']')
            }
            is ObjectValue -> {
                sb.append('{')
                var first = true
                v.forEach { k, x ->
                    if (!first) sb.append(',')
                    first = false
                    sb.append(JsonWriter.stringify(StringValue.of(k))).append(':')
                    write(x, sb)
                }
                sb.append('}')
            }
            else -> sb.append(JsonWriter.stringify(v))
        }
    }
}
