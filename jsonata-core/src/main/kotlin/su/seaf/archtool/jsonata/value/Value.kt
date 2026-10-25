package su.seaf.archtool.jsonata.value

/**
 * A JSONata value: the JSON types plus functions. JSONata `undefined` (no value) is not a [Value]: it is Kotlin `null`
 * wherever a `Value?` is expected, and it is never equal to [NullValue] (JSON `null`).
 *
 * Values that come from the input document or from bindings are never mutated by the evaluator, so a parsed document
 * can be shared between evaluations and threads. Arrays and objects created by the evaluator are mutable only while
 * they are being built.
 */
public sealed class Value {
    /** The JSONata type name, as `$type` returns it. */
    public abstract val typeName: String

    /** JSON text of this value as `JSON.stringify` writes it (no indentation). Functions are written as `""`. */
    override fun toString(): String = JsonWriter.stringify(this)
}

/** JSON `null`. */
public object NullValue : Value() {
    override val typeName: String get() = "null"
}

/** JSON `true`/`false`. */
public class BooleanValue private constructor(public val value: Boolean) : Value() {
    override val typeName: String get() = "boolean"

    public companion object {
        @JvmField public val TRUE: BooleanValue = BooleanValue(true)
        @JvmField public val FALSE: BooleanValue = BooleanValue(false)

        @JvmStatic public fun of(value: Boolean): BooleanValue = if (value) TRUE else FALSE
    }
}

/**
 * A number: a JS number, that is an IEEE 754 double. `-0.0`, NaN and infinities can appear as intermediate results
 * (as in jsonata-js); [equals] follows JS `===` (`0 == -0`, `NaN != NaN`).
 */
public class NumberValue private constructor(public val value: Double) : Value() {
    override val typeName: String get() = "number"

    /** JS `Number.isInteger`. */
    public val isInteger: Boolean get() = !value.isInfinite() && value == Math.floor(value)

    override fun equals(other: Any?): Boolean = other is NumberValue && other.value == value
    override fun hashCode(): Int = if (value == 0.0) 0 else value.hashCode()

    public companion object {
        private const val CACHE_LOW = -128
        private const val CACHE_HIGH = 1024
        private val cache = Array(CACHE_HIGH - CACHE_LOW + 1) { NumberValue((it + CACHE_LOW).toDouble()) }

        @JvmStatic public fun of(value: Double): NumberValue {
            if (value >= CACHE_LOW && value <= CACHE_HIGH) {
                val i = value.toInt()
                // -0.0 keeps its own instance: its sign is observable through JSON output and `1/x`.
                if (i.toDouble() == value && !(value == 0.0 && 1.0 / value < 0)) return cache[i - CACHE_LOW]
            }
            return NumberValue(value)
        }

        @JvmStatic public fun of(value: Int): NumberValue = of(value.toDouble())
        @JvmStatic public fun of(value: Long): NumberValue = of(value.toDouble())
    }
}

/** A string (UTF-16, as in JS). */
public class StringValue(public val value: String) : Value() {
    override val typeName: String get() = "string"

    override fun equals(other: Any?): Boolean = other is StringValue && other.value == value
    override fun hashCode(): Int = value.hashCode()

    public companion object {
        @JvmField public val EMPTY: StringValue = StringValue("")
        @JvmStatic public fun of(value: String): StringValue = if (value.isEmpty()) EMPTY else StringValue(value)
    }
}

/**
 * An array, or a JSONata sequence ([isSequence]): the result of a path, which collapses to its single item unless it
 * keeps singletons. The flags mirror the properties jsonata-js sets on JS arrays (utils.js, jsonata.js:
 * `sequence`, `keepSingleton`, `tupleStream`, `outerWrapper`, `cons`). Items are never `undefined`.
 */
public class ArrayValue(initialCapacity: Int = 4) : Value(), Iterable<Value> {
    @PublishedApi internal var items: Array<Value?> = arrayOfNulls(initialCapacity.coerceAtLeast(1))
    private var count = 0

    /** A sequence produced by evaluation rather than an array from the input or an array constructor. */
    public var isSequence: Boolean = false
        internal set
    internal var keepSingleton: Boolean = false
    internal var tupleStream: Boolean = false
    internal var outerWrapper: Boolean = false
    internal var cons: Boolean = false

    override val typeName: String get() = "array"

    public val size: Int get() = count
    public fun isEmpty(): Boolean = count == 0

    public operator fun get(index: Int): Value {
        if (index < 0 || index >= count) throw IndexOutOfBoundsException("index $index, size $count")
        return items[index]!!
    }

    /** JS `array[index]`: undefined (null) outside the bounds. */
    public fun getOrNull(index: Int): Value? = if (index in 0 until count) items[index] else null

    /** Appends an item; only for arrays under construction. */
    public fun add(value: Value) {
        if (count == items.size) items = items.copyOf(maxOf(8, count * 2))
        items[count++] = value
    }

    public fun addAll(values: ArrayValue) {
        val n = values.count
        if (count + n > items.size) items = items.copyOf(maxOf(count + n, count * 2))
        System.arraycopy(values.items, 0, items, count, n)
        count += n
    }

    internal fun set(index: Int, value: Value?) {
        items[index] = value
    }

    /**
     * Engine-internal: jsonata-js sequences can hold `undefined` (`createSequence(undefined)`, a filter that keeps an
     * undefined context). Such items exist only inside evaluation; they collapse away or are written as JSON null.
     */
    internal fun addNullable(value: Value?) {
        if (count == items.size) items = items.copyOf(maxOf(8, count * 2))
        items[count++] = value
    }

    /** Engine-internal read that tolerates undefined items. */
    internal fun raw(index: Int): Value? = items[index]

    override fun iterator(): Iterator<Value> = object : Iterator<Value> {
        var i = 0
        override fun hasNext(): Boolean = i < count
        override fun next(): Value = if (i < count) items[i++]!! else throw NoSuchElementException()
    }

    public fun toList(): List<Value> = List(count) { items[it]!! }

    /** Deep equality as jsonata-js `isDeepEqual` (arrays and sequences compare alike). */
    override fun equals(other: Any?): Boolean = other is Value && Values.deepEquals(this, other)
    override fun hashCode(): Int {
        var h = 1
        for (i in 0 until count) h = 31 * h + (items[i]?.hashCode() ?: 0)
        return h
    }

    public companion object {
        @JvmStatic public fun of(vararg values: Value): ArrayValue = ArrayValue(values.size).also { a -> values.forEach { a.add(it) } }

        @JvmStatic public fun of(values: Collection<Value>): ArrayValue = ArrayValue(values.size).also { a -> values.forEach { a.add(it) } }

        /** jsonata.js: createSequence. */
        @JvmStatic public fun sequence(): ArrayValue = ArrayValue().also { it.isSequence = true }

        @JvmStatic public fun sequence(value: Value): ArrayValue = ArrayValue(1).also { it.isSequence = true; it.add(value) }

        /** `createSequence(value)` where value may be undefined. */
        internal fun sequenceOf(value: Value?): ArrayValue = ArrayValue(1).also { it.isSequence = true; it.addNullable(value) }
    }
}

/**
 * An object. Key order is the order of a JS object, which is what jsonata-js produces everywhere (`$keys`, `$each`,
 * `$string`, output): keys that are array indices (`"0"`, `"17"`, canonical integers below 2^32-1) come first in
 * ascending numeric order, the other keys follow in insertion order. Re-assigning a key keeps its position.
 */
public class ObjectValue : Value() {
    private val map = LinkedHashMap<String, Value>()
    private var indexKeys = 0
    private var orderedKeys: List<String>? = null

    override val typeName: String get() = "object"

    public val size: Int get() = map.size
    public fun isEmpty(): Boolean = map.isEmpty()

    public operator fun get(key: String): Value? = map[key]
    public fun containsKey(key: String): Boolean = map.containsKey(key)

    /** Sets a key; only for objects under construction (and for clones being transformed). */
    public fun put(key: String, value: Value) {
        if (map.put(key, value) == null) {
            if (isArrayIndex(key)) indexKeys++
            orderedKeys = null
        }
    }

    internal fun remove(key: String) {
        if (map.remove(key) != null) {
            if (isArrayIndex(key)) indexKeys--
            orderedKeys = null
        }
    }

    /** Keys in JS order (`Object.keys`). */
    public val keys: List<String>
        get() {
            orderedKeys?.let { return it }
            val result = if (indexKeys == 0) {
                ArrayList(map.keys)
            } else {
                val idx = map.keys.filter { isArrayIndex(it) }.sortedBy { it.toLong() }
                idx + map.keys.filter { !isArrayIndex(it) }
            }
            orderedKeys = result
            return result
        }

    /** Entries in JS key order. */
    public val entries: List<Pair<String, Value>> get() = keys.map { it to map[it]!! }

    public inline fun forEach(action: (String, Value) -> Unit) {
        for (k in keys) action(k, get(k)!!)
    }

    override fun equals(other: Any?): Boolean = other is Value && Values.deepEquals(this, other)
    override fun hashCode(): Int = map.hashCode()

    public companion object {
        /** A JS array index: canonical decimal integer in [0, 2^32 - 2]. */
        @JvmStatic public fun isArrayIndex(key: String): Boolean {
            val n = key.length
            if (n == 0 || n > 10) return false
            val c0 = key[0]
            if (c0 == '0') return n == 1
            for (c in key) if (c !in '0'..'9') return false
            return n < 10 || key.toLong() <= 4294967294L
        }

        @JvmStatic public fun of(vararg entries: Pair<String, Value>): ObjectValue =
            ObjectValue().also { o -> entries.forEach { (k, v) -> o.put(k, v) } }
    }
}

/**
 * A function value: a lambda, a native function, a partially applied function, a regex matcher. The evaluator
 * provides the implementations; [arity] is what jsonata-js reports through `getFunctionArity` and decides how many
 * arguments higher-order functions pass.
 */
public abstract class FunctionValue : Value() {
    override val typeName: String get() = "function"
    public abstract val arity: Int
}
