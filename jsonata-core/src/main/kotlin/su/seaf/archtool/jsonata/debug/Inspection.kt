package su.seaf.archtool.jsonata.debug

import su.seaf.archtool.jsonata.engine.Frame
import su.seaf.archtool.jsonata.engine.FrameView
import su.seaf.archtool.jsonata.value.ArrayValue
import su.seaf.archtool.jsonata.value.BooleanValue
import su.seaf.archtool.jsonata.value.FunctionValue
import su.seaf.archtool.jsonata.value.JsonWriter
import su.seaf.archtool.jsonata.value.NullValue
import su.seaf.archtool.jsonata.value.NumberValue
import su.seaf.archtool.jsonata.value.ObjectValue
import su.seaf.archtool.jsonata.value.StringValue
import su.seaf.archtool.jsonata.value.Value

/** A scope of a frame (DAP `scopes`): `Context`, `Tuple`, `Local`, `Arguments`, `Closure`, `Global`. */
public class ScopeView(public val name: String, public val variablesReference: Int, public val size: Int) {
    override fun toString(): String = "$name($size)"
}

/**
 * A variable or an item (DAP `variables`): [value] a short JSON preview (`undefined` distinct from `null`), [type]
 * `undefined`, `null`, `number`, `string`, `boolean`, `array`, `sequence`, `object` or `function`;
 * [variablesReference] > 0 when it has children ([size] of them), fetched lazily and by pages.
 */
public class VariableView(
    public val name: String,
    public val value: String,
    public val type: String,
    public val variablesReference: Int,
    public val size: Int,
) {
    override fun toString(): String = "$name=$value"
}

/** References of one stop: scopes and structured values, valid until the evaluation moves on. */
internal class References {
    private val entries = HashMap<Int, List<Pair<String, Value?>>>()
    private val lazy = HashMap<Int, Value>()
    private var next = 1

    fun clear() {
        entries.clear()
        lazy.clear()
        next = 1
    }

    fun scope(vars: List<Pair<String, Value?>>): Int = next++.also { entries[it] = vars }

    fun variables(ref: Int, start: Int, count: Int): List<VariableView> {
        val all: List<Pair<String, Value?>> = entries[ref] ?: lazy[ref]?.let { children(it) } ?: return emptyList()
        val from = start.coerceIn(0, all.size)
        val to = if (count <= 0) all.size else minOf(all.size, from + count)
        return all.subList(from, to).map { (n, v) -> view(n, v) }
    }

    private fun children(v: Value): List<Pair<String, Value?>> = when (v) {
        is ArrayValue -> (0 until v.size).map { "[$it]" to v.getOrNull(it) }
        is ObjectValue -> v.keys.map { it to v[it] }
        else -> emptyList()
    }

    fun view(name: String, v: Value?): VariableView {
        val type = when (v) {
            null -> "undefined"
            is NullValue -> "null"
            is NumberValue -> "number"
            is StringValue -> "string"
            is BooleanValue -> "boolean"
            is ArrayValue -> if (v.isSequence) "sequence" else "array"
            is ObjectValue -> "object"
            is FunctionValue -> "function"
        }
        val size = when (v) {
            is ArrayValue -> v.size
            is ObjectValue -> v.keys.size
            else -> 0
        }
        val ref = if (size > 0) next++.also { lazy[it] = v!! } else 0
        val preview = when (v) {
            null -> "undefined"
            is FunctionValue -> "function"
            else -> JsonWriter.stringify(v).let { if (it.length > 200) it.substring(0, 197) + "..." else it }
        }
        return VariableView(name, preview, type, ref, size)
    }
}

/** The scopes of a frame, innermost first (CAP-10 p. 7). */
internal fun scopesOf(view: FrameView, input: Value?, withInput: Boolean, refs: References): List<ScopeView> {
    val out = ArrayList<ScopeView>()
    val context = ArrayList<Pair<String, Value?>>()
    if (withInput) context += "\$" to input
    context += "\$\$" to view.root()
    out += ScopeView("Context", refs.scope(context), context.size)
    val tuple = ArrayList<Pair<String, Value?>>()
    val local = ArrayList<Pair<String, Value?>>()
    val seen = HashSet<String>()
    var f: Frame? = view.scope
    val function = view.frame
    while (f != null && f !== function) {
        val target = if (f.tupleFrame) tuple else local
        for ((n, v) in f.localBindings()) if (seen.add(n)) target += n to v
        f = f.parent
    }
    if (tuple.isNotEmpty()) out += ScopeView("Tuple", refs.scope(tuple), tuple.size)
    if (local.isNotEmpty()) out += ScopeView("Local", refs.scope(local), local.size)
    if (function.parent != null || function.lambdaName != null) {
        val args = function.localBindings().filter { seen.add(it.first) }
        if (function.lambdaName != null) out += ScopeView("Arguments", refs.scope(args), args.size)
        val closure = ArrayList<Pair<String, Value?>>()
        var c = function.parent
        while (c != null && c.parent != null) {
            for ((n, v) in c.localBindings()) if (seen.add(n)) closure += n to v
            c = c.parent
        }
        if (closure.isNotEmpty()) out += ScopeView("Closure", refs.scope(closure), closure.size)
        val root = generateSequence(function) { it.parent }.last()
        val global = root.localBindings().filter { it.first != "\$" && seen.add(it.first) }
        out += ScopeView("Global", refs.scope(global), global.size)
    } else {
        val global = function.localBindings().filter { it.first != "\$" && seen.add(it.first) }
        out += ScopeView("Global", refs.scope(global), global.size)
    }
    return out
}
