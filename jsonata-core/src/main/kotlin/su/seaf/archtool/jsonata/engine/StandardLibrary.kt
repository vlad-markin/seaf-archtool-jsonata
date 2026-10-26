package su.seaf.archtool.jsonata.engine

import su.seaf.archtool.jsonata.CallContext
import su.seaf.archtool.jsonata.FunctionRegistry
import su.seaf.archtool.jsonata.NativeImplementation
import su.seaf.archtool.jsonata.parser.JsonataException
import su.seaf.archtool.jsonata.parser.JsonataParser
import su.seaf.archtool.jsonata.parser.Signature
import su.seaf.archtool.jsonata.value.ArrayValue
import su.seaf.archtool.jsonata.value.BooleanValue
import su.seaf.archtool.jsonata.value.FunctionValue
import su.seaf.archtool.jsonata.value.JsNumber
import su.seaf.archtool.jsonata.value.NullValue
import su.seaf.archtool.jsonata.value.NumberValue
import su.seaf.archtool.jsonata.value.ObjectValue
import su.seaf.archtool.jsonata.value.StringValue
import su.seaf.archtool.jsonata.value.Value

/**
 * The jsonata-js 2.2.2 standard library (functions.js, datetime.js), registered through the same [FunctionRegistry]
 * as the caller's functions (jsonata.js: the `staticFrame.bind(... defineFunction(...))` list, same signatures).
 * Arities are the JS `implementation.length`: higher-order functions pass as many of (value, index, array) as that.
 */
internal object StandardLibrary {
    fun registerAll(r: FunctionRegistry) {
        def(r, "sum", "<a<n>:n>", 1) { c, a -> sum(ctx(c), a[0]) }
        def(r, "count", "<a:n>", 1) { _, a -> NumberValue.of((a[0] as ArrayValue?)?.size ?: 0) }
        def(r, "max", "<a<n>:n>", 1) { c, a -> extreme(ctx(c), a[0], true) }
        def(r, "min", "<a<n>:n>", 1) { c, a -> extreme(ctx(c), a[0], false) }
        def(r, "average", "<a<n>:n>", 1) { c, a -> average(ctx(c), a[0]) }
        def(r, "string", "<x-b?:s>", 1, 2) { c, a -> a[0]?.let { StringValue.of(Evaluator.string(it, (a.getOrNull(1) as? BooleanValue)?.value == true, ctx(c))) } }
        def(r, "substring", "<s-nn?:s>", 3) { _, a -> substring(a[0], a[1], a[2]) }
        def(r, "substringBefore", "<s-s:s>", 2) { _, a -> str(a[0])?.let { s -> val p = s.indexOf(str(a[1]) ?: "undefined"); StringValue.of(if (p > -1) s.substring(0, p) else s) } }
        def(r, "substringAfter", "<s-s:s>", 2) { _, a ->
            str(a[0])?.let { s ->
                val chars = str(a[1]) ?: "undefined"
                val p = s.indexOf(chars)
                StringValue.of(if (p > -1) s.substring(p + chars.length) else s)
            }
        }
        def(r, "lowercase", "<s-:s>", 1) { _, a -> str(a[0])?.let { StringValue.of(it.lowercase()) } }
        def(r, "uppercase", "<s-:s>", 1) { _, a -> str(a[0])?.let { StringValue.of(it.uppercase()) } }
        def(r, "length", "<s-:n>", 1) { _, a -> str(a[0])?.let { NumberValue.of(it.codePointCount(0, it.length)) } }
        def(r, "trim", "<s-:s>", 1) { _, a -> str(a[0])?.let { StringValue.of(trim(it)) } }
        def(r, "pad", "<s-ns?:s>", 3) { c, a -> pad(ctx(c), a[0], a[1], a[2]) }
        def(r, "match", "<s-f<s:o>n?:a<o>>", 3) { c, a -> match(c, a[0], a[1], a[2]) }
        def(r, "contains", "<s-(sf):b>", 2) { c, a -> contains(c, a[0], a[1]) }
        def(r, "replace", "<s-(sf)(sf)n?:s>", 4) { c, a -> replace(c, a[0], a[1], a[2], a[3]) }
        def(r, "split", "<s-(sf)n?:a<s>>", 3) { c, a -> split(c, a[0], a[1], a[2]) }
        def(r, "join", "<a<s>s?:s>", 2) { c, a ->
            (a[0] as ArrayValue?)?.let { arr ->
                val sep = str(a.getOrNull(1)) ?: ""
                val context = ctx(c)
                var total = if (arr.size > 0) sep.length.toLong() * (arr.size - 1) else 0L
                for (i in 0 until arr.size) {
                    context.tick()
                    total += ((arr.raw(i) as? StringValue)?.value?.length ?: 0)
                }
                context.checkString(total)
                StringValue.of((0 until arr.size).joinToString(sep) { (arr.raw(it) as? StringValue)?.value ?: "" })
            }
        }
        def(r, "formatBase", "<n-n?:s>", 2) { _, a -> formatBase(a[0], a[1]) }
        def(r, "number", "<(nsb)-:n>", 1) { _, a -> number(a[0]) }
        def(r, "floor", "<n-:n>", 1) { _, a -> num(a[0])?.let { NumberValue.of(Math.floor(it)) } }
        def(r, "ceil", "<n-:n>", 1) { _, a -> num(a[0])?.let { NumberValue.of(Math.ceil(it)) } }
        def(r, "round", "<n-n?:n>", 2) { _, a -> num(a[0])?.let { NumberValue.of(round(it, num(a.getOrNull(1)))) } }
        def(r, "abs", "<n-:n>", 1) { _, a -> num(a[0])?.let { NumberValue.of(Math.abs(it)) } }
        def(r, "sqrt", "<n-:n>", 1) { _, a ->
            num(a[0])?.let {
                if (it < 0) throw JsonataException("D3060", value = a[0], index = 1)
                NumberValue.of(Math.sqrt(it))
            }
        }
        def(r, "power", "<n-n:n>", 2) { _, a -> power(a[0], a[1]) }
        def(r, "random", "<:n>", 0) { _, _ -> NumberValue.of(Math.random()) }
        def(r, "boolean", "<x-:b>", 1) { _, a -> boolean(a[0])?.let { BooleanValue.of(it) } }
        def(r, "not", "<x-:b>", 1) { _, a -> a[0]?.let { BooleanValue.of(boolean(it) != true) } }
        def(r, "map", "<af>", 2) { c, a -> map(c, a[0], a[1]) }
        def(r, "zip", "<a+>", 0) { c, a -> zip(c, a) }
        def(r, "filter", "<af>", 2) { c, a -> filter(c, a[0], a[1]) }
        def(r, "single", "<af?>", 2) { c, a -> single(c, a[0], a.getOrNull(1)) }
        def(r, "reduce", "<afj?:j>", 3) { c, a -> foldLeft(c, a[0], a[1], a.getOrNull(2)) }
        def(r, "sift", "<o-f?:o>", 2) { c, a -> sift(c, a[0], a.getOrNull(1)) }
        def(r, "keys", "<x-:a<s>>", 1) { c, a -> keys(ctx(c), a[0]) }
        def(r, "lookup", "<x-s:x>", 2) { c, a -> Evaluator.lookup(a[0], str(a[1]) ?: "undefined", ctx(c)) }
        def(r, "append", "<xx:a>", 2) { c, a -> append(c, a[0], a[1]) }
        def(r, "exists", "<x:b>", 1) { _, a -> BooleanValue.of(a[0] != null) }
        def(r, "spread", "<x-:a<o>>", 1) { c, a -> spread(c, a[0]) }
        def(r, "merge", "<a<o>:o>", 1) { c, a -> merge(ctx(c), a[0]) }
        def(r, "reverse", "<a:a>", 1) { c, a -> reverse(ctx(c), a[0]) }
        def(r, "each", "<o-f:a>", 2) { c, a -> each(c, a[0], a[1]) }
        def(r, "error", "<s?:x>", 1) { _, a ->
            val m = str(a.getOrNull(0))
            throw JsonataException("D3137", details = mapOf("message" to (if (m.isNullOrEmpty()) "\$error() function evaluated" else m)))
        }
        def(r, "assert", "<bs?:x>", 2) { _, a ->
            if (!jsTruthy(a[0])) {
                val m = str(a.getOrNull(1))
                throw JsonataException("D3141", details = mapOf("message" to (if (m.isNullOrEmpty()) "\$assert() statement failed" else m)))
            }
            null
        }
        def(r, "type", "<x:s>", 1) { _, a -> type(a[0]) }
        def(r, "sort", "<af?:a>", 2) { c, a -> sort(c, a[0], a.getOrNull(1)) }
        def(r, "shuffle", "<a:a>", 1) { c, a -> shuffle(ctx(c), a[0]) }
        def(r, "distinct", "<x:x>", 1) { c, a -> distinct(ctx(c), a[0]) }
        def(r, "base64encode", "<s-:s>", 1) { c, a -> str(a[0])?.let { StringValue.of(Encoding.base64encode(it, ctx(c))) } }
        def(r, "base64decode", "<s-:s>", 1) { c, a -> str(a[0])?.let { StringValue.of(Encoding.base64decode(it, ctx(c))) } }
        def(r, "encodeUrlComponent", "<s-:s>", 1) { c, a -> str(a[0])?.let { StringValue.of(Encoding.encodeUri(it, true, "encodeUrlComponent", ctx(c))) } }
        def(r, "encodeUrl", "<s-:s>", 1) { c, a -> str(a[0])?.let { StringValue.of(Encoding.encodeUri(it, false, "encodeUrl", ctx(c))) } }
        def(r, "decodeUrlComponent", "<s-:s>", 1) { c, a -> str(a[0])?.let { StringValue.of(Encoding.decodeUri(it, true, "decodeUrlComponent", ctx(c))) } }
        def(r, "decodeUrl", "<s-:s>", 1) { c, a -> str(a[0])?.let { StringValue.of(Encoding.decodeUri(it, false, "decodeUrl", ctx(c))) } }
        def(r, "eval", "<sx?:x>", 2) { c, a -> eval(c, a[0], a.getOrNull(1)) }
        def(r, "clone", "<(oa)-:o>", 1) { c, a -> Evaluator.clone(a[0], ctx(c)) }
        def(r, "millis", "<:n>", 0) { c, _ -> NumberValue.of(c.timestamp.toDouble()) }
        DateTimeFunctions.register(r)
        NumberFormatting.register(r)
    }

    fun def(r: FunctionRegistry, name: String, signature: String, arity: Int, paramCount: Int = arity, impl: NativeImplementation) {
        r.register(NativeFunction(name, Signature.parse(signature), arity, impl, maxOf(paramCount, arity), source = su.seaf.archtool.jsonata.FunctionSource.STDLIB))
    }

    private fun str(v: Value?): String? = (v as? StringValue)?.value

    /** The evaluation behind a native call: its [EvalContext.tick] is the checkpoint of native loops (review RV-6). */
    private fun ctx(c: CallContext): EvalContext = (c as CallContextImpl).frame.ctx
    private fun num(v: Value?): Double? = (v as? NumberValue)?.value

    // ---- aggregation ----

    private fun sum(ctx: EvalContext, arg: Value?): Value? {
        val arr = arg as ArrayValue? ?: return null
        var total = 0.0
        for (i in 0 until arr.size) {
            ctx.tick()
            total += (arr.raw(i) as NumberValue).value
        }
        return NumberValue.of(total)
    }

    private fun extreme(ctx: EvalContext, arg: Value?, max: Boolean): Value? {
        val arr = arg as ArrayValue? ?: return null
        if (arr.size == 0) return null
        var result = if (max) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
        for (i in 0 until arr.size) {
            ctx.tick()
            val d = (arr.raw(i) as NumberValue).value
            result = if (max) Math.max(result, d) else Math.min(result, d)
        }
        return NumberValue.of(result)
    }

    private fun average(ctx: EvalContext, arg: Value?): Value? {
        val arr = arg as ArrayValue? ?: return null
        if (arr.size == 0) return null
        var total = 0.0
        for (i in 0 until arr.size) {
            ctx.tick()
            total += (arr.raw(i) as NumberValue).value
        }
        return NumberValue.of(total / arr.size)
    }

    // ---- strings ----

    /** JS `Array.prototype.slice` bounds over code points (functions.js: substring uses stringToArray). */
    private fun substring(strArg: Value?, startArg: Value?, lengthArg: Value?): Value? {
        val s = str(strArg) ?: return null
        val cps = s.codePoints().toArray()
        val strLength = cps.size
        var start = num(startArg) ?: Double.NaN
        if (strLength + start < 0) start = 0.0
        val length = num(lengthArg)
        if (length != null) {
            if (length <= 0) return StringValue.EMPTY
            val end = if (start >= 0) start + length else strLength + start + length
            return StringValue.of(sliceCodePoints(cps, start, end))
        }
        return StringValue.of(sliceCodePoints(cps, start, strLength.toDouble()))
    }

    private fun relativeIndex(x: Double, len: Int): Int {
        val r = if (x.isNaN()) 0.0 else if (x.isInfinite()) x else (if (x < 0) Math.ceil(x) else Math.floor(x))
        return if (r < 0) Math.max(len + r, 0.0).toInt() else Math.min(r, len.toDouble()).toInt()
    }

    private fun sliceCodePoints(cps: IntArray, start: Double, end: Double): String {
        val from = relativeIndex(start, cps.size)
        val to = relativeIndex(end, cps.size)
        if (from >= to) return ""
        val sb = StringBuilder()
        for (i in from until to) sb.appendCodePoint(cps[i])
        return sb.toString()
    }

    private val whitespace = Regex("[ \t\n\r]+")

    private fun trim(s: String): String {
        var result = s.replace(whitespace, " ")
        if (result.startsWith(" ")) result = result.substring(1)
        if (result.endsWith(" ")) result = result.substring(0, result.length - 1)
        return result
    }

    private fun pad(ctx: EvalContext, strArg: Value?, widthArg: Value?, charArg: Value?): Value? {
        val s = str(strArg) ?: return null
        var c = str(charArg)
        if (c == null || c.isEmpty()) c = " "
        val w = num(widthArg) ?: Double.NaN
        val width = if (w.isNaN()) 0.0 else if (w < 0) Math.ceil(w) else Math.floor(w)
        val padLength = Math.abs(width) - s.codePointCount(0, s.length)
        if (padLength <= 0) return StringValue.of(s)
        ctx.checkString(s.length + padLength.toLong() * c.length)
        var padding = c.repeat(padLength.toInt())
        if (c.length > 1) padding = sliceCodePoints(padding.codePoints().toArray(), 0.0, padLength)
        return StringValue.of(if (width > 0) s + padding else padding + s)
    }

    /** functions.js: evaluateMatcher — calls a matcher function and checks the shape of its result (T1010). */
    private fun evaluateMatcher(c: CallContext, matcher: Value?, s: Value?): Value? {
        val result = c.call(matcher as? FunctionValue ?: throw JsonataException("T1006"), listOf(s))
        if (jsTruthy(result)) {
            val o = result as? ObjectValue
            val ok = o != null && (o["start"] is NumberValue || o["groups"] is ArrayValue || o["next"] is FunctionValue)
            if (!ok) throw JsonataException("T1010")
        }
        return result
    }

    private fun contains(c: CallContext, strArg: Value?, token: Value?): Value? {
        val s = str(strArg) ?: return null
        if (token == null) return null
        if (token is StringValue) return BooleanValue.of(s.indexOf(token.value) != -1)
        if (token is RegexFunction) return BooleanValue.of(token.firstMatch(s, ctx(c)) != null)
        return BooleanValue.of(evaluateMatcher(c, token, strArg) != null)
    }

    private fun match(c: CallContext, strArg: Value?, regex: Value?, limitArg: Value?): Value? {
        if (strArg == null) return null
        val limit = num(limitArg)
        if (limit != null && limit < 0) throw JsonataException("D3040", value = limitArg, index = 3)
        val result = ArrayValue.sequence()
        if (regex is RegexFunction && strArg is StringValue) {
            // the built-in matcher without its {match, start, end, groups, next} protocol objects
            if (limit == null || limit > 0) {
                val str = strArg.value
                regex.forEachMatch(str, ctx(c), limit) { m ->
                    val item = ObjectValue()
                    item.put("match", StringValue.of(str.substring(m.start(), m.end())))
                    item.put("index", NumberValue.of(m.start()))
                    item.put("groups", regex.groups(str, m))
                    result.add(item)
                }
            }
            return result
        }
        if (limit == null || limit > 0) {
            var count = 0
            var matches = evaluateMatcher(c, regex, strArg)
            while (matches != null && (limit == null || count < limit)) {
                val m = matches as ObjectValue
                val item = ObjectValue()
                m["match"]?.let { item.put("match", it) }
                m["start"]?.let { item.put("index", it) }
                m["groups"]?.let { item.put("groups", it) }
                result.add(item)
                matches = evaluateMatcher(c, m["next"], null)
                count++
            }
        }
        return result
    }

    private fun replace(c: CallContext, strArg: Value?, pattern: Value?, replacement: Value?, limitArg: Value?): Value? {
        val s = str(strArg) ?: return null
        if (pattern is StringValue && pattern.value.isEmpty()) throw JsonataException("D3010", value = pattern, index = 2)
        val limit = num(limitArg)
        if (limit != null && limit < 0) throw JsonataException("D3011", value = limitArg, index = 4)
        val replacer: (ObjectValue) -> Value? = if (replacement is StringValue) {
            { m -> StringValue.of(substitute(replacement.value, (m["match"] as? StringValue)?.value ?: "undefined", m["groups"] as? ArrayValue)) }
        } else {
            { m -> c.call(replacement as? FunctionValue ?: throw JsonataException("T1006"), listOf(m)) }
        }
        if (limit != null && limit <= 0) return StringValue.of(s)
        val result = StringBuilder()
        var position = 0
        var count = 0
        if (pattern is StringValue) {
            val p = pattern.value
            val rep = when (replacement) {
                is StringValue -> replacement.value
                null -> "undefined"
                else -> Evaluator.string(replacement)
            }
            var index = s.indexOf(p, position)
            while (index != -1 && (limit == null || count < limit)) {
                ctx(c).tick()
                result.append(s, position, index).append(rep)
                ctx(c).checkString(result.length.toLong())
                position = index + p.length
                count++
                index = s.indexOf(p, position)
            }
            // the rest of the string counts against the budget too (review RV3-32)
            ctx(c).checkString(result.length.toLong() + (s.length - position))
            result.append(s.substring(position))
            return StringValue.of(result.toString())
        }
        if (pattern is RegexFunction && replacement is StringValue) {
            // the built-in matcher without its protocol objects (a function replacer still receives them)
            val first = pattern.firstMatch(s, ctx(c)) ?: return StringValue.of(s)
            pattern.forEachMatchFrom(first, s, ctx(c), limit) { m ->
                result.append(s, position, m.start())
                result.append(substitute(replacement.value, s.substring(m.start(), m.end()), pattern.groups(s, m)))
                ctx(c).checkString(result.length.toLong())
                position = m.end()
            }
            ctx(c).checkString(result.length.toLong() + (s.length - position))
            result.append(s, position, s.length)
            return StringValue.of(result.toString())
        }
        var matches = evaluateMatcher(c, pattern, strArg) ?: return StringValue.of(s)
        while (limit == null || count < limit) {
            val m = matches as ObjectValue
            val start = ((m["start"] as? NumberValue)?.value ?: 0.0).toInt()
            result.append(jsSubstring(s, position, start))
            val replacedWith = replacer(m)
            if (replacedWith is StringValue) result.append(replacedWith.value).also { ctx(c).checkString(result.length.toLong()) }
            else throw JsonataException("D3012", value = replacedWith)
            position = start + ((m["match"] as? StringValue)?.value?.length ?: 0)
            count++
            matches = evaluateMatcher(c, m["next"], null) ?: break
        }
        val tail = jsSubstring(s, position, s.length)
        ctx(c).checkString(result.length.toLong() + tail.length)
        result.append(tail)
        return StringValue.of(result.toString())
    }

    /** JS `String.prototype.substring` (arguments clamped and swapped). */
    private fun jsSubstring(s: String, a: Int, b: Int): String {
        val x = a.coerceIn(0, s.length)
        val y = b.coerceIn(0, s.length)
        return if (x <= y) s.substring(x, y) else s.substring(y, x)
    }

    /** The `$n` substitution of functions.js: replace (string replacement). */
    private fun substitute(replacement: String, matched: String, groups: ArrayValue?): String {
        val groupCount = groups?.size ?: 0
        val sb = StringBuilder()
        var position = 0
        var index = replacement.indexOf('$', position)
        while (index != -1 && position < replacement.length) {
            sb.append(replacement, position, index)
            position = index + 1
            val dollarVal = replacement.getOrNull(position)
            if (dollarVal == '$') {
                sb.append('$')
                position++
            } else if (dollarVal == '0') {
                sb.append(matched)
                position++
            } else {
                val maxDigits = if (groupCount == 0) 1 else Math.floor(Math.log(groupCount.toDouble()) * (1 / Math.log(10.0))).toInt() + 1
                var idx = parseIntPrefix(replacement.substring(position, minOf(position + maxDigits, replacement.length)))
                if (maxDigits > 1 && idx != null && idx > groupCount) {
                    idx = parseIntPrefix(replacement.substring(position, minOf(position + maxDigits - 1, replacement.length)))
                }
                if (idx != null) {
                    if (groupCount > 0) {
                        val sub = groups!!.getOrNull(idx - 1)
                        if (sub != null) sb.append(if (sub is StringValue) sub.value else Evaluator.string(sub))
                    }
                    position += idx.toString().length
                } else {
                    sb.append('$')
                }
            }
            index = replacement.indexOf('$', position)
        }
        sb.append(replacement.substring(minOf(position, replacement.length)))
        return sb.toString()
    }

    /** JS `parseInt(s, 10)`: leading digits (after optional sign/space), or null for NaN. */
    private fun parseIntPrefix(s: String): Int? {
        val t = s.trimStart()
        var i = 0
        var neg = false
        if (i < t.length && (t[i] == '+' || t[i] == '-')) { neg = t[i] == '-'; i++ }
        val start = i
        while (i < t.length && t[i].isDigit() && t[i] < '\u0080') i++
        if (i == start) return null
        val v = t.substring(start, i).toInt()
        return if (neg) -v else v
    }

    private fun split(c: CallContext, strArg: Value?, separator: Value?, limitArg: Value?): Value? {
        val s = str(strArg) ?: return null
        val limit = num(limitArg)
        if (limit != null && limit < 0) throw JsonataException("D3020", value = limitArg, index = 3)
        val result = ArrayValue()
        if (limit != null && limit <= 0) return result
        if (separator is StringValue) {
            val lim = if (limit == null) Long.MAX_VALUE else jsToUint32(limit)
            for (part in jsSplit(s, separator.value, ctx(c))) {
                if (result.size >= lim) break
                ctx(c).tick()
                result.add(StringValue.of(part))
            }
            return result
        }
        if (separator is RegexFunction) {
            // the built-in matcher without its protocol objects
            val first = separator.firstMatch(s, ctx(c))
            if (first == null) {
                result.add(StringValue.of(s))
                return result
            }
            var start = 0
            val count = separator.forEachMatchFrom(first, s, ctx(c), limit) { m ->
                result.add(StringValue.of(s.substring(start, m.start())))
                start = m.end()
            }
            if (limit == null || count < limit) result.add(StringValue.of(s.substring(start)))
            return result
        }
        var count = 0
        var matches = evaluateMatcher(c, separator, strArg)
        if (matches == null) {
            result.add(StringValue.of(s))
            return result
        }
        var start = 0
        while (matches != null && (limit == null || count < limit)) {
            val m = matches as ObjectValue
            val ms = ((m["start"] as? NumberValue)?.value ?: 0.0).toInt()
            result.add(StringValue.of(jsSubstring(s, start, ms)))
            start = ((m["end"] as? NumberValue)?.value ?: 0.0).toInt()
            matches = evaluateMatcher(c, m["next"], null)
            count++
        }
        if (limit == null || count < limit) result.add(StringValue.of(jsSubstring(s, start, s.length)))
        return result
    }

    private fun jsToUint32(d: Double): Long {
        if (d.isNaN() || d.isInfinite()) return 0
        val t = (if (d < 0) Math.ceil(d) else Math.floor(d))
        val m = t % 4294967296.0
        return (if (m < 0) m + 4294967296.0 else m).toLong()
    }

    /** JS `String.prototype.split(separator)` with a string separator. */
    private fun jsSplit(s: String, sep: String, ctx: EvalContext): List<String> {
        if (sep.isEmpty()) return s.map { ctx.tick(); it.toString() }
        val out = ArrayList<String>()
        var p = 0
        while (true) {
            ctx.tick()
            val q = s.indexOf(sep, p)
            if (q < 0) break
            out += s.substring(p, q)
            p = q + sep.length
        }
        out += s.substring(p)
        return out
    }

    // ---- numbers ----

    private fun formatBase(valueArg: Value?, radixArg: Value?): Value? {
        val value = num(valueArg) ?: return null
        val v = round(value, null)
        val radix = if (radixArg == null) 10.0 else round(num(radixArg) ?: Double.NaN, null)
        if (!(radix >= 2 && radix <= 36)) throw JsonataException("D3100", value = NumberValue.of(radix))
        return StringValue.of(JsNumber.toString(v, radix.toInt()))
    }

    private val decimalNumber = Regex("^-?[0-9]+(\\.[0-9]+)?([Ee][-+]?[0-9]+)?$")
    private val prefixedNumber = Regex("^(0[xX][0-9A-Fa-f]+)|(0[oO][0-7]+)|(0[bB][0-1]+)$")

    private fun number(arg: Value?): Value? = when {
        arg == null -> null
        arg is NumberValue -> arg
        arg is StringValue && decimalNumber.containsMatchIn(arg.value) && !arg.value.toDouble().isInfinite() -> NumberValue.of(arg.value.toDouble())
        arg is StringValue && prefixedNumber.containsMatchIn(arg.value) -> NumberValue.of(jsNumberOf(arg.value))
        arg is BooleanValue -> NumberValue.of(if (arg.value) 1.0 else 0.0)
        else -> throw JsonataException("D3030", value = arg, index = 1)
    }

    /** JS `Number(str)` for the forms `$number` passes on: decimal, `0x`, `0o`, `0b` literals; NaN otherwise. */
    private fun jsNumberOf(s: String): Double {
        val t = s.trim()
        if (t.length > 2 && t[0] == '0') {
            val radix = when (t[1]) {
                'x', 'X' -> 16
                'o', 'O' -> 8
                'b', 'B' -> 2
                else -> 0
            }
            if (radix != 0) {
                return try {
                    java.math.BigInteger(t.substring(2), radix).toDouble()
                } catch (_: NumberFormatException) {
                    Double.NaN
                }
            }
        }
        return JsNumber.parse(t)
    }

    /** JS `Math.round`: halves go towards +∞, -0 is kept for (-0.5, 0]. */
    private fun jsMathRound(x: Double): Double {
        if (x.isNaN() || x.isInfinite() || Math.abs(x) >= 4503599627370496.0) return x
        if (x < 0 && x >= -0.5) return -0.0
        val f = Math.floor(x)
        return if (x - f >= 0.5) f + 1 else f
    }

    /** functions.js: round — round half to even, shifting the decimal point through the string form. */
    fun round(arg: Double, precision: Double?): Double {
        var a = arg
        val shift = precision != null && precision != 0.0 && !precision.isNaN()
        if (shift) {
            val parts = JsNumber.toString(a).split('e')
            val exp = if (parts.size > 1) parts[1].toDouble() + precision!! else precision!!
            a = JsNumber.parse(parts[0] + "e" + JsNumber.toString(exp))
        }
        var result = jsMathRound(a)
        val diff = result - a
        if (Math.abs(diff) == 0.5 && Math.abs(result % 2) == 1.0) result -= 1
        if (shift) {
            val parts = JsNumber.toString(result).split('e')
            val exp = if (parts.size > 1) parts[1].toDouble() - precision!! else -precision!!
            result = JsNumber.parse(parts[0] + "e" + JsNumber.toString(exp))
        }
        if (result == 0.0) result = 0.0 // JSON has no -0
        return result
    }

    private fun power(base: Value?, exp: Value?): Value? {
        val b = num(base) ?: return null
        val e = num(exp) ?: Double.NaN
        // JS Math.pow differs from Java for |base| = 1 with an infinite or NaN exponent; StrictMath is fdlibm, as V8
        // mostly is (the last bit can still differ from V8, docs/deviations.md)
        val result = if (Math.abs(b) == 1.0 && (e.isInfinite() || e.isNaN())) Double.NaN else StrictMath.pow(b, e)
        if (result.isNaN() || result.isInfinite()) {
            throw JsonataException("D3061", value = base, index = 1, details = mapOf("exp" to exp))
        }
        return NumberValue.of(result)
    }

    /** functions.js: boolean — undefined for undefined (also for `[undefined]`). */
    fun boolean(arg: Value?): Boolean? = when (arg) {
        null -> null
        is ArrayValue -> when (arg.size) {
            0 -> false
            1 -> boolean(arg.raw(0))
            else -> (0 until arg.size).any { boolean(arg.raw(it)) == true }
        }
        is StringValue -> arg.value.isNotEmpty()
        is NumberValue -> Evaluator.isNumeric(arg) && arg.value != 0.0
        is ObjectValue -> !arg.isEmpty()
        is BooleanValue -> arg.value
        else -> false
    }

    // ---- higher-order functions ----

    /** functions.js: hofFuncArgs — value, then index and the whole structure if the function takes them. */
    private fun hofArgs(func: FunctionValue, a1: Value?, a2: Value?, a3: Value?): List<Value?> {
        val arity = func.arity
        return when {
            arity >= 3 -> listOf(a1, a2, a3)
            arity >= 2 -> listOf(a1, a2)
            else -> listOf(a1)
        }
    }

    private fun fn(v: Value?): FunctionValue = v as? FunctionValue ?: throw JsonataException("T1006")

    private fun map(c: CallContext, arrArg: Value?, funcArg: Value?): Value? {
        val arr = arrArg as ArrayValue? ?: return null
        val func = fn(funcArg)
        val result = ArrayValue.sequence()
        val ctx = ctx(c)
        for (i in 0 until arr.size) {
            ctx.tick()
            val res = c.call(func, hofArgs(func, arr.raw(i), NumberValue.of(i), arr))
            if (res != null) result.add(res)
        }
        return result
    }

    private fun filter(c: CallContext, arrArg: Value?, funcArg: Value?): Value? {
        val arr = arrArg as ArrayValue? ?: return null
        val func = fn(funcArg)
        val result = ArrayValue.sequence()
        val ctx = ctx(c)
        for (i in 0 until arr.size) {
            ctx.tick()
            val entry = arr.raw(i)
            if (boolean(c.call(func, hofArgs(func, entry, NumberValue.of(i), arr))) == true) result.addNullable(entry)
        }
        return result
    }

    private fun single(c: CallContext, arrArg: Value?, funcArg: Value?): Value? {
        val arr = arrArg as ArrayValue? ?: return null
        var found = false
        var result: Value? = null
        val ctx = ctx(c)
        for (i in 0 until arr.size) {
            ctx.tick()
            val entry = arr.raw(i)
            var positive = true
            if (funcArg != null) {
                val func = fn(funcArg)
                positive = boolean(c.call(func, hofArgs(func, entry, NumberValue.of(i), arr))) == true
            }
            if (positive) {
                if (!found) {
                    result = entry
                    found = true
                } else {
                    throw JsonataException("D3138", index = i)
                }
            }
        }
        if (!found) throw JsonataException("D3139")
        return result
    }

    private fun foldLeft(c: CallContext, seqArg: Value?, funcArg: Value?, init: Value?): Value? {
        val seq = seqArg as ArrayValue? ?: return null
        val func = fn(funcArg)
        val arity = func.arity
        if (arity < 2) throw JsonataException("D3050", index = 1)
        var result: Value?
        var index: Int
        if (init == null && seq.size > 0) {
            result = seq.raw(0)
            index = 1
        } else {
            result = init
            index = 0
        }
        val ctx = ctx(c)
        while (index < seq.size) {
            ctx.tick()
            val args = ArrayList<Value?>(4)
            args.add(result)
            args.add(seq.raw(index))
            if (arity >= 3) args.add(NumberValue.of(index))
            if (arity >= 4) args.add(seq)
            result = c.call(func, args)
            index++
        }
        return result
    }

    private fun sift(c: CallContext, arg: Value?, funcArg: Value?): Value? {
        val obj = arg as? ObjectValue ?: return null
        val func = fn(funcArg)
        val result = ObjectValue()
        val ctx = ctx(c)
        for (k in obj.keys) {
            ctx.tick()
            val entry = obj[k]
            if (boolean(c.call(func, hofArgs(func, entry, StringValue.of(k), obj))) == true) result.put(k, entry!!)
        }
        return if (result.isEmpty()) null else result
    }

    private fun each(c: CallContext, arg: Value?, funcArg: Value?): Value? {
        val result = ArrayValue.sequence()
        val obj = arg as? ObjectValue ?: return result
        val func = fn(funcArg)
        val ctx = ctx(c)
        for (k in obj.keys) {
            ctx.tick()
            val v = c.call(func, hofArgs(func, obj[k], StringValue.of(k), obj))
            if (v != null) result.add(v)
        }
        return result
    }

    private fun zip(c: CallContext, args: List<Value?>): Value {
        val ctx = ctx(c)
        val result = ArrayValue()
        if (args.isEmpty()) return result
        val length = args.minOf { if (it is ArrayValue) it.size else 0 }
        for (i in 0 until length) {
            ctx.tick()
            val tuple = ArrayValue(args.size)
            for (a in args) tuple.addNullable((a as ArrayValue).raw(i))
            result.add(tuple)
        }
        return result
    }

    // ---- arrays and objects ----

    private fun keys(ctx: EvalContext, arg: Value?): Value {
        ctx.tick()
        val result = ArrayValue.sequence()
        if (arg is ArrayValue) {
            val merged = LinkedHashSet<String>()
            for (i in 0 until arg.size) {
                val k = keys(ctx, arg.raw(i)) as ArrayValue
                for (j in 0 until k.size) merged += (k.raw(j) as StringValue).value
            }
            val ordered = ObjectValue()
            for (k in merged) ordered.put(k, NullValue)
            for (k in ordered.keys) result.add(StringValue.of(k))
        } else if (arg is ObjectValue) {
            for (k in arg.keys) result.add(StringValue.of(k))
        }
        return result
    }

    private fun append(c: CallContext, a: Value?, b: Value?): Value? {
        val result = Evaluator.append(a, b, ctx(c))
        if (result is ArrayValue && a != null && b != null) (c as CallContextImpl).frame.ctx.checkSequence(result.size)
        return result
    }

    private fun spread(c: CallContext, arg: Value?): Value? {
        var result: Value? = ArrayValue.sequence()
        if (arg is ArrayValue) {
            for (i in 0 until arg.size) {
                ctx(c).tick()
                result = append(c, result, spread(c, arg.raw(i)))
            }
        } else if (arg is ObjectValue) {
            val seq = result as ArrayValue
            for (k in arg.keys) {
                ctx(c).tick()
                seq.add(ObjectValue.of(k to arg[k]!!))
            }
        } else {
            result = arg
        }
        return result
    }

    private fun merge(ctx: EvalContext, arg: Value?): Value? {
        val arr = arg as ArrayValue? ?: return null
        val result = ObjectValue()
        for (i in 0 until arr.size) {
            ctx.tick()
            val o = arr.raw(i) as? ObjectValue ?: continue
            o.forEach { k, v ->
                ctx.tick()
                result.put(k, v)
            }
        }
        return result
    }

    private fun reverse(ctx: EvalContext, arg: Value?): Value? {
        val arr = arg as ArrayValue? ?: return null
        if (arr.size <= 1) return arr
        val result = ArrayValue(arr.size)
        for (i in arr.size - 1 downTo 0) {
            ctx.tick()
            result.addNullable(arr.raw(i))
        }
        return result
    }

    private fun type(v: Value?): Value? = when (v) {
        null -> null
        is NullValue -> StringValue.of("null")
        is NumberValue -> if (Evaluator.isNumeric(v)) StringValue.of("number") else StringValue.of("object")
        else -> StringValue.of(v.typeName)
    }

    private fun sort(c: CallContext, arrArg: Value?, comparator: Value?): Value? {
        val arr = arrArg as ArrayValue? ?: return null
        if (arr.size <= 1) return arr
        val ctx = (c as CallContextImpl).frame.ctx
        if (comparator == null) {
            if (!isArrayOf(arr) { Evaluator.isNumeric(it) } && !isArrayOf(arr) { it is StringValue }) {
                throw JsonataException("D3070", index = 1)
            }
            return Evaluator.mergeSort(arr, ctx) { a, b -> Evaluator.jsCompare(a!!, b!!) == 1 }
        }
        val func = fn(comparator)
        return Evaluator.mergeSort(arr, ctx) { a, b -> jsTruthy(c.call(func, listOf(a, b))) }
    }

    private inline fun isArrayOf(arr: ArrayValue, test: (Value?) -> Boolean): Boolean {
        for (i in 0 until arr.size) if (!test(arr.raw(i))) return false
        return true
    }

    private fun shuffle(ctx: EvalContext, arg: Value?): Value? {
        val arr = arg as ArrayValue? ?: return null
        if (arr.size <= 1) return arr
        val result = arrayOfNulls<Value>(arr.size)
        for (i in 0 until arr.size) {
            ctx.tick()
            val j = Math.floor(Math.random() * (i + 1)).toInt()
            if (i != j) result[i] = result[j]
            result[j] = arr.raw(i)
        }
        return ArrayValue(arr.size).also { out -> result.forEach { ctx.tick(); out.addNullable(it) } }
    }

    private fun distinct(ctx: EvalContext, arg: Value?): Value? {
        if (arg !is ArrayValue || arg.size <= 1) return arg
        val results = if (arg.isSequence) ArrayValue.sequence() else ArrayValue()
        // deep equality (utils.js: isDeepEqual) through hashing: equal values have equal hash codes
        val seen = HashSet<Value>()
        var seenUndefined = false
        for (i in 0 until arg.size) {
            ctx.tick()
            val v = arg.raw(i)
            if (v == null) {
                if (!seenUndefined) {
                    seenUndefined = true
                    results.addNullable(null)
                }
            } else if (v is NumberValue && v.value.isNaN()) {
                results.add(v) // NaN !== NaN
            } else if (seen.add(v)) {
                results.add(v)
            }
        }
        return results
    }

    // ---- $eval ----

    private fun eval(c: CallContext, exprArg: Value?, focus: Value?): Value? {
        val expr = str(exprArg) ?: return null
        val ctx = c as CallContextImpl
        var input = c.input
        if (focus != null) {
            input = focus
            if (input is ArrayValue && !input.isSequence) input = ArrayValue.sequence(input).also { it.outerWrapper = true }
        }
        val listener = ctx.frame.ctx.listener
        val node = try {
            if (listener == null) {
                Compiler(false).compile(JsonataParser.ast(expr))
            } else {
                // with a listener the text of $eval is a source of its own, compiled for debugging (CAP-10 p. 9)
                val id = "eval:" + (++ctx.frame.ctx.evalSources)
                val parsed = JsonataParser.parseWithRanges(expr)
                Compiler(true, parsed.ranges, id).compile(parsed.ast).also {
                    listener.source(su.seaf.archtool.jsonata.debug.SourceInfo(id, expr, su.seaf.archtool.jsonata.debug.SourceInfo.Kind.EVAL))
                }
            }
        } catch (e: JsonataException) {
            throw JsonataException("D3120", value = StringValue.of(e.message), cause = e)
        }
        try {
            return node.evaluate(input, ctx.frame)
        } catch (e: JsonataException) {
            // DEV-13: engine codes (X) pass unwrapped; jsonata-js wraps every error into D3121
            if (e.code.startsWith("X")) {
                ctx.frame.ctx.deviation(su.seaf.archtool.jsonata.deviation.Deviations.EVAL_KEEPS_ENGINE_ERRORS.id, null)
                throw e
            }
            throw JsonataException("D3121", value = StringValue.of(e.message), cause = e)
        } catch (e: RuntimeException) {
            // DEV-13: an exception of a host function passes unchanged (CAP-9 p. 4)
            ctx.frame.ctx.deviation(su.seaf.archtool.jsonata.deviation.Deviations.EVAL_KEEPS_ENGINE_ERRORS.id, null)
            throw e
        }
    }
}
