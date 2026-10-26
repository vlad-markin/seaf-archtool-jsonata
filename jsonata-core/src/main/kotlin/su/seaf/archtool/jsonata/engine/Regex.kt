package su.seaf.archtool.jsonata.engine

import su.seaf.archtool.jsonata.parser.JsRegex
import su.seaf.archtool.jsonata.parser.JsonataException
import su.seaf.archtool.jsonata.value.ArrayValue
import su.seaf.archtool.jsonata.value.NumberValue
import su.seaf.archtool.jsonata.value.ObjectValue
import su.seaf.archtool.jsonata.value.StringValue
import su.seaf.archtool.jsonata.value.Value
import java.util.regex.Pattern

/**
 * A JS regular expression literal compiled to `java.util.regex` (shared, immutable). A pattern that is not valid JS
 * syntax has no [pattern] but an [error]: jsonata-js fails when it evaluates the literal (`new RegExp`), not when it
 * compiles the expression, so the error is raised at evaluation.
 */
internal class JsRegexPattern(val source: String, val flags: String, private val compiled: Pattern?, val error: String?) {
    val pattern: Pattern get() = compiled ?: throw JsonataException("X0003", value = StringValue.of(source), details = mapOf("message" to error))

    companion object {
        /** Patterns by flags and source, least recently used dropped beyond [CACHE_LIMIT] (sources may come from `$eval`). */
        private val cache = object : LinkedHashMap<String, JsRegexPattern>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JsRegexPattern>?): Boolean = size > CACHE_LIMIT
        }

        const val CACHE_LIMIT: Int = 2048

        /** A [java.util.concurrent.locks.ReentrantLock], not `synchronized`: a virtual thread must not pin here. */
        private val lock = java.util.concurrent.locks.ReentrantLock()

        internal fun cacheSize(): Int = locked { cache.size }

        private inline fun <T> locked(block: () -> T): T {
            lock.lock()
            try {
                return block()
            } finally {
                lock.unlock()
            }
        }

        fun of(regex: JsRegex): JsRegexPattern {
            val key = regex.flags + "/" + regex.pattern
            locked { cache[key] }?.let { return it }
            val compiled = compile(regex)
            locked { cache[key] = compiled }
            return compiled
        }

        private fun compile(regex: JsRegex): JsRegexPattern {
            val multiline = 'm' in regex.flags
            var flags = 0
            if ('i' in regex.flags) flags = flags or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
            if ('s' in regex.flags) flags = flags or Pattern.DOTALL
            return try {
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
 *
 * With [unicode] (the JS `u` flag: JSON Schema `pattern` of manifest functions, as ajv 8 compiles it) the pattern
 * matches code points, `\p{…}` and `\u{…}` are supported and the Annex B leniencies (identity escapes of letters,
 * legacy octal escapes, lone braces) are syntax errors.
 */
internal object JsRegexTranslator {
    private const val WS = "\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"
    private const val LINE_TERMINATORS = "\\n\\r\\u2028\\u2029"

    fun translate(src: String, multiline: Boolean, dotAll: Boolean = false, unicode: Boolean = false): String {
        val groups = countGroups(src)
        val names = namedGroups(src)
        val sb = StringBuilder(src.length + 16)
        var i = 0
        val n = src.length
        while (i < n) {
            val c = src[i]
            when (c) {
                '\\' -> i = escape(src, i, sb, false, groups, names, unicode)
                '[' -> i = charClass(src, i, sb, unicode, names.isNotEmpty())
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
                        if (unicode) throw IllegalArgumentException("Incomplete quantifier")
                        sb.append("\\{")
                        i++
                    }
                }
                '}', ']' -> {
                    if (unicode) throw IllegalArgumentException("Lone quantifier brackets")
                    sb.append('\\').append(c)
                    i++
                }
                else -> { sb.append(if (unicode) c else JsRegexPattern.unit(c)); i++ }
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
    private fun escape(src: String, start: Int, sb: StringBuilder, inClass: Boolean, groups: Int, names: Map<String, String>, unicode: Boolean = false, hasNamedGroups: Boolean = false): Int {
        if (start + 1 >= src.length) {
            if (unicode) throw IllegalArgumentException("\\ at end of pattern")
            sb.append("\\\\")
            return start + 1
        }
        val c = src[start + 1]
        var i = start + 2
        if (unicode) {
            unicodeEscape(src, start, sb, inClass, groups, names)?.let { return it }
        }
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
                // with named groups in the pattern `\k` must be a valid named reference outside a class, and is a
                // syntax error inside one; without them it is the letter k (Annex B; review RV2-14)
                if (names.isNotEmpty() || (inClass && hasNamedGroups)) {
                    if (inClass) throw IllegalArgumentException("Invalid escape")
                    val end = if (src.getOrNull(i) == '<') src.indexOf('>', i) else -1
                    if (end < 0) throw IllegalArgumentException("Invalid named reference")
                    val name = src.substring(i + 1, end)
                    val mapped = names[name] ?: throw IllegalArgumentException("Invalid named capture referenced")
                    namedReference(sb, mapped)
                    i = end + 1
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

    /** JS characters that may be escaped to stand for themselves with the `u` flag (and `-` inside a class). */
    private const val SYNTAX_CHARS = "^$\\.*+?()[]{}|/"

    /**
     * Escapes that differ with the `u` flag; returns the index after the escape, or null for an escape translated as
     * without the flag (`\d`, `\s`, `\n`, `\x41`, `\cJ`, …).
     */
    private fun unicodeEscape(src: String, start: Int, sb: StringBuilder, inClass: Boolean, groups: Int, names: Map<String, String>): Int? {
        val c = src[start + 1]
        val i = start + 2
        when (c) {
            'd', 'D', 'w', 'W', 's', 'S', 'f', 'n', 'r', 't', 'v' -> return null
            'b', 'B' -> if (c == 'b' || !inClass) return null else throw IllegalArgumentException("Invalid class escape")
            'c' -> {
                val l = src.getOrNull(i)
                if (l != null && (l in 'a'..'z' || l in 'A'..'Z')) return null
                throw IllegalArgumentException("Invalid unicode escape")
            }
            'x' -> {
                val h = src.substring(i, minOf(i + 2, src.length))
                if (h.length == 2 && h.all { it.isHexDigitChar() }) return null
                throw IllegalArgumentException("Invalid escape")
            }
            'u' -> {
                if (src.getOrNull(i) == '{') {
                    val end = src.indexOf('}', i)
                    val hex = if (end > 0) src.substring(i + 1, end) else ""
                    val code = if (hex.isNotEmpty() && hex.all { it.isHexDigitChar() }) hex.toIntOrNull(16) else null
                    if (code == null || code > 0x10FFFF) throw IllegalArgumentException("Invalid Unicode escape")
                    sb.append("\\x{").append(code.toString(16)).append('}')
                    return end + 1
                }
                val h = src.substring(i, minOf(i + 4, src.length))
                if (h.length != 4 || !h.all { it.isHexDigitChar() }) throw IllegalArgumentException("Invalid Unicode escape")
                var code = h.toInt(16)
                var next = i + 4
                // a surrogate pair written as two escapes is one code point
                if (code in 0xD800..0xDBFF && src.startsWith("\\u", next)) {
                    val l = src.substring(next + 2, minOf(next + 6, src.length))
                    if (l.length == 4 && l.all { it.isHexDigitChar() } && l.toInt(16) in 0xDC00..0xDFFF) {
                        code = Character.toCodePoint(code.toChar(), l.toInt(16).toChar())
                        next += 6
                    }
                }
                sb.append("\\x{").append(code.toString(16)).append('}')
                return next
            }
            'p', 'P' -> {
                if (src.getOrNull(i) != '{') throw IllegalArgumentException("Invalid property name")
                val end = src.indexOf('}', i)
                if (end < 0) throw IllegalArgumentException("Invalid property name")
                val java = unicodeProperty(src.substring(i + 1, end)) ?: throw IllegalArgumentException("Invalid property name")
                sb.append(if (c == 'P') negate(java, inClass) else java)
                return end + 1
            }
            'k' -> {
                if (inClass || src.getOrNull(i) != '<') throw IllegalArgumentException("Invalid named reference")
                val end = src.indexOf('>', i)
                val name = if (end > 0) src.substring(i + 1, end) else null
                val mapped = name?.let { names[it] } ?: throw IllegalArgumentException("Invalid named capture referenced")
                namedReference(sb, mapped)
                return end + 1
            }
            '0' -> {
                if (src.getOrNull(i)?.isDigit() == true) throw IllegalArgumentException("Invalid decimal escape")
                sb.append("\\x00")
                return i
            }
            in '1'..'9' -> {
                if (inClass) throw IllegalArgumentException("Invalid class escape")
                var j = start + 1
                while (j < src.length && src[j].isDigit()) j++
                val digits = src.substring(start + 1, j)
                if (digits.toInt() > groups) throw IllegalArgumentException("Invalid escape")
                sb.append('\\').append(digits)
                return j
            }
            '-' -> if (inClass) {
                sb.append("\\-")
                return i
            }
        }
        if (c in SYNTAX_CHARS) {
            sb.append('\\').append(c)
            return i
        }
        throw IllegalArgumentException("Invalid escape")
    }

    @Suppress("UNUSED_PARAMETER")
    private fun negate(java: String, inClass: Boolean): String = when {
        java.startsWith("\\p") -> "\\P" + java.substring(2)
        java.startsWith("\\P") -> "\\p" + java.substring(2)
        else -> "[^" + java.substring(1) // inside a class a nested class is a union in java.util.regex
    }

    /** Long names of the General_Category values (ECMA-262 table 70) → the short names `java.util.regex` knows. */
    private val GENERAL_CATEGORIES: Map<String, String> = run {
        val pairs = listOf(
            "Cased_Letter" to "LC", "Close_Punctuation" to "Pe", "Connector_Punctuation" to "Pc", "Control" to "Cc",
            "cntrl" to "Cc", "Currency_Symbol" to "Sc", "Dash_Punctuation" to "Pd", "Decimal_Number" to "Nd", "digit" to "Nd",
            "Enclosing_Mark" to "Me", "Final_Punctuation" to "Pf", "Format" to "Cf", "Initial_Punctuation" to "Pi",
            "Letter" to "L", "Letter_Number" to "Nl", "Line_Separator" to "Zl", "Lowercase_Letter" to "Ll", "Mark" to "M",
            "Combining_Mark" to "M", "Math_Symbol" to "Sm", "Modifier_Letter" to "Lm", "Modifier_Symbol" to "Sk",
            "Nonspacing_Mark" to "Mn", "Number" to "N", "Open_Punctuation" to "Ps", "Other" to "C", "Other_Letter" to "Lo",
            "Other_Number" to "No", "Other_Punctuation" to "Po", "Other_Symbol" to "So", "Paragraph_Separator" to "Zp",
            "Private_Use" to "Co", "Punctuation" to "P", "punct" to "P", "Separator" to "Z", "Space_Separator" to "Zs",
            "Spacing_Mark" to "Mc", "Surrogate" to "Cs", "Symbol" to "S", "Titlecase_Letter" to "Lt", "Unassigned" to "Cn",
            "Uppercase_Letter" to "Lu",
        )
        val m = HashMap<String, String>()
        for ((long, short) in pairs) {
            m[long] = short
            m[short] = short
        }
        for (short in listOf("L", "Lu", "Ll", "Lt", "Lm", "Lo", "M", "Mn", "Mc", "Me", "N", "Nd", "Nl", "No", "P", "Pc", "Pd", "Ps", "Pe", "Pi", "Pf", "Po", "S", "Sm", "Sc", "Sk", "So", "Z", "Zs", "Zl", "Zp", "C", "Cc", "Cf", "Cs", "Co", "Cn", "LC")) m[short] = short
        m
    }

    /** Binary Unicode properties of ECMA-262 (table 69) that `java.util.regex` supports as `\p{IsName}`. */
    private val BINARY_PROPERTIES: Map<String, String> = mapOf(
        "Alphabetic" to "\\p{IsAlphabetic}", "Alpha" to "\\p{IsAlphabetic}",
        "White_Space" to "\\p{IsWhite_Space}", "space" to "\\p{IsWhite_Space}",
        "Uppercase" to "\\p{IsUppercase}", "Upper" to "\\p{IsUppercase}",
        "Lowercase" to "\\p{IsLowercase}", "Lower" to "\\p{IsLowercase}",
        "Ideographic" to "\\p{IsIdeographic}", "Ideo" to "\\p{IsIdeographic}",
        "Hex_Digit" to "\\p{IsHex_Digit}", "Hex" to "\\p{IsHex_Digit}",
        "ASCII_Hex_Digit" to "[0-9A-Fa-f]", "AHex" to "[0-9A-Fa-f]",
        "Noncharacter_Code_Point" to "\\p{IsNoncharacter_Code_Point}", "NChar" to "\\p{IsNoncharacter_Code_Point}",
        "Join_Control" to "\\p{IsJoin_Control}", "Join_C" to "\\p{IsJoin_Control}",
        "Emoji" to "\\p{IsEmoji}", "Emoji_Presentation" to "\\p{IsEmoji_Presentation}", "EPres" to "\\p{IsEmoji_Presentation}",
        "Emoji_Modifier" to "\\p{IsEmoji_Modifier}", "EMod" to "\\p{IsEmoji_Modifier}",
        "Emoji_Modifier_Base" to "\\p{IsEmoji_Modifier_Base}", "EBase" to "\\p{IsEmoji_Modifier_Base}",
        "Emoji_Component" to "\\p{IsEmoji_Component}", "EComp" to "\\p{IsEmoji_Component}",
        "Extended_Pictographic" to "\\p{IsExtended_Pictographic}", "ExtPict" to "\\p{IsExtended_Pictographic}",
        "ASCII" to "[\\x00-\\x7F]", "Any" to "[\\x{0}-\\x{10FFFF}]", "Assigned" to "\\P{Cn}",
    )

    /** `\p{…}` of JS (with the `u` flag) as `java.util.regex` syntax, or null for a name JS does not accept. */
    private fun unicodeProperty(body: String): String? {
        val eq = body.indexOf('=')
        if (eq < 0) {
            GENERAL_CATEGORIES[body]?.let { return "\\p{$it}" }
            return BINARY_PROPERTIES[body]
        }
        val name = body.substring(0, eq)
        val value = body.substring(eq + 1)
        return when (name) {
            "General_Category", "gc" -> GENERAL_CATEGORIES[value]?.let { "\\p{$it}" }
            // Script_Extensions is approximated by Script: java.util.regex has no scx
            "Script", "sc", "Script_Extensions", "scx" -> try {
                Character.UnicodeScript.forName(value)
                "\\p{script=$value}"
            } catch (_: IllegalArgumentException) {
                null
            }
            else -> null
        }
    }

    /**
     * `\k<name>` of the group renamed [mapped]. A reference before the group in the pattern can never see a capture
     * (JS resets the captures of a quantified atom on each iteration), so it matches the empty string, as in JS;
     * java.util.regex rejects such a reference (review RV3-28). The group is opened when its `(?<gN>` is already in
     * [sb]: literal parentheses are always escaped there.
     */
    private fun namedReference(sb: StringBuilder, mapped: String) {
        if (sb.indexOf("(?<$mapped>") >= 0) sb.append("\\k<").append(mapped).append('>') else sb.append("(?:)")
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
    private fun charClass(src: String, start: Int, sb: StringBuilder, unicode: Boolean = false, hasNamedGroups: Boolean = false): Int {
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
        // the last atom could start a range, and whether it is a class escape (`\d`, `\w`, ...)
        var rangeStart = false
        var classEscape = false
        while (i < src.length && src[i] != ']') {
            val c = src[i]
            if (c == '-' && rangeStart && i + 1 < src.length && src[i + 1] != ']') {
                if (classEscape || isClassEscape(src, i + 1, unicode)) {
                    // a range with a class escape as a bound: a SyntaxError with `u`, otherwise the hyphen is a
                    // literal (ECMA-262 Annex B, ClassAtomNoDash); java.util.regex would reject the range (RV3-14)
                    if (unicode) throw IllegalArgumentException("Invalid character class")
                    sb.append("\\-")
                } else {
                    sb.append('-')
                }
                i++
                // the next atom ends this range (or stands alone): it cannot start another one
                rangeStart = false
                classEscape = false
                i = classAtom(src, i, sb, unicode, hasNamedGroups)
                continue
            }
            classEscape = isClassEscape(src, i, unicode)
            rangeStart = true
            i = classAtom(src, i, sb, unicode, hasNamedGroups)
        }
        if (i >= src.length) throw IllegalArgumentException("Unterminated character class")
        sb.append(']')
        return i + 1
    }

    /** Translates one atom of a class at [i]; returns the index after it. */
    private fun classAtom(src: String, i: Int, sb: StringBuilder, unicode: Boolean, hasNamedGroups: Boolean): Int {
        val c = src[i]
        return when (c) {
            '\\' -> escape(src, i, sb, true, 0, emptyMap(), unicode, hasNamedGroups)
            '[', '&', '^', '~' -> { sb.append('\\').append(c); i + 1 }
            else -> { sb.append(if (unicode) c else JsRegexPattern.unit(c)); i + 1 }
        }
    }

    /** Whether a class escape starts at [i]: `\d \D \w \W \s \S`, and `\p{…}`/`\P{…}` with `u`. */
    private fun isClassEscape(src: String, i: Int, unicode: Boolean): Boolean {
        if (i + 1 >= src.length || src[i] != '\\') return false
        val e = src[i + 1]
        return e in "dDwWsS" || (unicode && (e == 'p' || e == 'P'))
    }
}

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

/**
 * The matcher function a regex literal evaluates to (jsonata.js: evaluateRegex): called as `(str, fromIndex)` it
 * returns `{match, start, end, groups, next}`. The library functions iterate it through [forEachMatch] instead, which
 * yields the same matches without building the protocol objects.
 */
internal class RegexFunction(val regex: JsRegexPattern, val position: Int?) : InternalFunction(2, UNUSED_BODY) {
    override val body: (CallContextImpl, List<Value?>) -> Value? = { c, args ->
        val str = when (val s = args.getOrNull(0)) {
            null -> "undefined"
            is StringValue -> s.value
            else -> Evaluator.string(s)
        }
        val from = (args.getOrNull(1) as? NumberValue)?.value?.toInt() ?: 0
        exec(str, from, c.frame.ctx)
    }

    /**
     * `re.lastIndex = fromIndex; re.exec(str)`: the matcher positioned on the match, or null. The RegExp state
     * (lastIndex, the matcher over the subject) is kept per evaluation ([EvalContext.regexState]): a regex value that
     * leaves its evaluation (a result, a binding) can be used by other evaluations, also at the same time, each with
     * its own state and its own deadline and cancellation in the subject's charAt (review RV2-2).
     */
    private fun find(str: String, fromIndex: Int, ctx: EvalContext): java.util.regex.Matcher? {
        val st = ctx.regexState(this)
        st.lastIndex = fromIndex
        if (fromIndex > str.length) {
            st.lastIndex = 0
            return null
        }
        val m = st.matcherFor(regex, str, ctx)
        if (!m.find(fromIndex)) {
            st.lastIndex = 0
            return null
        }
        st.lastIndex = m.end()
        return m
    }

    /** `next()`: the following match, D1004 when it is empty; null at the end. */
    private fun findNext(str: String, ctx: EvalContext): java.util.regex.Matcher? {
        val lastIndex = ctx.regexState(this).lastIndex
        if (lastIndex >= str.length) return null
        val m = find(str, lastIndex, ctx) ?: return null
        if (m.start() == m.end()) throw JsonataException("D1004", position, value = StringValue.of(regex.source))
        return m
    }

    fun exec(str: String, fromIndex: Int, ctx: EvalContext): Value? {
        val m = find(str, fromIndex, ctx) ?: return null
        return protocolObject(str, m)
    }

    private fun protocolObject(str: String, m: java.util.regex.Matcher): ObjectValue {
        val result = ObjectValue()
        result.put("match", StringValue.of(str.substring(m.start(), m.end())))
        result.put("start", NumberValue.of(m.start()))
        result.put("end", NumberValue.of(m.end()))
        result.put("groups", groups(str, m))
        result.put("next", InternalFunction(0) { c, _ -> findNext(str, c.frame.ctx)?.let { protocolObject(str, it) } })
        return result
    }

    fun groups(str: String, m: java.util.regex.Matcher): ArrayValue {
        val groups = ArrayValue(m.groupCount())
        for (g in 1..m.groupCount()) groups.addNullable(if (m.start(g) < 0) null else StringValue.of(str.substring(m.start(g), m.end(g))))
        return groups
    }

    /**
     * The matches `evaluateMatcher(regex, str)` and then `matches.next()` would give, while `count < limit`
     * (functions.js: match, replace, split). As in the reference, the next match is looked for (and may raise D1004)
     * before the limit is checked. [visit] reads the matcher before the next search moves it.
     */
    inline fun forEachMatch(str: String, ctx: EvalContext, limit: Double?, visit: (java.util.regex.Matcher) -> Unit) {
        val first = firstMatch(str, ctx) ?: return
        forEachMatchFrom(first, str, ctx, limit, visit)
    }

    /** [forEachMatch] continuing from the [first] match; returns the number of matches visited. */
    inline fun forEachMatchFrom(
        first: java.util.regex.Matcher,
        str: String,
        ctx: EvalContext,
        limit: Double?,
        visit: (java.util.regex.Matcher) -> Unit,
    ): Int {
        var m: java.util.regex.Matcher? = first
        var count = 0
        while (m != null && (limit == null || count < limit)) {
            visit(m)
            m = nextMatch(str, ctx)
            count++
            ctx.tick()
        }
        return count
    }

    fun firstMatch(str: String, ctx: EvalContext): java.util.regex.Matcher? = find(str, 0, ctx)

    fun nextMatch(str: String, ctx: EvalContext): java.util.regex.Matcher? = findNext(str, ctx)

    private companion object {
        val UNUSED_BODY: (CallContextImpl, List<Value?>) -> Value? = { _, _ -> null }
    }
}

/**
 * The state a JS RegExp object carries between calls, for one regex value in one evaluation: lastIndex (shared by the
 * match objects of that evaluation, as in jsonata-js) and one Matcher over the current subject, so that iterating
 * over a long string does not rebuild the mapped subject per match (review RV-5).
 */
internal class RegexState {
    @JvmField var lastIndex: Int = 0
    private var lastSubject: String? = null
    private var matcher: java.util.regex.Matcher? = null

    fun matcherFor(regex: JsRegexPattern, str: String, ctx: EvalContext): java.util.regex.Matcher {
        val cached = matcher
        if (cached != null && lastSubject === str) return cached
        // the checkpoint of this evaluation passes through the subject's charAt, so that backtracking stops on
        // cancellation and the deadline (review RV-8, design §2.4)
        val subject = JsRegexPattern.subject(str)
        // a private use character U+E000-U+E7FF of the subject also matches the lone surrogate it stands for (DEV-05)
        if (ctx.options.deviations != null && str.any { it in '\uE000'..'\uE7FF' }) {
            ctx.deviation(su.seaf.archtool.jsonata.deviation.Deviations.REGEX_PRIVATE_USE.id, null)
        }
        val m = regex.pattern.matcher(CheckedChars(subject, ctx))
        lastSubject = str
        matcher = m
        return m
    }
}

internal object RegexMatcher {
    fun create(regex: JsRegexPattern, position: Int?): RegexFunction = RegexFunction(regex, position)
}
