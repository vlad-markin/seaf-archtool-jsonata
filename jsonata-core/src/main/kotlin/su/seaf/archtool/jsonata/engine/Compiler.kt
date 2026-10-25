package su.seaf.archtool.jsonata.engine

import su.seaf.archtool.jsonata.parser.AstNode
import su.seaf.archtool.jsonata.parser.JsRegex
import su.seaf.archtool.jsonata.parser.JsonataException
import su.seaf.archtool.jsonata.parser.Signature
import su.seaf.archtool.jsonata.value.BooleanValue
import su.seaf.archtool.jsonata.value.NullValue
import su.seaf.archtool.jsonata.value.NumberValue
import su.seaf.archtool.jsonata.value.StringValue

/**
 * Compiles the processed AST (the jsonata-js `ast()` shape) into a tree of [Node]s, once per expression. With [debug]
 * every node reports to the evaluation's debug hook (the tree is otherwise identical); without it no node does.
 */
internal class Compiler(private val debug: Boolean) {
    fun compile(ast: AstNode): Node {
        val node = compileNode(ast)
        return node
    }

    private fun compileNode(ast: AstNode): Node {
        val node = when (val type = ast.type) {
            "path" -> compilePath(ast)
            "binary" -> BinaryNode(ast, BinaryOp.of(ast["value"] as String), compile(ast.node("lhs")!!), compile(ast.node("rhs")!!))
            "unary" -> compileUnary(ast)
            "name" -> NameNode(ast, ast["value"] as String)
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
            "bind" -> BindNode(ast, (ast.node("lhs")!!)["value"] as String, compile(ast.node("rhs")!!))
            "regex" -> RegexNode(ast, JsRegexPattern.of(ast["value"] as JsRegex))
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
                compile(ast.node("body")!!),
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

    private fun compilePath(ast: AstNode): Node {
        val steps = nodes(ast, "steps")
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

    private fun procName(procedure: AstNode): String? =
        if (procedure.type == "path") (procedure.nodes("steps")!![0] as AstNode)["value"] as? String else procedure["value"] as? String

    private fun pathHint(procedure: AstNode): String? =
        if (procedure.type == "path") (procedure.nodes("steps")!![0] as AstNode)["value"] as? String else null
}
