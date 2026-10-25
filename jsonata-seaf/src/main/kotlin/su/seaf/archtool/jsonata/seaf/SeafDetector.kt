package su.seaf.archtool.jsonata.seaf

import su.seaf.archtool.jsonata.deviation.Finding
import su.seaf.archtool.jsonata.parser.AstNode
import su.seaf.archtool.jsonata.seaf.core.CoreFunctions
import su.seaf.archtool.jsonata.seaf.manifest.ManifestFunctions
import su.seaf.archtool.jsonata.seaf.manifest.NameConflictReport
import su.seaf.archtool.jsonata.value.Value

/**
 * Checks of SEAF expressions and manifests without loading them and without evaluating anything (CAP-7 p. 3a, CAP-15):
 * what would fail when the installation flag is on, and where the engine may give another result than the core.
 * Findings carry the id of the DEV record or the error code that explains them.
 */
public object SeafDetector {
    /** The same list of conflicts `SeafEnvironment.create` fails with (X0014), in manifest order. */
    @JvmStatic
    public fun manifestConflicts(manifestFunctions: Value?): List<NameConflictReport> = ManifestFunctions.conflicts(ManifestFunctions.parse(manifestFunctions))

    /**
     * Manifest functions whose `params` or `result` schema fails to compile as ajv of the core (X0017 at the first
     * call): the finding's id is `X0017`, its recommendation names the function and the reason.
     */
    @JvmStatic
    public fun invalidDeclarations(manifestFunctions: Value?): List<Finding> =
        ManifestFunctions.invalidDeclarations(ManifestFunctions.parse(manifestFunctions)).map { (f, reason) ->
            Finding("X0017", null, "manifest function ${f.id}: the $reason")
        }

    /** Calls of `$set`/`$get`: the store lives for one evaluation in the engine (DEV-15). */
    @JvmStatic
    public fun storeCalls(ast: AstNode): List<Finding> = calls(ast).filter { it.second in STORE }.map { (node, name) ->
        Finding("DEV-15", node.position, "\$$name: the store of \$set/\$get lives for one evaluation in the engine; in the core it outlives evaluations of a request")
    }

    /**
     * All SEAF rules for one expression: store calls (DEV-15), `$jsonschema` and calls of manifest functions with
     * `params`/`result` (texts of violations, DEV-14), and calls of core functions the engine does not have yet.
     */
    @JvmStatic
    @JvmOverloads
    public fun scan(ast: AstNode, manifestFunctions: Value? = null): List<Finding> {
        val checked = ManifestFunctions.parse(manifestFunctions).filter { it.params != null || it.result != null }.map { it.id }.toSet()
        val out = ArrayList<Finding>(storeCalls(ast))
        for ((node, name) in calls(ast)) {
            when {
                name == "jsonschema" || name in checked -> out += Finding(
                    "DEV-14", node.position,
                    "\$$name: violation messages of JSON Schema are networknt texts, the core's are ajv texts; the violations themselves are the same",
                )
                name in CoreFunctions.ALL_NAMES && name !in CoreFunctions.NAMES -> out += Finding(
                    "T1006", node.position,
                    "\$$name: this core function is not available in the engine yet (docs/seaf-functions.md)",
                )
            }
        }
        return out.sortedBy { it.position ?: -1 }
    }

    private val STORE = setOf("set", "get")

    /** Function calls `$name(...)` with the called name, in any order. */
    private fun calls(ast: AstNode): List<Pair<AstNode, String>> {
        val out = ArrayList<Pair<AstNode, String>>()
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
        val stack = ArrayList<Any?>()
        stack.add(ast)
        while (stack.isNotEmpty()) {
            when (val x = stack.removeAt(stack.size - 1)) {
                is AstNode -> {
                    if (!seen.add(x)) continue
                    if (x.type == "function" || x.type == "partial") {
                        val proc = x["procedure"] as? AstNode
                        if (proc?.type == "variable") (proc["value"] as? String)?.let { out += x to it }
                    }
                    for (v in x.values) stack.add(v)
                }
                is List<*> -> for (v in x) stack.add(v)
            }
        }
        return out
    }
}
