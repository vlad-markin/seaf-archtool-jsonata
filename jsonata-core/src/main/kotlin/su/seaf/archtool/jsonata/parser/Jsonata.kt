package su.seaf.archtool.jsonata.parser

/** Result of parsing for IDE features: the AST (null on error), the error, and every token and comment seen. */
public class ParseResult internal constructor(
    public val ast: AstNode?,
    public val error: JsonataException?,
    public val tokens: List<Token>,
    public val comments: List<Comment>,
)

/** Entry points of the parser (phase 3). The API is unstable until jsonata-host. */
public object JsonataParser {
    /** jsonata-js `jsonata(expr).ast()`; throws [JsonataException] with the reference code, position and token. */
    public fun ast(expression: String): AstNode = Parser(expression).parse()

    /**
     * Parses and also returns the tokens in source order and the comments, for highlighting. On a syntax error the
     * tokens before the error are kept and the rest of the text is tokenized as far as possible.
     */
    public fun analyze(expression: String): ParseResult {
        val tokens = ArrayList<Token>()
        val parser = Parser(expression) { tokens += it }
        return try {
            ParseResult(parser.parse(), null, tokens.distinctBy { it.start }, parser.comments)
        } catch (e: JsonataException) {
            val rest = tokenizeRest(expression, tokens.lastOrNull()?.end ?: 0)
            ParseResult(null, e, (tokens + rest.first).distinctBy { it.start }.sortedBy { it.start }, parser.comments + rest.second)
        }
    }

    /** Best-effort tokens after a syntax error: unknown regions are skipped character by character. */
    private fun tokenizeRest(expression: String, from: Int): Pair<List<Token>, List<Comment>> {
        val tokens = ArrayList<Token>()
        val comments = ArrayList<Comment>()
        var offset = from
        while (offset < expression.length) {
            val tail = expression.substring(offset)
            val lexer = Tokenizer(tail)
            val shift = offset
            try {
                var prefix = true
                while (true) {
                    val t = lexer.next(prefix) ?: break
                    tokens += t.copy(start = t.start + shift, end = t.end + shift)
                    prefix = t.type != TokenType.OPERATOR || t.value in setOf(")", "]", "}")
                }
                comments += lexer.comments.map { Comment(it.start + shift, it.end + shift) }
                break
            } catch (_: JsonataException) {
                comments += lexer.comments.map { Comment(it.start + shift, it.end + shift) }
                offset = (tokens.lastOrNull()?.end ?: offset).coerceAtLeast(offset) + 1
            }
        }
        return tokens to comments
    }
}
