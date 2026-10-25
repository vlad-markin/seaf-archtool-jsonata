package su.seaf.archtool.jsonata

import su.seaf.archtool.jsonata.engine.JsRegexPattern
import su.seaf.archtool.jsonata.engine.JsRegexTranslator
import java.util.regex.Pattern

/**
 * JS regular expressions on `java.util.regex`, translated the same way as the regex literals of expressions: for a
 * host that must match like JavaScript, e.g. the JSON Schema `pattern` of SEAF functions (design §2.3, variant A).
 */
public object JsRegexes {
    /**
     * Compiles [pattern] with JS [flags] (`i`, `m`, `s`, `u`; `g` and `y` are accepted and ignored). With `u` the
     * pattern matches code points and has the strict syntax of the flag (as `new RegExp(pattern, "u")`).
     *
     * @throws IllegalArgumentException when JS would throw a SyntaxError for this pattern
     */
    public fun compile(pattern: String, flags: String = ""): JsRegexMatcher {
        for (f in flags) require(f in "gimsuy") { "Invalid flags supplied to RegExp constructor '$flags'" }
        val unicode = 'u' in flags
        var javaFlags = 0
        if ('i' in flags) javaFlags = javaFlags or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        if ('s' in flags) javaFlags = javaFlags or Pattern.DOTALL
        try {
            JsRegexTranslator.validate(pattern)
            val java = JsRegexTranslator.translate(pattern, 'm' in flags, 's' in flags, unicode)
            return JsRegexMatcher(pattern, flags, Pattern.compile(java, javaFlags), unicode)
        } catch (e: java.util.regex.PatternSyntaxException) {
            throw IllegalArgumentException("Invalid regular expression: /$pattern/$flags: ${e.description}", e)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid regular expression: /$pattern/$flags: ${e.message}", e)
        }
    }
}

/** A compiled JS regular expression ([JsRegexes.compile]); immutable and safe to share between threads. */
public class JsRegexMatcher internal constructor(
    public val pattern: String,
    public val flags: String,
    private val compiled: Pattern,
    private val unicode: Boolean,
) {
    /**
     * `RegExp.prototype.test(input)` from index 0. [poll] is called about every thousand characters the regex engine
     * reads, so that a backtracking pattern can be stopped by throwing from it (cancellation, deadline).
     */
    @JvmOverloads
    public fun test(input: String, poll: (() -> Unit)? = null): Boolean {
        // without `u` a JS regex matches UTF-16 units: surrogates are mapped to private use characters (JsRegexPattern)
        val subject: CharSequence = if (unicode) input else JsRegexPattern.subject(input)
        return compiled.matcher(if (poll == null) subject else PolledChars(subject, poll)).find()
    }

    override fun toString(): String = "/$pattern/$flags"
}

private class PolledChars(private val s: CharSequence, private val poll: () -> Unit) : CharSequence {
    private var reads = 0
    override val length: Int get() = s.length
    override fun get(index: Int): Char {
        if (++reads and 1023 == 0) poll()
        return s[index]
    }
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = PolledChars(s.subSequence(startIndex, endIndex), poll)
    override fun toString(): String = s.toString()
}
