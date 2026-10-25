package su.seaf.archtool.jsonata.parser

/**
 * An AST node as jsonata-js builds it: a JS object whose own properties are this map, in insertion order. A missing key
 * is `undefined`, a key mapped to null is JSON `null`. Values: [AstNode], [List], String, Double, Boolean, null,
 * [JsRegex], [Signature]. Parser nodes also carry a symbol (the JS prototype: `id`, binding power, nud/led), which is
 * not part of the AST, as `ast()` in jsonata-js only exposes own properties.
 */
public class AstNode internal constructor(internal val symbol: Symbol? = null) : LinkedHashMap<String, Any?>() {
    /** JS `node.id`: the prototype's id (`(name)`, `(literal)`, an operator); null for nodes built by processAST. */
    public val id: String? get() = symbol?.id

    public val type: String? get() = this["type"] as? String

    /** JS `node.value`: own value, or the prototype's (`symbol.value = id`). */
    public val value: Any? get() = if (containsKey("value")) this["value"] else symbol?.id

    /** Offset right after the token the node starts from, as in jsonata-js. */
    public val position: Int? get() = (this["position"] as? Number)?.toInt()

    internal fun has(key: String): Boolean = containsKey(key)

    /** JS property read: the value, or [Undefined] when the property does not exist. */
    internal fun prop(key: String): Any? = if (containsKey(key)) this[key] else Undefined

    /** JS `obj.key = undefined` leaves no trace in `ast()` (JSON.stringify drops it): the key is removed. */
    override fun put(key: String, value: Any?): Any? = if (value === Undefined) remove(key) else super.put(key, value)

    internal fun node(key: String): AstNode? = this[key] as? AstNode

    @Suppress("UNCHECKED_CAST")
    internal fun nodes(key: String): MutableList<Any?>? = this[key] as? MutableList<Any?>

    internal fun truthy(key: String): Boolean = when (val v = this[key]) {
        null, false -> false
        is String -> v.isNotEmpty()
        is Double -> v != 0.0 && !v.isNaN()
        else -> true
    }

    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)

    internal companion object {
        fun of(vararg entries: Pair<String, Any?>): AstNode = AstNode().also { n -> entries.forEach { (k, v) -> n[k] = v } }

        /** The JS `undefined` value while building nodes. */
        val Undefined: Any = Any()

        /** `JSON.parse(JSON.stringify(value))`: own properties only, functions and undefined dropped, RegExp → {}. */
        fun jsonCopy(value: Any?): Any? = when (value) {
            is AstNode -> AstNode().also { out -> value.forEach { (k, v) -> out[k] = jsonCopy(v) } }
            is List<*> -> value.mapTo(ArrayList()) { jsonCopy(it) }
            is JsRegex -> AstNode()
            is Signature -> AstNode.of("definition" to value.definition)
            is Double -> if (value == 0.0) 0.0 else value
            else -> value
        }
    }
}

/** A regular expression literal: the pattern between slashes and the flags (`i`, `m`, plus `g` added by the tokenizer). */
public class JsRegex(public val pattern: String, public val flags: String) {
    /** `RegExp.prototype.source`: slashes outside character classes and line terminators are escaped. */
    public val source: String get() {
        if (pattern.isEmpty()) return "(?:)"
        val sb = StringBuilder()
        var inClass = false
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\\' && i + 1 < pattern.length -> { sb.append(c).append(pattern[i + 1]); i += 2; continue }
                c == '[' -> inClass = true
                c == ']' -> inClass = false
            }
            when {
                c == '/' && !inClass -> sb.append("\\/")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == ' ' -> sb.append("\\u2028")
                c == ' ' -> sb.append("\\u2029")
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    /** `RegExp.prototype.flags`: canonical order. */
    public val canonicalFlags: String get() = "dgimsuvy".filter { it in flags }
}

/** Token types of the jsonata-js tokenizer. */
public enum class TokenType { OPERATOR, NAME, VARIABLE, STRING, NUMBER, VALUE, REGEX }

/**
 * A token with its source range: [start] is the first character, [end] the offset after the last one (the jsonata-js
 * `position`). [value] is the unescaped string, the number (Double), true/false/null, the operator, or a [JsRegex].
 */
public data class Token(val type: TokenType, val value: Any?, val start: Int, val end: Int)

/** A `/* ... */` comment range (start inclusive, end exclusive). */
public data class Comment(val start: Int, val end: Int)

/** Parser symbol: the JS prototype of parser nodes. */
internal class Symbol(val id: String) {
    var lbp: Int = 0
    var nud: ((AstNode) -> AstNode)? = null
    var led: ((AstNode, AstNode) -> AstNode)? = null
}
