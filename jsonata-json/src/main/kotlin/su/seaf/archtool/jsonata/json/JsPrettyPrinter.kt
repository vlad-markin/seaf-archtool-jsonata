package su.seaf.archtool.jsonata.json

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.PrettyPrinter

/** The layout of `JSON.stringify(value, null, indent)`: `"key": value`, one item per line, `[]`/`{}` when empty. */
internal class JsPrettyPrinter(private val indent: Int) : PrettyPrinter {
    private var level = 0
    private val empty = ArrayList<Boolean>()

    private fun newline(gen: JsonGenerator) {
        gen.writeRaw('\n')
        repeat(level * indent) { gen.writeRaw(' ') }
    }

    override fun writeRootValueSeparator(gen: JsonGenerator) {}

    override fun writeStartObject(gen: JsonGenerator) {
        gen.writeRaw('{')
        level++
        empty += true
    }

    override fun writeEndObject(gen: JsonGenerator, nrOfEntries: Int) {
        level--
        if (!empty.removeAt(empty.size - 1)) newline(gen)
        gen.writeRaw('}')
    }

    override fun writeObjectEntrySeparator(gen: JsonGenerator) {
        gen.writeRaw(',')
        newline(gen)
    }

    override fun writeObjectFieldValueSeparator(gen: JsonGenerator) {
        gen.writeRaw(": ")
    }

    override fun writeStartArray(gen: JsonGenerator) {
        gen.writeRaw('[')
        level++
        empty += true
    }

    override fun writeEndArray(gen: JsonGenerator, nrOfValues: Int) {
        level--
        if (!empty.removeAt(empty.size - 1)) newline(gen)
        gen.writeRaw(']')
    }

    override fun writeArrayValueSeparator(gen: JsonGenerator) {
        gen.writeRaw(',')
        newline(gen)
    }

    override fun beforeArrayValues(gen: JsonGenerator) {
        empty[empty.size - 1] = false
        newline(gen)
    }

    override fun beforeObjectEntries(gen: JsonGenerator) {
        empty[empty.size - 1] = false
        newline(gen)
    }
}
