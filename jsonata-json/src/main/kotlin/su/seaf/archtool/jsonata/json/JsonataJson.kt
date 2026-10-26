package su.seaf.archtool.jsonata.json

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamWriteConstraints
import su.seaf.archtool.jsonata.value.ArrayValue
import su.seaf.archtool.jsonata.value.BooleanValue
import su.seaf.archtool.jsonata.value.FunctionValue
import su.seaf.archtool.jsonata.value.JsNumber
import su.seaf.archtool.jsonata.value.NullValue
import su.seaf.archtool.jsonata.value.NumberValue
import su.seaf.archtool.jsonata.value.ObjectValue
import su.seaf.archtool.jsonata.value.StringValue
import su.seaf.archtool.jsonata.value.Value
import java.io.InputStream
import java.io.Reader
import java.io.StringWriter
import java.io.Writer

/**
 * Streaming JSON text ↔ [Value] with `JSON.parse`/`JSON.stringify` semantics: numbers are doubles (correctly rounded,
 * `-0` kept), object keys get JS order (see [ObjectValue]), a duplicate key keeps its first position and its last value,
 * and a missing field stays missing (never `null`). Reading and writing do not recurse, so nesting depth is limited
 * only by memory.
 */
public object JsonataJson {
    private val factory: JsonFactory = JsonFactory.builder()
        .streamReadConstraints(
            StreamReadConstraints.builder()
                .maxNestingDepth(Int.MAX_VALUE)
                .maxNumberLength(Int.MAX_VALUE)
                .maxStringLength(Int.MAX_VALUE)
                .maxNameLength(Int.MAX_VALUE)
                .maxDocumentLength(-1)
                .build(),
        )
        .streamWriteConstraints(StreamWriteConstraints.builder().maxNestingDepth(Int.MAX_VALUE).build())
        .build()

    /** Parses one JSON value; trailing content other than whitespace is an error. */
    @JvmStatic
    public fun parse(text: String): Value = factory.createParser(text).use { read(it) }

    @JvmStatic
    public fun parse(reader: Reader): Value = factory.createParser(reader).use { read(it) }

    @JvmStatic
    public fun parse(input: InputStream): Value = factory.createParser(input).use { read(it) }

    /** Reads the next value from [parser], which must be positioned before a value token. */
    @JvmStatic
    public fun read(parser: JsonParser): Value {
        val first = parser.nextToken() ?: throw JsonFormatException("empty document")
        val result = readValue(parser, first)
        if (parser.nextToken() != null) throw JsonFormatException("unexpected content after the JSON value at ${parser.currentLocation()}")
        return result
    }

    /**
     * Short string values of one document share one [StringValue] (step 6.3): architecture documents repeat types,
     * technologies, tags, owners and references many times. Values are immutable and their identity is not observable
     * in JSONata, so this changes only memory. Bounded, so that a document of unique strings costs a fixed table.
     */
    private class StringPool {
        // open addressing over the characters of the parser: a repeated value allocates nothing. The table is created
        // on the first string and doubles at half load up to TABLE_SIZE, so that a small document costs a small table
        // (review RV3-20)
        private var table: Array<StringValue?>? = null
        private var size = 0

        fun of(parser: JsonParser): StringValue {
            val len = parser.textLength
            if (len > MAX_POOLED_LENGTH || len == 0) return StringValue.of(parser.text)
            val chars = parser.textCharacters
            val off = parser.textOffset
            var h = len
            for (i in off until off + len) h = 31 * h + chars[i].code
            val t = table ?: arrayOfNulls<StringValue>(INITIAL_SIZE).also { table = it }
            val mask = t.size - 1
            var slot = mix(h) and mask
            while (true) {
                val v = t[slot] ?: break
                val s = v.value
                if (s.length == len && same(s, chars, off, len)) return v
                slot = (slot + 1) and mask
            }
            val v = StringValue.of(String(chars, off, len))
            if (size < MAX_POOLED) {
                t[slot] = v
                size++
                if (size * 2 > t.size && t.size < TABLE_SIZE) grow(t)
            }
            return v
        }

        private fun grow(old: Array<StringValue?>) {
            val t = arrayOfNulls<StringValue>(old.size * 2)
            val mask = t.size - 1
            for (v in old) {
                if (v == null) continue
                var slot = mix(hash(v.value)) and mask
                while (t[slot] != null) slot = (slot + 1) and mask
                t[slot] = v
            }
            table = t
        }

        private fun hash(s: String): Int {
            var h = s.length
            for (c in s) h = 31 * h + c.code
            return h
        }

        private fun mix(h: Int): Int = h xor (h ushr 16)

        private fun same(t: String, chars: CharArray, off: Int, len: Int): Boolean {
            for (i in 0 until len) if (t[i] != chars[off + i]) return false
            return true
        }
    }

    /** Largest table of the pool: twice the most pooled values, so that probing stays short. */
    private const val TABLE_SIZE = 1 shl 17
    private const val INITIAL_SIZE = 64
    private const val MAX_POOLED_LENGTH = 32
    private const val MAX_POOLED = 1 shl 16

    private fun readValue(parser: JsonParser, first: JsonToken): Value {
        scalar(parser, first, null)?.let { return it }
        val strings = StringPool()
        // containers: explicit stack of (container, pending key)
        val stack = ArrayList<Any>()
        val keys = ArrayList<String?>()
        var root: Value? = null
        fun open(token: JsonToken): Value = if (token == JsonToken.START_ARRAY) ArrayValue() else ObjectValue()
        fun attach(value: Value) {
            if (stack.isEmpty()) {
                root = value
                return
            }
            when (val top = stack[stack.size - 1]) {
                is ArrayValue -> top.add(value)
                is ObjectValue -> top.put(keys[keys.size - 1]!!, value)
            }
        }
        stack += open(first)
        keys += null
        while (stack.isNotEmpty()) {
            val token = parser.nextToken() ?: throw JsonFormatException("unexpected end of input")
            when (token) {
                JsonToken.END_ARRAY, JsonToken.END_OBJECT -> {
                    val done = stack.removeAt(stack.size - 1) as Value
                    keys.removeAt(keys.size - 1)
                    attach(done)
                }
                JsonToken.FIELD_NAME -> keys[keys.size - 1] = parser.currentName()
                JsonToken.START_ARRAY, JsonToken.START_OBJECT -> {
                    stack += open(token)
                    keys += null
                }
                else -> attach(scalar(parser, token, strings) ?: throw JsonFormatException("unexpected token $token"))
            }
        }
        return root!!
    }

    private fun scalar(parser: JsonParser, token: JsonToken, strings: StringPool?): Value? = when (token) {
        JsonToken.VALUE_STRING -> if (strings != null) strings.of(parser) else StringValue.of(parser.text)
        JsonToken.VALUE_NUMBER_INT, JsonToken.VALUE_NUMBER_FLOAT -> NumberValue.of(parser.text.toDouble())
        JsonToken.VALUE_TRUE -> BooleanValue.TRUE
        JsonToken.VALUE_FALSE -> BooleanValue.FALSE
        JsonToken.VALUE_NULL -> NullValue
        else -> null
    }

    /** `JSON.stringify(value, null, indent)`; undefined (null) is written as nothing and returns "undefined". */
    @JvmStatic
    public fun stringify(value: Value?, indent: Int = 0): String {
        if (value == null) return "undefined"
        val out = StringWriter()
        write(value, out, indent)
        return out.toString()
    }

    /**
     * Writes [value] as JSON text. Numbers use JS formatting (`1e+21`, `0.1`), NaN and infinities become `null`,
     * functions are skipped in objects and written as `null` elsewhere.
     */
    @JvmStatic
    public fun write(value: Value, out: Writer, indent: Int = 0) {
        val gen = factory.createGenerator(out)
        if (indent > 0) gen.prettyPrinter = JsPrettyPrinter(indent)
        gen.use { writeValue(it, value) }
    }

    private class Frame(val items: Iterator<Any>, val isObject: Boolean)

    private fun writeValue(gen: JsonGenerator, root: Value) {
        val stack = ArrayList<Frame>()
        fun emit(v: Value) {
            when (v) {
                is NullValue -> gen.writeNull()
                is BooleanValue -> gen.writeBoolean(v.value)
                is NumberValue -> if (v.value.isNaN() || v.value.isInfinite()) gen.writeNull() else gen.writeNumber(JsNumber.toString(v.value))
                is StringValue -> gen.writeString(v.value)
                is FunctionValue -> gen.writeNull()
                is ArrayValue -> { gen.writeStartArray(); stack += Frame(v.iterator(), false) }
                is ObjectValue -> { gen.writeStartObject(); stack += Frame(v.entries.iterator(), true) }
            }
        }
        emit(root)
        while (stack.isNotEmpty()) {
            val top = stack[stack.size - 1]
            if (!top.items.hasNext()) {
                stack.removeAt(stack.size - 1)
                if (top.isObject) gen.writeEndObject() else gen.writeEndArray()
                continue
            }
            val next = top.items.next()
            if (top.isObject) {
                val entry = next as Pair<*, *>
                if (entry.second is FunctionValue) continue
                gen.writeFieldName(entry.first as String)
                emit(entry.second as Value)
            } else {
                emit(next as Value)
            }
        }
    }
}

/** Malformed JSON input. */
public class JsonFormatException(message: String) : RuntimeException(message)
