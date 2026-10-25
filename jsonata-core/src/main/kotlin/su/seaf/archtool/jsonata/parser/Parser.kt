package su.seaf.archtool.jsonata.parser

/**
 * Port of jsonata-js 2.2.2 parser.js: Pratt parser + processAST. The AST equals `jsonata(expr).ast()` of the reference.
 * The `recover` mode of the reference is not ported (core does not use it).
 */
internal class Parser(
    private val source: String,
    onToken: ((Token) -> Unit)? = null,
    /** Record the source range of every node (CAP-9, CAP-10): [parseWithRanges]. */
    private val withRanges: Boolean = false,
) {
    private val lexer = Tokenizer(source, onToken)

    /** Own ranges: the token of a node, extended to its closing bracket; children are added by [ranges]. */
    private val own = java.util.IdentityHashMap<AstNode, IntArray>()

    /** End offset of the token consumed by the last [advance] (the closing bracket after `advance(")")`). */
    private var consumedEnd = -1
    private val symbols = HashMap<String, Symbol>()
    private lateinit var node: AstNode

    val comments: List<Comment> get() = lexer.comments

    // parser.js: operators (binding powers)
    private val operators = mapOf(
        "." to 75, "[" to 80, "]" to 0, "{" to 70, "}" to 0, "(" to 80, ")" to 0, "," to 0, "@" to 80, "#" to 80,
        ";" to 80, ":" to 80, "?" to 20, "+" to 50, "-" to 50, "*" to 60, "/" to 60, "%" to 60, "|" to 20, "=" to 40,
        "<" to 40, ">" to 40, "^" to 40, "**" to 60, ".." to 20, ":=" to 10, "!=" to 40, "<=" to 40, ">=" to 40,
        "~>" to 40, "?:" to 40, "??" to 40, "and" to 30, "or" to 25, "in" to 40, "&" to 50, "!" to 0, "~" to 0,
    )

    private fun bp(id: String): Int = operators.getValue(id)

    // base_symbol.nud: the symbol has been invoked as a unary operator
    private val baseNud: (AstNode) -> AstNode = { self -> throw JsonataException("S0211", self.position, token = self.value) }

    private fun symbol(id: String, bp: Int = 0): Symbol {
        val existing = symbols[id]
        if (existing != null) {
            if (bp >= existing.lbp) existing.lbp = bp
            return existing
        }
        return Symbol(id).also { it.lbp = bp; it.nud = baseNud; symbols[id] = it }
    }

    private fun handleError(err: JsonataException): Nothing = throw err

    private fun advance(id: String? = null, infix: Boolean = false): AstNode {
        if (id != null && node.id != id) {
            val code = if (node.id == "(end)") "S0203" else "S0202"
            handleError(JsonataException(code, node.position, token = node.value, value = id))
        }
        if (withRanges && ::node.isInitialized) consumedEnd = node.position ?: consumedEnd
        val nextToken = lexer.next(infix)
        if (nextToken == null) {
            // the (end) symbol object itself is the node, as in the reference
            node = endNode
            node["position"] = source.length.toDouble()
            return node
        }
        val value = nextToken.value
        var type = nextToken.type.js
        val sym: Symbol = when (nextToken.type) {
            TokenType.NAME, TokenType.VARIABLE -> symbols.getValue("(name)")
            TokenType.OPERATOR -> symbols[value as String]
                ?: handleError(JsonataException("S0204", nextToken.end, token = value))
            TokenType.STRING, TokenType.NUMBER, TokenType.VALUE -> symbols.getValue("(literal)")
            TokenType.REGEX -> { type = "regex"; symbols.getValue("(regex)") }
        }
        node = AstNode(sym)
        node["value"] = value
        node["type"] = type
        node["position"] = nextToken.end.toDouble()
        if (withRanges) own[node] = intArrayOf(nextToken.start, nextToken.end)
        return node
    }

    /** Extends the own range of [self] to the token just consumed (a closing bracket). */
    private fun closed(self: AstNode) {
        if (!withRanges) return
        val r = own[self] ?: return
        if (consumedEnd > r[1]) r[1] = consumedEnd
    }

    /** Gives [target] the own range of [from] (a node made from it). */
    private fun sameRange(target: AstNode, from: AstNode) {
        if (!withRanges || own.containsKey(target)) return
        own[from]?.let { own[target] = it.copyOf() }
    }

    /** Widens the own range of [target] over [nodes] (the `@`/`#` operator and its variable, which are dropped). */
    private fun widen(target: AstNode, vararg nodes: AstNode?) {
        if (!withRanges) return
        for (n in nodes) {
            val r = own[n ?: continue] ?: continue
            val t = own[target]
            if (t == null) own[target] = r.copyOf() else { t[0] = minOf(t[0], r[0]); t[1] = maxOf(t[1], r[1]) }
        }
    }

    /**
     * `JSON.parse(JSON.stringify(left))` of `??` and `?:`: every node of the copy takes the range of the operator
     * [op], so that the condition the parser synthesizes points at the operator and the original operand keeps the
     * source of `left` (design §2.2: one source place, the outermost executable node gets the breakpoint).
     */
    private fun copyWithRanges(value: AstNode, op: AstNode): AstNode {
        val copy = AstNode.jsonCopy(value) as AstNode
        if (withRanges) own[op]?.let { r -> markAll(copy, r) }
        return copy
    }

    private fun markAll(v: Any?, r: IntArray) {
        when (v) {
            is AstNode -> {
                own[v] = r.copyOf()
                for (x in v.values) markAll(x, r)
            }
            is List<*> -> for (x in v) markAll(x, r)
        }
    }

    private lateinit var endNode: AstNode

    // Pratt's algorithm
    private fun expression(rbp: Int): AstNode {
        var t = node
        advance(null, true)
        var left = t.symbol!!.nud!!(t)
        while (rbp < node.symbol!!.lbp) {
            t = node
            advance()
            left = t.symbol!!.led!!(t, left)
        }
        return left
    }

    private fun terminal(id: String) {
        symbol(id, 0).nud = { self -> self }
    }

    private fun infix(id: String, bp: Int? = null, led: ((AstNode, AstNode) -> AstNode)? = null): Symbol {
        val bindingPower = bp ?: operators.getValue(id)
        val s = symbol(id, bindingPower)
        s.led = led ?: { self, left ->
            self["lhs"] = left
            self["rhs"] = expression(bindingPower)
            self["type"] = "binary"
            self
        }
        return s
    }

    private fun infixr(id: String, bp: Int, led: (AstNode, AstNode) -> AstNode): Symbol {
        val s = symbol(id, bp)
        s.led = led
        return s
    }

    private fun prefix(id: String, nud: ((AstNode) -> AstNode)? = null): Symbol {
        val s = symbol(id)
        s.nud = nud ?: { self ->
            self["expression"] = expression(70)
            self["type"] = "unary"
            self
        }
        return s
    }

    init {
        terminal("(end)")
        terminal("(name)")
        terminal("(literal)")
        terminal("(regex)")
        endNode = AstNode(symbols.getValue("(end)"))
        symbol(":"); symbol(";"); symbol(","); symbol(")"); symbol("]"); symbol("}")
        symbol("..") // range operator
        for (op in listOf(".", "+", "-", "*", "/", "%", "=", "<", ">", "!=", "<=", ">=", "&", "and", "or", "in")) infix(op)
        terminal("and"); terminal("or"); terminal("in") // the 'keywords' can also be used as terminals (field names)
        prefix("-") // unary numeric negation
        infix("~>") // function application

        // coalescing operator
        infix("??", bp("??")) { self, left ->
            self["type"] = "condition"
            val exists = AstNode.of("type" to "variable", "value" to "exists")
            val condition = AstNode.of(
                "type" to "function",
                "value" to "(",
                "procedure" to exists,
                "arguments" to mutableListOf<Any?>(copyWithRanges(left, self)),
            )
            // the synthetic $exists(left) takes the range of the operator
            sameRange(condition, self)
            sameRange(exists, self)
            self["condition"] = condition
            self["then"] = left
            self["else"] = expression(0)
            self
        }

        prefix("*") { self -> self["type"] = "wildcard"; self } // field wildcard (single level)
        prefix("**") { self -> self["type"] = "descendant"; self } // descendant wildcard (multi-level)
        prefix("%") { self -> self["type"] = "parent"; self } // parent operator

        // function invocation
        infix("(", bp("(")) { self, left -> functionInvocation(self, left) }

        // parenthesis - block expression
        prefix("(") { self ->
            val expressions = ArrayList<Any?>()
            while (node.id != ")") {
                expressions += expression(0)
                if (node.id != ";") break
                advance(";")
            }
            advance(")", true)
            closed(self)
            self["type"] = "block"
            self["expressions"] = expressions
            self
        }

        // array constructor
        prefix("[") { self ->
            val a = ArrayList<Any?>()
            if (node.id != "]") {
                while (true) {
                    var item = expression(0)
                    if (node.id == "..") {
                        // range operator
                        val range = AstNode.of("type" to "binary", "value" to "..", "position" to node["position"], "lhs" to item)
                        advance("..")
                        range["rhs"] = expression(0)
                        item = range
                    }
                    a += item
                    if (node.id != ",") break
                    advance(",")
                }
            }
            advance("]", true)
            closed(self)
            self["expressions"] = a
            self["type"] = "unary"
            self
        }

        // filter - predicate or array index
        infix("[", bp("[")) { self, left ->
            if (node.id == "]") {
                // empty predicate means maintain singleton arrays in the output
                var step: AstNode? = left
                while (step != null && step["type"] == "binary" && step.value == "[") step = step.node("lhs")
                step!!["keepArray"] = true
                advance("]")
                left
            } else {
                self["lhs"] = left
                self["rhs"] = expression(bp("]"))
                self["type"] = "binary"
                advance("]", true)
                closed(self)
                self
            }
        }

        // order-by
        infix("^", bp("^")) { self, left ->
            advance("(")
            val terms = ArrayList<Any?>()
            while (true) {
                val term = AstNode.of("descending" to false)
                if (node.id == "<") {
                    advance("<") // ascending sort
                } else if (node.id == ">") {
                    term["descending"] = true // descending sort
                    advance(">")
                }
                term["expression"] = expression(0)
                terms += term
                if (node.id != ",") break
                advance(",")
            }
            advance(")")
            closed(self)
            self["lhs"] = left
            self["rhs"] = terms
            self["type"] = "binary"
            self
        }

        prefix("{") { self -> objectParser(self, null) } // object constructor
        infix("{", bp("{")) { self, left -> objectParser(self, left) } // object grouping

        // bind variable
        infixr(":=", bp(":=")) { self, left ->
            if (left["type"] != "variable") handleError(JsonataException("S0212", left.position, token = left.value))
            self["lhs"] = left
            self["rhs"] = expression(bp(":=") - 1) // subtract 1 from bindingPower for right associative operators
            self["type"] = "binary"
            self
        }

        // focus variable bind
        infix("@", bp("@")) { self, left ->
            self["lhs"] = left
            val rhs = expression(bp("@"))
            self["rhs"] = rhs
            if (rhs["type"] != "variable") handleError(JsonataException("S0214", rhs.position, token = "@"))
            self["type"] = "binary"
            self
        }

        // index (position) variable bind
        infix("#", bp("#")) { self, left ->
            self["lhs"] = left
            val rhs = expression(bp("#"))
            self["rhs"] = rhs
            if (rhs["type"] != "variable") handleError(JsonataException("S0214", rhs.position, token = "#"))
            self["type"] = "binary"
            self
        }

        // if/then/else ternary operator ?:
        infix("?", bp("?")) { self, left ->
            self["type"] = "condition"
            self["condition"] = left
            self["then"] = expression(0)
            if (node.id == ":") {
                advance(":")
                self["else"] = expression(0)
            }
            self
        }

        // elvis/default operator
        infix("?:", bp("?:")) { self, left ->
            self["type"] = "condition"
            self["condition"] = copyWithRanges(left, self)
            self["then"] = left
            self["else"] = expression(0)
            self
        }

        // object transformer
        prefix("|") { self ->
            self["type"] = "transform"
            self["pattern"] = expression(0)
            advance("|")
            self["update"] = expression(0)
            if (node.id == ",") {
                advance(",")
                self["delete"] = expression(0)
            }
            advance("|")
            closed(self)
            self
        }
    }

    private fun functionInvocation(self: AstNode, left: AstNode): AstNode {
        // left is what we are trying to invoke
        self["procedure"] = left
        self["type"] = "function"
        val arguments = ArrayList<Any?>()
        self["arguments"] = arguments
        if (node.id != ")") {
            while (true) {
                if (node["type"] == "operator" && node.id == "?") {
                    // partial function application
                    self["type"] = "partial"
                    arguments += node
                    advance("?")
                } else {
                    arguments += expression(0)
                }
                if (node.id != ",") break
                advance(",")
            }
        }
        advance(")", true)
        closed(self)
        // if the name of the function is 'function' or λ, then this is function definition (lambda function)
        if (left["type"] == "name" && (left.value == "function" || left.value == "λ")) {
            // all of the args must be VARIABLE tokens
            arguments.forEachIndexed { index, arg ->
                arg as AstNode
                if (arg["type"] != "variable") {
                    handleError(JsonataException("S0208", arg.position, token = arg.value, value = (index + 1).toDouble()))
                }
            }
            self["type"] = "lambda"
            // the lambda starts at the `function`/`λ` keyword, which is not kept as a child
            if (withRanges) own[left]?.let { l -> own[self]?.let { r -> r[0] = minOf(r[0], l[0]) } }
            // is the next token a '<' - if so, parse the function signature
            if (node.id == "<") {
                val sigPos = node.position ?: 0
                var depth = 1
                val sig = StringBuilder("<")
                while (depth > 0 && node.id != "{" && node.id != "(end)") {
                    val tok = advance()
                    if (tok.id == ">") depth-- else if (tok.id == "<") depth++
                    sig.append(jsString(tok.value))
                }
                advance(">")
                try {
                    self["signature"] = Signature.parse(sig.toString())
                } catch (err: JsonataException) {
                    // insert the position into this error
                    handleError(JsonataException(err.code, sigPos + (err.offset ?: 0), err.token, err.value))
                }
            }
            // parse the function body
            advance("{")
            self["body"] = expression(0)
            advance("}")
            closed(self)
        }
        return self
    }

    private fun objectParser(self: AstNode, left: AstNode?): AstNode {
        val a = ArrayList<Any?>()
        if (node.id != "}") {
            while (true) {
                val n = expression(0)
                advance(":")
                val v = expression(0)
                a.add(mutableListOf<Any?>(n, v)) // holds an array of name/value expression pairs
                if (node.id != ",") break
                advance(",")
            }
        }
        advance("}", true)
        closed(self)
        if (left == null) {
            // NUD - unary prefix form
            self["lhs"] = a
            self["type"] = "unary"
        } else {
            // LED - binary infix form
            self["lhs"] = left
            self["rhs"] = a
            self["type"] = "binary"
        }
        return self
    }

    // ------------------------------------------------------------------------------------------------- post-parse

    // tail call optimization: a lambda body ending in a function call becomes a thunk for the trampoline
    private fun tailCallOptimize(expr: AstNode): AstNode = when {
        expr["type"] == "function" && !expr.truthy("predicate") ->
            AstNode.of("type" to "lambda", "thunk" to true, "arguments" to ArrayList<Any?>(), "position" to expr.prop("position"))
                .also { it["body"] = expr }
        expr["type"] == "condition" -> {
            expr["then"] = tailCallOptimize(expr.node("then")!!)
            if (expr.has("else")) expr["else"] = tailCallOptimize(expr.node("else")!!)
            expr
        }
        expr["type"] == "block" -> {
            val expressions = expr.nodes("expressions")!!
            if (expressions.isNotEmpty()) expressions[expressions.size - 1] = tailCallOptimize(expressions.last() as AstNode)
            expr
        }
        else -> expr
    }

    private var ancestorLabel = 0
    private var ancestorIndex = 0
    private val ancestry = ArrayList<AstNode>()

    private fun level(slot: AstNode): Int = (slot["level"] as Double).toInt()
    private fun setLevel(slot: AstNode, level: Int) { slot["level"] = level.toDouble() }

    private fun seekParent(node: AstNode, slotIn: AstNode): AstNode {
        var slot = slotIn
        when (node["type"]) {
            "name", "wildcard" -> {
                setLevel(slot, level(slot) - 1)
                if (level(slot) == 0) {
                    if (!node.has("ancestor")) {
                        node["ancestor"] = slot
                    } else {
                        // reuse the existing label
                        ancestry[(slot["index"] as Double).toInt()].node("slot")!!["label"] = node.node("ancestor")!!["label"]
                        node["ancestor"] = slot
                    }
                    node["tuple"] = true
                }
            }
            "parent" -> setLevel(slot, level(slot) + 1)
            "block" -> {
                // look in last expression in the block
                val expressions = node.nodes("expressions")!!
                if (expressions.isNotEmpty()) {
                    node["tuple"] = true
                    slot = seekParent(expressions.last() as AstNode, slot)
                }
            }
            "path" -> {
                // last step in path
                node["tuple"] = true
                val steps = node.nodes("steps")!!
                var index = steps.size - 1
                slot = seekParent(steps[index--] as AstNode, slot)
                while (level(slot) > 0 && index >= 0) slot = seekParent(steps[index--] as AstNode, slot) // check previous steps
            }
            else -> throw JsonataException("S0217", node.position, token = node["type"]) // can't derive ancestor
        }
        return slot
    }

    private fun pushAncestry(result: AstNode, value: AstNode) {
        if (value.has("seekingParent") || value["type"] == "parent") {
            val slots: MutableList<Any?> = if (value.has("seekingParent")) value.nodes("seekingParent")!! else ArrayList()
            if (value["type"] == "parent") slots += value["slot"]
            if (!result.has("seekingParent")) result["seekingParent"] = slots else result.nodes("seekingParent")!!.addAll(slots)
        }
    }

    private fun resolveAncestry(path: AstNode) {
        val steps = path.nodes("steps")!!
        var index = steps.size - 1
        val laststep = steps[index] as AstNode
        val slots: MutableList<Any?> = if (laststep.has("seekingParent")) laststep.nodes("seekingParent")!! else ArrayList()
        if (laststep["type"] == "parent") slots += laststep["slot"]
        var isIndex = 0
        while (isIndex < slots.size) {
            var slot = slots[isIndex] as AstNode
            index = steps.size - 2
            while (level(slot) > 0) {
                if (index < 0) {
                    if (!path.has("seekingParent")) path["seekingParent"] = mutableListOf<Any?>(slot) else path.nodes("seekingParent")!! += slot
                    break
                }
                // try previous step
                var step = steps[index--] as AstNode
                // multiple contiguous steps that bind the focus should be skipped
                while (index >= 0 && step.has("focus") && (steps[index] as AstNode).has("focus")) step = steps[index--] as AstNode
                slot = seekParent(step, slot)
            }
            isIndex++
        }
    }

    private fun processAST(expr: AstNode): AstNode {
        var result: AstNode
        when (expr["type"]) {
            "binary" -> when (expr.value) {
                "." -> {
                    val lstep = processAST(expr.node("lhs")!!)
                    result = if (lstep["type"] == "path") lstep else AstNode.of("type" to "path", "steps" to mutableListOf<Any?>(lstep))
                    if (lstep["type"] == "parent") result["seekingParent"] = mutableListOf(lstep["slot"])
                    val rest = processAST(expr.node("rhs")!!)
                    val steps = result.nodes("steps")!!
                    if (rest["type"] == "path") {
                        steps.addAll(rest.nodes("steps")!!)
                    } else {
                        if (rest.has("predicate")) {
                            rest["stages"] = rest["predicate"]
                            rest.remove("predicate")
                        }
                        steps += rest
                    }
                    // any steps within a path that are string literals, should be changed to 'name'
                    steps.map { it as AstNode }.filter { step ->
                        if (step["type"] == "number" || step["type"] == "value") {
                            // don't allow steps to be numbers or the values true/false/null
                            throw JsonataException("S0213", step.position, value = step["value"])
                        }
                        step["type"] == "string"
                    }.forEach { lit -> lit["type"] = "name" }
                    // any step that signals keeping a singleton array, should be flagged on the path
                    if (steps.any { (it as AstNode)["keepArray"] == true }) result["keepSingletonArray"] = true
                    // if first step is a path constructor, flag it for special handling
                    val firststep = steps.first() as AstNode
                    if (firststep["type"] == "unary" && firststep.value == "[") firststep["consarray"] = true
                    // if the last step is an array constructor, flag it so it doesn't flatten
                    val laststep = steps.last() as AstNode
                    if (laststep["type"] == "unary" && laststep.value == "[") laststep["consarray"] = true
                    resolveAncestry(result)
                }
                "[" -> {
                    // predicated step: LHS is a step or a predicated step, RHS is the predicate expr
                    result = processAST(expr.node("lhs")!!)
                    var step = result
                    var type = "predicate"
                    if (result["type"] == "path") {
                        step = result.nodes("steps")!!.last() as AstNode
                        type = "stages"
                    }
                    if (step.has("group")) throw JsonataException("S0209", expr.position)
                    if (!step.has(type)) step[type] = ArrayList<Any?>()
                    val predicate = processAST(expr.node("rhs")!!)
                    if (predicate.has("seekingParent")) {
                        predicate.nodes("seekingParent")!!.forEach { s ->
                            val slot = s as AstNode
                            if (level(slot) == 1) seekParent(step, slot) else setLevel(slot, level(slot) - 1)
                        }
                        pushAncestry(step, predicate)
                    }
                    step.nodes(type)!! += AstNode.of("type" to "filter", "expr" to predicate, "position" to expr.prop("position")).also { sameRange(it, expr) }
                }
                "{" -> {
                    // group-by: LHS is a step or a predicated step, RHS is the object constructor expr
                    result = processAST(expr.node("lhs")!!)
                    if (result.has("group")) throw JsonataException("S0210", expr.position)
                    // object constructor - process each pair
                    result["group"] = AstNode.of(
                        "lhs" to expr.nodes("rhs")!!.mapTo(ArrayList()) { pair ->
                            pair as List<*>
                            mutableListOf<Any?>(processAST(pair[0] as AstNode), processAST(pair[1] as AstNode))
                        },
                        "position" to expr.prop("position"),
                    ).also { sameRange(it, expr) }
                }
                "^" -> {
                    // order-by: LHS is the array to be ordered, RHS defines the terms
                    result = processAST(expr.node("lhs")!!)
                    if (result["type"] != "path") result = AstNode.of("type" to "path", "steps" to mutableListOf<Any?>(result))
                    val sortStep = AstNode.of("type" to "sort", "position" to expr.prop("position"))
                    sameRange(sortStep, expr)
                    sortStep["terms"] = expr.nodes("rhs")!!.mapTo(ArrayList()) { t ->
                        t as AstNode
                        val expression = processAST(t.node("expression")!!)
                        pushAncestry(sortStep, expression)
                        AstNode.of("descending" to t["descending"], "expression" to expression)
                    }
                    result.nodes("steps")!! += sortStep
                    resolveAncestry(result)
                }
                ":=" -> {
                    result = AstNode.of("type" to "bind", "value" to expr.value, "position" to expr.prop("position"))
                    result["lhs"] = processAST(expr.node("lhs")!!)
                    result["rhs"] = processAST(expr.node("rhs")!!)
                    pushAncestry(result, result.node("rhs")!!)
                }
                "@" -> {
                    result = processAST(expr.node("lhs")!!)
                    var step = result
                    if (result["type"] == "path") step = result.nodes("steps")!!.last() as AstNode
                    // throw error if there are any predicates defined at this point
                    if (step.has("stages") || step.has("predicate")) throw JsonataException("S0215", expr.position)
                    // also throw if this is applied after an 'order-by' clause
                    if (step["type"] == "sort") throw JsonataException("S0216", expr.position)
                    if (expr.truthy("keepArray")) step["keepArray"] = true
                    step["focus"] = expr.node("rhs")!!["value"]
                    step["tuple"] = true
                    widen(step, expr, expr.node("rhs"))
                }
                "#" -> {
                    result = processAST(expr.node("lhs")!!)
                    var step = result
                    if (result["type"] == "path") {
                        step = result.nodes("steps")!!.last() as AstNode
                    } else {
                        result = AstNode.of("type" to "path", "steps" to mutableListOf<Any?>(result))
                        if (step.has("predicate")) {
                            step["stages"] = step["predicate"]
                            step.remove("predicate")
                        }
                    }
                    if (!step.has("stages")) {
                        step["index"] = expr.node("rhs")!!["value"]
                    } else {
                        step.nodes("stages")!! += AstNode.of("type" to "index", "value" to expr.node("rhs")!!["value"], "position" to expr.prop("position"))
                            .also { sameRange(it, expr); widen(it, expr.node("rhs")) }
                    }
                    step["tuple"] = true
                    widen(step, expr, expr.node("rhs"))
                }
                "~>" -> {
                    result = AstNode.of("type" to "apply", "value" to expr.value, "position" to expr.prop("position"))
                    val lhs = processAST(expr.node("lhs")!!)
                    val rhs = processAST(expr.node("rhs")!!)
                    result["lhs"] = lhs
                    result["rhs"] = rhs
                    // JS: lhs.keepArray || rhs.keepArray (undefined is dropped from the AST)
                    val keep = if (lhs.truthy("keepArray")) lhs["keepArray"] else rhs["keepArray"]
                    if (keep != null) result["keepArray"] = keep
                }
                else -> {
                    result = AstNode.of("type" to expr["type"], "value" to expr.value, "position" to expr.prop("position"))
                    result["lhs"] = processAST(expr.node("lhs")!!)
                    result["rhs"] = processAST(expr.node("rhs")!!)
                    pushAncestry(result, result.node("lhs")!!)
                    pushAncestry(result, result.node("rhs")!!)
                }
            }
            "unary" -> {
                result = AstNode.of("type" to expr["type"], "value" to expr.value, "position" to expr.prop("position"))
                when (expr.value) {
                    "[" -> {
                        // array constructor - process each item
                        val r = result
                        result["expressions"] = expr.nodes("expressions")!!.mapTo(ArrayList()) { item ->
                            processAST(item as AstNode).also { pushAncestry(r, it) }
                        }
                    }
                    "{" -> {
                        // object constructor - process each pair
                        val r = result
                        result["lhs"] = expr.nodes("lhs")!!.mapTo(ArrayList()) { pair ->
                            pair as List<*>
                            val key = processAST(pair[0] as AstNode).also { pushAncestry(r, it) }
                            val value = processAST(pair[1] as AstNode).also { pushAncestry(r, it) }
                            mutableListOf<Any?>(key, value)
                        }
                    }
                    else -> {
                        // all other unary expressions - just process the expression
                        val expression = processAST(expr.node("expression")!!)
                        result["expression"] = expression
                        // if unary minus on a number, then pre-process
                        if (expr.value == "-" && expression["type"] == "number") {
                            result = expression
                            result["value"] = -(result["value"] as Double)
                        } else {
                            pushAncestry(result, expression)
                        }
                    }
                }
            }
            "function", "partial" -> {
                result = AstNode.of("type" to expr["type"])
                if (expr.has("name")) result["name"] = expr["name"]
                result["value"] = expr.value
                result["position"] = expr.prop("position")
                val r = result
                result["arguments"] = expr.nodes("arguments")!!.mapTo(ArrayList()) { arg ->
                    processAST(arg as AstNode).also { pushAncestry(r, it) }
                }
                result["procedure"] = processAST(expr.node("procedure")!!)
            }
            "lambda" -> {
                result = AstNode.of("type" to expr["type"], "arguments" to expr["arguments"])
                if (expr.has("signature")) result["signature"] = expr["signature"]
                result["position"] = expr.prop("position")
                val body = processAST(expr.node("body")!!)
                result["body"] = tailCallOptimize(body)
            }
            "condition" -> {
                result = AstNode.of("type" to expr["type"], "position" to expr.prop("position"))
                result["condition"] = processAST(expr.node("condition")!!).also { pushAncestry(result, it) }
                result["then"] = processAST(expr.node("then")!!).also { pushAncestry(result, it) }
                if (expr.has("else")) result["else"] = processAST(expr.node("else")!!).also { pushAncestry(result, it) }
            }
            "transform" -> {
                result = AstNode.of("type" to expr["type"], "position" to expr.prop("position"))
                result["pattern"] = processAST(expr.node("pattern")!!)
                result["update"] = processAST(expr.node("update")!!)
                if (expr.has("delete")) result["delete"] = processAST(expr.node("delete")!!)
            }
            "block" -> {
                result = AstNode.of("type" to expr["type"], "position" to expr.prop("position"))
                val r = result
                // array of expressions - process each one
                result["expressions"] = expr.nodes("expressions")!!.mapTo(ArrayList()) { item ->
                    val part = processAST(item as AstNode)
                    pushAncestry(r, part)
                    if (part.truthy("consarray") || (part["type"] == "path" && (part.nodes("steps")!!.first() as AstNode).truthy("consarray"))) {
                        r["consarray"] = true
                    }
                    part
                }
            }
            "name" -> {
                result = AstNode.of("type" to "path", "steps" to mutableListOf<Any?>(expr))
                if (expr.truthy("keepArray")) result["keepSingletonArray"] = true
            }
            "parent" -> {
                result = AstNode.of(
                    "type" to "parent",
                    "slot" to AstNode.of("label" to "!" + ancestorLabel++, "level" to 1.0, "index" to (ancestorIndex++).toDouble()),
                )
                ancestry += result
            }
            "string", "number", "value", "wildcard", "descendant", "variable", "regex" -> result = expr
            "operator" -> {
                // the tokens 'and' and 'or' might have been used as a name rather than an operator
                result = if (expr.value == "and" || expr.value == "or" || expr.value == "in") {
                    expr["type"] = "name"
                    processAST(expr)
                } else if (expr.value == "?") {
                    expr // partial application
                } else {
                    throw JsonataException("S0201", expr.position, token = expr.value)
                }
            }
            "error" -> {
                result = expr
                if (expr.has("lhs")) result = processAST(expr.node("lhs")!!)
            }
            else -> {
                val code = if (expr.id == "(end)") "S0207" else "S0206"
                throw JsonataException(code, expr.position, token = expr.value)
            }
        }
        if (expr.truthy("keepArray")) result["keepArray"] = true
        sameRange(result, expr)
        return result
    }

    /** [parse] with the source range of every node (the parser must be created with `withRanges`). */
    fun parseWithRanges(): ParsedExpression {
        val ast = parse()
        return ParsedExpression(ast, ranges(ast))
    }

    /** Keys that refer to other nodes without containing them. */
    private val notChildren = setOf("ancestor", "slot", "seekingParent", "signature")

    /** Final ranges, bottom-up: a node covers its own range and those of its children (iterative: deep expressions). */
    private fun ranges(root: AstNode): SourceRanges {
        val result = java.util.IdentityHashMap<AstNode, SourceRange>()
        val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<AstNode, Boolean>())
        val stack = ArrayList<Pair<AstNode, Boolean>>()
        stack.add(root to false)
        while (stack.isNotEmpty()) {
            val (n, done) = stack.removeAt(stack.size - 1)
            if (!done) {
                if (!visited.add(n)) continue
                stack.add(n to true)
                forEachChild(n) { stack.add(it to false) }
                continue
            }
            var start = Int.MAX_VALUE
            var end = Int.MIN_VALUE
            own[n]?.let { start = it[0]; end = it[1] }
            forEachChild(n) { c -> result[c]?.let { r -> start = minOf(start, r.start); end = maxOf(end, r.end) } }
            if (start <= end) result[n] = SourceRange(start, end)
        }
        return SourceRanges(result)
    }

    private inline fun forEachChild(n: AstNode, action: (AstNode) -> Unit) {
        for ((k, v) in n) {
            if (k in notChildren) continue
            when (v) {
                is AstNode -> action(v)
                is List<*> -> for (x in v) {
                    if (x is AstNode) action(x) else if (x is List<*>) for (y in x) if (y is AstNode) action(y)
                }
            }
        }
    }

    fun parse(): AstNode {
        advance()
        // parse the tokens
        var expr = expression(0)
        if (node.id != "(end)") handleError(JsonataException("S0201", node.position, token = node.value))
        expr = processAST(expr)
        if (expr["type"] == "parent" || expr.has("seekingParent")) {
            // error - trying to derive ancestor at top level
            throw JsonataException("S0217", expr.position, token = expr["type"])
        }
        return expr
    }

    private val TokenType.js: String get() = name.lowercase()

    /** JS string concatenation of a token value. */
    private fun jsString(v: Any?): String = when (v) {
        is Double -> JsNumbers.toString(v)
        null -> "null"
        else -> v.toString()
    }
}
