package su.seaf.archtool.jsonata.deviation

import su.seaf.archtool.jsonata.parser.AstNode

/**
 * A deliberate difference from jsonata-js 2.2.2, as recorded in `docs/deviations.md` (decision 17: always on).
 *
 * @property id the record id, `DEV-NN`
 * @property category the trap category of the jsonata-java report (`STR-03`…) or a short own label
 * @property since the engine version that introduced it
 * @property changesResult whether the value or error of an expression can differ (false: only limits or text)
 */
public class Deviation(
    public val id: String,
    public val category: String,
    public val since: String,
    public val changesResult: Boolean,
    public val title: String,
)

/** A static finding: the expression may be affected by [id] at [position] (end of the token, as in errors). */
public class Finding(
    public val id: String,
    public val position: Int?,
    public val recommendation: String,
) {
    override fun toString(): String = "$id@$position: $recommendation"
}

/**
 * Receives probe hits: an evaluation went through a branch where the engine acts differently from jsonata-js 2.2.2,
 * so the result there may differ. Called on the evaluating thread; must be cheap and must not throw.
 */
public fun interface DeviationSink {
    public fun hit(id: String, position: Int?)
}

/** The register of deviations and their static rules (detector, story 1). */
public object Deviations {
    private const val V010 = "0.1.0"

    public val CONSTRUCTOR_ERRORS: Deviation =
        Deviation("DEV-09", "ERR", V010, true, "Several failing values of a constructor: the engine reports the first in order")

    public val LIBRARY_FUNCTION_TOKEN: Deviation =
        Deviation("DEV-17", "ERR-POS", V010, true, "The token of a library function called through a higher-order function or ~> is its name")

    public val POSITION_IN_LAMBDA: Deviation =
        Deviation("DEV-10", "ERR-POS", V010, true, "An error inside a lambda keeps the position of the failing operator")

    public val BASE64_UTF8: Deviation =
        Deviation("DEV-11", "STR-03", V010, true, "\$base64encode/\$base64decode use UTF-8")

    public val UNDEFINED_IN_SEQUENCES: Deviation =
        Deviation("DEV-12", "UND-SEQ", V010, true, "Undefined does not enter sequences; a step after a filtered array constructor iterates its item")

    public val EVAL_KEEPS_ENGINE_ERRORS: Deviation =
        Deviation("DEV-13", "ERR-EVAL", V010, true, "\$eval does not wrap engine errors (X codes) and host exceptions into D3121")

    /** Deviations that have a probe or a static rule here, by id. */
    public val all: List<Deviation> = listOf(CONSTRUCTOR_ERRORS, POSITION_IN_LAMBDA, LIBRARY_FUNCTION_TOKEN, BASE64_UTF8, UNDEFINED_IN_SEQUENCES, EVAL_KEEPS_ENGINE_ERRORS)

    public fun byId(id: String): Deviation? = all.firstOrNull { it.id == id }

    /** Static rules over the AST of an expression (`Jsonata.ast()`): what may be affected, in source order. */
    public fun scan(ast: AstNode): List<Finding> {
        val out = ArrayList<Finding>()
        walk(ast) { node -> rules.forEach { it(node, out) } }
        return out.sortedBy { it.position ?: -1 }
    }

    private val rules: List<(AstNode, MutableList<Finding>) -> Unit> = listOf(
        { node, out ->
            if (node.type == "lambda") {
                out += Finding(
                    POSITION_IN_LAMBDA.id, node.position,
                    "a runtime error inside this function reports the position of the failing operator, " +
                        "not of the call (jsonata-js 2.2.2 reports the call)",
                )
            }
        },
        { node, out ->
            if (node.type == "function") {
                val name = (node["procedure"] as? AstNode)?.let { if (it.type == "variable") it["value"] else null }
                if (name == "base64encode" || name == "base64decode") {
                    out += Finding(
                        BASE64_UTF8.id, node.position,
                        "characters above U+007F are encoded and decoded as UTF-8 " +
                            "(jsonata-js 2.2.2 keeps only the low byte of each character)",
                    )
                }
            }
        },
        { node, out ->
            if (node.type == "path") {
                val steps = node["steps"] as? List<*>
                val first = steps?.firstOrNull() as? AstNode
                if (steps != null && steps.size > 1 && first != null && first["consarray"] == true && first["predicate"] != null) {
                    out += Finding(
                        UNDEFINED_IN_SEQUENCES.id, first.position,
                        "the steps after a filtered array constructor iterate the selected item " +
                            "(jsonata-js 2.2.2 gives undefined when the filter selects one object or number); " +
                            "parentheses give the same result on both: ([…][0]).a",
                    )
                }
            }
            val filters = ((node["stages"] as? List<*>).orEmpty() + (node["predicate"] as? List<*>).orEmpty())
                .filterIsInstance<AstNode>().filter { it.type == "filter" }
            if (filters.isNotEmpty() && node.type in UNDEFINED_PRONE && filters.any { (it["expr"] as? AstNode)?.type != "number" }) {
                out += Finding(
                    UNDEFINED_IN_SEQUENCES.id, node.position,
                    "a filter on a value that is missing for some items keeps no undefined in the result " +
                        "(jsonata-js 2.2.2 keeps it, written as null in JSON)",
                )
            }
        },
    )

    /** Step kinds whose value may be missing for an item of the path, so that a filter sees undefined. */
    private val UNDEFINED_PRONE = setOf("name", "variable", "function", "block", "condition", "lambda")

    private fun walk(root: AstNode, visit: (AstNode) -> Unit) {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
        val stack = ArrayList<Any?>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            when (val x = stack.removeAt(stack.size - 1)) {
                is AstNode -> {
                    if (!seen.add(x)) continue
                    visit(x)
                    val values = x.values.toList()
                    for (i in values.indices.reversed()) stack.add(values[i])
                }
                is List<*> -> for (i in x.indices.reversed()) stack.add(x[i])
            }
        }
    }
}
