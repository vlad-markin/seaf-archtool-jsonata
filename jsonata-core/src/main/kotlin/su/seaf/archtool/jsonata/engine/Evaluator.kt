package su.seaf.archtool.jsonata.engine

import su.seaf.archtool.jsonata.CompileOptions
import su.seaf.archtool.jsonata.EvaluationOptions
import su.seaf.archtool.jsonata.FunctionRegistry
import su.seaf.archtool.jsonata.deviation.Deviations
import su.seaf.archtool.jsonata.parser.JsonataException
import su.seaf.archtool.jsonata.parser.JsonataParser
import su.seaf.archtool.jsonata.value.ArrayValue
import su.seaf.archtool.jsonata.value.BooleanValue
import su.seaf.archtool.jsonata.value.FunctionValue
import su.seaf.archtool.jsonata.value.JsonWriter
import su.seaf.archtool.jsonata.value.NullValue
import su.seaf.archtool.jsonata.value.NumberValue
import su.seaf.archtool.jsonata.value.ObjectValue
import su.seaf.archtool.jsonata.value.StringValue
import su.seaf.archtool.jsonata.value.Value
import su.seaf.archtool.jsonata.value.Values
import java.util.IdentityHashMap

/** The evaluation procedures of jsonata.js that are shared by several nodes, and the entry point. */
internal object Evaluator {
    init {
        // The error classes must be initialized before the JIT compiles the evaluation path: a compiled catch block
        // that refers to an uninitialized class is a trap, and an error unwinding a deep recursion would otherwise
        // deoptimize every compiled frame on its way (about 1 000 000 frames and 8 s for D1011 at the default depth).
        JsonataException("D1011").token = null
    }

    /** jsonata.js: jsonata(expr).evaluate(input, bindings). */
    fun run(
        root: Node,
        input: Value?,
        bindings: Map<String, Value?>,
        options: CompileOptions,
        evaluation: EvaluationOptions,
        deep: Boolean = false,
        onDeep: (() -> Unit)? = null,
        source: su.seaf.archtool.jsonata.debug.SourceInfo? = null,
    ): Value? {
        val registry = evaluation.functions?.also {
            require(it.descendsFrom(options.functions)) { "EvaluationOptions.functions must extend the registry the expression was compiled with" }
        } ?: options.functions
        val ctx = EvalContext(registry, evaluation, evaluation.clock())
        ctx.onDeep = onDeep
        ctx.rootInput = input
        val env = Frame(null, ctx)
        for ((k, v) in bindings) env.bind(k, v)
        env.bind("$", input)
        var focus = input
        // a JSON array input is wrapped in a singleton sequence so that it is treated as a single input
        if (input is ArrayValue && !input.isSequence) {
            focus = ArrayValue.sequence(input).also { it.outerWrapper = true }
        }
        ctx.start()
        try {
            if (source != null) ctx.listener?.source(source)
            // an expression known to go deep starts on a segment thread (one hand-over instead of one per crossing)
            val result = if (deep && !SegmentPool.onSegmentThread()) ctx.fromSegment { root.evaluate(focus, env) } else root.evaluate(focus, env)
            ctx.checkResult(result)
            return result
        } catch (_: StackOverflowError) {
            // jsonata-js fails with a JS RangeError (no JSONata code) here
            throw JsonataException("X0002")
        } catch (e: JsonataException) {
            throw e
        } catch (e: RuntimeException) {
            // an exception of a host function leaves unchanged (CAP-9 p. 4); anything else is a bug of the engine
            if (ctx.isHost(e)) throw e
            throw JsonataException("X0008", details = mapOf("message" to (e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))), cause = e)
        } finally {
            ctx.finish()
        }
    }

    // ---- function application ----

    /** jsonata.js: apply — with the trampoline that unwraps tail-call thunks without growing the stack. */
    fun apply(proc: Value?, args: List<Value?>, input: Value?, env: Frame, site: CallSite?, callNode: Node? = null): Value? {
        var result = applyInner(proc, args, input, env, site, callNode)
        while (result is Lambda && result.thunk) {
            val body = result.body as FunctionCallNode
            val procedure = body.procedure
            val next = procedure.evaluate(result.input, result.env)
            val token = (procedure as? VariableNode)?.name
            val nextArgs = ArrayList<Value?>(body.arguments.size)
            for (a in body.arguments) nextArgs.add(a.evaluate(result.input, result.env))
            env.ctx.tailCallNext = true
            result = applyInner(next, nextArgs, input, env, CallSite(token, procedure.position), body)
        }
        return result
    }

    /** A native call with the listener told before and after; lambdas it calls are named after it. */
    private fun invokeObserved(
        listener: su.seaf.archtool.jsonata.debug.EvaluationListener,
        proc: NativeFunction,
        c: CallContextImpl,
        args: List<Value?>,
        env: Frame,
        callNode: Node?,
    ): Value? {
        val ctx = env.ctx
        val view = FrameView.of(env)
        val name = proc.name.ifEmpty { "native" }
        listener.nativeEnter(name, proc.source, args, view)
        val calls = ctx.nativeCalls ?: ArrayList<String>().also { ctx.nativeCalls = it }
        calls.add(name)
        val result = try {
            c.checked(invokeNative(proc, c, args, ctx, callNode))
        } finally {
            calls.removeAt(calls.size - 1)
        }
        listener.nativeExit(name, result, view)
        return result
    }

    /** Calls a native function; an exception of a host function (not the standard library) is marked to leave unchanged. */
    private fun invokeNative(proc: NativeFunction, c: CallContextImpl, args: List<Value?>, ctx: EvalContext, callNode: Node?): Value? {
        // a debugger watch expression must not change anything (design §2.5)
        if (ctx.options.watch && proc.sideEffects) throw JsonataException("X0011", value = StringValue.of(proc.name))
        if (proc.source == su.seaf.archtool.jsonata.FunctionSource.STDLIB) return proc.implementation.invoke(c, args)
        ctx.enterHost()
        var exited = false
        try {
            return proc.implementation.invoke(c, args)
        } catch (e: JsonataException) {
            throw e
        } catch (e: InterruptedException) {
            // a blocking host call was interrupted: by the token (its interrupt is consumed) or by the host itself
            // (the flag is restored for the host)
            exited = true
            val byToken = ctx.exitHostInterrupted()
            if (!byToken) Thread.currentThread().interrupt()
            throw ctx.cancelled(e)
        } catch (e: RuntimeException) {
            ctx.markHost(e)
            // the call node is the innermost place (a tail call is not evaluated through its node)
            if (callNode != null) ctx.locate(e, callNode)
            throw e
        } finally {
            if (!exited) ctx.exitHost()
        }
    }

    /** jsonata.js: applyInner. */
    private fun applyInner(proc: Value?, args: List<Value?>, input: Value?, env: Frame, site: CallSite?, callNode: Node?): Value? {
        if (site != null && proc is Lambda) env.ctx.recordCall(proc, site)
        try {
            var validated = args
            if (proc is EngineFunction) {
                val sig = proc.signature
                if (sig != null) validated = SignatureValidator.validate(sig, args, input)
            }
            return when (proc) {
                is Lambda -> applyProcedure(proc, validated, env, callNode)
                is NativeFunction -> CallContextImpl(env, input).let { c ->
                    val listener = env.ctx.listener
                    if (listener == null) c.checked(invokeNative(proc, c, validated, env.ctx, callNode)) else invokeObserved(listener, proc, c, validated, env, callNode)
                }
                is PartialNative -> applyPartialNative(proc, validated, env)
                is InternalFunction -> proc.body(CallContextImpl(env, input), validated)
                is Transformer -> transform(proc, validated.getOrNull(0), env)
                else -> throw JsonataException("T1006")
            }
        } catch (e: JsonataException) {
            val s = if (proc is Lambda) env.ctx.lastCall(proc) else site
            // the JSONata call stack of the error, on its way up only (CAP-9)
            if (proc is Lambda || proc is NativeFunction) {
                val name = s?.token ?: (proc as? NativeFunction)?.name?.ifEmpty { null }
                val kind = if (proc is Lambda) su.seaf.archtool.jsonata.parser.ErrorFrame.Kind.LAMBDA else su.seaf.archtool.jsonata.parser.ErrorFrame.Kind.NATIVE
                e.addFrame(su.seaf.archtool.jsonata.parser.ErrorFrame(name, s?.position ?: callNode?.position, callNode?.start ?: -1, callNode?.end ?: -1, kind))
            }
            if (s != null) {
                if (e.token == null && s.token != null) e.token = s.token
                if (s.position != null) {
                    if (proc is Lambda && e.position != null) {
                        // DEV-10: jsonata-js overwrites the position of an error raised inside a lambda with the
                        // position of the (last) call; the engine keeps the position of the failing operator
                        if (e.position != s.position) env.ctx.deviation(Deviations.POSITION_IN_LAMBDA.id, e.position)
                    } else if (e.functionId != null && e.position != null) {
                        // raised inside the code of a manifest function (CallContext.evaluate): the position is in
                        // that code (CAP-5 p. 7), the call is in the error's frames
                    } else {
                        e.position = s.position
                    }
                }
            } else if (e.token == null) {
                // jsonata-js keeps the name of the last direct call on the shared function object, so a library
                // function called by $map or `~>` still reports its own name
                val name = when (proc) {
                    is NativeFunction -> proc.name
                    is PartialNative -> proc.native.name
                    else -> ""
                }
                if (name.isNotEmpty()) e.token = name
            }
            throw e
        }
    }

    /** jsonata.js: applyProcedure. */
    private fun applyProcedure(proc: Lambda, args: List<Value?>, caller: Frame, callNode: Node?): Value? {
        // the call runs under the caller's evaluation (limits, clock, registry), wherever the lambda was created
        // (review RV-9): the context comes from the calling frame, the variables from the closure
        val ctx = caller.ctx
        val frame = Frame(proc.env, ctx)
        frame.callSite = callNode
        frame.caller = caller
        frame.tailCall = ctx.tailCallNext
        ctx.tailCallNext = false
        if (ctx.listener != null) frame.lambdaName = lambdaName(callNode, ctx)
        val params = proc.params
        for (i in params.indices) frame.bind(params[i], args.getOrNull(i))
        return proc.body.evaluate(proc.input, frame)
    }

    /** The debugger's name of a lambda invocation: the name at the call, or `λ via $native` from a higher-order function. */
    private fun lambdaName(callNode: Node?, ctx: EvalContext): String {
        val name = (callNode as? FunctionCallNode)?.procName as? String
        if (name != null) return name
        if (callNode == null) ctx.nativeCalls?.lastOrNull()?.let { return "λ via \$$it" }
        return "λ"
    }

    private fun applyPartialNative(proc: PartialNative, args: List<Value?>, env: Frame): Value? {
        val full = ArrayList<Value?>(proc.bound.size)
        var k = 0
        for (b in proc.bound) {
            if (b === PartialNative.PLACEHOLDER) full.add(args.getOrNull(k++)) else full.add(b as Value?)
        }
        // jsonata-js calls the native implementation without signature checks (applyNativeFunction); the checks are
        // kept here so that native code always sees coerced arguments — they only differ where jsonata-js crashes
        val native = proc.native
        val validated = native.signature?.let { SignatureValidator.validate(it, full, null) } ?: full
        return native.implementation.invoke(CallContextImpl(env, null), validated)
    }

    /** jsonata.js: partialApplyProcedure. */
    fun partialLambda(proc: Lambda, args: Array<Any?>, env: Frame): Lambda {
        val frame = Frame(proc.env, env.ctx)
        val unbound = ArrayList<String>()
        for (i in proc.params.indices) {
            val arg = args.getOrNull(i)
            if (arg === PartialNative.PLACEHOLDER) unbound += proc.params[i] else frame.bind(proc.params[i], arg as Value?)
        }
        return Lambda(proc.input, frame, unbound.toTypedArray(), null, proc.body, false, proc.source)
    }

    /** Partial application of a partially applied native (a lambda over its open parameters in jsonata-js). */
    fun partialOfPartial(proc: PartialNative, args: Array<Any?>): PartialNative {
        val bound = proc.bound.copyOf()
        var k = 0
        for (i in bound.indices) {
            if (bound[i] === PartialNative.PLACEHOLDER) bound[i] = args.getOrNull(k++)
        }
        return PartialNative(proc.native, bound)
    }

    /** A plain JS function or a transformer seen as a native for partial application. */
    fun asNative(proc: EngineFunction): NativeFunction = NativeFunction("", null, proc.arity, { ctx, args ->
        when (proc) {
            is InternalFunction -> proc.body(ctx as CallContextImpl, args)
            is Transformer -> transform(proc, args.getOrNull(0), (ctx as CallContextImpl).frame)
            else -> null
        }
    })

    private val chainNode: Node by lazy(LazyThreadSafetyMode.PUBLICATION) {
        Compiler(false).compile(JsonataParser.ast("function(\$f, \$g) { function(\$x){ \$g(\$f(\$x)) } }"))
    }

    /** The function composition lambda of `f ~> g` where both sides are functions. */
    fun chainLambda(env: Frame): Value? = chainNode.evaluate(null, env)

    // ---- transform ----

    private fun transform(proc: Transformer, obj: Value?, caller: Frame): Value? {
        if (obj == null) return null
        val node = proc.node
        // the transformer's variables, the caller's evaluation (review RV-9)
        val env = Frame(proc.env, caller.ctx)
        val cloneFunction = env.lookup("clone")
        if (cloneFunction !is FunctionValue) throw JsonataException("T2013", node.position)
        var result = apply(cloneFunction, listOf(obj), NullValue, env, null)
        // a user-defined $clone may return (parts of) its argument: its result is copied before it is updated, so
        // that the input document is never modified (decision 8, review RV-2); the standard $clone is already a copy
        if (cloneFunction !== FunctionRegistry.standard().lookup("clone")) result = copyContainers(result, env.ctx)
        var matches = node.pattern.evaluate(result, env)
        if (matches != null) {
            if (matches !is ArrayValue) matches = ArrayValue.sequence(matches)
            // the reference updates the matched objects in place; only objects of the clone may change, so that the
            // input document is never modified (a pattern such as `$$.x` would otherwise update the input)
            val owned = IdentityHashMap<Value, Boolean>()
            collectContainers(result, owned, env.ctx)
            for (i in 0 until matches.size) {
                env.ctx.tick()
                val match = matches.raw(i)
                val update = node.update.evaluate(match, env)
                if (update != null) {
                    if (update !is ObjectValue) throw JsonataException("T2011", node.update.position, value = update)
                    if (match is ObjectValue && owned.containsKey(match)) {
                        for (k in update.keys) {
                            match.put(k, update[k]!!)
                            env.ctx.tick()
                        }
                    }
                }
                val delete = node.delete
                if (delete != null) {
                    val deletions = delete.evaluate(match, env)
                    if (deletions != null) {
                        val list = if (deletions is ArrayValue) deletions else ArrayValue.sequence(deletions)
                        for (j in 0 until list.size) {
                            if (list.raw(j) !is StringValue) throw JsonataException("T2012", delete.position, value = deletions)
                            env.ctx.tick()
                        }
                        if (match is ObjectValue && owned.containsKey(match)) {
                            for (j in 0 until list.size) {
                                match.remove((list.raw(j) as StringValue).value)
                                env.ctx.tick()
                            }
                        }
                    }
                }
            }
        }
        return result
    }

    /** A structural copy of arrays and objects (values, sequence flags and undefined items kept as they are). */
    fun copyContainers(v: Value?, ctx: EvalContext? = null): Value? {
        if (v !is ArrayValue && v !is ObjectValue) return v
        fun shallow(x: Value): Value = when (x) {
            is ArrayValue -> ArrayValue(x.size).also { c ->
                c.isSequence = x.isSequence
                c.keepSingleton = x.keepSingleton
                c.outerWrapper = x.outerWrapper
                c.cons = x.cons
                for (i in 0 until x.size) c.addNullable(x.raw(i))
            }
            is ObjectValue -> ObjectValue().also { c -> x.forEach { k, value -> c.put(k, value) } }
            else -> x
        }
        val root = shallow(v)
        val stack = ArrayList<Value>()
        stack += root
        while (stack.isNotEmpty()) {
            ctx?.tick()
            when (val c = stack.removeAt(stack.size - 1)) {
                is ArrayValue -> for (i in 0 until c.size) {
                    val item = c.raw(i)
                    if (item is ArrayValue || item is ObjectValue) {
                        val copy = shallow(item)
                        c.set(i, copy)
                        stack += copy
                    }
                }
                is ObjectValue -> for (k in c.keys) {
                    val item = c[k]
                    if (item is ArrayValue || item is ObjectValue) {
                        val copy = shallow(item)
                        c.put(k, copy)
                        stack += copy
                    }
                }
                else -> {}
            }
        }
        return root
    }

    private fun collectContainers(v: Value?, out: IdentityHashMap<Value, Boolean>, ctx: EvalContext) {
        val stack = ArrayList<Value>()
        if (v != null) stack += v
        while (stack.isNotEmpty()) {
            ctx.tick()
            val x = stack.removeAt(stack.size - 1)
            when (x) {
                is ObjectValue -> if (out.put(x, true) == null) x.forEach { _, c -> stack += c }
                is ArrayValue -> if (out.put(x, true) == null) for (i in 0 until x.size) x.raw(i)?.let { stack += it }
                else -> {}
            }
        }
    }

    /** jsonata.js: functionClone — `JSON.parse($string(arg))`. */
    fun clone(arg: Value?, ctx: EvalContext? = null): Value? {
        if (arg == null) return null
        val a = if (arg is ArrayValue && arg.outerWrapper && arg.size > 0) arg.raw(0) else arg
        return deepCopy(a!!, ctx)
    }

    /** `JSON.parse($string(v))` without recursion (review RV-4). */
    private fun deepCopy(v: Value, ctx: EvalContext?): Value {
        fun scalar(x: Value): Value = when (x) {
            is NumberValue -> {
                val d = x.value
                if (d.isInfinite()) throw JsonataException("D1001", value = x)
                if (d.isNaN()) NullValue
                else if (d != Math.floor(d)) NumberValue.of(su.seaf.archtool.jsonata.value.JsNumber.toPrecision15(d))
                else if (d == 0.0) NumberValue.of(0.0)
                else x
            }
            is FunctionValue -> StringValue.EMPTY
            is ArrayValue -> ArrayValue(x.size)
            is ObjectValue -> ObjectValue()
            else -> x
        }
        val root = scalar(v)
        val stack = ArrayList<Pair<Value, Value>>() // source container, empty copy
        if (v is ArrayValue || v is ObjectValue) stack += v to root
        while (stack.isNotEmpty()) {
            val (src, dst) = stack.removeAt(stack.size - 1)
            ctx?.tick()
            if (src is ArrayValue) {
                val out = dst as ArrayValue
                for (i in 0 until src.size) {
                    ctx?.tick()
                    val item = src.raw(i)
                    val copy = if (item == null) NullValue else scalar(item)
                    out.add(copy)
                    if (item is ArrayValue || item is ObjectValue) stack += item to copy
                }
            } else {
                val out = dst as ObjectValue
                (src as ObjectValue).forEach { k, item ->
                    ctx?.tick()
                    val copy = scalar(item)
                    out.put(k, copy)
                    if (item is ArrayValue || item is ObjectValue) stack += item to copy
                }
            }
        }
        return root
    }

    // ---- paths ----

    /** jsonata.js: evaluateStep. */
    fun step(expr: Node, input: Value?, env: Frame, lastStep: Boolean): Value? {
        if (expr is SortNode) {
            var result = sort(expr, input, env)
            val stages = expr.stages
            if (stages != null) result = stages(stages, result, env)
            return result
        }
        val result = ArrayValue(if (input is ArrayValue) input.size else 1)
        result.isSequence = true
        val stages = expr.stages
        var flatten = false
        val observed = env.ctx.listener != null
        val count = if (input is ArrayValue) input.size else if (input is StringValue) input.value.length else 0
        var index = 0
        forEachItem(input) { item ->
            // the debugger sees which item of the step's input is evaluated (one test outside the loop body otherwise)
            if (observed) env.ctx.iteration = su.seaf.archtool.jsonata.debug.Iteration(index++, count)
            var res = expr.evaluate(item, env)
            if (stages != null) {
                for (s in stages) if (s is FilterStage) res = filter(s.expr, res, env)
            }
            if (res != null) {
                result.add(res)
                if (res is ArrayValue && !res.cons) flatten = true
            }
        }
        env.ctx.checkSequence(result.size)
        if (lastStep && result.size == 1) {
            val only = result.raw(0)
            if (only is ArrayValue && !only.isSequence) return only
        }
        // nothing to flatten: the collected sequence is already the result
        if (!flatten) return result
        val resultSequence = ArrayValue.sequence()
        for (i in 0 until result.size) {
            val res = result.raw(i)
            if (res !is ArrayValue || res.cons) {
                resultSequence.addNullable(res)
            } else {
                for (j in 0 until res.size) {
                    // DEV-12: undefined does not enter sequences
                    val v = res.raw(j)
                    if (v != null) resultSequence.add(v) else env.ctx.deviation(Deviations.UNDEFINED_IN_SEQUENCES.id, expr.position)
                }
            }
        }
        env.ctx.checkSequence(resultSequence.size)
        return resultSequence
    }

    /** Iterates `input[0..input.length)` as JS does: arrays, and strings by UTF-16 unit; anything else has no length. */
    private inline fun forEachItem(input: Value?, action: (Value?) -> Unit) {
        when (input) {
            is ArrayValue -> {
                var i = 0
                while (i < input.size) action(input.raw(i++))
            }
            is StringValue -> for (c in input.value) action(StringValue.of(c.toString()))
            else -> {}
        }
    }

    /** JS `value.length` for the "empty result" test of evaluatePath: arrays and strings; -1 for no length. */
    fun jsLength(v: Value): Int = when (v) {
        is ArrayValue -> v.size
        is StringValue -> v.value.length
        else -> -1
    }

    /** jsonata.js: evaluateStages. */
    private fun stages(stages: Array<Stage>, input: Value?, env: Frame): Value? {
        var result = input
        for (stage in stages) {
            when (stage) {
                is FilterStage -> result = filter(stage.expr, result, env)
                is IndexStage -> if (result is ArrayValue) {
                    for (ee in 0 until result.size) (result.raw(ee) as? ObjectValue)?.put(stage.name, NumberValue.of(ee))
                }
            }
        }
        return result
    }

    private fun tuple(at: Value?): ObjectValue = ObjectValue().also { if (at != null) it.put("@", at) }

    /** jsonata.js: evaluateTupleStep. */
    fun tupleStep(expr: Node, input: Value?, tupleBindings: ArrayValue?, env: Frame): ArrayValue {
        if (expr is SortNode) {
            var result: Value?
            if (tupleBindings != null) {
                result = sort(expr, tupleBindings, env)
            } else {
                val sorted = sort(expr, input, env)
                val seq = ArrayValue.sequence()
                seq.tupleStream = true
                forEachItem(sorted) { v ->
                    val t = tuple(v)
                    expr.indexVar?.let { t.put(it, NumberValue.of(seq.size)) }
                    seq.add(t)
                }
                result = seq
            }
            val stages = expr.stages
            if (stages != null) result = stages(stages, result, env)
            return result as ArrayValue
        }
        val result = ArrayValue.sequence()
        result.tupleStream = true
        val bindings = tupleBindings ?: ArrayValue().also { tb ->
            val items = if (input is ArrayValue) input else ArrayValue.sequenceOf(input)
            for (i in 0 until items.size) tb.add(tuple(items.raw(i)))
        }
        for (ee in 0 until bindings.size) {
            val te = bindings.raw(ee) as ObjectValue
            val stepEnv = frameFromTuple(env, te)
            val at = te["@"]
            val res = expr.evaluate(at, stepEnv) ?: continue
            val arr = if (res is ArrayValue) res else ArrayValue.sequence(res)
            for (bb in 0 until arr.size) {
                val t = ObjectValue()
                te.forEach { k, v -> t.put(k, v) }
                val item = arr.raw(bb)
                if (res is ArrayValue && res.tupleStream) {
                    (item as ObjectValue).forEach { k, v -> t.put(k, v) }
                } else {
                    val focus = expr.focus
                    if (focus != null) {
                        if (item != null) t.put(focus, item) else t.remove(focus)
                        if (at != null) t.put("@", at)
                    } else {
                        if (item != null) t.put("@", item) else t.remove("@")
                    }
                    expr.indexVar?.let { t.put(it, NumberValue.of(bb)) }
                    expr.ancestorLabel?.let { if (at != null) t.put(it, at) }
                }
                result.add(t)
            }
        }
        env.ctx.checkSequence(result.size)
        val stages = expr.stages
        return if (stages != null) stages(stages, result, env) as ArrayValue else result
    }

    fun frameFromTuple(env: Frame, tuple: ObjectValue): Frame {
        val frame = Frame(env, env.ctx)
        frame.tupleFrame = true
        tuple.forEach { k, v -> frame.bind(k, v) }
        return frame
    }

    /** jsonata.js: evaluateFilter. */
    fun filter(predicate: Node, input: Value?, env: Frame): Value? {
        var results = ArrayValue.sequence()
        val tupleStream = input is ArrayValue && input.tupleStream
        if (tupleStream) results.tupleStream = true
        val items = if (input is ArrayValue) input else ArrayValue.sequenceOf(input)
        val literal = (predicate as? LiteralNode)?.value
        if (literal is NumberValue && predicate.ast.type == "number") {
            var index = Math.floor(literal.value)
            if (index < 0) index += items.size
            val item = if (index >= 0 && index < items.size) items.raw(index.toInt()) else null
            if (item != null) {
                if (item is ArrayValue) return item
                results.add(item)
            }
            return results
        }
        for (index in 0 until items.size) {
            val item = items.raw(index)
            var context = item
            var e = env
            if (tupleStream) {
                context = (item as ObjectValue)["@"]
                e = frameFromTuple(env, item)
            }
            var res = predicate.evaluate(context, e)
            if (isNumeric(res)) res = ArrayValue.sequence(res!!)
            if (isArrayOfNumbers(res)) {
                val arr = res as ArrayValue
                for (k in 0 until arr.size) {
                    var ii = Math.floor((arr.raw(k) as NumberValue).value)
                    if (ii < 0) ii += items.size
                    if (ii == index.toDouble()) addDefined(results, item, predicate, env)
                }
            } else if (Values.toBoolean(res) == true) {
                addDefined(results, item, predicate, env)
            }
        }
        env.ctx.checkSequence(results.size)
        return results
    }

    /**
     * DEV-12: a filter keeps no undefined item. jsonata-js wraps a missing value into `[undefined]` and keeps it when
     * the predicate accepts it (`x.a[true]` gives `[1, undefined, undefined]`, written `[1,null,null]`); the predicate
     * is still evaluated for it, so errors and side effects are those of the reference.
     */
    private fun addDefined(results: ArrayValue, item: Value?, predicate: Node, env: Frame) {
        if (item != null) results.add(item) else env.ctx.deviation(Deviations.UNDEFINED_IN_SEQUENCES.id, predicate.position)
    }

    private fun isArrayOfNumbers(v: Value?): Boolean {
        if (v !is ArrayValue) return false
        for (i in 0 until v.size) if (!isNumeric(v.raw(i))) return false
        return true
    }

    /** jsonata.js: evaluateGroupExpression. */
    fun group(spec: GroupSpec, input: Value?, env: Frame): Value {
        val result = ObjectValue()
        val groups = LinkedHashMap<String, GroupEntry>()
        val reduce = input is ArrayValue && input.tupleStream
        var items = if (input is ArrayValue) input else ArrayValue.sequenceOf(input)
        if (items.size == 0 && !reduce) items = ArrayValue.sequenceOf(null)
        for (itemIndex in 0 until items.size) {
            val item = items.raw(itemIndex)
            val e = if (reduce) frameFromTuple(env, item as ObjectValue) else env
            for (pairIndex in spec.pairs.indices) {
                val pair = spec.pairs[pairIndex]
                val key = pair[0].evaluate(if (reduce) (item as ObjectValue)["@"] else item, e)
                if (key != null && key !is StringValue) throw JsonataException("T1003", spec.position, value = key)
                if (key != null) {
                    val k = (key as StringValue).value
                    if (k == "_jsonata_lambda" || k == "_jsonata_function") throw JsonataException("D1013", spec.position, value = key)
                    val existing = groups[k]
                    if (existing != null) {
                        if (existing.exprIndex != pairIndex) throw JsonataException("D1009", spec.position, value = key)
                        existing.append(item)
                    } else {
                        groups[k] = GroupEntry(item, pairIndex)
                    }
                }
            }
        }
        for (key in jsKeyOrder(groups.keys)) {
            val entry = groups[key]!!
            var context = entry.data
            var e = env
            if (reduce) {
                val t = reduceTupleStream(entry.data)
                context = t["@"]
                t.remove("@")
                e = frameFromTuple(env, t)
            }
            val value = spec.pairs[entry.exprIndex][1].evaluate(context, e)
            if (value != null) result.put(key, value)
        }
        return result
    }

    /**
     * The items grouped under one key. The reference appends with `fn.append` (a JS `concat`, a copy per item); the
     * result is the same plain array, built here in one buffer.
     */
    private class GroupEntry(first: Value?, val exprIndex: Int) {
        private var single: Value? = first
        private var buffer: ArrayValue? = null

        val data: Value? get() = buffer ?: single

        fun append(item: Value?) {
            if (item == null) return
            var b = buffer
            if (b == null) {
                val first = single ?: run { single = item; return }
                b = ArrayValue()
                if (first is ArrayValue) for (i in 0 until first.size) b.addNullable(first.raw(i)) else b.add(first)
                buffer = b
            }
            if (item is ArrayValue) for (i in 0 until item.size) b.addNullable(item.raw(i)) else b.add(item)
        }
    }

    private fun jsKeyOrder(keys: Collection<String>): List<String> {
        if (keys.none { ObjectValue.isArrayIndex(it) }) return ArrayList(keys)
        return keys.filter { ObjectValue.isArrayIndex(it) }.sortedBy { it.toLong() } + keys.filter { !ObjectValue.isArrayIndex(it) }
    }

    /** jsonata.js: reduceTupleStream. */
    private fun reduceTupleStream(stream: Value?): ObjectValue {
        if (stream !is ArrayValue) return ObjectValue().also { t -> (stream as ObjectValue).forEach { k, v -> t.put(k, v) } }
        val result = ObjectValue()
        (stream.raw(0) as ObjectValue).forEach { k, v -> result.put(k, v) }
        for (ii in 1 until stream.size) {
            val t = stream.raw(ii) as ObjectValue
            t.forEach { k, v -> append(result[k], v)?.let { result.put(k, it) } }
        }
        return result
    }

    // ---- sort ----

    /** jsonata.js: evaluateSortExpression. */
    fun sort(expr: SortNode, input: Value?, env: Frame): Value? {
        val isTupleSort = input is ArrayValue && input.tupleStream
        val comparator = { a: Value?, b: Value? ->
            var comp = 0
            var index = 0
            while (comp == 0 && index < expr.terms.size) {
                val term = expr.terms[index++]
                var context = a
                var e = env
                if (isTupleSort) {
                    context = (a as ObjectValue)["@"]
                    e = frameFromTuple(env, a)
                }
                val aa = term.expression.evaluate(context, e)
                context = b
                e = env
                if (isTupleSort) {
                    context = (b as ObjectValue)["@"]
                    e = frameFromTuple(env, b)
                }
                val bb = term.expression.evaluate(context, e)
                if (aa == null) {
                    comp = if (bb == null) 0 else 1
                    continue
                }
                if (bb == null) {
                    comp = -1
                    continue
                }
                val aOk = aa is StringValue || aa is NumberValue
                val bOk = bb is StringValue || bb is NumberValue
                if (!aOk || !bOk) throw JsonataException("T2008", expr.position, value = if (!aOk) aa else bb)
                if (aa.javaClass != bb.javaClass) throw JsonataException("T2007", expr.position, value = aa, value2 = bb)
                val cmp = jsCompare(aa, bb)
                if (cmp == JS_EQUAL) continue
                comp = if (cmp == JS_LESS) -1 else 1
                if (term.descending) comp = -comp
            }
            comp == 1
        }
        return mergeSort(input, env.ctx, comparator)
    }

    private const val JS_LESS = -1
    private const val JS_EQUAL = 0
    private const val JS_OTHER = 1

    /** `a === b` / `a < b` for two strings or two numbers: [JS_EQUAL], [JS_LESS], or [JS_OTHER] (greater or NaN). */
    fun jsCompare(a: Value, b: Value): Int {
        if (a is NumberValue && b is NumberValue) {
            return if (a.value == b.value) JS_EQUAL else if (a.value < b.value) JS_LESS else JS_OTHER
        }
        val c = (a as StringValue).value.compareTo((b as StringValue).value)
        return if (c == 0) JS_EQUAL else if (c < 0) JS_LESS else JS_OTHER
    }

    /**
     * functions.js: sort — the reference's stable top-down merge sort, with the same sequence of comparator calls
     * (the result for non-strict comparators depends on it). `swap(a, b)` true means b goes first.
     */
    fun mergeSort(arr: Value?, ctx: EvalContext, swap: (Value?, Value?) -> Boolean): Value? {
        if (arr == null) return null
        if (arr !is ArrayValue || arr.size <= 1) return arr
        val items = arrayOfNulls<Value>(arr.size)
        for (i in 0 until arr.size) items[i] = arr.raw(i)
        val sorted = msort(items, ctx, swap)
        val out = ArrayValue(sorted.size)
        for (v in sorted) out.addNullable(v)
        return out
    }

    private fun msort(array: Array<Value?>, ctx: EvalContext, swap: (Value?, Value?) -> Boolean): Array<Value?> {
        if (array.size <= 1) return array
        val middle = array.size / 2
        val left = msort(array.copyOfRange(0, middle), ctx, swap)
        val right = msort(array.copyOfRange(middle, array.size), ctx, swap)
        val merged = arrayOfNulls<Value>(array.size)
        var i = 0
        var j = 0
        var k = 0
        while (i < left.size && j < right.size) {
            if (swap(left[i], right[j])) merged[k++] = right[j++] else merged[k++] = left[i++]
            ctx.tick()
        }
        while (i < left.size) merged[k++] = left[i++]
        while (j < right.size) merged[k++] = right[j++]
        ctx.checkpointIfGuarded()
        return merged
    }

    private fun EvalContext.checkpointIfGuarded() {
        checkpoint()
    }

    // ---- operators and helpers from functions.js / utils.js ----

    /** utils.js: isNumeric — a non-NaN number; an infinite number raises D1001. */
    fun isNumeric(v: Value?): Boolean {
        if (v !is NumberValue) return false
        val d = v.value
        if (d.isNaN()) return false
        if (d.isInfinite()) throw JsonataException("D1001", value = v)
        return true
    }

    fun boolize(v: Value?): Boolean = Values.toBoolean(v) ?: false

    /** jsonata.js: evaluateNumericExpression. */
    fun numeric(l: Value?, r: Value?, op: BinaryOp): Value? {
        if (l != null && !isNumeric(l)) throw JsonataException("T2001", value = l)
        if (r != null && !isNumeric(r)) throw JsonataException("T2002", value = r)
        if (l == null || r == null) return null
        val a = (l as NumberValue).value
        val b = (r as NumberValue).value
        return NumberValue.of(
            when (op) {
                BinaryOp.ADD -> a + b
                BinaryOp.SUB -> a - b
                BinaryOp.MUL -> a * b
                BinaryOp.DIV -> a / b
                else -> a % b
            },
        )
    }

    /** jsonata.js: evaluateComparisonExpression. */
    fun compare(l: Value?, r: Value?, op: BinaryOp): Value? {
        val lc = l == null || l is StringValue || l is NumberValue
        val rc = r == null || r is StringValue || r is NumberValue
        if (!lc || !rc) {
            throw JsonataException("T2010", value = if (!(l is StringValue || l is NumberValue)) l else r)
        }
        if (l == null || r == null) return null
        if (l.javaClass != r.javaClass) throw JsonataException("T2009", value = l, value2 = r)
        val result = if (l is NumberValue) {
            val a = l.value
            val b = (r as NumberValue).value
            when (op) {
                BinaryOp.LT -> a < b
                BinaryOp.LE -> a <= b
                BinaryOp.GT -> a > b
                else -> a >= b
            }
        } else {
            val c = (l as StringValue).value.compareTo((r as StringValue).value)
            when (op) {
                BinaryOp.LT -> c < 0
                BinaryOp.LE -> c <= 0
                BinaryOp.GT -> c > 0
                else -> c >= 0
            }
        }
        return BooleanValue.of(result)
    }

    /** jsonata.js: evaluateIncludesExpression — `===` membership. */
    fun includes(l: Value?, r: Value?): Value {
        if (l == null || r == null) return BooleanValue.FALSE
        if (r !is ArrayValue) return BooleanValue.of(strictEquals(r, l))
        for (i in 0 until r.size) if (strictEquals(r.raw(i), l)) return BooleanValue.TRUE
        return BooleanValue.FALSE
    }

    /** JS `===`: numbers and strings by value, everything else by identity. */
    fun strictEquals(a: Value?, b: Value?): Boolean = when (a) {
        is NumberValue -> b is NumberValue && a.value == b.value
        is StringValue -> b is StringValue && a.value == b.value
        else -> a === b
    }

    /** jsonata.js: evaluateRangeExpression. */
    fun range(l: Value?, r: Value?, env: Frame): Value? {
        if (l != null && !(l is NumberValue && l.isInteger)) throw JsonataException("T2003", value = l)
        if (r != null && !(r is NumberValue && r.isInteger)) throw JsonataException("T2004", value = r)
        if (l == null || r == null) return null
        val a = (l as NumberValue).value
        val b = (r as NumberValue).value
        if (a > b) return null
        val size = b - a + 1
        if (size > 1e7) throw JsonataException("D2014", value = NumberValue.of(size))
        val n = size.toInt()
        env.ctx.checkSequence(n)
        val result = ArrayValue(n)
        result.isSequence = true
        var x = a
        val ctx = env.ctx
        for (i in 0 until n) {
            result.add(NumberValue.of(x))
            x += 1.0
            ctx.tick()
        }
        return result
    }

    /** functions.js: string. */
    fun string(arg: Value, prettify: Boolean = false, ctx: EvalContext? = null): String {
        val tick: ((Int) -> Unit)? = ctx?.let { c -> { length -> c.tick(); c.checkString(length.toLong()) } }
        return when (arg) {
            is StringValue -> arg.value
            is FunctionValue -> ""
            is NumberValue -> {
                if (arg.value.isNaN() || arg.value.isInfinite()) throw JsonataException("D3001", value = arg)
                JsonWriter.stringifyJsonata(arg, prettify)
            }
            is ArrayValue -> {
                val a = if (arg.outerWrapper) arg.raw(0) else arg
                if (a == null) "undefined" else JsonWriter.stringifyJsonata(a, prettify, tick)
            }
            else -> JsonWriter.stringifyJsonata(arg, prettify, tick)
        }
    }

    /** functions.js: lookup. */
    fun lookup(input: Value?, key: String, ctx: EvalContext? = null): Value? {
        if (input is ArrayValue) {
            val result = ArrayValue.sequence()
            for (i in 0 until input.size) {
                ctx?.tick()
                val res = lookup(input.raw(i), key, ctx) ?: continue
                if (res is ArrayValue) {
                    for (j in 0 until res.size) result.addNullable(res.raw(j))
                } else {
                    result.add(res)
                }
            }
            return result
        }
        if (input is ObjectValue) return input[key]
        return null
    }

    /** functions.js: append — the concatenation is a plain array (JS `concat` does not keep the sequence flag). */
    fun append(arg1: Value?, arg2: Value?, ctx: EvalContext? = null): Value? {
        if (arg1 == null) return arg2
        if (arg2 == null) return arg1
        val a = if (arg1 is ArrayValue) arg1 else ArrayValue.sequence(arg1)
        val out = ArrayValue(a.size + 1)
        for (i in 0 until a.size) {
            out.addNullable(a.raw(i))
            ctx?.tick()
        }
        if (arg2 is ArrayValue) {
            for (i in 0 until arg2.size) {
                out.addNullable(arg2.raw(i))
                ctx?.tick()
            }
        } else {
            out.add(arg2)
        }
        return out
    }

    /** jsonata.js: flatten — every level of nesting, in order, without recursion. */
    fun flatten(arg: Value?, out: ArrayValue, ctx: EvalContext? = null) {
        if (arg !is ArrayValue) {
            out.addNullable(arg)
            return
        }
        val arrays = ArrayList<ArrayValue>()
        val indexes = ArrayList<Int>()
        arrays += arg
        indexes += 0
        while (arrays.isNotEmpty()) {
            val top = arrays.size - 1
            val a = arrays[top]
            val i = indexes[top]
            if (i >= a.size) {
                arrays.removeAt(top)
                indexes.removeAt(top)
                continue
            }
            indexes[top] = i + 1
            ctx?.tick()
            val item = a.raw(i)
            if (item is ArrayValue) {
                arrays += item
                indexes += 0
            } else {
                out.addNullable(item)
            }
        }
    }

    /** jsonata.js: recurseDescendants — pre-order, without recursion. */
    fun recurseDescendants(input: Value?, results: ArrayValue, ctx: EvalContext? = null) {
        val stack = ArrayList<Iterator<Value?>>()
        fun visit(v: Value?) {
            ctx?.tick()
            if (v !is ArrayValue) results.addNullable(v)
            if (v is ArrayValue) stack += (0 until v.size).asSequence().map { v.raw(it) }.iterator()
            else if (v is ObjectValue) stack += v.keys.asSequence().map { v[it] }.iterator()
        }
        visit(input)
        while (stack.isNotEmpty()) {
            val it = stack[stack.size - 1]
            if (it.hasNext()) visit(it.next()) else stack.removeAt(stack.size - 1)
        }
    }
}
