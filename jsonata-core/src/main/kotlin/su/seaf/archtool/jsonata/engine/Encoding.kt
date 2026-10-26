package su.seaf.archtool.jsonata.engine

import su.seaf.archtool.jsonata.deviation.Deviations
import su.seaf.archtool.jsonata.parser.JsonataException
import su.seaf.archtool.jsonata.value.StringValue

/** `$base64*` (UTF-8, DEV-11) and ECMA-262 URI encoding/decoding. */
internal object Encoding {
    /**
     * `Buffer.from(str, 'utf8').toString('base64')` (DEV-11): the UTF-8 bytes of the string; a lone surrogate becomes
     * U+FFFD, as in Node. jsonata-js 2.2.2 uses the `binary` encoding, which keeps only the low byte of each UTF-16
     * unit, so the result differs for any character above U+007F; [ctx] receives the probe then.
     */
    fun base64encode(s: String, ctx: EvalContext?): String {
        val out = java.io.ByteArrayOutputStream(s.length + 8)
        var nonAscii = false
        var i = 0
        while (i < s.length) {
            ctx?.tick()
            val c = s[i]
            var cp = c.code
            if (Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1])) {
                cp = Character.toCodePoint(c, s[i + 1])
                i++
            } else if (Character.isSurrogate(c)) {
                cp = 0xFFFD
            }
            i++
            if (cp >= 0x80) nonAscii = true
            when {
                cp < 0x80 -> out.write(cp)
                cp < 0x800 -> { out.write(0xC0 or (cp shr 6)); out.write(0x80 or (cp and 0x3F)) }
                cp < 0x10000 -> { out.write(0xE0 or (cp shr 12)); out.write(0x80 or ((cp shr 6) and 0x3F)); out.write(0x80 or (cp and 0x3F)) }
                else -> {
                    out.write(0xF0 or (cp shr 18)); out.write(0x80 or ((cp shr 12) and 0x3F))
                    out.write(0x80 or ((cp shr 6) and 0x3F)); out.write(0x80 or (cp and 0x3F))
                }
            }
        }
        if (nonAscii) ctx?.deviation(Deviations.BASE64_UTF8.id, null)
        return java.util.Base64.getEncoder().encodeToString(out.toByteArray())
    }

    /**
     * `Buffer.from(str, 'base64').toString('utf8')` (DEV-11): lenient decoding as Node's (url-safe alphabet, junk and
     * padding ignored; Node classifies each UTF-16 unit by its low byte, so `'и'` (U+0438) counts as `'8'`), then the
     * bytes as UTF-8 with U+FFFD for malformed sequences. jsonata-js 2.2.2 maps each byte to one character (`binary`),
     * so the result differs when a byte is above 0x7F; [ctx] receives the probe then.
     */
    fun base64decode(s: String, ctx: EvalContext?): String {
        val clean = StringBuilder()
        for (unit in s) {
            when (val c = (unit.code and 0xFF).toChar()) {
                in 'A'..'Z', in 'a'..'z', in '0'..'9', '+', '/' -> clean.append(c)
                '-' -> clean.append('+')
                '_' -> clean.append('/')
                '=' -> break
                else -> {}
            }
        }
        if (clean.length % 4 == 1) clean.setLength(clean.length - 1)
        while (clean.length % 4 != 0) clean.append('=')
        val bytes = java.util.Base64.getDecoder().decode(clean.toString())
        if (bytes.any { it < 0 }) ctx?.deviation(Deviations.BASE64_UTF8.id, null)
        return decodeUtf8(bytes)
    }

    /**
     * UTF-8 decoding of the WHATWG Encoding standard, which Node follows: each maximal invalid subpart becomes one
     * U+FFFD (`ED A0 80` gives three, where the JDK decoder gives one).
     */
    private fun decodeUtf8(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size)
        var codePoint = 0
        var needed = 0
        var seen = 0
        var lower = 0x80
        var upper = 0xBF
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            if (needed == 0) {
                when (b) {
                    in 0x00..0x7F -> sb.append(b.toChar())
                    in 0xC2..0xDF -> { needed = 1; codePoint = b and 0x1F }
                    in 0xE0..0xEF -> {
                        if (b == 0xE0) lower = 0xA0
                        if (b == 0xED) upper = 0x9F
                        needed = 2
                        codePoint = b and 0x0F
                    }
                    in 0xF0..0xF4 -> {
                        if (b == 0xF0) lower = 0x90
                        if (b == 0xF4) upper = 0x8F
                        needed = 3
                        codePoint = b and 0x07
                    }
                    else -> sb.append('\uFFFD')
                }
                i++
                continue
            }
            if (b !in lower..upper) {
                // the byte is not consumed: it starts the next sequence
                codePoint = 0; needed = 0; seen = 0; lower = 0x80; upper = 0xBF
                sb.append('\uFFFD')
                continue
            }
            lower = 0x80
            upper = 0xBF
            codePoint = (codePoint shl 6) or (b and 0x3F)
            seen++
            i++
            if (seen == needed) {
                sb.appendCodePoint(codePoint)
                codePoint = 0; needed = 0; seen = 0
            }
        }
        if (needed != 0) sb.append('\uFFFD')
        return sb.toString()
    }

    private const val UNRESERVED = "-_.!~*'()"
    private const val RESERVED = ";/?:@&=+$,#"

    /** ECMA-262 Encode (encodeURIComponent / encodeURI); a lone surrogate raises D3140. */
    fun encodeUri(s: String, component: Boolean, functionName: String, ctx: EvalContext? = null): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            ctx?.tick()
            val c = s[i]
            if (c < '\u0080' && (c.isLetterOrDigit() || c in UNRESERVED || (!component && c in RESERVED))) {
                sb.append(c)
                i++
                continue
            }
            val cp: Int
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= s.length || !Character.isLowSurrogate(s[i + 1])) throw malformed(s, functionName)
                cp = Character.toCodePoint(c, s[i + 1])
                i += 2
            } else if (Character.isLowSurrogate(c)) {
                throw malformed(s, functionName)
            } else {
                cp = c.code
                i++
            }
            for (b in String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)) {
                sb.append('%').append("0123456789ABCDEF"[(b.toInt() shr 4) and 0xF]).append("0123456789ABCDEF"[b.toInt() and 0xF])
            }
        }
        return sb.toString()
    }

    /** ECMA-262 Decode (decodeURIComponent / decodeURI); malformed sequences raise D3140. */
    fun decodeUri(s: String, component: Boolean, functionName: String, ctx: EvalContext? = null): String {
        val sb = StringBuilder()
        var k = 0
        val n = s.length
        while (k < n) {
            ctx?.tick()
            val c = s[k]
            if (c != '%') {
                sb.append(c)
                k++
                continue
            }
            val start = k
            val b = hexByte(s, k) ?: throw malformed(s, functionName)
            k += 3
            if (b and 0x80 == 0) {
                val ch = b.toChar()
                if (!component && ch in RESERVED) sb.append(s, start, k) else sb.append(ch)
                continue
            }
            val count = when {
                b and 0xE0 == 0xC0 -> 2
                b and 0xF0 == 0xE0 -> 3
                b and 0xF8 == 0xF0 -> 4
                else -> throw malformed(s, functionName)
            }
            val bytes = ByteArray(count)
            bytes[0] = b.toByte()
            for (j in 1 until count) {
                if (k >= n || s[k] != '%') throw malformed(s, functionName)
                val bj = hexByte(s, k) ?: throw malformed(s, functionName)
                if (bj and 0xC0 != 0x80) throw malformed(s, functionName)
                bytes[j] = bj.toByte()
                k += 3
            }
            val decoder = Charsets.UTF_8.newDecoder()
            val text = try {
                decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            } catch (_: java.nio.charset.CharacterCodingException) {
                throw malformed(s, functionName)
            }
            sb.append(text)
        }
        return sb.toString()
    }

    private fun hexByte(s: String, k: Int): Int? {
        if (k + 2 >= s.length) return null
        val h = s.substring(k + 1, k + 3)
        if (!h.all { c -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' }) return null
        return h.toInt(16)
    }

    private fun malformed(s: String, functionName: String) =
        JsonataException("D3140", value = StringValue.of(s), details = mapOf("functionName" to functionName))
}
