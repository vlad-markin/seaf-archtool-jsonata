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

    public val TRANSFORM_INPUT: Deviation =
        Deviation("DEV-01", "DATA", V010, true, "A transform never modifies the input document")

    public val STACK_DEPTH: Deviation =
        Deviation("DEV-02", "ERR", V010, true, "Recursion depth: X0002/X0005 where jsonata-js fails with a JS RangeError")

    public val PARTIAL_SIGNATURE: Deviation =
        Deviation("DEV-03", "PART-01", V010, true, "A partially applied native function checks and coerces its arguments by the signature")

    public val REGEX_SYNTAX: Deviation =
        Deviation("DEV-04", "RE-01", V010, true, "Regex syntax that JS rejects fails with X0003")

    public val REGEX_PRIVATE_USE: Deviation =
        Deviation("DEV-05", "RE-02", V010, true, "UTF-16 units in regex are emulated with private use characters U+E000-U+E7FF")

    public val REGEX_CASE_FOLDING: Deviation =
        Deviation("DEV-06", "RE-03", V010, true, "Case folding with the i flag and back references to groups not captured yet follow java.util.regex")

    public val TO_MILLIS_UTC: Deviation =
        Deviation("DEV-07", "DT-06", V010, true, "\$toMillis of a date-time without an offset is UTC")

    public val EMPTY_ARRAY_GROUP: Deviation =
        Deviation("DEV-08", "DATA", V010, true, "An object constructor over an empty array does not push undefined into it")

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
    public val all: List<Deviation> = listOf(
        TRANSFORM_INPUT, STACK_DEPTH, PARTIAL_SIGNATURE, REGEX_SYNTAX, REGEX_PRIVATE_USE, REGEX_CASE_FOLDING, TO_MILLIS_UTC, EMPTY_ARRAY_GROUP,
        CONSTRUCTOR_ERRORS, POSITION_IN_LAMBDA, LIBRARY_FUNCTION_TOKEN, BASE64_UTF8, UNDEFINED_IN_SEQUENCES, EVAL_KEEPS_ENGINE_ERRORS)

    public fun byId(id: String): Deviation? = all.firstOrNull { it.id == id }

    /** Static rules over the AST of an expression (`Jsonata.ast()`): what may be affected, in source order. */
    public fun scan(ast: AstNode): List<Finding> {
        val out = ArrayList<Finding>()
        walk(ast) { node -> rules.forEach { it(node, out) } }
        return out.sortedBy { it.position ?: -1 }
    }

    private val rules: List<(AstNode, MutableList<Finding>) -> Unit> = listOf(
        { node, out ->
            if (node.type == "transform" && refersToInputOrVariable(node["pattern"])) {
                out += Finding(TRANSFORM_INPUT.id, node.position, "the transform pattern refers to \$\$ or a variable: jsonata-js may update the input document in place, the engine updates only the copy")
            }
        },
        { node, out ->
            if (node.type == "partial" && (node["procedure"] as? AstNode)?.type == "variable") {
                out += Finding(PARTIAL_SIGNATURE.id, node.position, "a partially applied function: if it is a native one, the engine checks and coerces its arguments by the signature (jsonata-js does not)")
            }
        },
        { node, out ->
            val re = node.value as? su.seaf.archtool.jsonata.parser.JsRegex
            if (node.type == "regex" && re != null) {
                if (JAVA_ONLY.containsMatchIn(re.pattern)) {
                    out += Finding(REGEX_SYNTAX.id, node.position, "a regex construct of java.util.regex that JS rejects: the engine fails with X0003")
                }
                if (re.pattern.any { it in '\uE000'..'\uE7FF' }) {
                    out += Finding(REGEX_PRIVATE_USE.id, node.position, "private use characters U+E000-U+E7FF in a regex: they also match lone surrogates (emulation of UTF-16 units)")
                }
                if ('i' in re.flags && FOLDING.containsMatchIn(re.pattern) || backReferenceBeforeGroup(re.pattern)) {
                    out += Finding(REGEX_CASE_FOLDING.id, node.position, "case folding with the i flag (s, k, i, ß) or a back reference before its group: java.util.regex rules")
                }
            }
        },
        { node, out ->
            if (node.type == "function") {
                val name = (node["procedure"] as? AstNode)?.let { if (it.type == "variable") it["value"] else null }
                if (name == "toMillis" && (node["arguments"] as? List<*>)?.size == 1) {
                    out += Finding(TO_MILLIS_UTC.id, node.position, "\$toMillis of a date-time without an offset is UTC in the engine, local time of the Node process in jsonata-js")
                }
            }
        },
        { node, out ->
            if (node["group"] != null) {
                out += Finding(EMPTY_ARRAY_GROUP.id, node.position, "an object constructor over a path: on an empty array jsonata-js also pushes undefined into that array, the engine does not")
            }
        },
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

    private val JAVA_ONLY = Regex("[*+?}]\\+|\\(\\?[a-zA-Z>]")
    private val FOLDING = Regex("[skiSKIß]")

    /** A back reference `\N` left of the opening of group N. */
    private fun backReferenceBeforeGroup(p: String): Boolean {
        var groups = 0
        var i = 0
        var inClass = false
        while (i < p.length) {
            val c = p[i]
            when {
                c == '\\' && i + 1 < p.length -> {
                    val d = p[i + 1]
                    if (!inClass && d in '1'..'9' && (d - '0') > groups && countGroups(p) >= (d - '0')) return true
                    i++
                }
                inClass -> if (c == ']') inClass = false
                c == '[' -> inClass = true
                c == '(' && p.getOrNull(i + 1) != '?' -> groups++
                c == '(' && p.startsWith("(?<", i) && p.getOrNull(i + 3) != '=' && p.getOrNull(i + 3) != '!' -> groups++
            }
            i++
        }
        return false
    }

    private fun countGroups(p: String): Int = Regex("\\((?!\\?)|\\(\\?<(?![=!])").findAll(p).count()

    private fun refersToInputOrVariable(pattern: Any?): Boolean {
        var found = false
        walk(pattern as? AstNode ?: return false) { n -> if (n.type == "variable" && n["value"] != "") found = true } // `$` is the context
        return found
    }

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
