package su.seaf.archtool.jsonata.engine

import su.seaf.archtool.jsonata.CompileOptions
import su.seaf.archtool.jsonata.EvaluationOptions
import su.seaf.archtool.jsonata.FunctionRegistry
import su.seaf.archtool.jsonata.Jsonata
import su.seaf.archtool.jsonata.parser.JsonataException
import su.seaf.archtool.jsonata.value.ArrayValue
import su.seaf.archtool.jsonata.value.FunctionValue
import su.seaf.archtool.jsonata.value.JsonWriter
import su.seaf.archtool.jsonata.value.NumberValue
import su.seaf.archtool.jsonata.value.ObjectValue
import su.seaf.archtool.jsonata.value.StringValue
import su.seaf.archtool.jsonata.value.Value
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Evaluator behaviour outside the test-suite: API, registry, debugging entry points, limits, isolation. */
class EngineTest {
    /** A JSON literal is a JSONata expression: this builds input values without a JSON library. */
    private fun json(text: String): Value? = Jsonata.compile(text).evaluate(null)

    private fun eval(expr: String, input: String? = null, options: CompileOptions = CompileOptions.DEFAULT, limits: EvaluationOptions = EvaluationOptions.DEFAULT): String =
        JsonWriter.stringify(Jsonata.compile(expr, options).evaluate(input?.let { json(it) }, emptyMap(), limits))

    private fun error(expr: String, input: String? = null, options: CompileOptions = CompileOptions.DEFAULT, limits: EvaluationOptions = EvaluationOptions.DEFAULT): JsonataException =
        assertFailsWith<JsonataException> { Jsonata.compile(expr, options).evaluate(input?.let { json(it) }, emptyMap(), limits) }

    private val testdata2 = """{"Account":{"Account Name":"Firefly","Order":[{"OrderID":"order103","Product":[{"Product Name":"Bowler Hat"}]},{"OrderID":"order104","Product":[{"Product Name":"Cloak"}]}]}}"""

    @Test
    fun `registered native functions with signatures`() {
        val registry = FunctionRegistry.create()
            .register("double", "<n-:n>", { _, a -> NumberValue.of((a[0] as NumberValue).value * 2) })
            .register("myfunc", "<af:n>", { c, a -> NumberValue.of(2 * (c.call(a[1] as FunctionValue, listOf(a[0])) as NumberValue).value) })
            .register("startsWith", "<s:f>", { _, a ->
                val prefix = (a[0] as StringValue).value
                FunctionRegistry.function("<s:b>", { _, b -> su.seaf.archtool.jsonata.value.BooleanValue.of((b[0] as StringValue).value.startsWith(prefix)) })
            })
        val options = CompileOptions(registry)
        assertEquals("[2,4,6]", eval("[1,2,3].\$double()", options = options))
        assertEquals("12", eval("\$myfunc([1,2,3], \$sum)", options = options))
        assertEquals("10", eval("\$myfunc([1,2,3], λ(\$arr) { \$arr[1] + \$arr[2] })", options = options))
        assertEquals("[true,false]", eval("(\$s := \$startsWith('Hello'); [\$s('Hello, Bob'), \$s('Goodbye, Bill')])", options = options))
        assertEquals("[4,6]", eval("\$map([2,3], \$double)", options = options))
        assertEquals("T0410", error("\$double('x')", options = options).code)
        // partial application of a registered function
        assertEquals("20", eval("\$double(?)(10)", options = options))
    }

    @Test
    fun `registry names cannot be replaced (decision 18)`() {
        val r = FunctionRegistry.create()
        assertEquals("X0001", assertFailsWith<JsonataException> { r.register("sum", null, { _, _ -> null }) }.code)
        r.register("mine", null, { _, _ -> null })
        assertEquals("X0001", assertFailsWith<JsonataException> { r.register("mine", null, { _, _ -> null }) }.code)
        assertEquals("X0001", assertFailsWith<JsonataException> { r.extend().register("mine", null, { _, _ -> null }) }.code)
        // a variable binding still shadows a library function, as in jsonata-js
        assertEquals("5", eval("(\$sum := function(\$a){5}; \$sum([1,2]))"))
    }

    @Test
    fun `a lambda runs under the evaluation that calls it (RV-9)`() {
        val f = Jsonata.compile("function(\$x){ \$x * 2 }").evaluate(null, emptyMap(), EvaluationOptions(timeoutMillis = 50))
        Thread.sleep(100)
        assertEquals("42", JsonWriter.stringify(Jsonata.compile("\$f(21)").evaluate(null, mapOf("f" to f))))
        // the caller's limits apply
        val g = Jsonata.compile("function(\$n){ (\$r := function(\$k){ \$k = 0 ? 0 : 1 + \$r(\$k - 1) }; \$r(\$n)) }").evaluate(null)
        val e = assertFailsWith<JsonataException> {
            Jsonata.compile("\$g(1000)").evaluate(null, mapOf("g" to g), EvaluationOptions(maxDepth = 50))
        }
        assertEquals("D1011", e.code)
        // the caller's clock
        val m = Jsonata.compile("function(){ \$millis() }").evaluate(null, emptyMap(), EvaluationOptions(clock = { 1L }))
        assertEquals("2", JsonWriter.stringify(Jsonata.compile("\$m()").evaluate(null, mapOf("m" to m), EvaluationOptions(clock = { 2L }))))
        // a transformer too
        val t = Jsonata.compile("|a|{'b': \$millis()}|").evaluate(null, emptyMap(), EvaluationOptions(clock = { 1L }))
        assertEquals("{\"a\":{\"b\":3}}", JsonWriter.stringify(Jsonata.compile("\$t({'a': {}})").evaluate(null, mapOf("t" to t), EvaluationOptions(clock = { 3L }))))
    }

    @Test
    fun `one lambda failing in several evaluations at once reports each call site (RV-9)`() {
        val f = Jsonata.compile("function(\$x){ \$x + 'a' }").evaluate(null)
        val pool = Executors.newFixedThreadPool(8)
        val exprs = listOf("\$f(1)", "  \$f(1)", "    \$f(1)", "      \$f(1)")
        val futures = (0 until 400).map { i ->
            val expr = exprs[i % exprs.size]
            pool.submit<Pair<Int?, Int>> {
                val e = assertFailsWith<JsonataException> { Jsonata.compile(expr).evaluate(null, mapOf("f" to f)) }
                e.position to expr.indexOf('(') + 1
            }
        }
        futures.forEach { val (got, want) = it.get(10, TimeUnit.SECONDS); assertEquals(want, got) }
        pool.shutdown()
    }

    @Test
    fun `the standard registry is read-only (RV-12)`() {
        val e = assertFailsWith<JsonataException> { FunctionRegistry.standard().register("myfn", null, { _, _ -> NumberValue.of(1) }) }
        assertEquals("X0004", e.code)
        assertEquals("T1006", error("\$myfn()").code)
        // a child registry is the way to add functions
        assertEquals("1", eval("\$myfn()", options = CompileOptions(FunctionRegistry.create().register("myfn", null, { _, _ -> NumberValue.of(1) }))))
    }

    @Test
    fun `partial application of a native function checks its signature (DEV-03, RV-14)`() {
        for (input in listOf("1", "[[1,2]]", "{\"a\":1}")) {
            assertEquals("T0410", error("\$map(\$, \$match(?, /^(\\w*\\s\\w*)/)).match", input).code, input)
        }
        assertEquals("\"ab cd\"", eval("\$map(\$, \$match(?, /^(\\w*\\s\\w*)/)).match", "\"ab cd\""))
        assertEquals("5", eval("\$sum(?)(5)"))
        assertEquals("T0410", error("\$uppercase(?)(1)").code)
        assertEquals("1", eval("\$count(?)(5)"))
    }

    @Test
    fun `transform with a clone bound as a variable`() {
        var count = 0
        val clone = FunctionRegistry.function(null, { _, a -> count++; Evaluator.clone(a[0]) }, 1)
        val result = Jsonata.compile("Account ~> |Order|{\"Product\":\"blah\"},nomatch|").evaluate(json(testdata2), mapOf("clone" to clone))
        assertEquals("""{"Account Name":"Firefly","Order":[{"OrderID":"order103","Product":"blah"},{"OrderID":"order104","Product":"blah"}]}""", JsonWriter.stringify(result))
        assertEquals(1, count)
        assertEquals("T2013", error("( \$clone := 5; \$ ~> |Account.Order.Product|{\"blah\":\"foo\"}| )", testdata2).code)
    }

    @Test
    fun `transform never modifies the input`() {
        val input = json("""{"a":{"b":1},"c":[{"d":2}]}""")
        val before = JsonWriter.stringify(input)
        val r = Jsonata.compile("\$ ~> |\$\$.a|{\"b\":99}| ~> |c|{\"e\":3},['d']|").evaluate(input)
        assertEquals(before, JsonWriter.stringify(input))
        assertEquals("""{"a":{"b":1},"c":[{"e":3}]}""", JsonWriter.stringify(r))
    }

    @Test
    fun `transform with a user clone returning its argument leaves the input unchanged (RV-2)`() {
        val input = json("""{"a":{"b":1}}""")
        val r = Jsonata.compile("(\$clone := function(\$x){\$x}; \$ ~> |a|{\"b\":99}|)").evaluate(input)
        assertEquals("""{"a":{"b":99}}""", JsonWriter.stringify(r))
        assertEquals("""{"a":{"b":1}}""", JsonWriter.stringify(input))
        // a clone from the bindings that returns the argument
        val identity = FunctionRegistry.function(null, { _, a -> a[0] }, 1)
        val input2 = json("""{"a":{"b":1},"c":[{"d":2}]}""")
        val r2 = Jsonata.compile("\$ ~> |c|{\"e\":3},['d']|").evaluate(input2, mapOf("clone" to identity))
        assertEquals("""{"a":{"b":1},"c":[{"e":3}]}""", JsonWriter.stringify(r2))
        assertEquals("""{"a":{"b":1},"c":[{"d":2}]}""", JsonWriter.stringify(input2))
    }

    @Test
    fun `tail calls do not grow the JVM stack`() {
        assertEquals("0", eval("(\$f := function(\$n){ \$n = 0 ? 0 : \$f(\$n - 1) }; \$f(200000))"))
        assertEquals("125", eval(ackermann(3, 4), limits = EvaluationOptions(maxDepth = 500, timeoutMillis = 10_000)))
    }

    private fun ackermann(m: Int, n: Int) = "(\$ack := function(\$m, \$n) { \$m = 0 ? \$n + 1 : \$n = 0 ? \$ack(\$m - 1, 1) : \$ack(\$m - 1, \$ack(\$m, \$n - 1)) }; \$ack($m, $n))"

    @Test
    fun `limits as the jsonata-js options stack timeout sequence`() {
        val e1 = error("(\$inf := function(\$n){\$n+\$inf(\$n-1)};  \$inf(5))", limits = EvaluationOptions(maxDepth = 300, timeoutMillis = 1000))
        assertEquals("D1011", e1.code)
        assertEquals("inf", e1.token)
        assertEquals(30, e1.position)
        val e2 = error("( \$inf := function(){\$inf()}; \$inf())", limits = EvaluationOptions(maxDepth = 500, timeoutMillis = 200))
        assertEquals("D1012", e2.code)
        assertEquals("inf", e2.token)
        val e3 = error(ackermann(4, 4), limits = EvaluationOptions(maxDepth = 500))
        assertEquals("D1011", e3.code)
        assertEquals("ack", e3.token)
        assertEquals("D2015", error("[0..1001]", limits = EvaluationOptions(maxSequence = 1000)).code)
        assertEquals("D2015", error("\$append([0..600], [0..600]) ~> \$count()", limits = EvaluationOptions(maxSequence = 1000)).code)
    }

    /** Runs [body] on a thread with a small stack, as a virtual thread or a 512 KB worker would be. */
    private fun onSmallStack(body: () -> Unit) {
        var failure: Throwable? = null
        val t = Thread(null, { try { body() } catch (e: Throwable) { failure = e } }, "small-stack", 256 * 1024)
        t.start()
        t.join()
        failure?.let { throw it }
    }

    private fun nested(depth: Int, leaf: Value): Value {
        var v = leaf
        repeat(depth) { v = ObjectValue.of("c" to v) }
        return v
    }

    @Test
    fun `non-tail recursion 100 000 levels deep as jsonata-js (RV-4)`() {
        onSmallStack {
            assertEquals("100000", JsonWriter.stringify(Jsonata.compile("(\$f := function(\$n){ \$n = 0 ? 0 : 1 + \$f(\$n - 1) }; \$f(100000))").evaluate(null)))
        }
        val vt = Thread.ofVirtual().start {
            assertEquals("5000", JsonWriter.stringify(Jsonata.compile("(\$f := function(\$n){ \$n = 0 ? 0 : 1 + \$f(\$n - 1) }; \$f(5000))").evaluate(null)))
        }
        vt.join()
        // the depth limit still applies first
        assertEquals("D1011", error("(\$f := function(\$n){ \$n = 0 ? 0 : 1 + \$f(\$n - 1) }; \$f(100000))", limits = EvaluationOptions(maxDepth = 5000)).code)
    }

    /** D1012 must come within a small margin of the deadline, also from loops inside native functions (RV-6). */
    private fun assertTimesOut(expr: String, input: Value? = null, timeout: Long = 5) {
        val t0 = System.nanoTime()
        val e = assertFailsWith<JsonataException>(expr) {
            Jsonata.compile(expr).evaluate(input, emptyMap(), EvaluationOptions(timeoutMillis = timeout))
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals("D1012", e.code, expr)
        assertTrue(ms < timeout + 250, "$expr: D1012 after $ms ms")
    }

    @Test
    fun `native loops pass the checkpoint (RV-6)`() {
        val big = ArrayValue(400_000).also { a -> repeat(400_000) { a.add(ObjectValue.of("k" to NumberValue.of(it), "s" to StringValue.of("v$it"))) } }
        val nums = ArrayValue(1_000_000).also { a -> repeat(1_000_000) { a.add(NumberValue.of(it)) } }
        Jsonata.compile("\$count(\$)").evaluate(big) // warm up
        assertTimesOut("\$count([1..10000000])", timeout = 1)
        assertTimesOut("\$count(\$map(\$, \$string))", nums)
        assertTimesOut("\$count(\$filter(\$, \$boolean))", nums)
        assertTimesOut("\$reduce(\$, function(\$a, \$b){ \$a + \$b })", nums)
        assertTimesOut("\$count(**)", big)
        assertTimesOut("\$count(\$clone(\$).k)", big)
        assertTimesOut("\$length(\$string(\$))", big)
        assertTimesOut("\$count(\$sort(\$, function(\$a, \$b){ \$a < \$b }))", nums)
        assertTimesOut("\$count(\$distinct(\$))", nums)
        assertTimesOut("\$count(\$append(\$, \$))", nums)
        assertTimesOut("\$count(\$keys(\$))", big)
        assertTimesOut("\$count(\$zip(\$, \$))", nums)
    }

    @Test
    fun `regex backtracking stops at the deadline (RV-8)`() {
        assertTimesOut("\$contains(\$, /(?:a*)*?b\\0/)", StringValue.of("a".repeat(30) + "!"), 50)
    }

    @Test
    fun `runaway recursion stops at the default depth limit`() {
        val e = error("(\$f := function(\$n){ 1 + \$f(\$n + 1) }; \$f(0))")
        assertEquals("D1011", e.code)
        assertEquals(EvaluationOptions.DEFAULT_MAX_DEPTH.toDouble(), e.value)
    }

    @Test
    fun `recursion over data 100 000 levels deep (RV-4)`() {
        val depth = 100_000
        val deep = nested(depth - 1, ObjectValue.of("x" to NumberValue.of(1)))
        onSmallStack {
            assertEquals("$depth", JsonWriter.stringify(Jsonata.compile("(\$f := function(\$t){ \$t.c ? 1 + \$max(\$t.c.\$f(\$)) : 1 }; \$f(\$))").evaluate(deep)))
            val text = JsonWriter.stringify(deep)
            assertEquals(StringValue.of(text), Jsonata.compile("\$string(\$)").evaluate(deep))
            assertEquals("true", JsonWriter.stringify(Jsonata.compile("\$ = \$clone(\$)").evaluate(deep)))
            assertEquals("${depth + 1}", JsonWriter.stringify(Jsonata.compile("\$count(**)").evaluate(deep)))
            assertEquals(text, JsonWriter.stringify(Jsonata.compile("\$ ~> |x|{}|").evaluate(deep)))
            var arr: Value = NumberValue.of(7)
            repeat(depth) { arr = ArrayValue.of(arr) }
            assertEquals("[7]", JsonWriter.stringify(Jsonata.compile("\$.*").evaluate(ObjectValue.of("a" to arr))))
        }
    }

    @Test
    fun `JVM stack exhaustion is reported as X0002`() {
        // a native callback that recurses into the evaluator without passing through a JSONata frame boundary
        val registry = FunctionRegistry.create().register("again", "<f:x>", { c, a -> c.call(a[0] as FunctionValue, listOf(a[0])) })
        val e = assertFailsWith<JsonataException> {
            Jsonata.compile("\$again(function(\$g){ \$again(\$g) })", CompileOptions(registry)).evaluate(null)
        }
        assertTrue(e.code == "X0002" || e.code == "D1011", e.code)
    }

    @Test
    fun `debug hook sees every node with positions and the call stack`() {
        val expr = Jsonata.compile("(\$f := function(\$x){ \$x * 2 }; \$f(21))", CompileOptions(debug = true))
        val entered = ArrayList<String?>()
        var maxStack = 0
        val result = expr.evaluate(null, object : DebugHook {
            override fun enter(node: Node, input: Value?, frame: Frame) {
                entered += node.ast.type
                var depth = 0
                var f: Frame? = frame
                while (f != null) {
                    if (f.callSite != null) depth++
                    f = f.caller ?: f.parent
                }
                maxStack = maxOf(maxStack, depth)
            }
            override fun exit(node: Node, input: Value?, frame: Frame, result: Value?) {}
        })
        assertEquals("42", JsonWriter.stringify(result))
        assertTrue("binary" in entered && "lambda" in entered && "function" in entered, entered.toString())
        assertEquals(1, maxStack)
        // the debug flag only marks the nodes
        assertTrue(!Jsonata.compile("1 + 2").compiledRoot.debug)
        assertTrue(Jsonata.compile("1 + 2", CompileOptions(debug = true)).compiledRoot.debug)
    }

    @Test
    fun `one compiled expression evaluated concurrently`() {
        val expr = Jsonata.compile("{ \"id\": \$\$.id, \"sum\": \$sum([1..\$\$.n]), \"now\": \$millis() > 0 }")
        val pool = Executors.newVirtualThreadPerTaskExecutor()
        val futures = (1..200).map { i -> pool.submit<String> { JsonWriter.stringify(expr.evaluate(json("{\"id\":\"x$i\",\"n\":$i}"))) } }
        futures.forEachIndexed { idx, f ->
            val i = idx + 1
            assertEquals("{\"id\":\"x$i\",\"sum\":${i * (i + 1) / 2},\"now\":true}", f.get(10, TimeUnit.SECONDS))
        }
        pool.shutdown()
    }

    @Test
    fun `regex matcher protocol (implementation-tests js)`() {
        assertEquals("""{"match":"ab","start":0,"end":2,"groups":[]}""", eval("/ab/ (\"ab\")"))
        assertEquals("undefined", eval("/ab/ ()"))
        assertEquals("""{"match":"abb","start":2,"end":5,"groups":["bb"]}""", eval("/a(b+)/ (\"ababbabbcc\").next()"))
        assertEquals("""{"match":"abb","start":5,"end":8,"groups":["bb"]}""", eval("/a(b+)/ (\"ababbabbcc\").next().next()"))
        assertEquals("undefined", eval("/a(b+)/ (\"ababbabbcc\").next().next().next()"))
        assertEquals("""{"match":"Ab","start":0,"end":2,"groups":["b"]}""", eval("/a(b+)/i (\"Ababbabbcc\")"))
        assertEquals("""{"match":"\\","index":12,"groups":[]}""", eval("\$match(\"test escape \\\\\", /\\\\/)"))
    }

    @Test
    fun `library functions give the same results with and without the matcher protocol`() {
        // a lambda around the regex hides it from the fast path, so the call goes through {match, start, end, groups, next}
        val subjects = listOf("", "ab", "ababbabbcc", "a-b--c", "x\uD83D\uDE00y\uD83D\uDE00", "aaa")
        val patterns = listOf("/a(b+)/", "/(x)?b/", "/-/", "/\\uD83D\\uDE00/", "/a|(z)/i", "/x*/", "/(?<n>a)(b)?/")
        val calls = listOf(
            "\$match(\$s, R)", "\$match(\$s, R, 1)", "\$match(\$s, R, 0)",
            "\$replace(\$s, R, '[\$0|\$1|\$2|\$\$|\$9]')", "\$replace(\$s, R, '<\$1>', 2)",
            "\$split(\$s, R)", "\$split(\$s, R, 2)", "\$contains(\$s, R)",
        )
        for (subject in subjects) for (pattern in patterns) for (call in calls) {
            fun run(regex: String) = try {
                eval("(\$r := $pattern; \$f := function(\$x) { \$r(\$x) }; \$s := '$subject'; ${call.replace("R", regex)})")
            } catch (e: JsonataException) {
                "error ${e.code}"
            }
            assertEquals(run("\$f"), run("\$r"), "$call with $pattern on '$subject'")
        }
        assertEquals("\"x[ab|b||$|][abb|bb||$|]cc\"", eval("\$replace('xababbcc', /a(b+)/, '[\$0|\$1|\$2|\$\$|\$9]', 2)"))
        assertEquals("[\"a\",\"b\",\"\",\"c\"]", eval("\$split('a-b--c', /-/)"))
        assertEquals("D1004", error("\$match('aaa', /x*/)").code)
    }

    @Test
    fun `cached number pictures and parsed dates give the reference results`() {
        // expectations from jsonata@2.2.2; the same picture alternates with and without options
        val expr = "[\$formatNumber(1234.5, '#,##0.00'), \$formatNumber(1234.5, '#.##0,00', {'decimal-separator': ',', 'grouping-separator': '.'}), " +
            "\$formatNumber(-1234.5, '#,##0.00'), \$formatNumber(1234.5, '#,##\u0660.\u0660\u0660', {'zero-digit': '\u0660'}), \$formatNumber(1234.5, '#,##0.00')]"
        assertEquals("[\"1,234.50\",\"1.234,50\",\"-1,234.50\",\"\u0661,\u0662\u0663\u0664.\u0665\u0660\",\"1,234.50\"]", eval(expr))
        assertEquals("D3091", error("\$formatNumber(1, '#,##0.00', {'decimal-separator': ',', 'grouping-separator': '.'})").code)
        repeat(2) { assertEquals("D3081", error("\$formatNumber(1, '#.#.#')").code) }
        assertEquals("1517472733500", eval("\$toMillis('2018-02-01T09:42:13.5+01:30')"))
    }

    @Test
    fun `regex over a long string with surrogates is linear (RV-5)`() {
        val s = StringValue.of("ab".repeat(80_000) + "\uD83D\uDE00")
        val t0 = System.nanoTime()
        assertEquals("80000", JsonWriter.stringify(Jsonata.compile("\$count(\$match(\$, /a/))").evaluate(s)))
        assertEquals("160001", JsonWriter.stringify(Jsonata.compile("\$length(\$replace(\$, /a/, 'x'))").evaluate(s)))
        assertEquals("80001", JsonWriter.stringify(Jsonata.compile("\$count(\$split(\$, /b/))").evaluate(s)))
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(ms < 3000, "took $ms ms")
    }

    @Test
    fun `the regex cache is bounded (RV-13)`() {
        val expr = Jsonata.compile("\$eval('\$contains(\"a\" & \$string(\$), /a' & \$string(\$) & '/)', \$)")
        for (i in 0 until JsRegexPattern.CACHE_LIMIT + 500) assertEquals("true", JsonWriter.stringify(expr.evaluate(NumberValue.of(i))))
        assertTrue(JsRegexPattern.cacheSize() <= JsRegexPattern.CACHE_LIMIT)
    }

    @Test
    fun `custom matcher functions`() {
        val repeating = FunctionRegistry.create().register("repeatingLetters", "<sn:f>", { _, a ->
            val chars = (a[0] as StringValue).value.repeat((a[1] as NumberValue).value.toInt())
            lateinit var match: (String, Int) -> Value?
            match = { str, offset ->
                val pos = str.indexOf(chars, offset)
                if (pos < 0) null else ObjectValue.of(
                    "match" to StringValue.of(chars), "start" to NumberValue.of(pos), "end" to NumberValue.of(pos + chars.length),
                    "groups" to ArrayValue(), "next" to FunctionRegistry.function(null, { _, _ -> match(str, pos + chars.length) }, 0),
                )
            }
            FunctionRegistry.function(null, { _, b -> match((b[0] as StringValue).value, ((b.getOrNull(1) as? NumberValue)?.value ?: 0.0).toInt()) }, 2)
        })
        val o = CompileOptions(repeating)
        val text = "'LLANFAIRPWLLGWYNGYLLGOGERYCHWYRNDROBWLLLLANTYSILIOGOGOGOCH'"
        assertEquals("""[{"match":"LL","index":0,"groups":[]},{"match":"LL","index":10,"groups":[]},{"match":"LL","index":18,"groups":[]},{"match":"LL","index":37,"groups":[]},{"match":"LL","index":39,"groups":[]}]""", eval("\$match($text, \$repeatingLetters('L', 2))", options = o))
        assertEquals("""["","ANFAIRPW","GWYNGY","GOGERYCHWYRNDROBW","","ANTYSILIOGOGOGOCH"]""", eval("\$split($text, \$repeatingLetters('L', 2))", options = o))
        assertEquals("\"ỺANFAIRPWỺGWYNGYỺGOGERYCHWYRNDROBWỺỺANTYSILIOGOGOGOCH\"", eval("\$replace($text, \$repeatingLetters('L', 2), 'Ỻ')", options = o))
        assertEquals("true", eval("\$contains($text, \$repeatingLetters('L', 4))", options = o))
    }

    @Test
    fun `object traversal and prototype safety`() {
        assertEquals("42", eval("foo.*[0]", """{"foo":{"bar":42,"blah":[{"baz":{"fud":"hello"}},{"baz":{"fud":"world"}},{"bazz":"gotcha"}],"blah.baz":"here"},"bar":98}"""))
        assertEquals("\"Firefly\"", eval("**[2]", testdata2))
        assertEquals("undefined", eval("foo.__proto__", """{"foo":{"bar":"baz"}}"""))
        val e = error("foo.toString()", """{"foo":{"bar":"baz"}}""")
        assertEquals("T1006", e.code)
        assertEquals(13, e.position)
        assertEquals("{}", eval("{} ~> | __proto__ | {\"is_admin\": true} |"))
        assertEquals("{}", eval("{} ~> | constructor | {\"is_admin\": true} |"))
        assertEquals("D1013", error("(\$lambda = {\"_jsonata_lambda\": true}; \$lambda())").code)
    }

    @Test
    fun `clone and millis`() {
        assertEquals("undefined", eval("\$clone(foo)", testdata2))
        assertEquals("{\"a\":1}", eval("\$clone({\"a\": 1})"))
        val r = Jsonata.compile("[\$millis(), \$millis()]").evaluate(null, emptyMap(), EvaluationOptions(clock = { 1234L }))
        assertEquals("[1234,1234]", JsonWriter.stringify(r))
    }

    @Test
    fun `toMillis without an offset is UTC whatever the JVM time zone (DEV-07, RV-15)`() {
        val saved = java.util.TimeZone.getDefault()
        try {
            for (zone in listOf("Europe/Moscow", "America/New_York", "UTC")) {
                java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(zone))
                assertEquals("1700000000000", eval("\$toMillis(\"2023-11-14T22:13:20\")"), zone)
                assertEquals("1699920000000", eval("\$toMillis(\"2023-11-14\")"), zone)
            }
        } finally {
            java.util.TimeZone.setDefault(saved)
        }
    }

    @Test
    fun `invalid dates format as jsonata-js does (fuzzing)`() {
        assertEquals("\"NaNth undefined NaN\"", eval("\$fromMillis(\$toMillis(\"2018-13-01\"), \"[D1o] [MNn] [Y]\")"))
        assertEquals("\"undefined, NaN undefined NaN\"", eval("\$fromMillis(\$toMillis(\"2018-13-01\"), \"[FNn], [D] [MNn] [Y]\")"))
    }

    @Test
    fun `roman numerals of huge numbers fail fast as the reference does (fuzzing)`() {
        val t0 = System.nanoTime()
        assertEquals("X0002", error("\$formatInteger(9007199254740991, \"i\")").code)
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 1000)
        assertEquals(9765, JsonWriter.stringify(Jsonata.compile("\$formatInteger(9765661, \"I\")").evaluate(null)).count { it == 'M' })
    }

    @Test
    fun `formatBase follows V8 for large numbers (fuzzing)`() {
        assertEquals("\"5v1j4f4ds7c000\"", eval("\$formatBase(1e21, 36)"))
        assertEquals("\"q1ysz911igw00\"", eval("\$formatBase(123456789012345680000, 36)"))
    }

    @Test
    fun `functions called by higher-order functions get JS null as context (fuzzing)`() {
        assertEquals("T0411", error("\$map([\"a\"], function(\$v){\$uppercase()})").code)
        assertEquals("T0411", error("\$filter(3.5, function(\$v){\$pad(1234.5678)})").code)
    }

    @Test
    fun `a regex on the left of the elvis operator (fuzzing)`() {
        assertEquals("1", eval("/zz/ ?: 1"))
        assertEquals("[true]", eval("[systems[\$contains(name, /a/i)], true] ?: 2"))
        assertEquals("true", eval("\$exists(/a/ ?? 1)"))
    }

    @Test
    fun `base64decode reads non-Latin characters by their low byte as Node does (fuzzing)`() {
        assertEquals("\"ónA\"", eval("\$base64decode(\"Привет мир\")"))
    }

    @Test
    fun `undefined is not null`() {
        assertEquals("undefined", eval("a", """{"b":null}"""))
        assertEquals("null", eval("b", """{"b":null}"""))
        assertEquals("[1,null,null]", eval("x.a[true]", """{"x":[{"a":1},{"b":2},{"c":3}]}"""))
        assertNull(Jsonata.compile("nothing").evaluate(null))
    }
}
