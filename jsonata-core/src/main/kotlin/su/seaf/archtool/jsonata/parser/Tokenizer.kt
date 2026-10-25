package su.seaf.archtool.jsonata.parser

/**
 * The jsonata-js tokenizer (parser.js: tokenizer), one token per [next] call. Besides the reference behaviour it records
 * token starts and comment ranges for IDE features ([Token.start], [comments]).
 */
internal class Tokenizer(private val path: String, private val onToken: ((Token) -> Unit)? = null) {
    private var position = 0
    private val length = path.length
    private var tokenStart = 0
    val comments = ArrayList<Comment>()

    private fun charAt(i: Int): Char? = if (i in 0 until length) path[i] else null

    private fun create(type: TokenType, value: Any?): Token {
        val token = Token(type, value, tokenStart, position)
        onToken?.invoke(token)
        return token
    }

    private fun scanRegex(): JsRegex {
        // the prefix '/' will have been previously scanned. Find the end of the regex.
        var start = position
        var depth = 0
        fun isClosingSlash(p: Int): Boolean {
            if (charAt(p) == '/' && depth == 0) {
                var backslashCount = 0
                while (charAt(p - (backslashCount + 1)) == '\\') backslashCount++
                if (backslashCount % 2 == 0) return true
            }
            return false
        }
        while (position < length) {
            var currentChar = charAt(position)
            if (isClosingSlash(position)) {
                val pattern = path.substring(start, position)
                if (pattern == "") throw JsonataException("S0301", position)
                position++
                currentChar = charAt(position)
                start = position
                while (currentChar == 'i' || currentChar == 'm') {
                    position++
                    currentChar = charAt(position)
                }
                return JsRegex(pattern, path.substring(start, position) + "g")
            }
            if ((currentChar == '(' || currentChar == '[' || currentChar == '{') && charAt(position - 1) != '\\') depth++
            if ((currentChar == ')' || currentChar == ']' || currentChar == '}') && charAt(position - 1) != '\\') depth--
            position++
        }
        throw JsonataException("S0302", position)
    }

    fun next(prefix: Boolean = false): Token? {
        if (position >= length) return null
        var currentChar = charAt(position)
        // skip whitespace
        while (position < length && currentChar != null && currentChar in WHITESPACE) {
            position++
            currentChar = charAt(position)
        }
        // skip comments
        if (currentChar == '/' && charAt(position + 1) == '*') {
            val commentStart = position
            position += 2
            currentChar = charAt(position)
            while (!(currentChar == '*' && charAt(position + 1) == '/')) {
                currentChar = charAt(++position)
                if (position >= length) throw JsonataException("S0106", commentStart)
            }
            position += 2
            comments += Comment(commentStart, position)
            return next(prefix) // need this to swallow any following whitespace
        }
        tokenStart = position
        // test for regex
        if (!prefix && currentChar == '/') {
            position++
            return create(TokenType.REGEX, scanRegex())
        }
        // handle double-char operators
        val nextChar = charAt(position + 1)
        val double = when {
            currentChar == '.' && nextChar == '.' -> ".."
            currentChar == ':' && nextChar == '=' -> ":="
            currentChar == '!' && nextChar == '=' -> "!="
            currentChar == '>' && nextChar == '=' -> ">="
            currentChar == '<' && nextChar == '=' -> "<="
            currentChar == '*' && nextChar == '*' -> "**"
            currentChar == '~' && nextChar == '>' -> "~>"
            currentChar == '?' && nextChar == ':' -> "?:"
            currentChar == '?' && nextChar == '?' -> "??"
            else -> null
        }
        if (double != null) {
            position += 2
            return create(TokenType.OPERATOR, double)
        }
        // test for single char operators
        if (currentChar != null && currentChar in SINGLE_CHAR_OPERATORS) {
            position++
            return create(TokenType.OPERATOR, currentChar.toString())
        }
        // test for string literals
        if (currentChar == '"' || currentChar == '\'') {
            val quoteType = currentChar
            position++
            val qstr = StringBuilder()
            while (position < length) {
                currentChar = charAt(position)
                if (currentChar == '\\') { // escape sequence
                    position++
                    currentChar = charAt(position)
                    val escaped = currentChar?.let { ESCAPES[it] }
                    if (escaped != null) {
                        qstr.append(escaped)
                    } else if (currentChar == 'u') {
                        // \u should be followed by 4 hex digits (substr may return fewer at the end of input)
                        val octets = path.substring(minOf(position + 1, length), minOf(position + 5, length))
                        if (octets.isNotEmpty() && octets.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                            qstr.append(octets.toInt(16).toChar())
                            position += 4
                        } else {
                            throw JsonataException("S0104", position)
                        }
                    } else {
                        // illegal escape sequence
                        throw JsonataException("S0103", position, token = currentChar?.toString() ?: "")
                    }
                } else if (currentChar == quoteType) {
                    position++
                    return create(TokenType.STRING, qstr.toString())
                } else {
                    qstr.append(currentChar)
                }
                position++
            }
            throw JsonataException("S0101", position)
        }
        // test for numbers
        val match = NUMBER.find(path.substring(position))
        if (match != null) {
            val num = match.value.toDoubleOrNull()
            if (num != null && !num.isNaN() && !num.isInfinite()) {
                position += match.value.length
                return create(TokenType.NUMBER, num)
            } else {
                throw JsonataException("S0102", position, token = match.value)
            }
        }
        // test for quoted names (backticks)
        if (currentChar == '`') {
            position++
            val end = path.indexOf('`', position)
            if (end != -1) {
                val name = path.substring(position, end)
                position = end + 1
                return create(TokenType.NAME, name)
            }
            position = length
            throw JsonataException("S0105", position)
        }
        // test for names
        var i = position
        while (true) {
            val ch = charAt(i)
            if (i == length || ch == null || ch in WHITESPACE || ch in SINGLE_CHAR_OPERATORS) {
                if (charAt(position) == '$') {
                    // variable reference
                    val name = path.substring(position + 1, i)
                    position = i
                    return create(TokenType.VARIABLE, name)
                } else {
                    val name = path.substring(position, i)
                    position = i
                    return when (name) {
                        "or", "in", "and" -> create(TokenType.OPERATOR, name)
                        "true" -> create(TokenType.VALUE, true)
                        "false" -> create(TokenType.VALUE, false)
                        "null" -> create(TokenType.VALUE, null)
                        else -> {
                            if (position == length && name == "") return null // whitespace at end of input
                            create(TokenType.NAME, name)
                        }
                    }
                }
            } else {
                i++
            }
        }
    }

    companion object {
        private const val WHITESPACE = " \t\n\r\u000B"

        // Keys of parser.js `operators` that are one character long ('!' and '~' are stop characters only).
        private const val SINGLE_CHAR_OPERATORS = ".[]{}(),@#;:?+-*/%|=<>^&!~"

        private val ESCAPES = mapOf('"' to "\"", '\\' to "\\", '/' to "/", 'b' to "\b", 'f' to "\u000C", 'n' to "\n", 'r' to "\r", 't' to "\t")

        private val NUMBER = Regex("^-?(0|([1-9][0-9]*))(\\.[0-9]+)?([Ee][-+]?[0-9]+)?")
    }
}
