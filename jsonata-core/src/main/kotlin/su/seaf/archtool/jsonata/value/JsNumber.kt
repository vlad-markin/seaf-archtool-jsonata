package su.seaf.archtool.jsonata.value

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Number ↔ string conversions with JS semantics (ECMA-262 Number::toString, toPrecision, toFixed, toString(radix)).
 * Kotlin's `Double.toString` writes `1.0E21` where JS writes `1e+21`, so every place that turns a number into text goes
 * through here.
 */
public object JsNumber {
    /** JS `String(d)` / `Number.prototype.toString()`. */
    @JvmStatic
    public fun toString(d: Double): String {
        if (d.isNaN()) return "NaN"
        if (d == 0.0) return "0"
        if (d.isInfinite()) return if (d > 0) "Infinity" else "-Infinity"
        val negative = d < 0
        val (digits, n) = shortestDigits(Math.abs(d))
        val k = digits.length
        val sb = StringBuilder(24)
        if (negative) sb.append('-')
        when {
            n in k..21 -> {
                sb.append(digits)
                repeat(n - k) { sb.append('0') }
            }
            n in 1..21 -> sb.append(digits, 0, n).append('.').append(digits, n, k)
            n in -5..0 -> {
                sb.append("0.")
                repeat(-n) { sb.append('0') }
                sb.append(digits)
            }
            else -> {
                sb.append(digits[0])
                if (k > 1) sb.append('.').append(digits, 1, k)
                sb.append('e').append(if (n - 1 >= 0) '+' else '-').append(Math.abs(n - 1))
            }
        }
        return sb.toString()
    }

    /**
     * The shortest decimal digits `s` and exponent `n` with `x = 0.s × 10^n` that round-trip to [x] (x > 0, finite).
     * JDK 19+ `Double.toString` yields the shortest round-tripping decimal, except that it prefers a closer 2-digit
     * decimal when a 1-digit one exists (only possible for subnormals, e.g. `4.9E-324` where JS writes `5e-324`).
     */
    internal fun shortestDigits(x: Double): Pair<String, Int> {
        val s = java.lang.Double.toString(x)
        val ePos = s.indexOf('E')
        val mantissa = if (ePos < 0) s else s.substring(0, ePos)
        val exp = if (ePos < 0) 0 else s.substring(ePos + 1).toInt()
        val dot = mantissa.indexOf('.')
        val intPart = mantissa.substring(0, dot)
        val fracPart = mantissa.substring(dot + 1)
        var all = intPart + fracPart
        var n = intPart.length + exp
        // strip leading zeros (e.g. "0.001")
        var lead = 0
        while (lead < all.length - 1 && all[lead] == '0') lead++
        all = all.substring(lead)
        n -= lead
        all = all.trimEnd('0')
        if (all.isEmpty()) return "0" to 1
        if (all.length == 2) {
            // try a 1-digit decimal that round-trips (JS picks the shortest first)
            val first = all[0] - '0'
            val candidates = listOf(first, first + 1).filter { it in 1..10 }
            var best: Pair<String, Int>? = null
            var bestDiff: BigDecimal? = null
            for (c in candidates) {
                val text = if (c == 10) "1e${n}" else "${c}e${n - 1}"
                val v = text.toDouble()
                if (v == x) {
                    val diff = BigDecimal(text).subtract(BigDecimal(x)).abs()
                    if (bestDiff == null || diff < bestDiff) {
                        bestDiff = diff
                        best = if (c == 10) "1" to n + 1 else c.toString() to n
                    }
                }
            }
            if (best != null) return best
        }
        return all to n
    }

    /** JS `Number(x.toPrecision(15))`, used by `$string` for non-integers. */
    @JvmStatic
    public fun toPrecision15(d: Double): Double {
        if (d.isNaN() || d.isInfinite() || d == 0.0) return d
        return BigDecimal(d).round(MathContext(15, RoundingMode.HALF_UP)).toDouble()
    }

    /** JS `x.toPrecision(p)` (1 ≤ p ≤ 100). */
    @JvmStatic
    public fun toPrecision(d: Double, p: Int): String {
        if (d.isNaN()) return "NaN"
        if (d.isInfinite()) return if (d > 0) "Infinity" else "-Infinity"
        var x = d
        val sb = StringBuilder()
        if (x < 0) { sb.append('-'); x = -x }
        val digits: String
        val e: Int
        if (x == 0.0) {
            digits = "0".repeat(p)
            e = 0
        } else {
            val bd = BigDecimal(x).round(MathContext(p, RoundingMode.HALF_UP))
            val unscaled = bd.unscaledValue().toString()
            // bd = unscaled × 10^-scale, normalise to exactly p digits
            val padded = if (unscaled.length < p) unscaled + "0".repeat(p - unscaled.length) else unscaled.substring(0, p)
            digits = padded
            e = unscaled.length - 1 - bd.scale()
        }
        if (e < -6 || e >= p) {
            sb.append(digits[0])
            if (p > 1) sb.append('.').append(digits, 1, p)
            sb.append('e').append(if (e >= 0) '+' else '-').append(Math.abs(e))
            return sb.toString()
        }
        if (e == p - 1) return sb.append(digits).toString()
        if (e >= 0) return sb.append(digits, 0, e + 1).append('.').append(digits, e + 1, p).toString()
        sb.append("0.")
        repeat(-(e + 1)) { sb.append('0') }
        return sb.append(digits).toString()
    }

    /** JS `x.toFixed(digits)` (0 ≤ digits ≤ 100). */
    @JvmStatic
    public fun toFixed(d: Double, digits: Int): String {
        if (d.isNaN()) return "NaN"
        if (Math.abs(d) >= 1e21 || d.isInfinite()) return toString(d)
        val negative = d < 0
        val bd = BigDecimal(Math.abs(d)).setScale(digits, RoundingMode.HALF_UP)
        val s = bd.toPlainString()
        return if (negative && bd.signum() != 0) "-$s" else if (negative) "-$s" else s
    }

    /**
     * JS `x.toString(radix)` for 2 ≤ radix ≤ 36: V8's DoubleToRadixCString. Digits are produced only as far as the
     * double's precision goes; integer digits below that precision are zeros (`(1e21).toString(36)` ends in `000`).
     */
    @JvmStatic
    public fun toString(d: Double, radix: Int): String {
        if (radix == 10) return toString(d)
        if (d.isNaN()) return "NaN"
        if (d.isInfinite()) return if (d > 0) "Infinity" else "-Infinity"
        val chars = "0123456789abcdefghijklmnopqrstuvwxyz"
        var value = d
        val negative = value < 0
        if (negative) value = -value
        var integer = Math.floor(value)
        var fraction = value - integer
        var delta = 0.5 * (Math.nextUp(value) - value)
        delta = Math.max(Math.nextUp(0.0), delta)
        val frac = StringBuilder()
        if (fraction >= delta) {
            frac.append('.')
            do {
                fraction *= radix
                delta *= radix
                val digit = fraction.toInt()
                frac.append(chars[digit])
                fraction -= digit
                // round to even
                if (fraction > 0.5 || (fraction == 0.5 && (digit and 1) == 1)) {
                    if (fraction + delta > 1) {
                        // carry over already written digits
                        while (true) {
                            val last = frac.length - 1
                            if (last == 0) {
                                // reached the decimal point: carry into the integer part
                                frac.setLength(0)
                                integer += 1
                                break
                            }
                            val c = frac[last]
                            frac.setLength(last)
                            val dgt = if (c > '9') c - 'a' + 10 else c - '0'
                            if (dgt + 1 < radix) {
                                frac.append(chars[dgt + 1])
                                break
                            }
                        }
                        break
                    }
                }
            } while (fraction >= delta)
            if (frac.length == 1) frac.setLength(0)
        }
        val intDigits = StringBuilder()
        while (exponentOf(integer / radix) > 0) {
            integer /= radix
            intDigits.append('0')
        }
        do {
            val remainder = integer % radix
            intDigits.append(chars[remainder.toInt()])
            integer = (integer - remainder) / radix
        } while (integer > 0)
        if (negative) intDigits.append('-')
        return intDigits.reverse().toString() + frac
    }

    /** V8 `Double(x).Exponent()`: the binary exponent of the 53-bit integer significand. */
    private fun exponentOf(x: Double): Int {
        val bits = java.lang.Double.doubleToRawLongBits(x)
        val biased = ((bits ushr 52) and 0x7FF).toInt()
        return if (biased == 0) 1 - 1075 else biased - 1075
    }

    /**
     * JS `Number(str)` for the strings a JSON number literal or `parseFloat` accepts; returns NaN when [s] is not a
     * decimal literal.
     */
    @JvmStatic
    public fun parse(s: String): Double = try {
        s.toDouble()
    } catch (_: NumberFormatException) {
        Double.NaN
    }
}
