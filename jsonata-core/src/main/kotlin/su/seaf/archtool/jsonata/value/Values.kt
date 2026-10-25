package su.seaf.archtool.jsonata.value

/** Helpers over [Value] with jsonata-js semantics. */
public object Values {
    /** utils.js: isDeepEqual. Undefined equals only undefined; numbers compare as JS `===`; object key order is ignored. */
    @JvmStatic
    public fun deepEquals(lhs: Value?, rhs: Value?): Boolean {
        if (lhs === rhs) return true
        if (lhs == null || rhs == null) return false
        return when (lhs) {
            is NumberValue -> rhs is NumberValue && lhs.value == rhs.value
            is StringValue -> rhs is StringValue && lhs.value == rhs.value
            is ArrayValue -> {
                if (rhs !is ArrayValue || lhs.size != rhs.size) return false
                for (i in 0 until lhs.size) if (!deepEquals(lhs.getOrNull(i), rhs.getOrNull(i))) return false
                true
            }
            is ObjectValue -> {
                if (rhs !is ObjectValue || lhs.size != rhs.size) return false
                for (k in lhs.keys) {
                    val r = rhs[k] ?: return false
                    if (!deepEquals(lhs[k], r)) return false
                }
                true
            }
            // booleans and null are singletons; functions compare by identity
            else -> false
        }
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
