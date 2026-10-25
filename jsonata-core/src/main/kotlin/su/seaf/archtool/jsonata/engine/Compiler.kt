package su.seaf.archtool.jsonata.engine

import su.seaf.archtool.jsonata.parser.AstNode
import su.seaf.archtool.jsonata.parser.JsRegex
import su.seaf.archtool.jsonata.parser.JsonataException
import su.seaf.archtool.jsonata.parser.SourceRanges
import su.seaf.archtool.jsonata.parser.Signature
import su.seaf.archtool.jsonata.value.BooleanValue
import su.seaf.archtool.jsonata.value.NullValue
import su.seaf.archtool.jsonata.value.NumberValue
import su.seaf.archtool.jsonata.value.StringValue

/**
 * Compiles the processed AST (the jsonata-js `ast()` shape) into a tree of [Node]s, once per expression. With [debug]
 * every node reports to the evaluation's debug hook (the tree is otherwise identical); without it no node does.
 */
internal class Compiler(
    private val debug: Boolean,
    /** Source ranges of the AST nodes (null: nodes get no range, e.g. internal helper expressions). */
    private val ranges: SourceRanges? = null,
    /** Source of the expression for the debugger (`main`, `eval:N`, a manifest function). */
    private val sourceId: String = "main",
) {
    private var nextId = 0
    private val parents = ArrayList<Int>()

    /** NodeInfo of every node of the debugging tree, by id. */
    val infos: MutableList<su.seaf.archtool.jsonata.debug.NodeInfo> = ArrayList()

    /**
     * True when the expression binds a lambda that refers to its own name (`$f := function(){ ... $f(...) }`): such
     * an expression may recurse deeper than the caller's first stack segment, so it starts on a segment thread
     * (condition U1). A hint only: shadowing is ignored, and recursion without one is found at run time.
     */
    var recursive: Boolean = false
        private set

    fun compile(ast: AstNode): Node {
        if (!debug) return compileNode(ast)
        // the debugging tree: every node gets its NodeInfo (id in pre-order, parent, range, stop kind)
        val id = nextId++
        val parent = parents.lastOrNull() ?: -1
        parents.add(id)
        val node = try {
            compileNode(ast)
        } finally {
            parents.removeAt(parents.size - 1)
        }
        val value = ast["value"]?.let { if (it is Double) su.seaf.archtool.jsonata.value.JsNumber.toString(it) else it.toString() }
        // a tail-call thunk only wraps the call: the evaluator does not stop on it
        val executable = !(ast.type == "lambda" && ast["thunk"] == true)
        val info = su.seaf.archtool.jsonata.debug.NodeInfo(
            id, ast.type ?: "?", value, sourceId, node.start, node.end, executable,
            literal = node is LiteralNode, operatorStop = ast.type in OPERATOR_STOPS, parentId = parent,
        )
        node.info = info
        while (infos.size <= id) infos.add(info)
        infos[id] = info
        return node
    }

    private fun compileNode(ast: AstNode): Node {
        val node = when (val type = ast.type) {
            "path" -> compilePath(ast)
            "binary" -> BinaryNode(ast, BinaryOp.of(ast["value"] as String), compile(ast.node("lhs")!!), compile(ast.node("rhs")!!))
            "unary" -> compileUnary(ast)
            // interned: document keys from Jackson are interned too, so lookups usually match by identity
            "name" -> NameNode(ast, (ast["value"] as String).intern())
            "string" -> LiteralNode(ast, StringValue.of(ast["value"] as String))
            "number" -> LiteralNode(ast, NumberValue.of((ast["value"] as Number).toDouble()))
            "value" -> LiteralNode(
                ast,
                when (val v = ast["value"]) {
                    null -> NullValue
                    true -> BooleanValue.TRUE
                    false -> BooleanValue.FALSE
                    else -> throw JsonataException("S0206", ast.position, v)
                },
            )
            "wildcard" -> WildcardNode(ast)
            "descendant" -> DescendantNode(ast)
            "parent" -> ParentNode(ast, (ast.node("slot")!!)["label"] as String)
            "condition" -> ConditionNode(ast, compile(ast.node("condition")!!), compile(ast.node("then")!!), ast.node("else")?.let { compile(it) })
            "block" -> BlockNode(ast, nodes(ast, "expressions"))
            "bind" -> {
                val name = (ast.node("lhs")!!)["value"] as String
                val rhs = ast.node("rhs")!!
                if (!recursive && rhs.type == "lambda" && refersTo(rhs, name)) recursive = true
                BindNode(ast, name, compile(rhs))
            }
            // the parser copies the left side of `?:`/`??` through JSON (parser.js), which turns a regex into `{}`;
            // jsonata-js then builds `new RegExp({})`, i.e. /[object Object]/ (only tested for truthiness there)
            "regex" -> RegexNode(ast, JsRegexPattern.of(ast["value"] as? JsRegex ?: JsRegex("[object Object]", "")))
            "function" -> {
                val procedure = ast.node("procedure")!!
                FunctionCallNode(ast, compile(procedure), nodes(ast, "arguments"), procName(procedure), pathHint(procedure))
            }
            "partial" -> {
                val procedure = ast.node("procedure")!!
                val args = ast.nodes("arguments")!!.map { a ->
                    a as AstNode
                    if (a.type == "operator" && a["value"] == "?") null else compile(a)
                }.toTypedArray()
                PartialNode(ast, compile(procedure), args, procName(procedure), pathHint(procedure))
            }
            "variable" -> VariableNode(ast, ast["value"] as String)
            "lambda" -> LambdaNode(
                ast,
                ast.nodes("arguments")!!.map { (it as AstNode)["value"] as String }.toTypedArray(),
                ast["signature"] as? Signature,
                compile(ast.node("body")!!).also { body -> if (ast["thunk"] != true) body.info?.operatorStop = true },
                ast["thunk"] == true,
            )
            "apply" -> ApplyNode(ast, compile(ast.node("lhs")!!), compile(ast.node("rhs")!!))
            "transform" -> TransformNode(ast, compile(ast.node("pattern")!!), compile(ast.node("update")!!), ast.node("delete")?.let { compile(it) })
            "sort" -> SortNode(
                ast,
                ast.nodes("terms")!!.map { t ->
                    t as AstNode
                    SortTerm(compile(t.node("expression")!!), t["descending"] == true)
                }.toTypedArray(),
            )
            "operator" -> PlaceholderNode(ast)
            else -> throw JsonataException("S0206", ast.position, type)
        }
        applyCommon(node, ast)
        node.debug = debug
        ranges?.get(ast)?.let { node.start = it.start; node.end = it.end }
        return node
    }

    /** Properties every node may carry: predicates, group-by, `[]`, and the path step annotations of processAST. */
    private fun applyCommon(node: Node, ast: AstNode) {
        ast.nodes("predicate")?.let { preds -> node.predicates = preds.map { compile((it as AstNode).node("expr")!!) }.toTypedArray() }
        if (ast.type != "path") ast.node("group")?.let { node.group = groupSpec(it) }
        if (ast["keepArray"] == true) node.keepArray = true
        ast.nodes("stages")?.let { stages ->
            node.stages = stages.map { s ->
                s as AstNode
                when (s.type) {
                    "filter" -> FilterStage(compile(s.node("expr")!!))
                    "index" -> IndexStage(s["value"] as String)
                    else -> throw JsonataException("S0206", s.position, s.type)
                }
            }.toTypedArray()
        }
        (ast["focus"] as? String)?.let { node.focus = it }
        (ast["index"] as? String)?.let { node.indexVar = it }
        if (ast["tuple"] == true) node.tuple = true
        ast.node("ancestor")?.let { node.ancestorLabel = it["label"] as String }
        if (ast["consarray"] == true) node.consarray = true
    }

    private companion object {
        /** Stops of the default step granularity besides path steps and lambda bodies. */
        val OPERATOR_STOPS = setOf("function", "partial", "apply", "block")
    }

    /** Whether a `variable` node named [name] occurs anywhere under [ast]. */
    private fun refersTo(ast: AstNode, name: String): Boolean {
        val stack = ArrayList<Any?>()
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
        stack.add(ast)
        while (stack.isNotEmpty()) {
            when (val x = stack.removeAt(stack.size - 1)) {
                is AstNode -> {
                    if (!seen.add(x)) continue
                    if (x.type == "variable" && x["value"] == name) return true
                    for (k in x.keys) stack.add(x[k])
                }
                is List<*> -> stack.addAll(x)
                is Array<*> -> stack.addAll(x)
            }
        }
        return false
    }

    private fun compilePath(ast: AstNode): Node {
        val steps = nodes(ast, "steps")
        for (s in steps) s.info?.operatorStop = true
        return PathNode(ast, steps, ast["keepSingletonArray"] == true, ast["tuple"] == true, ast.node("group")?.let { groupSpec(it) })
    }

    private fun compileUnary(ast: AstNode): Node = when (ast["value"]) {
        "-" -> NegateNode(ast, compile(ast.node("expression")!!))
        "[" -> {
            val exprs = ast.nodes("expressions")!!.map { it as AstNode }
            ArrayNode(
                ast,
                exprs.map { compile(it) }.toTypedArray(),
                BooleanArray(exprs.size) { exprs[it]["value"] == "[" },
                ast["consarray"] == true,
            )
        }
        "{" -> ObjectNode(ast, groupSpec(ast))
        else -> throw JsonataException("S0206", ast.position, ast["value"])
    }

    private fun groupSpec(ast: AstNode): GroupSpec {
        val pairs = ast.nodes("lhs")!!.map { p ->
            @Suppress("UNCHECKED_CAST")
            val pair = p as List<AstNode>
            arrayOf(compile(pair[0]), compile(pair[1]))
        }.toTypedArray()
        return GroupSpec(pairs, ast.position)
    }

    private fun nodes(ast: AstNode, key: String): Array<Node> = (ast.nodes(key) ?: emptyList()).map { compile(it as AstNode) }.toTypedArray()

    /**
     * jsonata.js evaluateFunction: `expr.procedure.type === 'path' ? expr.procedure.steps[0].value : expr.procedure.value`
     * — the error token of a call; a literal gives its value (`2(blah)` → token 2).
     */
    private fun procName(procedure: AstNode): Any? {
        val v = if (procedure.type == "path") (procedure.nodes("steps")!![0] as AstNode)["value"] else procedure["value"]
        return when (v) {
            is String, is Boolean -> v
            is Number -> v.toDouble()
            else -> null
        }
    }

    private fun pathHint(procedure: AstNode): String? =
        if (procedure.type == "path") (procedure.nodes("steps")!![0] as AstNode)["value"] as? String else null
}
