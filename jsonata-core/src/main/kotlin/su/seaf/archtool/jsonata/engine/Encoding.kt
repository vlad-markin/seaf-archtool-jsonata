package su.seaf.archtool.jsonata.engine

import su.seaf.archtool.jsonata.parser.JsonataException
import su.seaf.archtool.jsonata.value.StringValue

/** `$base64*` as Node's Buffer with the `binary` (latin1) encoding, and ECMA-262 URI encoding/decoding. */
internal object Encoding {
    /** `Buffer.from(str, 'binary').toString('base64')`: each UTF-16 unit is truncated to its low byte (ORD: STR-03). */
    fun base64encode(s: String): String {
        val bytes = ByteArray(s.length) { s[it].code.toByte() }
        return java.util.Base64.getEncoder().encodeToString(bytes)
    }

    /** `Buffer.from(str, 'base64').toString('binary')`: lenient decoding (url-safe alphabet, junk and padding ignored). */
    fun base64decode(s: String): String {
        val clean = StringBuilder()
        for (c in s) {
            when (c) {
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
        val sb = StringBuilder(bytes.size)
        for (b in bytes) sb.append((b.toInt() and 0xFF).toChar())
        return sb.toString()
    }

    private const val UNRESERVED = "-_.!~*'()"
    private const val RESERVED = ";/?:@&=+$,#"

    /** ECMA-262 Encode (encodeURIComponent / encodeURI); a lone surrogate raises D3140. */
    fun encodeUri(s: String, component: Boolean, functionName: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
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
    fun decodeUri(s: String, component: Boolean, functionName: String): String {
        val sb = StringBuilder()
        var k = 0
        val n = s.length
        while (k < n) {
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
