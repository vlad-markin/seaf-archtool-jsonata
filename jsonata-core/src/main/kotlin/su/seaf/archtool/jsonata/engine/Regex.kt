package su.seaf.archtool.jsonata.engine

import su.seaf.archtool.jsonata.parser.JsRegex
import su.seaf.archtool.jsonata.parser.JsonataException
import su.seaf.archtool.jsonata.value.ArrayValue
import su.seaf.archtool.jsonata.value.NumberValue
import su.seaf.archtool.jsonata.value.ObjectValue
import su.seaf.archtool.jsonata.value.StringValue
import su.seaf.archtool.jsonata.value.Value
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * A JS regular expression literal compiled to `java.util.regex` (shared, immutable). A pattern that is not valid JS
 * syntax has no [pattern] but an [error]: jsonata-js fails when it evaluates the literal (`new RegExp`), not when it
 * compiles the expression, so the error is raised at evaluation.
 */
internal class JsRegexPattern(val source: String, val flags: String, private val compiled: Pattern?, val error: String?) {
    val pattern: Pattern get() = compiled ?: throw JsonataException("X0003", value = StringValue.of(source), details = mapOf("message" to error))

    companion object {
        private val cache = ConcurrentHashMap<String, JsRegexPattern>()

        fun of(regex: JsRegex): JsRegexPattern = cache.getOrPut(regex.flags + "/" + regex.pattern) {
            val multiline = 'm' in regex.flags
            var flags = 0
            if ('i' in regex.flags) flags = flags or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
            if ('s' in regex.flags) flags = flags or Pattern.DOTALL
            try {
                JsRegexTranslator.validate(regex.pattern)
                JsRegexPattern(regex.source, regex.flags, Pattern.compile(JsRegexTranslator.translate(regex.pattern, multiline, 's' in regex.flags), flags), null)
            } catch (e: java.util.regex.PatternSyntaxException) {
                JsRegexPattern(regex.source, regex.flags, null, e.description)
            } catch (e: IllegalArgumentException) {
                JsRegexPattern(regex.source, regex.flags, null, e.message)
            }
        }

        /**
         * JS regexes without the `u` flag match UTF-16 code units (an astral character is two units, `.` takes one),
         * `java.util.regex` matches code points. Surrogate units are mapped one-to-one to private use characters
         * U+E000–U+E7FF both in the pattern and in the subject, so indices are preserved and each unit matches alone.
         */
        fun unit(c: Char): Char = if (c in '\uD800'..'\uDFFF') (c.code - 0xD800 + 0xE000).toChar() else c

        fun subject(s: String): String {
            var i = 0
            while (i < s.length && !Character.isSurrogate(s[i])) i++
            if (i == s.length) return s
            val chars = s.toCharArray()
            for (k in i until chars.size) chars[k] = unit(chars[k])
            return String(chars)
        }
    }
}

/**
 * Translates JS regular expression syntax (ECMA-262 without the `u` flag, with the Annex B extensions) into an
 * equivalent `java.util.regex` pattern. Covered differences: `.` and `\s` follow JS line terminators and white space,
 * `$`/`^` without `m` mean only the ends of the input, `[^]`/`[]`, identity escapes (`\a`, `\e`, `\Q` are plain letters
 * in JS), `\v`, `\0` and legacy octal escapes, a literal `{`/`}`/`]`, a `[` or `&&` inside a class.
 */
internal object JsRegexTranslator {
    private const val WS = "\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"
    private const val LINE_TERMINATORS = "\\n\\r\\u2028\\u2029"

    fun translate(src: String, multiline: Boolean, dotAll: Boolean = false): String {
        val groups = countGroups(src)
        val names = namedGroups(src)
        val sb = StringBuilder(src.length + 16)
        var i = 0
        val n = src.length
        while (i < n) {
            val c = src[i]
            when (c) {
                '\\' -> i = escape(src, i, sb, false, groups, names)
                '[' -> i = charClass(src, i, sb)
                '(' -> {
                    val name = groupNameAt(src, i)
                    if (name != null) {
                        // JS group names (`_`, `$`, any letter) are not all valid in Java: groups are renamed g1, g2…
                        sb.append("(?<").append(names[name]).append('>')
                        i += 4 + name.length
                    } else {
                        sb.append('(')
                        i++
                    }
                }
                '.' -> { sb.append(if (dotAll) "[\\s\\S]" else "[^$LINE_TERMINATORS]"); i++ }
                '$' -> { sb.append(if (multiline) "(?=[$LINE_TERMINATORS]|\\z)" else "\\z"); i++ }
                '^' -> { sb.append(if (multiline) "(?:^|(?<=[$LINE_TERMINATORS]))" else "^"); i++ }
                '{' -> {
                    val m = Regex("\\{\\d+(,\\d*)?}").matchAt(src, i)
                    if (m != null) {
                        sb.append(m.value)
                        i += m.value.length
                    } else {
                        sb.append("\\{")
                        i++
                    }
                }
                '}' -> { sb.append("\\}"); i++ }
                ']' -> { sb.append("\\]"); i++ }
                else -> { sb.append(JsRegexPattern.unit(c)); i++ }
            }
        }
        return sb.toString()
    }

    /**
     * Rejects what `java.util.regex` accepts but JS does not (jsonata-js throws a SyntaxError there): possessive
     * quantifiers (`a++`) and Java group constructs (`(?i)`, `(?>...)`).
     */
    fun validate(src: String) {
        var i = 0
        var inClass = false
        var lastWasQuantifier = false
        while (i < src.length) {
            val c = src[i]
            if (c == '\\') {
                i += 2
                lastWasQuantifier = false
                continue
            }
            if (inClass) {
                if (c == ']') inClass = false
                i++
                continue
            }
            when (c) {
                '[' -> { inClass = true; lastWasQuantifier = false }
                '*', '+', '?' -> {
                    if (lastWasQuantifier && c == '+') throw IllegalArgumentException("Nothing to repeat")
                    // `??`, `*?`, `+?` are lazy quantifiers; a `?` after a quantifier ends it
                    lastWasQuantifier = !(lastWasQuantifier && c == '?')
                }
                '{' -> {
                    val m = Regex("\\{\\d+(,\\d*)?}").matchAt(src, i)
                    if (m != null) {
                        i += m.value.length
                        lastWasQuantifier = true
                        continue
                    }
                    lastWasQuantifier = false
                }
                '(' -> {
                    lastWasQuantifier = false
                    if (src.getOrNull(i + 1) == '?') {
                        val n = src.getOrNull(i + 2)
                        val ok = n == ':' || n == '=' || n == '!' ||
                            (n == '<' && (src.getOrNull(i + 3) == '=' || src.getOrNull(i + 3) == '!' || src.getOrNull(i + 3)?.let { it.isLetter() || it == '_' || it == '$' } == true))
                        if (!ok) throw IllegalArgumentException("Invalid group")
                    }
                }
                else -> lastWasQuantifier = false
            }
            i++
        }
    }

    /** The name of the named group opening at [i] (`(?<name>`), or null. */
    private fun groupNameAt(src: String, i: Int): String? {
        if (!src.startsWith("(?<", i)) return null
        val c = src.getOrNull(i + 3)
        if (c == null || c == '=' || c == '!') return null
        val end = src.indexOf('>', i + 3)
        return if (end < 0) null else src.substring(i + 3, end)
    }

    /** Named groups in order of appearance → internal Java names. */
    private fun namedGroups(src: String): Map<String, String> {
        val names = LinkedHashMap<String, String>()
        var i = 0
        var inClass = false
        while (i < src.length) {
            val c = src[i]
            when {
                c == '\\' -> i++
                inClass -> if (c == ']') inClass = false
                c == '[' -> inClass = true
                c == '(' -> groupNameAt(src, i)?.let { if (it !in names) names[it] = "g" + (names.size + 1) }
            }
            i++
        }
        return names
    }

    private fun countGroups(src: String): Int {
        var count = 0
        var i = 0
        var inClass = false
        while (i < src.length) {
            val c = src[i]
            when {
                c == '\\' -> i++
                inClass -> if (c == ']') inClass = false
                c == '[' -> inClass = true
                c == '(' -> if (i + 1 >= src.length || src[i + 1] != '?' || (src.startsWith("(?<", i) && i + 3 < src.length && src[i + 3] != '=' && src[i + 3] != '!')) count++
            }
            i++
        }
        return count
    }

    /** Translates the escape at [start] (a backslash); returns the index after it. */
    private fun escape(src: String, start: Int, sb: StringBuilder, inClass: Boolean, groups: Int, names: Map<String, String>): Int {
        if (start + 1 >= src.length) {
            sb.append("\\\\")
            return start + 1
        }
        val c = src[start + 1]
        var i = start + 2
        when (c) {
            'd', 'D', 'w', 'W' -> sb.append('\\').append(c)
            's' -> sb.append(if (inClass) WS else "[$WS]")
            'S' -> sb.append("[^$WS]")
            'b' -> sb.append(if (inClass) "\\x08" else "\\b")
            'B' -> sb.append(if (inClass) "B" else "\\B")
            'f' -> sb.append("\\f")
            'n' -> sb.append("\\n")
            'r' -> sb.append("\\r")
            't' -> sb.append("\\t")
            'v' -> sb.append("\\x0B")
            'c' -> {
                val l = src.getOrNull(i)
                if (l != null && (l in 'a'..'z' || l in 'A'..'Z' || (inClass && (l in '0'..'9' || l == '_')))) {
                    sb.append(literal((l.code % 32).toChar()))
                    i++
                } else {
                    sb.append("\\\\c")
                }
            }
            'x' -> {
                val h = src.substring(i, minOf(i + 2, src.length))
                if (h.length == 2 && h.all { it.isHexDigitChar() }) {
                    sb.append("\\x").append(h)
                    i += 2
                } else {
                    sb.append('x')
                }
            }
            'u' -> {
                val h = src.substring(i, minOf(i + 4, src.length))
                if (h.length == 4 && h.all { it.isHexDigitChar() }) {
                    val code = h.toInt(16)
                    if (code in 0xD800..0xDFFF) sb.append(JsRegexPattern.unit(code.toChar())) else sb.append("\\u").append(h)
                    i += 4
                } else {
                    sb.append('u')
                }
            }
            'k' -> {
                if (names.isNotEmpty() && !inClass && src.getOrNull(i) == '<') {
                    val end = src.indexOf('>', i)
                    if (end > 0) {
                        val name = src.substring(i + 1, end)
                        sb.append("\\k<").append(names[name] ?: name).append('>')
                        i = end + 1
                    } else {
                        sb.append('k')
                    }
                } else {
                    sb.append('k')
                }
            }
            in '0'..'9' -> {
                // back reference, or a legacy octal escape (Annex B)
                var j = start + 1
                while (j < src.length && src[j].isDigit()) j++
                val digits = src.substring(start + 1, j)
                if (!inClass && c != '0' && digits.toInt() <= groups) {
                    sb.append('\\').append(digits)
                    i = j
                } else if (c in '0'..'7') {
                    var k = start + 1
                    var value = 0
                    while (k < src.length && k < start + 4 && src[k] in '0'..'7' && value * 8 + (src[k] - '0') <= 255) {
                        value = value * 8 + (src[k] - '0')
                        k++
                    }
                    sb.append(literal(value.toChar()))
                    i = k
                } else {
                    sb.append(c) // \8 and \9 are identity escapes
                }
            }
            else -> sb.append(literal(c))
        }
        return i
    }

    private fun Char.isHexDigitChar(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    /** A character matched literally. */
    private fun literal(c0: Char): String = when (val c = JsRegexPattern.unit(c0)) {
        in '\uE000'..'\uE7FF' -> c.toString()
        else -> if (c.isLetterOrDigit()) c.toString() else literalOther(c)
    }

    private fun literalOther(c: Char): String = when {
        c.code < 0x20 || c.code > 0x7e -> "\\u" + c.code.toString(16).padStart(4, '0')
        else -> "\\" + c
    }

    /** Translates the class starting at [start] (a `[`); returns the index after the closing `]`. */
    private fun charClass(src: String, start: Int, sb: StringBuilder): Int {
        var i = start + 1
        val negated = i < src.length && src[i] == '^'
        if (negated) i++
        if (i < src.length && src[i] == ']') {
            // `[]` matches nothing, `[^]` matches anything
            sb.append(if (negated) "[\\s\\S]" else "(?!)")
            return i + 1
        }
        sb.append('[')
        if (negated) sb.append('^')
        while (i < src.length && src[i] != ']') {
            val c = src[i]
            when (c) {
                '\\' -> i = escape(src, i, sb, true, 0, emptyMap())
                '[', '&', '^', '~' -> { sb.append('\\').append(c); i++ }
                else -> { sb.append(JsRegexPattern.unit(c)); i++ }
            }
        }
        sb.append(']')
        return i + 1
    }
}

/** The matcher function a regex literal evaluates to (jsonata.js: evaluateRegex). */
/** A subject for `java.util.regex` that passes the evaluation checkpoint while the regex engine reads it. */
internal class CheckedChars(private val s: CharSequence, private val ctx: EvalContext) : CharSequence {
    override val length: Int get() = s.length
    override fun get(index: Int): Char {
        ctx.tick()
        return s[index]
    }
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = s.subSequence(startIndex, endIndex)
    override fun toString(): String = s.toString()
}

internal object RegexMatcher {
    fun create(regex: JsRegexPattern, position: Int?): InternalFunction {
        val state = intArrayOf(0) // the RegExp's lastIndex, shared by the match objects it returns
        lateinit var closure: InternalFunction
        // one Matcher over the (surrogate-mapped) subject for all the matches of a string: mapping and matcher
        // creation per match made iterating over a long string quadratic (review RV-5)
        var lastSubject: String? = null
        var matcher: java.util.regex.Matcher? = null
        fun matcherFor(str: String, ctx: EvalContext): java.util.regex.Matcher {
            val cached = matcher
            if (cached != null && lastSubject === str) return cached
            val subject: CharSequence = JsRegexPattern.subject(str)
            // with a deadline, backtracking passes the checkpoint through the subject's charAt (review RV-8)
            val m = regex.pattern.matcher(if (ctx.hasDeadline) CheckedChars(subject, ctx) else subject)
            lastSubject = str
            matcher = m
            return m
        }
        fun exec(str: String, fromIndex: Int, ctx: EvalContext): Value? {
            state[0] = fromIndex
            if (fromIndex > str.length) {
                state[0] = 0
                return null
            }
            val m = matcherFor(str, ctx)
            if (!m.find(fromIndex)) {
                state[0] = 0
                return null
            }
            state[0] = m.end()
            val result = ObjectValue()
            result.put("match", StringValue.of(str.substring(m.start(), m.end())))
            result.put("start", NumberValue.of(m.start()))
            result.put("end", NumberValue.of(m.end()))
            val groups = ArrayValue(m.groupCount())
            for (g in 1..m.groupCount()) groups.addNullable(if (m.start(g) < 0) null else StringValue.of(str.substring(m.start(g), m.end(g))))
            result.put("groups", groups)
            result.put("next", InternalFunction(0) { c, _ ->
                if (state[0] >= str.length) {
                    null
                } else {
                    val next = exec(str, state[0], c.frame.ctx)
                    if (next is ObjectValue && (next["match"] as StringValue).value.isEmpty()) {
                        throw JsonataException("D1004", position, value = StringValue.of(regex.source))
                    }
                    next
                }
            })
            return result
        }
        closure = InternalFunction(2) { c, args ->
            val str = when (val s = args.getOrNull(0)) {
                null -> "undefined"
                is StringValue -> s.value
                else -> Evaluator.string(s)
            }
            val from = (args.getOrNull(1) as? NumberValue)?.value?.toInt() ?: 0
            exec(str, from, c.frame.ctx)
        }
        return closure
    }
}
