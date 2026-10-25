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

    private fun readValue(parser: JsonParser, first: JsonToken): Value {
        scalar(parser, first)?.let { return it }
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
                else -> attach(scalar(parser, token) ?: throw JsonFormatException("unexpected token $token"))
            }
        }
        return root!!
    }

    private fun scalar(parser: JsonParser, token: JsonToken): Value? = when (token) {
        JsonToken.VALUE_STRING -> StringValue.of(parser.text)
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
