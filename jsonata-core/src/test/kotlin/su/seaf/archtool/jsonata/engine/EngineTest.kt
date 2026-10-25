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
    fun `undefined is not null`() {
        assertEquals("undefined", eval("a", """{"b":null}"""))
        assertEquals("null", eval("b", """{"b":null}"""))
        assertEquals("[1,null,null]", eval("x.a[true]", """{"x":[{"a":1},{"b":2},{"c":3}]}"""))
        assertNull(Jsonata.compile("nothing").evaluate(null))
    }
}
