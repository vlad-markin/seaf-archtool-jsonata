package su.seaf.archtool.jsonata.parser

/** One parameter of a function signature (signature.js: param). */
public class SignatureParam internal constructor() {
    public var regex: String = ""
        internal set
    public var type: String? = null
        internal set
    public var array: Boolean = false
        internal set
    /** `-`: the context value is used when the argument is missing. */
    public var context: Boolean = false
        internal set
    public var contextRegex: String? = null
        internal set
    public var subtype: String? = null
        internal set
}

/**
 * A parsed function signature such as `<n-n?:n>` (signature.js: parseSignature). The argument validator built from it
 * belongs to the evaluator (phase 4); here only the structure and the parse errors S0401/S0402.
 */
public class Signature internal constructor(public val definition: String, public val params: List<SignatureParam>) {
    /** The whole-signature regex, as signature.js builds it. */
    public val regex: String get() = "^" + params.joinToString("") { "(" + it.regex + ")" } + "$"

    /** Compiled [regex], cached: signatures are validated on every call. */
    internal val pattern: java.util.regex.Pattern by lazy { java.util.regex.Pattern.compile(regex) }

    /** Per-parameter context patterns (`new RegExp(param.contextRegex)` in signature.js, unanchored). */
    internal val contextPatterns: List<java.util.regex.Pattern?> by lazy {
        params.map { p -> p.contextRegex?.let { java.util.regex.Pattern.compile(it) } }
    }

    internal companion object {
        fun parse(signature: String): Signature {
            var position = 1
            val params = ArrayList<SignatureParam>()
            var param = SignatureParam()
            var prevParam = param
            fun next() {
                params += param
                prevParam = param
                param = SignatureParam()
            }
            fun findClosingBracket(str: String, start: Int, open: Char, close: Char): Int {
                var depth = 1
                var p = start
                while (p < str.length) {
                    p++
                    val symbol = str.getOrNull(p)
                    if (symbol == close) {
                        depth--
                        if (depth == 0) break
                    } else if (symbol == open) {
                        depth++
                    }
                }
                return p
            }
            while (position < signature.length) {
                val symbol = signature[position]
                if (symbol == ':') break // the return type is ignored, as in the reference
                when (symbol) {
                    's', 'n', 'b', 'l', 'o' -> { param.regex = "[${symbol}m]"; param.type = symbol.toString(); next() }
                    'a' -> { param.regex = "[asnblfom]"; param.type = "a"; param.array = true; next() }
                    'f' -> { param.regex = "f"; param.type = "f"; next() }
                    'j' -> { param.regex = "[asnblom]"; param.type = "j"; next() }
                    'x' -> { param.regex = "[asnblfom]"; param.type = "x"; next() }
                    '-' -> { prevParam.context = true; prevParam.contextRegex = prevParam.regex; prevParam.regex += "?" }
                    '?', '+' -> prevParam.regex += symbol
                    '(' -> {
                        val endParen = findClosingBracket(signature, position, '(', ')')
                        val choice = signature.substring(position + 1, minOf(endParen, signature.length))
                        if ('<' !in choice) {
                            param.regex = "[${choice}m]"
                        } else {
                            throw JsonataException("S0402", value = choice).also { it.offset = position }
                        }
                        param.type = "($choice)"
                        position = endParen
                        next()
                    }
                    '<' -> {
                        if (prevParam.type == "a" || prevParam.type == "f") {
                            val endPos = findClosingBracket(signature, position, '<', '>')
                            prevParam.subtype = signature.substring(position + 1, minOf(endPos, signature.length))
                            position = endPos
                        } else {
                            throw JsonataException("S0401", value = prevParam.type).also { it.offset = position }
                        }
                    }
                }
                position++
            }
            return Signature(signature, params)
        }
    }
}
