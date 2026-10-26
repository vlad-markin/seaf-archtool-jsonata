package su.seaf.archtool.jsonata.value

/** Helpers over [Value] with jsonata-js semantics. */
public object Values {
    /** utils.js: isDeepEqual. Undefined equals only undefined; numbers compare as JS `===`; object key order is ignored. */
    @JvmStatic
    public fun deepEquals(lhs: Value?, rhs: Value?): Boolean = deepEquals(lhs, rhs, null)

    /** [deepEquals] calling [poll] for every pair compared (the checkpoint of an evaluation, review RV3-27). */
    internal fun deepEquals(lhs: Value?, rhs: Value?, poll: (() -> Unit)?): Boolean {
        // pairs still to compare; no recursion, so nesting depth is limited only by memory
        val left = ArrayList<Value?>()
        val right = ArrayList<Value?>()
        left += lhs
        right += rhs
        while (left.isNotEmpty()) {
            poll?.invoke()
            val a = left.removeAt(left.size - 1)
            val b = right.removeAt(right.size - 1)
            if (a === b) continue
            if (a == null || b == null) return false
            when (a) {
                is NumberValue -> if (!(b is NumberValue && a.value == b.value)) return false
                is StringValue -> if (!(b is StringValue && a.value == b.value)) return false
                is ArrayValue -> {
                    if (b !is ArrayValue || a.size != b.size) return false
                    for (i in a.size - 1 downTo 0) {
                        poll?.invoke()
                        left += a.getOrNull(i)
                        right += b.getOrNull(i)
                    }
                }
                is ObjectValue -> {
                    if (b !is ObjectValue || a.size != b.size) return false
                    val keys = a.keys
                    for (i in keys.size - 1 downTo 0) {
                        poll?.invoke()
                        val r = b[keys[i]] ?: return false
                        left += a[keys[i]]
                        right += r
                    }
                }
                // booleans and null are singletons; functions compare by identity
                else -> return false
            }
        }
        return true
    }

    /** functions.js: boolean — the effective boolean value; undefined for undefined. */
    @JvmStatic
    public fun toBoolean(arg: Value?): Boolean? {
        if (arg == null) return null
        return effectiveBoolean(arg)
    }

    internal fun effectiveBoolean(arg: Value): Boolean = when (arg) {
        is ArrayValue -> when (arg.size) {
            0 -> false
            1 -> arg.getOrNull(0)?.let { effectiveBoolean(it) } ?: false
            else -> (0 until arg.size).any { i -> arg.getOrNull(i)?.let { effectiveBoolean(it) } ?: false }
        }
        is StringValue -> arg.value.isNotEmpty()
        is NumberValue -> {
            val d = arg.value
            if (d.isInfinite()) throw su.seaf.archtool.jsonata.parser.JsonataException("D1001", value = arg)
            !d.isNaN() && d != 0.0
        }
        is ObjectValue -> !arg.isEmpty()
        is BooleanValue -> arg.value
        is NullValue -> false
        is FunctionValue -> false
    }

    /** A number value for a Kotlin double. */
    @JvmStatic public fun number(d: Double): NumberValue = NumberValue.of(d)

    @JvmStatic public fun string(s: String): StringValue = StringValue.of(s)

    @JvmStatic public fun bool(b: Boolean): BooleanValue = BooleanValue.of(b)
}
