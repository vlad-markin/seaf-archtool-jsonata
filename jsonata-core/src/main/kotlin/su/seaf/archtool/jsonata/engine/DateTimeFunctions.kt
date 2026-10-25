package su.seaf.archtool.jsonata.engine

import su.seaf.archtool.jsonata.CallContext
import su.seaf.archtool.jsonata.FunctionRegistry
import su.seaf.archtool.jsonata.parser.JsonataException
import su.seaf.archtool.jsonata.value.JsNumber
import su.seaf.archtool.jsonata.value.NumberValue
import su.seaf.archtool.jsonata.value.StringValue
import su.seaf.archtool.jsonata.value.Value
import java.time.LocalDate
import java.util.regex.Pattern

/** datetime.js: `$now`, `$fromMillis`, `$toMillis`, and `$formatInteger`/`$parseInteger`. */
internal object DateTimeFunctions {
    fun register(r: FunctionRegistry) {
        StandardLibrary.def(r, "formatInteger", "<n-s:s>", 2) { _, a -> formatInteger(a[0], a[1]) }
        StandardLibrary.def(r, "parseInteger", "<s-s:n>", 2) { _, a -> parseInteger(a[0], a[1]) }
        StandardLibrary.def(r, "fromMillis", "<n-s?s?:s>", 3) { _, a -> fromMillis(a[0], a.getOrNull(1), a.getOrNull(2)) }
        StandardLibrary.def(r, "toMillis", "<s-s?:n>", 2) { c, a -> toMillis(c, a[0], a.getOrNull(1)) }
        StandardLibrary.def(r, "now", "<s?s?:s>", 2) { c, a -> StringValue.of(formatDateTime(c.timestamp.toDouble(), str(a.getOrNull(0)), str(a.getOrNull(1)))) }
    }

    private fun str(v: Value?): String? = (v as? StringValue)?.value

    // ---- JS Date (UTC only) ----

    private const val MS_PER_DAY = 86_400_000.0

    /** ToIntegerOrInfinity. */
    private fun toInt(x: Double): Double = if (x.isNaN()) Double.NaN else if (x.isInfinite()) x else if (x < 0) Math.ceil(x) else Math.floor(x)

    /** TimeClip. */
    private fun timeClip(t: Double): Double = if (t.isNaN() || Math.abs(t) > 8.64e15) Double.NaN else toInt(t) + 0.0

    private fun jsMod(a: Double, b: Double): Double {
        val m = a % b
        return if (m < 0) m + b else m
    }

    /** JS `Date.UTC(year, month, day, h, m, s, ms)`, including two-digit years mapped to 19xx. */
    fun dateUtc(year: Double, month: Double, day: Double = 1.0, h: Double = 0.0, mi: Double = 0.0, s: Double = 0.0, ms: Double = 0.0): Double {
        var y = toInt(year)
        if (!y.isNaN() && y >= 0 && y <= 99) y += 1900
        val d = makeDay(y, toInt(month), toInt(day))
        val t = toInt(h) * 3_600_000 + toInt(mi) * 60_000 + toInt(s) * 1000 + toInt(ms)
        return timeClip(d * MS_PER_DAY + t)
    }

    private fun makeDay(year: Double, month: Double, date: Double): Double {
        if (!(year.isFinite() && month.isFinite() && date.isFinite())) return Double.NaN
        val ym = year + Math.floor(month / 12)
        if (Math.abs(ym) > 400_000) return Double.NaN
        val mn = jsMod(month, 12.0).toInt()
        val first = LocalDate.of(ym.toInt(), mn + 1, 1).toEpochDay().toDouble()
        return first + date - 1
    }

    /** A JS Date's UTC getters for a time value (NaN for an invalid date). */
    private class JsDate(val time: Double) {
        private val valid = !time.isNaN()
        private val date: LocalDate? = if (valid) LocalDate.ofEpochDay(Math.floor(time / MS_PER_DAY).toLong()) else null
        private val inDay = if (valid) jsMod(time, MS_PER_DAY) else Double.NaN

        val fullYear: Double get() = date?.year?.toDouble() ?: Double.NaN
        val month: Double get() = date?.let { (it.monthValue - 1).toDouble() } ?: Double.NaN
        val dayOfMonth: Double get() = date?.dayOfMonth?.toDouble() ?: Double.NaN
        val weekday: Double get() = if (valid) jsMod(Math.floor(time / MS_PER_DAY) + 4, 7.0) else Double.NaN
        val hours: Double get() = Math.floor(inDay / 3_600_000)
        val minutes: Double get() = Math.floor(jsMod(inDay, 3_600_000.0) / 60_000)
        val seconds: Double get() = Math.floor(jsMod(inDay, 60_000.0) / 1000)
        val millis: Double get() = jsMod(inDay, 1000.0)
    }

    // ---- integer pictures (datetime.js: analyseIntegerPicture, _formatInteger) ----

    private enum class Primary { DECIMAL, LETTERS, ROMAN, WORDS, SEQUENCE }
    private enum class Case { UPPER, LOWER, TITLE }

    private class Separator(val position: Int, val character: String)

    private class IntegerFormat {
        var primary = Primary.DECIMAL
        var case = Case.LOWER
        var ordinal = false
        var zeroCode = 0x30
        var mandatoryDigits = 0.0
        var optionalDigits = 0
        var regular = false
        var regularSeparator: Separator? = null
        var groupingSeparators: List<Separator> = emptyList()
        var token: String? = null
        var parseWidth: Double? = null
    }

    private val few = listOf("Zero", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten",
        "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen", "Eighteen", "Nineteen")
    private val ordinals = listOf("Zeroth", "First", "Second", "Third", "Fourth", "Fifth", "Sixth", "Seventh", "Eighth", "Ninth", "Tenth",
        "Eleventh", "Twelfth", "Thirteenth", "Fourteenth", "Fifteenth", "Sixteenth", "Seventeenth", "Eighteenth", "Nineteenth")
    private val decades = listOf("Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety", "Hundred")
    private val magnitudes = listOf("Thousand", "Million", "Billion", "Trillion")

    /** wordValues in its JS key order (the WORDS parsing regex lists them in this order). */
    private val wordValues: LinkedHashMap<String, Double> = LinkedHashMap<String, Double>().also { w ->
        few.forEachIndexed { i, word -> w[word.lowercase()] = i.toDouble() }
        ordinals.forEachIndexed { i, word -> w[word.lowercase()] = i.toDouble() }
        decades.forEachIndexed { i, word ->
            val l = word.lowercase()
            w[l] = (i + 2) * 10.0
            w[l.substring(0, word.length - 1) + "ieth"] = w[l]!!
        }
        w["hundredth"] = 100.0
        magnitudes.forEachIndexed { i, word ->
            val l = word.lowercase()
            val v = Math.pow(10.0, (i + 1) * 3.0)
            w[l] = v
            w[l + "th"] = v
        }
    }

    private fun numberToWords(value: Double, ordinal: Boolean): String {
        fun lookup(num: Double, prev: Boolean, ord: Boolean): String {
            var words: String
            if (num <= 19) {
                words = (if (prev) " and " else "") + (if (ord) ordinals[num.toInt()] else few[num.toInt()])
            } else if (num < 100) {
                val tens = Math.floor(num / 10).toInt()
                val remainder = num % 10
                words = (if (prev) " and " else "") + decades[tens - 2]
                if (remainder > 0) {
                    words += "-" + lookup(remainder, false, ord)
                } else if (ord) {
                    words = words.substring(0, words.length - 1) + "ieth"
                }
            } else if (num < 1000) {
                val hundreds = Math.floor(num / 100).toInt()
                val remainder = num % 100
                words = (if (prev) ", " else "") + few[hundreds] + " Hundred"
                if (remainder > 0) {
                    words += lookup(remainder, true, ord)
                } else if (ord) {
                    words += "th"
                }
            } else {
                var mag = Math.floor(Math.log10(num) / 3).toInt()
                if (mag > magnitudes.size) mag = magnitudes.size
                val factor = Math.pow(10.0, mag * 3.0)
                val mant = Math.floor(num / factor)
                val remainder = num - mant * factor
                words = (if (prev) ", " else "") + lookup(mant, false, false) + " " + magnitudes[mag - 1]
                if (remainder > 0) {
                    words += lookup(remainder, true, ord)
                } else if (ord) {
                    words += "th"
                }
            }
            return words
        }
        return lookup(value, false, ordinal)
    }

    private val wordSplit = Regex(",\\s|\\sand\\s|[\\s\\\\-]")

    private fun wordsToNumber(text: String): Double {
        val values = text.split(wordSplit).map { wordValues[it] ?: Double.NaN }
        val segs = ArrayList<Double>()
        segs += 0.0
        for (value in values) {
            if (value < 100) {
                var top = segs.removeAt(segs.size - 1)
                if (top >= 1000) {
                    segs += top
                    top = 0.0
                }
                segs += top + value
            } else {
                segs += segs.removeAt(segs.size - 1) * value
            }
        }
        return segs.sum()
    }

    private val romanNumerals = listOf(1000 to "m", 900 to "cm", 500 to "d", 400 to "cd", 100 to "c", 90 to "xc",
        50 to "l", 40 to "xl", 10 to "x", 9 to "ix", 5 to "v", 4 to "iv", 1 to "i")
    private val romanValues = mapOf('M' to 1000.0, 'D' to 500.0, 'C' to 100.0, 'L' to 50.0, 'X' to 10.0, 'V' to 5.0, 'I' to 1.0)

    private fun decimalToRoman(value: Double): String {
        val sb = StringBuilder()
        var v = value
        outer@ while (true) {
            for ((n, s) in romanNumerals) {
                if (v >= n) {
                    sb.append(s)
                    v -= n
                    continue@outer
                }
            }
            return sb.toString()
        }
    }

    private fun romanToDecimal(roman: String): Double {
        var decimal = 0.0
        var max = 1.0
        for (i in roman.length - 1 downTo 0) {
            val value = romanValues[roman[i]] ?: Double.NaN
            if (value < max) {
                decimal -= value
            } else {
                max = value
                decimal += value
            }
        }
        return decimal
    }

    private fun decimalToLetters(value: Double, aChar: Char): String {
        val letters = StringBuilder()
        var v = value
        while (v > 0) {
            letters.insert(0, ((v - 1) % 26 + aChar.code).toInt().toChar())
            v = Math.floor((v - 1) / 26)
        }
        return letters.toString()
    }

    private fun lettersToDecimal(letters: String, aChar: Char): Double {
        var decimal = 0.0
        for (i in letters.indices) decimal += (letters[letters.length - i - 1].code - aChar.code + 1) * Math.pow(26.0, i.toDouble())
        return decimal
    }

    private val decimalGroups = intArrayOf(0x30, 0x0660, 0x06F0, 0x07C0, 0x0966, 0x09E6, 0x0A66, 0x0AE6, 0x0B66, 0x0BE6, 0x0C66,
        0x0CE6, 0x0D66, 0x0DE6, 0x0E50, 0x0ED0, 0x0F20, 0x1040, 0x1090, 0x17E0, 0x1810, 0x1946, 0x19D0, 0x1A80, 0x1A90, 0x1B50,
        0x1BB0, 0x1C40, 0x1C50, 0xA620, 0xA8D0, 0xA900, 0xA9D0, 0xA9F0, 0xAA50, 0xABF0, 0xFF10)

    private fun analyseIntegerPicture(picture: String): IntegerFormat {
        val format = IntegerFormat()
        val semicolon = picture.lastIndexOf(';')
        val primaryFormat: String
        if (semicolon == -1) {
            primaryFormat = picture
        } else {
            primaryFormat = picture.substring(0, semicolon)
            val modifier = picture.substring(semicolon + 1)
            if (modifier.startsWith("o")) format.ordinal = true
        }
        when (primaryFormat) {
            "A" -> { format.case = Case.UPPER; format.primary = Primary.LETTERS }
            "a" -> format.primary = Primary.LETTERS
            "I" -> { format.case = Case.UPPER; format.primary = Primary.ROMAN }
            "i" -> format.primary = Primary.ROMAN
            "W" -> { format.case = Case.UPPER; format.primary = Primary.WORDS }
            "Ww" -> { format.case = Case.TITLE; format.primary = Primary.WORDS }
            "w" -> format.primary = Primary.WORDS
            else -> {
                var zeroCode: Int? = null
                var mandatory = 0
                var optional = 0
                val separators = ArrayList<Separator>()
                var separatorPosition = 0
                val cps = primaryFormat.codePoints().toArray().reversedArray()
                for (cp in cps) {
                    var digit = false
                    for (group in decimalGroups) {
                        if (cp >= group && cp <= group + 9) {
                            digit = true
                            mandatory++
                            separatorPosition++
                            if (zeroCode == null) zeroCode = group
                            else if (group != zeroCode) throw JsonataException("D3131")
                            break
                        }
                    }
                    if (!digit) {
                        if (cp == 0x23) {
                            separatorPosition++
                            optional++
                        } else {
                            separators += Separator(separatorPosition, String(Character.toChars(cp)))
                        }
                    }
                }
                if (mandatory > 0) {
                    format.primary = Primary.DECIMAL
                    format.zeroCode = zeroCode!!
                    format.mandatoryDigits = mandatory.toDouble()
                    format.optionalDigits = optional
                    val regular = regularRepeat(separators)
                    if (regular > 0) {
                        format.regular = true
                        format.regularSeparator = Separator(regular, separators[0].character)
                    } else {
                        format.regular = false
                        format.groupingSeparators = separators
                    }
                } else {
                    format.primary = Primary.SEQUENCE
                    format.token = primaryFormat
                }
            }
        }
        return format
    }

    private fun regularRepeat(separators: List<Separator>): Int {
        if (separators.isEmpty()) return 0
        val sepChar = separators[0].character
        if (separators.any { it.character != sepChar }) return 0
        val indexes = separators.map { it.position }
        fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
        val factor = indexes.reduce { a, b -> gcd(a, b) }
        for (index in 1..indexes.size) if (index * factor !in indexes) return 0
        return factor
    }

    private fun formatIntegerWith(value0: Double, format: IntegerFormat): String {
        var formatted: String
        val negative = value0 < 0
        val value = Math.abs(value0)
        when (format.primary) {
            Primary.LETTERS -> formatted = decimalToLetters(value, if (format.case == Case.UPPER) 'A' else 'a')
            Primary.ROMAN -> {
                formatted = decimalToRoman(value)
                if (format.case == Case.UPPER) formatted = formatted.uppercase()
            }
            Primary.WORDS -> {
                formatted = numberToWords(value, format.ordinal)
                if (format.case == Case.UPPER) formatted = formatted.uppercase()
                else if (format.case == Case.LOWER) formatted = formatted.lowercase()
            }
            Primary.DECIMAL -> {
                formatted = JsNumber.toString(value)
                val padLength = format.mandatoryDigits - formatted.length
                if (padLength > 0) formatted = "0".repeat(padLength.toInt()) + formatted
                if (format.zeroCode != 0x30) {
                    val sb = StringBuilder()
                    formatted.codePoints().forEach { sb.appendCodePoint(it + format.zeroCode - 0x30) }
                    formatted = sb.toString()
                }
                if (format.regular) {
                    val sep = format.regularSeparator!!
                    val n = Math.floor((formatted.length - 1).toDouble() / sep.position).toInt()
                    for (ii in n downTo 1) {
                        val pos = formatted.length - ii * sep.position
                        formatted = jsSubstr(formatted, pos) + sep.character + formatted.substring(jsIndex(formatted, pos))
                    }
                } else {
                    for (separator in format.groupingSeparators.reversed()) {
                        val pos = formatted.length - separator.position
                        formatted = jsSubstr(formatted, pos) + separator.character + formatted.substring(jsIndex(formatted, pos))
                    }
                }
                if (format.ordinal) {
                    val last = formatted.lastOrNull()
                    var suffix = when (last) {
                        '1' -> "st"
                        '2' -> "nd"
                        '3' -> "rd"
                        else -> null
                    }
                    if (suffix == null || (formatted.length > 1 && formatted[formatted.length - 2] == '1')) suffix = "th"
                    formatted += suffix
                }
            }
            Primary.SEQUENCE -> throw JsonataException("D3130", value = StringValue.of(format.token!!))
        }
        if (negative) formatted = "-$formatted"
        return formatted
    }

    /** JS `s.substr(0, pos)` (negative pos counts from the end). */
    private fun jsSubstr(s: String, pos: Int): String = s.substring(0, jsIndex(s, pos))
    private fun jsIndex(s: String, pos: Int): Int = if (pos < 0) maxOf(0, s.length + pos) else minOf(pos, s.length)

    private fun formatInteger(valueArg: Value?, pictureArg: Value?): Value? {
        val value = (valueArg as? NumberValue)?.value ?: return null
        val format = analyseIntegerPicture(str(pictureArg) ?: "undefined")
        return StringValue.of(formatIntegerWith(Math.floor(value), format))
    }

    private fun parseInteger(valueArg: Value?, pictureArg: Value?): Value? {
        val value = str(valueArg) ?: return null
        val format = analyseIntegerPicture(str(pictureArg) ?: "undefined")
        return NumberValue.of(integerMatcher(format).parse!!.invoke(value) as Double)
    }

    // ---- date/time pictures (datetime.js: analyseDateTimePicture, formatDateTime, parseDateTime) ----

    private class Width(val min: Double?, val max: Double?)

    private sealed class Part
    private class Literal(val value: String) : Part()
    private class Marker(val component: Char) : Part() {
        var width: Width? = null
        var presentation1: String? = null
        var presentation2: Char? = null
        var ordinal = false
        var names: Case? = null
        var integerFormat: IntegerFormat? = null
        var n = 0.0
    }

    private val defaultPresentationModifiers = mapOf(
        'Y' to "1", 'M' to "1", 'D' to "1", 'd' to "1", 'F' to "n", 'W' to "1", 'w' to "1", 'X' to "1", 'x' to "1", 'H' to "1", 'h' to "1",
        'P' to "n", 'm' to "01", 's' to "01", 'f' to "1", 'Z' to "01:01", 'z' to "01:01", 'C' to "n", 'E' to "n",
    )

    private val jsWhitespace = Regex("[\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]+")

    /** JS `parseInt(s)` (radix 10): NaN when there are no leading digits. */
    private fun jsParseInt(s: String): Double {
        val t = s.trimStart { it.isWhitespace() || it == '﻿' || it == ' ' }
        var i = 0
        var neg = false
        if (i < t.length && (t[i] == '+' || t[i] == '-')) {
            neg = t[i] == '-'
            i++
        }
        val start = i
        while (i < t.length && t[i] in '0'..'9') i++
        if (i == start) return Double.NaN
        val v = t.substring(start, i).toDouble()
        return if (neg) -v else v
    }

    private fun analyseDateTimePicture(picture: String): List<Part> {
        val spec = ArrayList<Part>()
        fun addLiteral(start: Int, end: Int) {
            if (end > start) spec += Literal(picture.substring(start, end).split("]]").joinToString("]"))
        }
        var start = 0
        var pos = 0
        while (pos < picture.length) {
            if (picture[pos] == '[') {
                if (picture.getOrNull(pos + 1) == '[') {
                    addLiteral(start, pos)
                    spec += Literal("[")
                    pos += 2
                    start = pos
                    continue
                }
                addLiteral(start, pos)
                start = pos
                pos = picture.indexOf(']', start)
                if (pos == -1) throw JsonataException("D3135")
                val marker = picture.substring(start + 1, pos).split(jsWhitespace).joinToString("")
                val def = Marker(marker.getOrElse(0) { '\u0000' })
                val comma = marker.lastIndexOf(',')
                val presMod: String
                if (comma != -1) {
                    val widthMod = marker.substring(comma + 1)
                    val dash = widthMod.indexOf('-')
                    fun parseWidth(wm: String?): Double? = if (wm == null || wm == "*") null else jsParseInt(wm)
                    val min: String
                    var max: String? = null
                    if (dash == -1) {
                        min = widthMod
                    } else {
                        min = widthMod.substring(0, dash)
                        max = widthMod.substring(dash + 1)
                    }
                    def.width = Width(parseWidth(min), parseWidth(max))
                    presMod = marker.substring(1, comma)
                } else {
                    presMod = if (marker.length > 1) marker.substring(1) else ""
                }
                if (presMod.length == 1) {
                    def.presentation1 = presMod
                } else if (presMod.length > 1) {
                    val lastChar = presMod[presMod.length - 1]
                    if (lastChar in "atco") {
                        def.presentation2 = lastChar
                        if (lastChar == 'o') def.ordinal = true
                        def.presentation1 = presMod.substring(0, presMod.length - 1)
                    } else {
                        def.presentation1 = presMod
                    }
                } else {
                    def.presentation1 = defaultPresentationModifiers[def.component]
                }
                val p1 = def.presentation1 ?: throw JsonataException("D3132", value = StringValue.of(if (marker.isEmpty()) "" else def.component.toString()))
                if (p1.startsWith("n")) {
                    def.names = Case.LOWER
                } else if (p1.startsWith("N")) {
                    def.names = if (p1.getOrNull(1) == 'n') Case.TITLE else Case.UPPER
                } else if (def.component in "YMDdFWwXxHhmsf") {
                    var integerPattern = p1
                    if (def.presentation2 != null) integerPattern += ";" + def.presentation2
                    val f = analyseIntegerPicture(integerPattern)
                    def.integerFormat = f
                    val w = def.width
                    if (w?.min != null && f.mandatoryDigits < w.min) f.mandatoryDigits = w.min
                    if (def.component == 'Y') {
                        def.n = -1.0
                        if (w?.max != null) {
                            def.n = w.max
                            f.mandatoryDigits = def.n
                        } else {
                            val width = f.mandatoryDigits + f.optionalDigits
                            if (width >= 2) def.n = width
                        }
                    }
                    val previous = spec.lastOrNull()
                    if (previous is Marker && previous.integerFormat != null) {
                        previous.integerFormat!!.parseWidth = previous.integerFormat!!.mandatoryDigits
                    }
                }
                if (def.component == 'Z' || def.component == 'z') def.integerFormat = analyseIntegerPicture(p1)
                spec += def
                start = pos + 1
            }
            pos++
        }
        addLiteral(start, pos)
        return spec
    }

    private val days = listOf("", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
    private val months = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

    private class YearMonth(val year: Double, val month: Double) {
        fun nextMonth() = if (month == 11.0) YearMonth(year + 1, 0.0) else YearMonth(year, month + 1)
        fun previousMonth() = if (month == 0.0) YearMonth(year - 1, 11.0) else YearMonth(year, month - 1)
        fun nextYear() = YearMonth(year + 1, month)
        fun previousYear() = YearMonth(year - 1, month)
    }

    private fun startOfFirstWeek(ym: YearMonth): Double {
        val jan1 = dateUtc(ym.year, ym.month)
        var dayOfJan1 = JsDate(jan1).weekday
        if (dayOfJan1 == 0.0) dayOfJan1 = 7.0
        return if (dayOfJan1 > 4) jan1 + (8 - dayOfJan1) * MS_PER_DAY else jan1 - (dayOfJan1 - 1) * MS_PER_DAY
    }

    private fun deltaWeeks(start: Double, end: Double): Double = (end - start) / (MS_PER_DAY * 7) + 1

    /** datetime.js: getDateTimeFragment — a Double, a String (P, C, E) or null (Z, unknown components). */
    private fun fragment(date: JsDate, component: Char): Any? = when (component) {
        'Y' -> date.fullYear
        'M' -> date.month + 1
        'D' -> date.dayOfMonth
        'd' -> {
            val today = dateUtc(date.fullYear, date.month, date.dayOfMonth)
            val firstJan = dateUtc(date.fullYear, 0.0)
            (today - firstJan) / MS_PER_DAY + 1
        }
        'F' -> date.weekday.let { if (it == 0.0) 7.0 else it }
        'W' -> {
            val thisYear = YearMonth(date.fullYear, 0.0)
            val startOfWeek1 = startOfFirstWeek(thisYear)
            val today = dateUtc(thisYear.year, date.month, date.dayOfMonth)
            var week = deltaWeeks(startOfWeek1, today)
            if (week > 52) {
                if (today >= startOfFirstWeek(thisYear.nextYear())) week = 1.0
            } else if (week < 1) {
                week = deltaWeeks(startOfFirstWeek(thisYear.previousYear()), today)
            }
            Math.floor(week)
        }
        'w' -> {
            val thisMonth = YearMonth(date.fullYear, date.month)
            val startOfWeek1 = startOfFirstWeek(thisMonth)
            val today = dateUtc(thisMonth.year, thisMonth.month, date.dayOfMonth)
            var week = deltaWeeks(startOfWeek1, today)
            if (week > 4) {
                if (today >= startOfFirstWeek(thisMonth.nextMonth())) week = 1.0
            } else if (week < 1) {
                week = deltaWeeks(startOfFirstWeek(thisMonth.previousMonth()), today)
            }
            Math.floor(week)
        }
        'X' -> {
            val thisYear = YearMonth(date.fullYear, 0.0)
            val startOfIsoYear = startOfFirstWeek(thisYear)
            val endOfIsoYear = startOfFirstWeek(thisYear.nextYear())
            val now = date.time
            if (now < startOfIsoYear) thisYear.year - 1 else if (now >= endOfIsoYear) thisYear.year + 1 else thisYear.year
        }
        'x' -> {
            val thisMonth = YearMonth(date.fullYear, date.month)
            val startOfIsoMonth = startOfFirstWeek(thisMonth)
            val nextMonth = thisMonth.nextMonth()
            val endOfIsoMonth = startOfFirstWeek(nextMonth)
            val now = date.time
            if (now < startOfIsoMonth) thisMonth.previousMonth().month + 1 else if (now >= endOfIsoMonth) nextMonth.month + 1 else thisMonth.month + 1
        }
        'H' -> date.hours
        'h' -> (date.hours % 12).let { if (it == 0.0) 12.0 else it }
        'P' -> if (date.hours >= 12) "pm" else "am"
        'm' -> date.minutes
        's' -> date.seconds
        'f' -> date.millis
        'C', 'E' -> "ISO"
        else -> null
    }

    private val iso8601Spec: List<Part> by lazy { analyseDateTimePicture("[Y0001]-[M01]-[D01]T[H01]:[m01]:[s01].[f001][Z01:01t]") }

    private fun jsToString(v: Any?): String = when (v) {
        null -> "undefined"
        is Double -> JsNumber.toString(v)
        else -> v.toString()
    }

    private fun fromMillis(millisArg: Value?, picture: Value?, timezone: Value?): Value? {
        val millis = (millisArg as? NumberValue)?.value ?: return null
        return StringValue.of(formatDateTime(millis, str(picture), str(timezone)))
    }

    /** datetime.js: formatDateTime. */
    fun formatDateTime(millis: Double, picture: String?, timezone: String?): String {
        var offsetHours = 0.0
        var offsetMinutes = 0.0
        if (timezone != null) {
            // the reference assumes ±hhmm: -0530 becomes -6 h and -30 min (jsonata-js behaviour, DT-05)
            val offset = jsParseInt(timezone)
            offsetHours = Math.floor(offset / 100)
            offsetMinutes = offset % 100
        }
        val spec = if (picture == null) iso8601Spec else analyseDateTimePicture(picture)
        val offsetMillis = (60 * offsetHours + offsetMinutes) * 60 * 1000
        val date = JsDate(timeClip(millis + offsetMillis))
        val sb = StringBuilder()
        for (part in spec) {
            when (part) {
                is Literal -> sb.append(part.value)
                is Marker -> sb.append(formatComponent(date, part, offsetHours, offsetMinutes))
            }
        }
        return sb.toString()
    }

    private fun formatComponent(date: JsDate, spec: Marker, offsetHours: Double, offsetMinutes: Double): String {
        var value: Any? = fragment(date, spec.component)
        val c = spec.component
        if (c in "YMDdFWwXxHhms") {
            if (c == 'Y' && spec.n != -1.0) value = (value as Double) % Math.pow(10.0, spec.n)
            if (spec.names != null) {
                var name: String? = when (c) {
                    'M', 'x' -> months.getOrNull(((value as Double) - 1).toInt().takeIf { !value.isNaN() } ?: -1)
                    'F' -> days.getOrNull((value as Double).toInt().takeIf { !value.isNaN() } ?: -1)
                    else -> throw JsonataException("D3133", value = StringValue.of(c.toString()))
                }
                if (name == null) throw JsonataException("D3133", value = StringValue.of(c.toString()))
                if (spec.names == Case.UPPER) name = name.uppercase()
                else if (spec.names == Case.LOWER) name = name.lowercase()
                val max = spec.width?.max
                if (max != null && name.length > max) name = name.substring(0, max.toInt())
                return name
            }
            return formatIntegerWith(value as Double, spec.integerFormat!!)
        }
        if (c == 'f') return formatIntegerWith(value as Double, spec.integerFormat!!)
        if (c == 'Z' || c == 'z') {
            val offset = offsetHours * 100 + offsetMinutes
            val format = spec.integerFormat!!
            var result: String
            if (format.regular) {
                result = formatIntegerWith(offset, format)
            } else {
                val numDigits = format.mandatoryDigits
                if (numDigits == 1.0 || numDigits == 2.0) {
                    result = formatIntegerWith(offsetHours, format)
                    if (offsetMinutes != 0.0) result += ":" + formatIntegerWith(Math.floor(offsetMinutes), analyseIntegerPicture("00"))
                } else if (numDigits == 3.0 || numDigits == 4.0) {
                    result = formatIntegerWith(offset, format)
                } else {
                    throw JsonataException("D3134", value = NumberValue.of(numDigits))
                }
            }
            if (offset >= 0) result = "+$result"
            if (c == 'z') result = "GMT$result"
            if (offset == 0.0 && spec.presentation2 == 't') result = "Z"
            return result
        }
        if (c == 'P' && spec.names == Case.UPPER) return (value as String).uppercase()
        return jsToString(value)
    }

    // ---- parsing ----

    /** A part of the parsing regex and how to read its match (datetime.js: generateRegex). */
    private class Matcher(val regex: String, val component: Char?, val parse: ((String) -> Any?)?)

    private fun escapeRegex(s: String): String = s.replace(Regex("[.*+?^\${}()|\\[\\]\\\\]")) { "\\" + it.value }

    private fun integerMatcher(format: IntegerFormat, component: Char? = null): Matcher {
        val isUpper = format.case == Case.UPPER
        return when (format.primary) {
            Primary.LETTERS -> Matcher(if (isUpper) "[A-Z]+" else "[a-z]+", component) { lettersToDecimal(it, if (isUpper) 'A' else 'a') }
            Primary.ROMAN -> Matcher(if (isUpper) "[MDCLXVI]+" else "[mdclxvi]+", component) { romanToDecimal(if (isUpper) it else it.uppercase()) }
            Primary.WORDS -> Matcher("(?:" + (wordValues.keys + listOf("and", "[\\-, ]")).joinToString("|") + ")+", component) { wordsToNumber(it.lowercase()) }
            Primary.DECIMAL -> {
                var regex = "[0-9]"
                val pw = format.parseWidth
                regex += if (pw != null && pw != 0.0 && !pw.isNaN()) "{" + JsNumber.toString(pw) + "}" else "+"
                if (format.ordinal) regex += "(?:th|st|nd|rd)"
                Matcher(regex, component) { value ->
                    var digits = value
                    if (format.ordinal) digits = digits.substring(0, maxOf(0, digits.length - 2))
                    if (format.regular) {
                        digits = digits.split(",").joinToString("")
                    } else {
                        for (sep in format.groupingSeparators) digits = digits.split(sep.character).joinToString("")
                    }
                    if (format.zeroCode != 0x30) {
                        digits = digits.map { (it.code - format.zeroCode + 0x30).toChar() }.joinToString("")
                    }
                    jsParseInt(digits)
                }
            }
            Primary.SEQUENCE -> throw JsonataException("D3130", value = StringValue.of(format.token!!))
        }
    }

    private fun partMatcher(part: Part): Matcher = when {
        part is Literal -> Matcher(escapeRegex(part.value), null, null)
        part is Marker && (part.component == 'Z' || part.component == 'z') -> {
            val format = part.integerFormat!!
            val separator = if (format.regular) format.regularSeparator else null
            var regex = if (part.component == 'z') "GMT" else ""
            regex += "[-+][0-9]+"
            if (separator != null) regex += separator.character + "[0-9]+"
            Matcher(regex, part.component) { v ->
                var value = v
                if (part.component == 'z') value = value.substring(3)
                var offsetHours = 0.0
                var offsetMinutes = 0.0
                if (separator != null) {
                    val idx = value.indexOf(separator.character)
                    offsetHours = jsParseInt(value.substring(0, maxOf(idx, 0)))
                    offsetMinutes = jsParseInt(value.substring(idx + 1))
                } else {
                    val numdigits = value.length - 1
                    if (numdigits <= 2) {
                        offsetHours = jsParseInt(value)
                    } else {
                        offsetHours = jsParseInt(value.substring(0, 3))
                        offsetMinutes = jsParseInt(value.substring(3))
                    }
                }
                // -05:30 reads as -5 h + 30 min (jsonata-js behaviour, DT-06)
                offsetHours * 60 + offsetMinutes
            }
        }
        part is Marker && part.component == 'f' -> Matcher("[0-9]+", 'f') { v -> ("0." + v.substring(0, minOf(3, v.length))).toDouble() * 1000 }
        part is Marker && part.integerFormat != null -> integerMatcher(part.integerFormat!!, part.component)
        else -> {
            part as Marker
            val lookup = HashMap<String, Double>()
            val max = part.width?.max
            fun key(name: String) = if (max != null && max != 0.0 && !max.isNaN()) name.substring(0, minOf(name.length, maxOf(0, max.toInt()))) else name
            when (part.component) {
                'M', 'x' -> months.forEachIndexed { i, name -> lookup[key(name)] = i + 1.0 }
                'F' -> days.forEachIndexed { i, name -> if (i > 0) lookup[key(name)] = i.toDouble() }
                'P' -> { lookup["am"] = 0.0; lookup["AM"] = 0.0; lookup["pm"] = 1.0; lookup["PM"] = 1.0 }
                else -> throw JsonataException("D3133", value = StringValue.of(part.component.toString()))
            }
            Matcher("[a-zA-Z]+", part.component) { lookup[it] }
        }
    }

    private val iso8601regex = Pattern.compile("^\\d{4}(-[01]\\d)?(-[0-3]\\d)?(T[0-2]\\d:[0-5]\\d:[0-5]\\d)?(\\.\\d+)?([+-][0-2]\\d:?[0-5]\\d|Z)?$")

    private fun toMillis(c: CallContext, timestampArg: Value?, picture: Value?): Value? {
        val timestamp = str(timestampArg) ?: return null
        if (picture == null) {
            if (!iso8601regex.matcher(timestamp).find()) throw JsonataException("D3110", value = timestampArg)
            return NumberValue.of(dateParse(timestamp))
        }
        return parseDateTime(c, timestamp, str(picture) ?: "undefined")?.let { NumberValue.of(it) }
    }

    private val isoFull = Pattern.compile("^(\\d{4})(?:-(\\d\\d)(?:-(\\d\\d))?)?(?:T(\\d\\d):(\\d\\d)(?::(\\d\\d)(?:\\.(\\d+))?)?)?(Z|[+-]\\d\\d:?\\d\\d)?$")

    /**
     * V8 `Date.parse` for the strings `$toMillis` accepts (ES date-time format). A date-time without an offset is local
     * time in JS — it depends on the process time zone; here it is UTC, as in the environment the test-suite expects.
     */
    private fun dateParse(s: String): Double {
        val m = isoFull.matcher(s)
        if (!m.matches()) return Double.NaN // V8 falls back to its legacy parser here (e.g. "2018.5"), not reproduced
        val year = m.group(1).toDouble()
        val month = m.group(2)?.toDouble() ?: 1.0
        val day = m.group(3)?.toDouble() ?: 1.0
        val hour = m.group(4)?.toDouble() ?: 0.0
        val minute = m.group(5)?.toDouble() ?: 0.0
        val second = m.group(6)?.toDouble() ?: 0.0
        val ms = m.group(7)?.let { (it + "00").substring(0, 3).toDouble() } ?: 0.0
        if (month < 1 || month > 12 || day < 1 || day > 31) return Double.NaN
        if (hour > 24 || minute > 59 || second > 59) return Double.NaN
        if (hour == 24.0 && (minute != 0.0 || second != 0.0 || ms != 0.0)) return Double.NaN
        var t = makeDay(year, month - 1, day) * MS_PER_DAY + hour * 3_600_000 + minute * 60_000 + second * 1000 + ms
        val zone = m.group(8)
        if (zone != null && zone != "Z") {
            val sign = if (zone[0] == '-') -1 else 1
            val digits = zone.substring(1).replace(":", "")
            val oh = digits.substring(0, 2).toDouble()
            val om = digits.substring(2, 4).toDouble()
            if (oh > 23 || om > 59) return Double.NaN
            t -= sign * (oh * 60 + om) * 60_000
        }
        return timeClip(t)
    }

    /** datetime.js: parseDateTime. */
    private fun parseDateTime(c: CallContext, timestamp: String, picture: String): Double? {
        val spec = analyseDateTimePicture(picture)
        val parts = spec.map { partMatcher(it) }
        val fullRegex = "^" + parts.joinToString("") { "(" + it.regex + ")" } + "$"
        val pattern = Pattern.compile(JsRegexTranslator.translate(fullRegex, false), Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
        val info = pattern.matcher(timestamp)
        if (!info.find()) return null
        val components = HashMap<Char, Any?>()
        for (i in 1..info.groupCount()) {
            if (i - 1 >= parts.size) break
            val mpart = parts[i - 1]
            val parse = mpart.parse ?: continue
            components[mpart.component!!] = parse.invoke(info.group(i) ?: "undefined")
        }
        if (components.isEmpty()) return null

        fun truthy(v: Any?): Boolean = when (v) {
            null -> false
            is Double -> v != 0.0 && !v.isNaN()
            is String -> v.isNotEmpty()
            else -> true
        }
        var mask = 0
        fun shift(bit: Boolean) {
            mask = (mask shl 1) + if (bit) 1 else 0
        }
        fun isType(type: Int): Boolean = (type.inv() and mask) == 0 && (type and mask) != 0
        "YXMxWwdD".forEach { shift(truthy(components[it])) }
        val dateA = isType(161)
        val dateB = !dateA && isType(130)
        val dateC = isType(84)
        val dateD = !dateC && isType(72)
        mask = 0
        "PHhmsf".forEach { shift(truthy(components[it])) }
        val timeA = isType(23)
        val timeB = !timeA && isType(47)

        val dateComps = if (dateB) "YD" else if (dateC) "XxwF" else if (dateD) "XWF" else "YMD"
        val timeComps = if (timeB) "Phmsf" else "Hmsf"
        val now = JsDate(c.timestamp.toDouble())
        var startSpecified = false
        var endSpecified = false
        for (part in dateComps + timeComps) {
            if (!components.containsKey(part) || components[part] == null) {
                if (startSpecified) {
                    components[part] = if (part in "MDd") 1.0 else 0.0
                    endSpecified = true
                } else {
                    components[part] = fragment(now, part)
                }
            } else {
                startSpecified = true
                if (endSpecified) throw JsonataException("D3136")
            }
        }
        fun num(k: Char): Double = when (val v = components[k]) {
            is Double -> v
            null -> Double.NaN
            else -> Double.NaN
        }
        val m = components['M']
        if (m is Double && m > 0) components['M'] = m - 1 else components['M'] = 0.0
        if (dateB) {
            val firstJan = dateUtc(num('Y'), 0.0)
            val derived = JsDate(timeClip(firstJan + (num('d') - 1) * MS_PER_DAY))
            components['M'] = derived.month
            components['D'] = derived.dayOfMonth
        }
        if (dateC || dateD) throw JsonataException("D3136")
        if (timeB) {
            var h = if (components['h'] == 12.0) 0.0 else num('h')
            if (components['P'] == 1.0) h += 12
            components['H'] = h
        }
        var millis = dateUtc(num('Y'), num('M'), num('D'), num('H'), num('m'), num('s'), num('f'))
        val zone = components['Z'].takeIf { truthy(it) } ?: components['z'].takeIf { truthy(it) }
        if (zone != null) millis -= (zone as Double) * 60 * 1000
        return millis
    }
}
