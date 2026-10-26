package su.seaf.archtool.jsonata.engine

import su.seaf.archtool.jsonata.deviation.Deviations
import su.seaf.archtool.jsonata.parser.AstNode
import su.seaf.archtool.jsonata.parser.JsonataException
import su.seaf.archtool.jsonata.parser.Signature
import su.seaf.archtool.jsonata.value.ArrayValue
import su.seaf.archtool.jsonata.value.BooleanValue
import su.seaf.archtool.jsonata.value.FunctionValue
import su.seaf.archtool.jsonata.value.NullValue
import su.seaf.archtool.jsonata.value.NumberValue
import su.seaf.archtool.jsonata.value.ObjectValue
import su.seaf.archtool.jsonata.value.StringValue
import su.seaf.archtool.jsonata.value.Value

/** A path step stage: a filter predicate or a `#$i` index binding (jsonata.js: evaluateStages). */
internal sealed class Stage
internal class FilterStage(@JvmField val expr: Node) : Stage()
internal class IndexStage(@JvmField val name: String) : Stage()

/** `{ key: value, ... }` pairs of an object constructor or a group-by (jsonata.js: evaluateGroupExpression). */
internal class GroupSpec(@JvmField val pairs: Array<Array<Node>>, @JvmField val position: Int?)

/**
 * A compiled AST node. [eval] is the node's own semantics; [evaluate] is jsonata.js `evaluate`: the checkpoint and
 * depth, predicates, group-by, and the collapse of singleton sequences. Every node keeps its AST (and so its position
 * in the source), which the debugger and error reports use.
 */
internal abstract class Node(@JvmField val ast: AstNode) {
    @JvmField val position: Int? = ast.position

    /** Source range of the node and its children (UTF-16 offsets, end exclusive); -1 when unknown. */
    @JvmField var start: Int = -1
    @JvmField var end: Int = -1

    /** The node as the debugger sees it; set in the debugging tree only. */
    @JvmField var info: su.seaf.archtool.jsonata.debug.NodeInfo? = null

    @JvmField var predicates: Array<Node>? = null
    @JvmField var group: GroupSpec? = null
    @JvmField var keepArray: Boolean = false

    // path step properties set by processAST
    @JvmField var stages: Array<Stage>? = null
    @JvmField var focus: String? = null
    @JvmField var indexVar: String? = null
    @JvmField var tuple: Boolean = false
    @JvmField var ancestorLabel: String? = null
    @JvmField var consarray: Boolean = false

    /**
     * Set by the compiler when the expression is compiled with debugging: then [evaluate] reports to the hook of the
     * evaluation. The node itself and its evaluation are the same either way (decision 15: the hook never changes the
     * result); without debugging the only cost is this field test.
     */
    @JvmField var debug: Boolean = false

    abstract fun eval(input: Value?, env: Frame): Value?

    fun evaluate(input: Value?, env: Frame): Value? {
        if (debug) {
            val listener = env.ctx.listener
            if (listener != null) return evaluateDebug(listener, input, env)
        }
        return evaluateNode(input, env)
    }

    private fun evaluateDebug(listener: su.seaf.archtool.jsonata.debug.EvaluationListener, input: Value?, env: Frame): Value? {
        val ctx = env.ctx
        val node = info!!
        val view = FrameView.of(env)
        val iteration = ctx.iteration
        ctx.iteration = null
        listener.enter(node, input, view, iteration)
        val result = try {
            evaluateNode(input, env)
        } catch (e: Throwable) {
            listener.error(node, input, view, e)
            throw e
        }
        listener.exit(node, input, view, result)
        return result
    }

    /** jsonata.js: evaluate — checkpoint and depth, predicates, group-by, collapse of singleton sequences. */
    private fun evaluateNode(input: Value?, env: Frame): Value? {
        val ctx = env.ctx
        ctx.enter()
        try {
            if (ctx.depth - ctx.segmentBase > ctx.segmentLimit) return ctx.onNewSegment { evaluateBody(input, env) }
            return evaluateBody(input, env)
        } catch (e: JsonataException) {
            // the range of the node the error position belongs to (CAP-9)
            e.fillRange(position, start, end)
            throw e
        } catch (e: RuntimeException) {
            ctx.locate(e, this)
            throw e
        } finally {
            ctx.depth--
        }
    }

    private fun evaluateBody(input: Value?, env: Frame): Value? {
        run {
            var result = eval(input, env)
            val preds = predicates
            if (preds != null) for (p in preds) result = Evaluator.filter(p, result, env)
            val g = group
            if (g != null) result = Evaluator.group(g, result, env)
            if (result is ArrayValue && result.isSequence && !result.tupleStream) {
                if (keepArray) result.keepSingleton = true
                if (result.size == 0) result = null
                else if (result.size == 1 && !result.keepSingleton) result = result.raw(0)
            }
            return result
        }
    }
}

internal class LiteralNode(ast: AstNode, @JvmField val value: Value?) : Node(ast) {
    override fun eval(input: Value?, env: Frame): Value? = value
}

/** jsonata.js: evaluateName → functions.js: lookup. */
internal class NameNode(ast: AstNode, @JvmField val key: String) : Node(ast) {
    override fun eval(input: Value?, env: Frame): Value? {
        val r = Evaluator.lookup(input, key, env.ctx)
        if (r is ArrayValue && r.isSequence) env.ctx.checkSequence(r.size)
        return r
    }
}

/** jsonata.js: evaluateWildcard. */
internal class WildcardNode(ast: AstNode) : Node(ast) {
    override fun eval(input: Value?, env: Frame): Value? {
        var results = ArrayValue.sequence()
        var inp = input
        if (inp is ArrayValue && inp.outerWrapper && inp.size > 0) inp = inp.raw(0)
        fun visit(value: Value?) {
            if (value is ArrayValue) {
                val flat = ArrayValue()
                Evaluator.flatten(value, flat, env.ctx)
                results = Evaluator.append(results, flat, env.ctx) as ArrayValue
            } else {
                results.addNullable(value)
            }
        }
        // a JS array is an object too: its values are visited by index
        if (inp is ObjectValue) {
            for (key in inp.keys) visit(inp[key])
        } else if (inp is ArrayValue) {
            for (i in 0 until inp.size) visit(inp.raw(i))
        }
        env.ctx.checkSequence(results.size)
        return results
    }
}

/** jsonata.js: evaluateDescendants. */
internal class DescendantNode(ast: AstNode) : Node(ast) {
    override fun eval(input: Value?, env: Frame): Value? {
        if (input == null) return null
        val results = ArrayValue.sequence()
        Evaluator.recurseDescendants(input, results, env.ctx)
        env.ctx.checkSequence(results.size)
        return if (results.size == 1) results.raw(0) else results
    }
}

internal class ParentNode(ast: AstNode, @JvmField val label: String) : Node(ast) {
    override fun eval(input: Value?, env: Frame): Value? = env.lookup(label)
}

/** jsonata.js: evaluateVariable. `$` is the context, `$$` the root (bound as `$`). */
internal class VariableNode(ast: AstNode, @JvmField val name: String) : Node(ast) {
    override fun eval(input: Value?, env: Frame): Value? {
        if (name.isEmpty()) return if (input is ArrayValue && input.outerWrapper) input.raw(0) else input
        return env.lookup(name)
    }
}

/** jsonata.js: evaluatePath. */
internal class PathNode(
    ast: AstNode,
    @JvmField val steps: Array<Node>,
    @JvmField val keepSingletonArray: Boolean,
    @JvmField val keepTuples: Boolean,
    @JvmField val pathGroup: GroupSpec?,
) : Node(ast) {
    private val firstIsVariable = steps[0] is VariableNode

    override fun eval(input: Value?, env: Frame): Value? {
        var inputSequence: Value? = if (input is ArrayValue && !firstIsVariable) input else ArrayValue.sequenceOf(input)
        var resultSequence: Value? = null
        var isTupleStream = false
        var tupleBindings: ArrayValue? = null
        val last = steps.size - 1
        for (ii in steps.indices) {
            val step = steps[ii]
            if (step.tuple) isTupleStream = true
            if (ii == 0 && step.consarray) {
                resultSequence = step.evaluate(inputSequence, env)
                if (ii < last && resultSequence != null && resultSequence !is ArrayValue) {
                    // DEV-12: a filter that selects one item of an array constructor collapses the step to that item;
                    // jsonata-js then iterates it by `.length` (nothing for an object or number, the characters of a
                    // string); the engine iterates the item itself: `[{"a":1},{"a":2}][0].a` gives 1, not undefined
                    env.ctx.deviation(Deviations.UNDEFINED_IN_SEQUENCES.id, step.position)
                    resultSequence = ArrayValue.sequence(resultSequence)
                }
            } else if (isTupleStream) {
                tupleBindings = Evaluator.tupleStep(step, inputSequence, tupleBindings, env)
            } else {
                resultSequence = Evaluator.step(step, inputSequence, env, ii == last)
            }
            if (!isTupleStream && (resultSequence == null || Evaluator.jsLength(resultSequence) == 0)) break
            if (step.focus == null) inputSequence = resultSequence
        }
        if (isTupleStream) {
            if (keepTuples) {
                resultSequence = tupleBindings
            } else {
                val seq = ArrayValue.sequence()
                val tb = tupleBindings!!
                for (i in 0 until tb.size) seq.addNullable((tb.raw(i) as ObjectValue)["@"])
                resultSequence = seq
            }
        }
        if (keepSingletonArray) {
            var rs = resultSequence
            if (rs is ArrayValue && rs.cons && !rs.isSequence) {
                rs = ArrayValue.sequence(rs)
            }
            if (rs is ArrayValue && rs.isSequence) rs.keepSingleton = true
            resultSequence = rs
        }
        if (pathGroup != null) {
            resultSequence = Evaluator.group(pathGroup, if (isTupleStream) tupleBindings else resultSequence, env)
        }
        return resultSequence
    }
}

internal enum class BinaryOp(val token: String) {
    ADD("+"), SUB("-"), MUL("*"), DIV("/"), MOD("%"),
    EQ("="), NE("!="), LT("<"), LE("<="), GT(">"), GE(">="),
    CONCAT("&"), RANGE(".."), IN("in"), AND("and"), OR("or");

    companion object {
        private val byToken = entries.associateBy { it.token }
        fun of(token: String): BinaryOp = byToken[token] ?: throw JsonataException("S0204", token = token)
    }
}

/** jsonata.js: evaluateBinary. */
internal class BinaryNode(ast: AstNode, @JvmField val op: BinaryOp, @JvmField val lhs: Node, @JvmField val rhs: Node) : Node(ast) {
    override fun eval(input: Value?, env: Frame): Value? {
        val l = lhs.evaluate(input, env)
        if (op == BinaryOp.AND || op == BinaryOp.OR) {
            try {
                val lb = Evaluator.boolize(l)
                val r = if (op == BinaryOp.AND) lb && Evaluator.boolize(rhs.evaluate(input, env))
                else lb || Evaluator.boolize(rhs.evaluate(input, env))
                return BooleanValue.of(r)
            } catch (e: JsonataException) {
                // jsonata-js moves any error of the operands onto the operator; engine codes (X) keep their place
                if (!e.code.startsWith("X")) {
                    e.position = position
                    e.token = op.token
                }
                throw e
            }
        }
        val r = rhs.evaluate(input, env)
        try {
            return when (op) {
                BinaryOp.ADD, BinaryOp.SUB, BinaryOp.MUL, BinaryOp.DIV, BinaryOp.MOD -> Evaluator.numeric(l, r, op)
                BinaryOp.EQ -> if (l == null || r == null) BooleanValue.FALSE else BooleanValue.of(equal(l, r, env))
                BinaryOp.NE -> if (l == null || r == null) BooleanValue.FALSE else BooleanValue.of(!equal(l, r, env))
                BinaryOp.LT, BinaryOp.LE, BinaryOp.GT, BinaryOp.GE -> Evaluator.compare(l, r, op)
                BinaryOp.CONCAT -> {
                    val ls = if (l != null) Evaluator.string(l, false, env.ctx) else ""
                    val rs = if (r != null) Evaluator.string(r, false, env.ctx) else ""
                    env.ctx.checkString(ls.length.toLong() + rs.length)
                    StringValue.of(ls + rs)
                }
                BinaryOp.RANGE -> Evaluator.range(l, r, env)
                BinaryOp.IN -> Evaluator.includes(l, r, env.ctx)
                else -> throw IllegalStateException(op.token)
            }
        } catch (e: JsonataException) {
            e.position = position
            e.token = op.token
            throw e
        }
    }

    /** `=` of two values, through the checkpoint: comparing large structures is a loop (review RV3-27). */
    private fun equal(l: Value, r: Value, env: Frame): Boolean = su.seaf.archtool.jsonata.value.Values.deepEquals(l, r, env.ctx.ticker)
}

/** jsonata.js: evaluateUnary '-'. */
internal class NegateNode(ast: AstNode, @JvmField val expr: Node) : Node(ast) {
    override fun eval(input: Value?, env: Frame): Value? {
        val v = expr.evaluate(input, env) ?: return null
        if (Evaluator.isNumeric(v)) return NumberValue.of(-(v as NumberValue).value)
        throw JsonataException("D1002", position, "-", v)
    }
}

/** jsonata.js: evaluateUnary '[' (array constructor). */
internal class ArrayNode(ast: AstNode, @JvmField val items: Array<Node>, @JvmField val nested: BooleanArray, @JvmField val isConsarray: Boolean) : Node(ast) {
    override fun eval(input: Value?, env: Frame): Value? {
        var result: Value = ArrayValue(items.size)
        for (i in items.indices) {
            val value = try {
                items[i].evaluate(input, env)
            } catch (e: JsonataException) {
                Evaluator.probeOtherFailures(e, env, position) { j -> items.getOrNull(i + 1 + j)?.evaluate(input, env) ?: if (i + 1 + j < items.size) null else throw NoSuchElementException() }
                throw e
            } ?: continue
            if (nested[i]) {
                (result as ArrayValue).add(value)
            } else {
                result = Evaluator.append(result, value, env.ctx)!!
            }
        }
        if (isConsarray) (result as ArrayValue).cons = true
        return result
    }
}

/** jsonata.js: evaluateUnary '{' (object constructor). */
internal class ObjectNode(ast: AstNode, @JvmField val spec: GroupSpec) : Node(ast) {
    override fun eval(input: Value?, env: Frame): Value? = Evaluator.group(spec, input, env)
}

internal class ConditionNode(ast: AstNode, @JvmField val condition: Node, @JvmField val then: Node, @JvmField val otherwise: Node?) : Node(ast) {
    override fun eval(input: Value?, env: Frame): Value? {
        val c = condition.evaluate(input, env)
        return if (su.seaf.archtool.jsonata.value.Values.toBoolean(c) == true) then.evaluate(input, env)
        else otherwise?.evaluate(input, env)
    }
}

internal class BlockNode(ast: AstNode, @JvmField val expressions: Array<Node>) : Node(ast) {
    override fun eval(input: Value?, env: Frame): Value? {
        val frame = Frame(env, env.ctx)
        var result: Value? = null
        for (e in expressions) result = e.evaluate(input, frame)
        return result
    }
}

internal class BindNode(ast: AstNode, @JvmField val name: String, @JvmField val rhs: Node) : Node(ast) {
    override fun eval(input: Value?, env: Frame): Value? {
        val value = rhs.evaluate(input, env)
        env.bind(name, value)
        return value
    }
}

/** jsonata.js: evaluateRegex — a matcher function `(str, fromIndex)`. */
internal class RegexNode(ast: AstNode, @JvmField val regex: JsRegexPattern) : Node(ast) {
    override fun eval(input: Value?, env: Frame): Value? {
        try {
            regex.pattern // an invalid pattern fails here, as `new RegExp` does in jsonata-js
        } catch (e: JsonataException) {
            e.position = position
            throw e
        }
        return RegexMatcher.create(regex, position)
    }
}

internal class LambdaNode(
    ast: AstNode,
    @JvmField val params: Array<String>,
    @JvmField val signature: Signature?,
    @JvmField val body: Node,
    @JvmField val thunk: Boolean,
) : Node(ast) {
    override fun eval(input: Value?, env: Frame): Value? = Lambda(input, env, params, signature, body, thunk, this)
}

/** jsonata.js: evaluateFunction. */
internal class FunctionCallNode(
    ast: AstNode,
    @JvmField val procedure: Node,
    @JvmField val arguments: Array<Node>,
    /** The name in the procedure position for error hints (`steps[0].value` of a path, else the node value). */
    @JvmField val procName: Any?,
    /** For T1005: the first step of a path procedure (`uppercase(x)` without `$`). */
    @JvmField val pathHint: String?,
) : Node(ast) {
    override fun eval(input: Value?, env: Frame): Value? = call(input, env, false, null)

    fun call(input: Value?, env: Frame, hasContext: Boolean, context: Value?): Value? {
        val proc = procedure.evaluate(input, env)
        if (proc == null && pathHint != null && jsTruthy(env.lookup(pathHint))) {
            throw JsonataException("T1005", position, pathHint)
        }
        val args = ArrayList<Value?>(arguments.size + 1)
        if (hasContext) args.add(context)
        for (a in arguments) args.add(a.evaluate(input, env))
        try {
            val site = if (proc is EngineFunction && proc.jsObject) CallSite(procName as? String, position) else null
            return Evaluator.apply(proc, args, input, env, site, this)
        } catch (e: JsonataException) {
            if (e.position == null) e.position = position
            if (e.token == null) e.token = procName
            throw e
        }
    }
}

/** jsonata.js: evaluatePartialApplication. */
internal class PartialNode(
    ast: AstNode,
    @JvmField val procedure: Node,
    @JvmField val arguments: Array<Node?>,
    @JvmField val procName: Any?,
    @JvmField val pathHint: String?,
) : Node(ast) {
    override fun eval(input: Value?, env: Frame): Value? {
        val args = arrayOfNulls<Any?>(arguments.size)
        for (i in arguments.indices) {
            val a = arguments[i]
            args[i] = if (a == null) PartialNative.PLACEHOLDER else a.evaluate(input, env)
        }
        val proc = procedure.evaluate(input, env)
        if (proc == null && pathHint != null && jsTruthy(env.lookup(pathHint))) {
            throw JsonataException("T1007", position, pathHint)
        }
        return when (proc) {
            is Lambda -> Evaluator.partialLambda(proc, args, env)
            is NativeFunction -> PartialNative(proc, Array(maxOf(proc.paramCount, 0)) { args.getOrNull(it) })
            is PartialNative -> Evaluator.partialOfPartial(proc, args)
            is InternalFunction, is Transformer -> PartialNative(Evaluator.asNative(proc), Array(proc.arity) { args.getOrNull(it) })
            else -> throw JsonataException("T1008", position, procName)
        }
    }
}

/** jsonata.js: evaluateApplyExpression (`~>`). */
internal class ApplyNode(ast: AstNode, @JvmField val lhs: Node, @JvmField val rhs: Node) : Node(ast) {
    override fun eval(input: Value?, env: Frame): Value? {
        val l = lhs.evaluate(input, env)
        if (rhs is FunctionCallNode) return rhs.call(input, env, true, l)
        val func = rhs.evaluate(input, env)
        if (func !is FunctionValue) throw JsonataException("T2006", position, value = func)
        if (l is FunctionValue) {
            // function chaining: λ($f, $g) { λ($x){ $g($f($x)) } }
            val chain = Evaluator.chainLambda(env)
            // jsonata.js passes JS null as the context here (evaluateApplyExpression)
            return Evaluator.apply(chain, listOf(l, func), NullValue, env, null, null)
        }
        return Evaluator.apply(func, listOf(l), NullValue, env, null, null)
    }
}

/** jsonata.js: evaluateTransformExpression. */
internal class TransformNode(ast: AstNode, @JvmField val pattern: Node, @JvmField val update: Node, @JvmField val delete: Node?) : Node(ast) {
    override fun eval(input: Value?, env: Frame): Value? = Transformer(this, env)
}

/** A sort step `^(...)` (jsonata.js: evaluateSortExpression); evaluated by the path, never through [eval]. */
internal class SortNode(ast: AstNode, @JvmField val terms: Array<SortTerm>) : Node(ast) {
    override fun eval(input: Value?, env: Frame): Value? = Evaluator.sort(this, input, env)
}

internal class SortTerm(@JvmField val expression: Node, @JvmField val descending: Boolean)

/** The `?` placeholder never evaluates on its own. */
internal class PlaceholderNode(ast: AstNode) : Node(ast) {
    override fun eval(input: Value?, env: Frame): Value? = null
}
