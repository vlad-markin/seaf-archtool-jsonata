package su.seaf.archtool.jsonata.seaf.schema

import su.seaf.archtool.jsonata.JsRegexMatcher
import su.seaf.archtool.jsonata.JsRegexes

/**
 * The formats of ajv-formats 2.1.1 in its default (full) mode, which $jsonschema of the SEAF core adds to ajv. The
 * regular expressions are those of ajv-formats (MIT, `compat/node/node_modules/ajv-formats/dist/formats.js`), run by
 * the engine's JS regex translator, so a value is valid exactly when it is for ajv.
 */
internal object AjvFormats {
    private fun rx(source: String, flags: String): JsRegexMatcher = JsRegexes.compile(source, flags)

    /** Formats checked by one regular expression. */
    private val REGEX: Map<String, JsRegexMatcher> = mapOf(
        "duration" to rx("^P(?!${"$"})((\\d+Y)?(\\d+M)?(\\d+D)?(T(?=\\d)(\\d+H)?(\\d+M)?(\\d+S)?)?|(\\d+W)?)${"$"}", ""),
        "uri-reference" to rx("^(?:[a-z][a-z0-9+\\-.]*:)?(?:\\/?\\/(?:(?:[a-z0-9\\-._~!${"$"}&'()*+,;=:]|%[0-9a-f]{2})*@)?(?:\\[(?:(?:(?:(?:[0-9a-f]{1,4}:){6}|::(?:[0-9a-f]{1,4}:){5}|(?:[0-9a-f]{1,4})?::(?:[0-9a-f]{1,4}:){4}|(?:(?:[0-9a-f]{1,4}:){0,1}[0-9a-f]{1,4})?::(?:[0-9a-f]{1,4}:){3}|(?:(?:[0-9a-f]{1,4}:){0,2}[0-9a-f]{1,4})?::(?:[0-9a-f]{1,4}:){2}|(?:(?:[0-9a-f]{1,4}:){0,3}[0-9a-f]{1,4})?::[0-9a-f]{1,4}:|(?:(?:[0-9a-f]{1,4}:){0,4}[0-9a-f]{1,4})?::)(?:[0-9a-f]{1,4}:[0-9a-f]{1,4}|(?:(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?))|(?:(?:[0-9a-f]{1,4}:){0,5}[0-9a-f]{1,4})?::[0-9a-f]{1,4}|(?:(?:[0-9a-f]{1,4}:){0,6}[0-9a-f]{1,4})?::)|[Vv][0-9a-f]+\\.[a-z0-9\\-._~!${"$"}&'()*+,;=:]+)\\]|(?:(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)|(?:[a-z0-9\\-._~!${"$"}&'\"()*+,;=]|%[0-9a-f]{2})*)(?::\\d*)?(?:\\/(?:[a-z0-9\\-._~!${"$"}&'\"()*+,;=:@]|%[0-9a-f]{2})*)*|\\/(?:(?:[a-z0-9\\-._~!${"$"}&'\"()*+,;=:@]|%[0-9a-f]{2})+(?:\\/(?:[a-z0-9\\-._~!${"$"}&'\"()*+,;=:@]|%[0-9a-f]{2})*)*)?|(?:[a-z0-9\\-._~!${"$"}&'\"()*+,;=:@]|%[0-9a-f]{2})+(?:\\/(?:[a-z0-9\\-._~!${"$"}&'\"()*+,;=:@]|%[0-9a-f]{2})*)*)?(?:\\?(?:[a-z0-9\\-._~!${"$"}&'\"()*+,;=:@/?]|%[0-9a-f]{2})*)?(?:#(?:[a-z0-9\\-._~!${"$"}&'\"()*+,;=:@/?]|%[0-9a-f]{2})*)?${"$"}", "i"),
        "uri-template" to rx("^(?:(?:[^\\x00-\\x20\"'<>%\\\\^`{|}]|%[0-9a-f]{2})|\\{[+#./;?&=,!@|]?(?:[a-z0-9_]|%[0-9a-f]{2})+(?::[1-9][0-9]{0,3}|\\*)?(?:,(?:[a-z0-9_]|%[0-9a-f]{2})+(?::[1-9][0-9]{0,3}|\\*)?)*\\})*${"$"}", "i"),
        "url" to rx("^(?:https?|ftp):\\/\\/(?:\\S+(?::\\S*)?@)?(?:(?!(?:10|127)(?:\\.\\d{1,3}){3})(?!(?:169\\.254|192\\.168)(?:\\.\\d{1,3}){2})(?!172\\.(?:1[6-9]|2\\d|3[0-1])(?:\\.\\d{1,3}){2})(?:[1-9]\\d?|1\\d\\d|2[01]\\d|22[0-3])(?:\\.(?:1?\\d{1,2}|2[0-4]\\d|25[0-5])){2}(?:\\.(?:[1-9]\\d?|1\\d\\d|2[0-4]\\d|25[0-4]))|(?:(?:[a-z0-9\\u{00a1}-\\u{ffff}]+-)*[a-z0-9\\u{00a1}-\\u{ffff}]+)(?:\\.(?:[a-z0-9\\u{00a1}-\\u{ffff}]+-)*[a-z0-9\\u{00a1}-\\u{ffff}]+)*(?:\\.(?:[a-z\\u{00a1}-\\u{ffff}]{2,})))(?::\\d{2,5})?(?:\\/[^\\s]*)?${"$"}", "iu"),
        "email" to rx("^[a-z0-9!#${"$"}%&'*+/=?^_`{|}~-]+(?:\\.[a-z0-9!#${"$"}%&'*+/=?^_`{|}~-]+)*@(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z0-9](?:[a-z0-9-]*[a-z0-9])?${"$"}", "i"),
        "hostname" to rx("^(?=.{1,253}\\.?${"$"})[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[-0-9a-z]{0,61}[0-9a-z])?)*\\.?${"$"}", "i"),
        "ipv4" to rx("^(?:(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)${"$"}", ""),
        "ipv6" to rx("^((([0-9a-f]{1,4}:){7}([0-9a-f]{1,4}|:))|(([0-9a-f]{1,4}:){6}(:[0-9a-f]{1,4}|((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3})|:))|(([0-9a-f]{1,4}:){5}(((:[0-9a-f]{1,4}){1,2})|:((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3})|:))|(([0-9a-f]{1,4}:){4}(((:[0-9a-f]{1,4}){1,3})|((:[0-9a-f]{1,4})?:((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}))|:))|(([0-9a-f]{1,4}:){3}(((:[0-9a-f]{1,4}){1,4})|((:[0-9a-f]{1,4}){0,2}:((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}))|:))|(([0-9a-f]{1,4}:){2}(((:[0-9a-f]{1,4}){1,5})|((:[0-9a-f]{1,4}){0,3}:((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}))|:))|(([0-9a-f]{1,4}:){1}(((:[0-9a-f]{1,4}){1,6})|((:[0-9a-f]{1,4}){0,4}:((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}))|:))|(:(((:[0-9a-f]{1,4}){1,7})|((:[0-9a-f]{1,4}){0,5}:((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}))|:)))${"$"}", "i"),
        "uuid" to rx("^(?:urn:uuid:)?[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}${"$"}", "i"),
        "json-pointer" to rx("^(?:\\/(?:[^~/]|~0|~1)*)*${"$"}", ""),
        "json-pointer-uri-fragment" to rx("^#(?:\\/(?:[a-z0-9_\\-.!${"$"}&'()*+,;:=@]|%[0-9a-f]{2}|~0|~1)*)*${"$"}", "i"),
        "relative-json-pointer" to rx("^(?:0|[1-9][0-9]*)(?:#|(?:\\/(?:[^~/]|~0|~1)*)*)${"$"}", ""),
    )

    // DATE and TIME are ASCII-only, so java.util.regex reads them like JS and gives the groups
    private val DATE = Regex("^(\\d\\d\\d\\d)-(\\d\\d)-(\\d\\d)$")
    private val TIME = Regex("^(\\d\\d):(\\d\\d):(\\d\\d)(\\.\\d+)?([zZ]|[+-]\\d\\d(?::?\\d\\d)?)?$")
    private val URI = rx("^(?:[a-z][a-z0-9+\\-.]*:)(?:\\/?\\/(?:(?:[a-z0-9\\-._~!${"$"}&'()*+,;=:]|%[0-9a-f]{2})*@)?(?:\\[(?:(?:(?:(?:[0-9a-f]{1,4}:){6}|::(?:[0-9a-f]{1,4}:){5}|(?:[0-9a-f]{1,4})?::(?:[0-9a-f]{1,4}:){4}|(?:(?:[0-9a-f]{1,4}:){0,1}[0-9a-f]{1,4})?::(?:[0-9a-f]{1,4}:){3}|(?:(?:[0-9a-f]{1,4}:){0,2}[0-9a-f]{1,4})?::(?:[0-9a-f]{1,4}:){2}|(?:(?:[0-9a-f]{1,4}:){0,3}[0-9a-f]{1,4})?::[0-9a-f]{1,4}:|(?:(?:[0-9a-f]{1,4}:){0,4}[0-9a-f]{1,4})?::)(?:[0-9a-f]{1,4}:[0-9a-f]{1,4}|(?:(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?))|(?:(?:[0-9a-f]{1,4}:){0,5}[0-9a-f]{1,4})?::[0-9a-f]{1,4}|(?:(?:[0-9a-f]{1,4}:){0,6}[0-9a-f]{1,4})?::)|[Vv][0-9a-f]+\\.[a-z0-9\\-._~!${"$"}&'()*+,;=:]+)\\]|(?:(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)|(?:[a-z0-9\\-._~!${"$"}&'()*+,;=]|%[0-9a-f]{2})*)(?::\\d*)?(?:\\/(?:[a-z0-9\\-._~!${"$"}&'()*+,;=:@]|%[0-9a-f]{2})*)*|\\/(?:(?:[a-z0-9\\-._~!${"$"}&'()*+,;=:@]|%[0-9a-f]{2})+(?:\\/(?:[a-z0-9\\-._~!${"$"}&'()*+,;=:@]|%[0-9a-f]{2})*)*)?|(?:[a-z0-9\\-._~!${"$"}&'()*+,;=:@]|%[0-9a-f]{2})+(?:\\/(?:[a-z0-9\\-._~!${"$"}&'()*+,;=:@]|%[0-9a-f]{2})*)*)(?:\\?(?:[a-z0-9\\-._~!${"$"}&'()*+,;=:@/?]|%[0-9a-f]{2})*)?(?:#(?:[a-z0-9\\-._~!${"$"}&'()*+,;=:@/?]|%[0-9a-f]{2})*)?${"$"}", "i")
    private val BYTE = rx("^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?${"$"}", "gm")
    private val Z_ANCHOR = rx("[^\\\\]\\\\Z", "")
    private val NOT_URI_FRAGMENT = rx("\\/|:", "")
    /** `/t|\\s/i` of ajv-formats: `t`, `T` or JS white space. */
    private val DATE_TIME_SEPARATOR = Regex("[tT\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]")

    /** Names ajv knows after `addFormats(ajv)`. */
    val NAMES: Set<String> = REGEX.keys + setOf("date", "time", "date-time", "uri", "regex", "byte", "int32", "int64", "float", "double", "password", "binary")

    /** Formats with a `compare` function (formatMinimum and the other limit keywords). */
    val COMPARABLE: Set<String> = setOf("date", "time", "date-time")

    /** Formats of ajv-formats that apply to numbers (`type: "number"`); the others apply to strings. */
    private val NUMBER_FORMATS = setOf("int32", "int64", "float", "double")

    fun appliesToNumbers(format: String): Boolean = format in NUMBER_FORMATS

    /** Whether a string is valid for [format] (a name of [NAMES] that applies to strings). */
    fun matches(format: String, s: String, poll: (() -> Unit)?): Boolean {
        REGEX[format]?.let { return it.test(s, poll) }
        return when (format) {
            "date" -> date(s)
            "time" -> time(s, false)
            "date-time" -> {
                val parts = s.split(DATE_TIME_SEPARATOR)
                parts.size == 2 && date(parts[0]) && time(parts[1], true)
            }
            "uri" -> NOT_URI_FRAGMENT.test(s) && URI.test(s, poll)
            "byte" -> BYTE.test(s, poll)
            "regex" -> !Z_ANCHOR.test(s) && try {
                JsRegexes.compile(s)
                true
            } catch (_: IllegalArgumentException) {
                false
            }
            else -> true // password, binary
        }
    }

    /** Whether a number is valid for [format] (int32, int64, float, double). */
    fun matches(format: String, d: Double): Boolean = when (format) {
        "int32" -> isInteger(d) && d <= 2147483647.0 && d >= -2147483648.0
        "int64" -> isInteger(d)
        else -> true
    }

    private fun isInteger(d: Double) = !d.isInfinite() && !d.isNaN() && d == Math.floor(d)

    private fun isLeapYear(year: Int) = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)

    private val DAYS = intArrayOf(0, 31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)

    private fun date(s: String): Boolean {
        val m = DATE.matchEntire(s) ?: return false
        val year = m.groupValues[1].toInt()
        val month = m.groupValues[2].toInt()
        val day = m.groupValues[3].toInt()
        return month in 1..12 && day >= 1 && day <= (if (month == 2 && isLeapYear(year)) 29 else DAYS[month])
    }

    @Suppress("UNUSED_PARAMETER")
    private fun time(s: String, withTimeZone: Boolean): Boolean {
        val m = TIME.matchEntire(s) ?: return false
        val hour = m.groupValues[1].toInt()
        val minute = m.groupValues[2].toInt()
        val second = m.groupValues[3].toInt()
        // ajv-formats 2.1.1 checks `timeZone !== ""` on an undefined group: the time zone is never required
        return (hour <= 23 && minute <= 59 && second <= 59) || (hour == 23 && minute == 59 && second == 60)
    }

    /** `compare` of ajv-formats: negative, 0, positive, or null when undefined. */
    fun compare(format: String, a: String, b: String): Int? = when (format) {
        "date" -> compareDate(a, b)
        "time" -> compareTime(a, b)
        "date-time" -> {
            val (d1, t1) = a.split(DATE_TIME_SEPARATOR).let { it.getOrNull(0) to it.getOrNull(1) }
            val (d2, t2) = b.split(DATE_TIME_SEPARATOR).let { it.getOrNull(0) to it.getOrNull(1) }
            when (val r = compareDate(d1, d2)) {
                null -> null
                0 -> compareTime(t1, t2)
                else -> r
            }
        }
        else -> null
    }

    private fun compareDate(a: String?, b: String?): Int? {
        if (a.isNullOrEmpty() || b.isNullOrEmpty()) return null
        return a.compareTo(b).coerceIn(-1, 1)
    }

    private fun compareTime(a: String?, b: String?): Int? {
        if (a.isNullOrEmpty() || b.isNullOrEmpty()) return null
        val m1 = TIME.matchEntire(a) ?: return null
        val m2 = TIME.matchEntire(b) ?: return null
        val t1 = m1.groupValues[1] + m1.groupValues[2] + m1.groupValues[3] + m1.groupValues[4]
        val t2 = m2.groupValues[1] + m2.groupValues[2] + m2.groupValues[3] + m2.groupValues[4]
        return t1.compareTo(t2).coerceIn(-1, 1)
    }
}
