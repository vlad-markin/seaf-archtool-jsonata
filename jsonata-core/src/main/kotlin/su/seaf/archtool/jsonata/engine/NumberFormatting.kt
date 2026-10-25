package su.seaf.archtool.jsonata.engine

import su.seaf.archtool.jsonata.FunctionRegistry
import su.seaf.archtool.jsonata.parser.JsonataException
import su.seaf.archtool.jsonata.value.JsNumber
import su.seaf.archtool.jsonata.value.NumberValue
import su.seaf.archtool.jsonata.value.ObjectValue
import su.seaf.archtool.jsonata.value.StringValue
import su.seaf.archtool.jsonata.value.Value

/** functions.js: formatNumber — XPath F&O fn:format-number with the reference's own rounding and quirks. */
internal object NumberFormatting {
    fun register(r: FunctionRegistry) {
        StandardLibrary.def(r, "formatNumber", "<n-so?:s>", 3) { _, a -> formatNumber(a[0], a[1], a.getOrNull(2)) }
    }

    private class Parts(
        val prefix: String,
        val suffix: String,
        val activePart: String,
        val mantissaPart: String,
        val exponentPart: String?,
        val integerPart: String,
        val fractionalPart: String,
        val subpicture: String,
    )

    private class Analysis(
        val integerPartGroupingPositions: List<Int>,
        val regularGrouping: Int,
        val minimumIntegerPartSize: Int,
        val scalingFactor: Int,
        var prefix: String,
        val fractionalPartGroupingPositions: List<Int>,
        val minimumFactionalPartSize: Int,
        val maximumFactionalPartSize: Int,
        val minimumExponentSize: Int,
        val suffix: String,
        val picture: String,
    )

    /** JS `s.charAt(i)` ("" outside the string). */
    private fun charAt(s: String, i: Int): String = if (i >= 0 && i < s.length) s[i].toString() else ""

    private fun formatNumber(valueArg: Value?, pictureArg: Value?, optionsArg: Value?): Value? {
        val value = (valueArg as? NumberValue)?.value ?: return null
        val picture = (pictureArg as? StringValue)?.value ?: "undefined"
        val properties = linkedMapOf(
            "decimal-separator" to ".", "grouping-separator" to ",", "exponent-separator" to "e", "infinity" to "Infinity",
            "minus-sign" to "-", "NaN" to "NaN", "percent" to "%", "per-mille" to "‰", "zero-digit" to "0",
            "digit" to "#", "pattern-separator" to ";",
        )
        if (optionsArg is ObjectValue) {
            optionsArg.forEach { k, v -> properties[k] = if (v is StringValue) v.value else Evaluator.string(v) }
        }
        val decimalSep = properties["decimal-separator"]!!
        val groupingSep = properties["grouping-separator"]!!
        val exponentSep = properties["exponent-separator"]!!
        val percent = properties["percent"]!!
        val perMille = properties["per-mille"]!!
        val digit = properties["digit"]!!
        val patternSep = properties["pattern-separator"]!!
        val zeroDigit = properties["zero-digit"]!!
        val minusSign = properties["minus-sign"]!!

        val zeroCharCode = if (zeroDigit.isEmpty()) Double.NaN.toInt() else zeroDigit[0].code
        val decimalDigitFamily = (zeroCharCode until zeroCharCode + 10).map { it.toChar().toString() }
        val activeChars = decimalDigitFamily + listOf(decimalSep, exponentSep, groupingSep, digit, patternSep)
        fun isDigitChar(ch: String) = ch in decimalDigitFamily

        val subPictures = jsSplit(picture, patternSep)
        if (subPictures.size > 2) throw JsonataException("D3080")

        fun splitParts(subpicture: String): Parts {
            var prefix = ""
            for (ii in subpicture.indices) {
                val ch = subpicture[ii].toString()
                if (ch in activeChars && ch != exponentSep) {
                    prefix = subpicture.substring(0, ii)
                    break
                }
            }
            var suffix = ""
            for (ii in subpicture.length - 1 downTo 0) {
                val ch = subpicture[ii].toString()
                if (ch in activeChars && ch != exponentSep) {
                    suffix = subpicture.substring(ii + 1)
                    break
                }
            }
            val activePart = subpicture.substring(prefix.length, maxOf(prefix.length, subpicture.length - suffix.length))
            val mantissaPart: String
            val exponentPart: String?
            val exponentPosition = subpicture.indexOf(exponentSep, prefix.length)
            if (exponentPosition == -1 || exponentPosition > subpicture.length - suffix.length) {
                mantissaPart = activePart
                exponentPart = null
            } else {
                mantissaPart = jsSubstring(activePart, 0, exponentPosition)
                exponentPart = jsSubstring(activePart, exponentPosition + 1, activePart.length)
            }
            val decimalPosition = mantissaPart.indexOf(decimalSep)
            val integerPart: String
            val fractionalPart: String
            if (decimalPosition == -1) {
                integerPart = mantissaPart
                fractionalPart = suffix
            } else {
                integerPart = mantissaPart.substring(0, decimalPosition)
                fractionalPart = mantissaPart.substring(decimalPosition + 1)
            }
            return Parts(prefix, suffix, activePart, mantissaPart, exponentPart, integerPart, fractionalPart, subpicture)
        }

        fun validate(parts: Parts) {
            var error: String? = null
            val sub = parts.subpicture
            val decimalPos = sub.indexOf(decimalSep)
            if (decimalPos != sub.lastIndexOf(decimalSep)) error = "D3081"
            if (sub.indexOf(percent) != sub.lastIndexOf(percent)) error = "D3082"
            if (sub.indexOf(perMille) != sub.lastIndexOf(perMille)) error = "D3083"
            if (sub.indexOf(percent) != -1 && sub.indexOf(perMille) != -1) error = "D3084"
            var valid = false
            for (ch in parts.mantissaPart) {
                val c = ch.toString()
                if (isDigitChar(c) || c == digit) {
                    valid = true
                    break
                }
            }
            if (!valid) error = "D3085"
            val charTypes = parts.activePart.map { if (it.toString() in activeChars) 'a' else 'p' }.joinToString("")
            if (charTypes.indexOf('p') != -1) error = "D3086"
            if (decimalPos != -1) {
                if (charAt(sub, decimalPos - 1) == groupingSep || charAt(sub, decimalPos + 1) == groupingSep) error = "D3087"
            } else if (charAt(parts.integerPart, parts.integerPart.length - 1) == groupingSep) {
                error = "D3088"
            }
            if (sub.indexOf(groupingSep + groupingSep) != -1) error = "D3089"
            var optionalDigitPos = parts.integerPart.indexOf(digit)
            if (optionalDigitPos != -1 && parts.integerPart.substring(0, optionalDigitPos).any { isDigitChar(it.toString()) }) error = "D3090"
            optionalDigitPos = parts.fractionalPart.lastIndexOf(digit)
            if (optionalDigitPos != -1 && parts.fractionalPart.substring(optionalDigitPos).any { isDigitChar(it.toString()) }) error = "D3091"
            val exponentExists = parts.exponentPart != null
            if (exponentExists && parts.exponentPart!!.isNotEmpty() && (sub.indexOf(percent) != -1 || sub.indexOf(perMille) != -1)) error = "D3092"
            if (exponentExists && (parts.exponentPart!!.isEmpty() || parts.exponentPart.any { !isDigitChar(it.toString()) })) error = "D3093"
            if (error != null) throw JsonataException(error)
        }

        fun analyse(parts: Parts): Analysis {
            fun groupingPositions(part: String, toLeft: Boolean): List<Int> {
                val positions = ArrayList<Int>()
                var groupingPosition = part.indexOf(groupingSep)
                while (groupingPosition != -1) {
                    val chars = if (toLeft) part.substring(0, groupingPosition) else part.substring(groupingPosition)
                    positions += chars.count { isDigitChar(it.toString()) || it.toString() == digit }
                    // the reference searches the integer part here even for the fractional part
                    groupingPosition = parts.integerPart.indexOf(groupingSep, groupingPosition + 1)
                }
                return positions
            }
            val integerPartGroupingPositions = groupingPositions(parts.integerPart, false)
            fun regular(indexes: List<Int>): Int {
                if (indexes.isEmpty()) return 0
                fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
                val factor = indexes.reduce { a, b -> gcd(a, b) }
                for (index in 1..indexes.size) if (index * factor !in indexes) return 0
                return factor
            }
            val regularGrouping = regular(integerPartGroupingPositions)
            val fractionalPartGroupingPositions = groupingPositions(parts.fractionalPart, true)
            var minimumIntegerPartSize = parts.integerPart.count { isDigitChar(it.toString()) }
            val scalingFactor = minimumIntegerPartSize
            var minimumFactionalPartSize = parts.fractionalPart.count { isDigitChar(it.toString()) }
            var maximumFactionalPartSize = parts.fractionalPart.count { isDigitChar(it.toString()) || it.toString() == digit }
            val exponentPresent = parts.exponentPart != null
            if (minimumIntegerPartSize == 0 && maximumFactionalPartSize == 0) {
                if (exponentPresent) {
                    minimumFactionalPartSize = 1
                    maximumFactionalPartSize = 1
                } else {
                    minimumIntegerPartSize = 1
                }
            }
            if (exponentPresent && minimumIntegerPartSize == 0 && parts.integerPart.indexOf(digit) != -1) minimumIntegerPartSize = 1
            if (minimumIntegerPartSize == 0 && minimumFactionalPartSize == 0) minimumFactionalPartSize = 1
            val minimumExponentSize = if (exponentPresent) parts.exponentPart!!.count { isDigitChar(it.toString()) } else 0
            return Analysis(
                integerPartGroupingPositions, regularGrouping, minimumIntegerPartSize, scalingFactor, parts.prefix,
                fractionalPartGroupingPositions, minimumFactionalPartSize, maximumFactionalPartSize, minimumExponentSize,
                parts.suffix, parts.subpicture,
            )
        }

        val parts = subPictures.map { splitParts(it) }
        parts.forEach { validate(it) }
        val variables = parts.map { analyse(it) }.toMutableList()
        if (variables.size == 1) {
            val v = variables[0]
            variables += Analysis(
                v.integerPartGroupingPositions, v.regularGrouping, v.minimumIntegerPartSize, v.scalingFactor, minusSign + v.prefix,
                v.fractionalPartGroupingPositions, v.minimumFactionalPartSize, v.maximumFactionalPartSize, v.minimumExponentSize,
                v.suffix, v.picture,
            )
        }

        val pic = if (value >= 0) variables[0] else variables[1]
        val adjustedNumber = when {
            pic.picture.indexOf(percent) != -1 -> value * 100
            pic.picture.indexOf(perMille) != -1 -> value * 1000
            else -> value
        }
        var mantissa: Double
        var exponent: Int? = null
        if (pic.minimumExponentSize == 0) {
            mantissa = adjustedNumber
        } else {
            val maxMantissa = Math.pow(10.0, pic.scalingFactor.toDouble())
            val minMantissa = Math.pow(10.0, pic.scalingFactor - 1.0)
            mantissa = adjustedNumber
            var e = 0
            if (mantissa != 0.0) {
                while (Math.abs(mantissa) < minMantissa) {
                    mantissa *= 10
                    e -= 1
                }
                while (Math.abs(mantissa) > maxMantissa) {
                    mantissa /= 10
                    e += 1
                }
            }
            exponent = e
        }
        val roundedNumber = StandardLibrary.round(mantissa, pic.maximumFactionalPartSize.toDouble())
        fun makeString(v: Double, dp: Int): String {
            var s = JsNumber.toFixed(Math.abs(v), dp)
            if (zeroDigit != "0") {
                s = s.map { c -> if (c in '0'..'9') decimalDigitFamily[c.code - 48] else c.toString() }.joinToString("")
            }
            return s
        }
        var stringValue = makeString(roundedNumber, pic.maximumFactionalPartSize)
        val dot = stringValue.indexOf('.')
        stringValue = if (dot == -1) stringValue + decimalSep else stringValue.replaceFirst(".", decimalSep)
        while (stringValue.isNotEmpty() && charAt(stringValue, 0) == zeroDigit) stringValue = stringValue.substring(1)
        while (stringValue.isNotEmpty() && charAt(stringValue, stringValue.length - 1) == zeroDigit) stringValue = stringValue.substring(0, stringValue.length - 1)
        var decimalPos = stringValue.indexOf(decimalSep)
        val padLeft = pic.minimumIntegerPartSize - decimalPos
        val padRight = pic.minimumFactionalPartSize - (stringValue.length - decimalPos - 1)
        stringValue = (if (padLeft > 0) zeroDigit.repeat(padLeft) else "") + stringValue
        stringValue += if (padRight > 0) zeroDigit.repeat(padRight) else ""
        decimalPos = stringValue.indexOf(decimalSep)
        if (pic.regularGrouping > 0) {
            val groupCount = Math.floor((decimalPos - 1).toDouble() / pic.regularGrouping).toInt()
            for (group in 1..groupCount) {
                val at = decimalPos - group * pic.regularGrouping
                stringValue = jsSlice(stringValue, 0, at) + groupingSep + jsSlice(stringValue, at, stringValue.length)
            }
        } else {
            for (pos in pic.integerPartGroupingPositions) {
                stringValue = jsSlice(stringValue, 0, decimalPos - pos) + groupingSep + jsSlice(stringValue, decimalPos - pos, stringValue.length)
                decimalPos++
            }
        }
        decimalPos = stringValue.indexOf(decimalSep)
        for (pos in pic.fractionalPartGroupingPositions) {
            stringValue = jsSlice(stringValue, 0, pos + decimalPos + 1) + groupingSep + jsSlice(stringValue, pos + decimalPos + 1, stringValue.length)
        }
        decimalPos = stringValue.indexOf(decimalSep)
        if (pic.picture.indexOf(decimalSep) == -1 || decimalPos == stringValue.length - 1) {
            stringValue = stringValue.substring(0, maxOf(0, stringValue.length - 1))
        }
        if (exponent != null) {
            var stringExponent = makeString(exponent.toDouble(), 0)
            val pad = pic.minimumExponentSize - stringExponent.length
            if (pad > 0) stringExponent = zeroDigit.repeat(pad) + stringExponent
            stringValue = stringValue + exponentSep + (if (exponent < 0) minusSign else "") + stringExponent
        }
        return StringValue.of(pic.prefix + stringValue + pic.suffix)
    }

    /** JS `String.prototype.slice`. */
    private fun jsSlice(s: String, start: Int, end: Int): String {
        val from = if (start < 0) maxOf(s.length + start, 0) else minOf(start, s.length)
        val to = if (end < 0) maxOf(s.length + end, 0) else minOf(end, s.length)
        return if (from < to) s.substring(from, to) else ""
    }

    private fun jsSubstring(s: String, a: Int, b: Int): String {
        val x = a.coerceIn(0, s.length)
        val y = b.coerceIn(0, s.length)
        return if (x <= y) s.substring(x, y) else s.substring(y, x)
    }

    private fun jsSplit(s: String, sep: String): List<String> {
        if (sep.isEmpty()) return s.map { it.toString() }
        val out = ArrayList<String>()
        var p = 0
        while (true) {
            val q = s.indexOf(sep, p)
            if (q < 0) break
            out += s.substring(p, q)
            p = q + sep.length
        }
        out += s.substring(p)
        return out
    }
}
