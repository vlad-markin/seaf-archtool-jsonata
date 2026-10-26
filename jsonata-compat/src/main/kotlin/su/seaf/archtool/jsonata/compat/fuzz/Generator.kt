package su.seaf.archtool.jsonata.compat.fuzz

import com.fasterxml.jackson.databind.JsonNode
import su.seaf.archtool.jsonata.compat.mapper
import java.util.SplittableRandom

/** One generated case: an expression, an input document (null = undefined) and the zone that produced it. */
/** One case: an expression, its input document and top-level bindings (replay files may carry them). */
data class FuzzCase(val id: String, val zone: String, val expr: String, val input: JsonNode?, val bindings: JsonNode? = null)

/**
 * Deterministic generator of JSONata expressions and documents for differential fuzzing (phase 8): a small grammar of
 * paths and operators plus templates for the zones where engines usually diverge (numbers, strings, regex, dates,
 * pictures, sorting, key order, grouping, sequences, undefined/null, errors, higher-order functions, transforms).
 * The same seed always gives the same cases.
 */
class Generator(seed: Long) {
    private val rnd = SplittableRandom(seed)

    private fun int(n: Int) = rnd.nextInt(n)
    private fun <T> pick(xs: List<T>): T = xs[int(xs.size)]
    private fun chance(p: Double) = rnd.nextDouble() < p
    private fun q(s: String) = mapper.writeValueAsString(s)

    // ---- pools of edge values ----

    private val nums = listOf("0", "-0", "1", "-1", "2", "3", "7", "10", "0.1", "0.2", "0.5", "1.5", "2.5", "-2.5", "3.5",
        "0.125", "1.005", "2.675", "1.45", "3.14159", "123.456", "1234.5678", "-1234.5678", "0.000001234", "1e-7", "1e-6",
        "5e-7", "1e20", "1e21", "1e22", "123456789012345680000", "9007199254740991", "9007199254740993", "1e300", "255",
        "65535", "1000000", "99.995", "0.30000000000000004", "100", "1e15", "1e16", "4.35", "1.1", "2.2", "0.7", "-0.4",
        "-0.5", "0.49999999999999994", "12345.6789", "-0.0001", "1.23e-10", "6.02e23")
    private val strs = listOf("", "a", "abc", "Hello World", "hello world", "héllo", "HÉLLO", "Straße", "İstanbul", "ﬁ",
        "😀", "a😀b", "😀😀", "é", "naïve café", "  padded  ", "\t tab\n", "a,b,,c", "a.b.c", "1,234.5", "42", " 42 ",
        "0x1F", "0b101", "0o17", "1e3", "-0", "true", "null", "αβγ", "Привет мир", "日本語", " nbsp ", "x y",
        "line1\nline2", "a\\b", "quote\"d", "mixed CASE Words", "abcabcabc", "aaa", "the cat sat on the mat", "ID-001",
        "svc.payment-api", "v1.2.3", "%E2%82%AC", "€100", "a b+c", "ab cd ef", "10", "2", "-12.5e2")
    private val regexes = listOf("/a/", "/a+/", "/[aeiou]/", "/\\d+/", "/\\w+/", "/\\s+/", "/\\b\\w/", "/^a/", "/c$/",
        "/a/i", "/^l/m", "/(\\w)(\\w)/", "/(a)|(b)/", "/(?<n>\\d)/", "/[^]/", "/[\\w-]+/", "/(?<=a)b/", "/(?<!a)b/",
        "/(a)\\1/", "/./", "/[a-z]{2,}/", "/(?:ab)+/", "/\\Bt/", "/\\x41/", "/é/", "/😀/", "/\\./", "/[.]/", "/\\//",
        "/\\d{2,3}?/", "/(\\d+)\\.(\\d+)/", "/[A-Z]/", "/o/i", "/\\s*,\\s*/", "/(?<word>\\w+)\\s/", "/a(b)?c/", "/$/m",
        "/[\\u0400-\\u04FF]+/", "/\\p{L}/", "/a{,2}/", "/x?/")
    private val repls = listOf("\"x\"", "\"$0\"", "\"$1\"", "\"$2\"", "\"$$\"", "\"[$0]\"", "\"$10\"", "\"\\\\\"", "\"\"",
        "\"$\"", "\"$1$1\"", "function(\$m){\$uppercase(\$m.match)}", "function(\$m){\$string(\$m.index)}")
    private val pictNum = listOf("\"#,##0.00\"", "\"0.000\"", "\"#0.#\"", "\"#%\"", "\"0%\"", "\"#‰\"", "\"00\"", "\"#,###\"",
        "\"0.0#\"", "\"#,##0.###\"", "\"0.00e0\"", "\"#.##e0\"", "\"00.0e00\"", "\"#\"", "\"0\"", "\"###,##0.00;(###,##0.00)\"",
        "\"#,##,##0\"", "\"#.000\"", "\"$#,##0.00\"", "\"#0.00 EUR\"", "\"0.###############\"", "\"#E0\"", "\"0,0\"")
    private val pictInt = listOf("\"w\"", "\"W\"", "\"Ww\"", "\"i\"", "\"I\"", "\"a\"", "\"A\"", "\"#,##0\"", "\"0001\"", "\"1\"",
        "\"#0\"", "\"1;o\"", "\"w;o\"", "\"#;o\"", "\"000,000\"", "\"##0\"", "\"①\"", "\"٠\"", "\"#\"")
    private val pictDate = listOf("\"[Y0001]-[M01]-[D01]\"", "\"[D1o] [MNn] [Y]\"", "\"[FNn], [D] [MNn] [Y]\"",
        "\"[H01]:[m01]:[s01].[f001][Z]\"", "\"[h]:[m01] [P]\"", "\"[h]:[m01] [PN]\"", "\"[Y,2]\"", "\"[YI]\"", "\"[Mi]\"",
        "\"[W]\"", "\"[w]\"", "\"[X]-W[W01]-[F1]\"", "\"[x]\"", "\"[d]\"", "\"[F1]\"", "\"[FN,3-3]\"", "\"[MN,3-3]\"", "\"[E]\"",
        "\"[z]\"", "\"[Z0]\"", "\"[Z01:01]\"", "\"[Z0101t]\"", "\"[Dw]\"", "\"[YW]\"", "\"[Y0001]-[d001]\"", "\"[f01]\"",
        "\"[H]h[m]\"", "\"[Y]-[M]-[D]T[H]:[m]:[s]\"", "\"[D01]/[M01]/[Y0001] [H01]:[m01]\"", "\"[MNn,*-3]\"", "\"[C]\"",
        "\"[Y01]\"", "\"[M1,2]\"", "\"[Q]\"", "\"[Y0001\"")
    private val tz = listOf("\"+0100\"", "\"-0530\"", "\"0000\"", "\"+1400\"", "\"-1200\"", "\"+0545\"", "\"-0100\"")
    private val millis = listOf("0", "1", "-1", "86399999", "951782400000", "1104537600000", "1230757500000",
        "1419940800000", "1483272000000", "1533038400000", "1609459199999", "1709208000000", "-62135596800000",
        "253402300799999", "1.5", "-86400000", "1700000000000")
    private val dateStrs = listOf("\"2018-02-01T09:42:13.123Z\"", "\"2018-02-01\"", "\"2018-02-01T09:42:13+01:00\"",
        "\"2018-02-01T09:42:13.123456Z\"", "\"2018\"", "\"2018-02\"", "\"2018-13-01\"", "\"2018-02-30\"",
        "\"2018-02-01 09:42:13\"", "\"20180201\"", "\"2018-02-01T24:00:00Z\"", "\"1970-01-01T00:00:00.000Z\"",
        "\"2018-02-01T09:42:13.123-05:30\"", "\"2018-02-01T09:42:13+0530\"")
    private val dateParse = listOf("\"[Y0001]-[M01]-[D01]\"" to "\"2018-02-01\"", "\"[D1]/[M1]/[Y0001]\"" to "\"1/2/2018\"",
        "\"[H01]:[m01]\"" to "\"13:45\"", "\"[Y]-[d]\"" to "\"2018-32\"", "\"[D1o] [MNn] [Y]\"" to "\"1st February 2018\"",
        "\"[h]:[m] [P]\"" to "\"1:15 pm\"", "\"[Y0001]-[M01]-[D01]T[H01]:[m01]:[s01][Z]\"" to "\"2018-02-01T09:42:13+05:30\"",
        "\"[Dwo] [MNn] [Yw]\"" to "\"first February two thousand eighteen\"", "\"[YI]\"" to "\"MMXVIII\"",
        "\"[Y0001][M01][D01]\"" to "\"20180201\"", "\"[F] [D]\"" to "\"Monday 3\"", "\"[Y]-[M]-[D] [H]:[m]:[s].[f]\"" to "\"2018-2-1 9:42:13.5\"")

    private val keys = listOf("id", "name", "type", "tags", "items", "value", "price", "qty", "owner", "children", "a", "b",
        "c", "Name", "deps", "meta", "10", "2", "1", "0", "x y", "\$weird", "é", "01")

    // ---- documents ----

    private fun randScalar(): Any? = when (int(20)) {
        in 0..6 -> pick(nums).toDouble().let { if (it == Math.floor(it) && Math.abs(it) < 1e15) it.toLong() else it }
        in 7..15 -> pick(strs)
        in 16..17 -> chance(0.5)
        else -> null
    }

    private fun randValue(depth: Int): Any? {
        val r = rnd.nextDouble()
        if (depth <= 0 || r < 0.35) return randScalar()
        if (r < 0.65) return List(int(5)) { randValue(depth - 1) }
        return randObject(depth - 1)
    }

    private fun randObject(depth: Int): Map<String, Any?> {
        val o = LinkedHashMap<String, Any?>()
        repeat(1 + int(5)) { o[pick(keys)] = randValue(depth) }
        return o
    }

    private fun archDoc(): Map<String, Any?> {
        val systems = List(2 + int(3)) { s ->
            mapOf(
                "id" to "s$s", "name" to "System $s",
                "owner" to if (chance(0.8)) mapOf("name" to pick(listOf("Ann", "Bob", "Čeněk")), "team" to pick(listOf("A", "B"))) else null,
                "components" to List(1 + int(4)) { c ->
                    mapOf(
                        "id" to "c$s$c", "name" to pick(listOf("api", "db", "Web", "queue", "cache", "Auth", "éditeur", "svc-$c")),
                        "type" to pick(listOf("service", "database", "queue", null)),
                        "cost" to pick(nums.subList(0, 30)).toDouble(),
                        "tags" to listOf(pick(listOf("prod", "dev", "Prod", "beta")), pick(listOf("eu", "us"))).subList(0, int(3)),
                        "deps" to if (chance(0.5)) listOf("c${int(4)}${int(3)}") else emptyList<String>(),
                    )
                },
            )
        }
        return mapOf("systems" to systems, "version" to pick(listOf("1.0", 1, 1.5)), "meta" to mapOf("2" to "two", "b" to "bee", "10" to "ten", "a" to "ay", "1" to "one"))
    }

    private fun doc(): JsonNode? = when (int(10)) {
        in 0..5 -> mapper.valueToTree(archDoc())
        in 6..8 -> mapper.valueToTree(randObject(3))
        else -> null
    }

    // ---- grammar ----

    private val fields = listOf("systems", "components", "name", "id", "type", "cost", "tags", "deps", "owner", "team", "meta", "version")
    private val agg = listOf("\$sum", "\$count", "\$max", "\$min", "\$average")

    private fun path(depth: Int): String {
        val sb = StringBuilder(pick(listOf("systems", "systems.components", "\$.systems", "systems[0]", "**", "meta", "*", "systems.owner", "\$\$.systems.components")))
        repeat(int(3)) {
            val r = rnd.nextDouble()
            when {
                r < 0.38 -> sb.append('.').append(pick(fields))
                r < 0.52 -> sb.append('[').append(pred(depth - 1)).append(']')
                r < 0.6 -> sb.append('[').append(pick(listOf("0", "-1", "1", "[0,1]", "[0..1]", "99", "1.5"))).append(']')
                r < 0.66 -> sb.append("@\$x")
                r < 0.72 -> sb.append("#\$i")
                r < 0.77 -> sb.append(".%")
                r < 0.83 -> sb.append("^(").append(pick(listOf("name", ">cost", "id", "\$", "cost, >name"))).append(')')
                r < 0.88 -> sb.append(".*")
                r < 0.92 -> sb.append("[]")
                else -> sb.append('.').append(pick(listOf("`10`", "`x y`", "\"name\"", "\$string()", "\$keys()", "\$count(\$)")))
            }
        }
        return sb.toString()
    }

    private fun pred(depth: Int): String = when (int(5)) {
        0 -> pick(listOf("type", "cost", "name", "tags")) + pick(listOf("=", "!=", ">", "<", ">=", "<=", " in ")) + atom(depth)
        1 -> "\$contains(name, " + pick(listOf("\"a\"", "/a/i", "\"é\"")) + ")"
        2 -> "\$exists(" + pick(listOf("owner", "deps", "nope")) + ")"
        3 -> pick(listOf("0", "-1", "true", "false", "\$i>0", "\"x\"", "[0,2]"))
        else -> expr(depth)
    }

    private fun atom(depth: Int): String = when (if (depth <= 0) int(4) else int(7)) {
        0 -> pick(nums)
        1 -> q(pick(strs))
        2 -> pick(listOf("true", "false", "null", "\$x", "\$i", "\$", "\$\$", "\$nothing"))
        3 -> pick(listOf("name", "cost", "tags", "id", "meta", "type"))
        4 -> path(depth)
        5 -> "[" + List(int(4)) { atom(depth - 1) }.joinToString(",") + "]"
        else -> "(" + expr(depth - 1) + ")"
    }

    private val strFns = listOf("\$string", "\$length", "\$uppercase", "\$lowercase", "\$trim", "\$substring", "\$substringBefore",
        "\$substringAfter", "\$pad", "\$contains", "\$split", "\$join", "\$replace", "\$match", "\$base64encode", "\$base64decode",
        "\$encodeUrlComponent", "\$encodeUrl", "\$decodeUrl", "\$decodeUrlComponent", "\$number", "\$boolean", "\$not", "\$type",
        "\$keys", "\$spread", "\$distinct", "\$reverse", "\$exists", "\$count", "\$abs", "\$floor", "\$ceil", "\$round", "\$sqrt")

    private fun expr(depth: Int): String {
        if (depth <= 0) return atom(0)
        return when (int(14)) {
            0, 1 -> atom(depth)
            2, 3 -> atom(depth - 1) + " " + pick(listOf("+", "-", "*", "/", "%", "&", "=", "!=", "<", ">", "<=", ">=", "and", "or", "in", "?:", "??", "..")) + " " + atom(depth - 1)
            4 -> pick(agg) + "(" + path(depth - 1) + ")"
            5 -> pick(strFns) + "(" + atom(depth - 1) + (if (chance(0.5)) ", " + atom(depth - 1) else "") + ")"
            6 -> "\$map(" + atom(depth - 1) + ", function(\$v, \$i){" + expr(depth - 1) + "})"
            7 -> "\$filter(" + atom(depth - 1) + ", function(\$v){" + expr(depth - 1) + "})"
            8 -> "\$reduce(" + atom(depth - 1) + ", function(\$a, \$b){\$a " + pick(listOf("+", "&", "*")) + " \$b}" + (if (chance(0.5)) ", " + atom(0) else "") + ")"
            9 -> "\$sort(" + atom(depth - 1) + (if (chance(0.5)) ", function(\$l, \$r){\$l " + pick(listOf(">", "<", ">=")) + " \$r}" else "") + ")"
            10 -> path(depth - 1) + "{" + pick(listOf("type", "name", "\$string(cost)", "tags", "id")) + ": " + pick(agg + "\$") + "(" + pick(listOf("cost", "name", "\$", "id")) + ")}"
            11 -> "{" + pick(listOf("\"k\"", "name", "type", "\"10\"", "\"2\"", "\"b\"")) + ": " + atom(depth - 1) + ", " + pick(listOf("\"1\"", "\"a\"", "\"01\"")) + ": " + atom(depth - 1) + "}"
            12 -> "(\$x := " + atom(depth - 1) + "; " + expr(depth - 1) + ")"
            else -> atom(depth - 1) + " ? " + atom(depth - 1) + " : " + atom(depth - 1)
        }
    }

    // ---- zones ----

    private val zones: List<Pair<String, () -> String>> = listOf(
        "grammar" to { expr(3) },
        "paths" to { path(3) },
        "numbers" to {
            when (int(9)) {
                0 -> "\$string(" + pick(nums) + (if (chance(0.3)) " " + pick(listOf("+", "*", "/", "-")) + " " + pick(nums) else "") + ")"
                1 -> "\$formatNumber(" + pick(nums) + ", " + pick(pictNum) + ")"
                2 -> "\$round(" + pick(nums) + ", " + pick(listOf("0", "1", "2", "3", "-1", "-2", "5")) + ")"
                3 -> "\$formatBase(" + pick(nums) + ", " + pick(listOf("2", "8", "16", "36", "10", "1", "37")) + ")"
                4 -> "\$formatInteger(" + pick(nums) + ", " + pick(pictInt) + ")"
                5 -> "\$number(" + q(pick(strs + nums)) + ")"
                6 -> "\$power(" + pick(nums) + ", " + pick(listOf("2", "0.5", "-1", "3", "10", "0")) + ")"
                7 -> "\$parseInteger(" + q(pick(listOf("12,345", "twelve", "xii", "MMXVIII", "ab", "1st", "0001", "one hundred and one"))) + ", " + pick(pictInt) + ")"
                else -> pick(nums) + " " + pick(listOf("+", "-", "*", "/", "%")) + " " + pick(nums)
            }
        },
        "strings" to {
            val s = q(pick(strs))
            when (int(9)) {
                0 -> "\$substring($s, " + pick(listOf("0", "1", "-1", "-3", "2.5", "99")) + (if (chance(0.5)) ", " + pick(listOf("0", "1", "2", "-1", "1.5")) else "") + ")"
                1 -> "\$pad($s, " + pick(listOf("5", "-5", "10", "0", "-10", "3.7")) + (if (chance(0.5)) ", " + q(pick(listOf("*", "ab", "😀", ""))) else "") + ")"
                2 -> "\$split($s, " + pick(listOf("\",\"", "\"\"", "\" \"", "/\\s+/", "/,\\s*/", "\"a\"")) + (if (chance(0.3)) ", " + pick(listOf("0", "1", "2")) else "") + ")"
                3 -> "\$length($s)"
                4 -> pick(listOf("\$uppercase", "\$lowercase", "\$trim", "\$base64encode", "\$encodeUrlComponent", "\$encodeUrl", "\$decodeUrlComponent")) + "($s)"
                5 -> "\$string(" + q(pick(strs)) + ") & " + pick(nums)
                6 -> "\$join([" + List(int(4)) { q(pick(strs)) }.joinToString(",") + "], " + q(pick(listOf(",", "", " - "))) + ")"
                7 -> "\$contains($s, " + q(pick(strs)) + ")"
                else -> "\$string({" + q(pick(strs)) + ": " + q(pick(strs)) + "})"
            }
        },
        "regex" to {
            val s = q(pick(strs))
            val re = pick(regexes)
            when (int(5)) {
                0 -> "\$match($s, $re" + (if (chance(0.3)) ", " + pick(listOf("1", "2", "0")) else "") + ")"
                1 -> "\$replace($s, $re, " + pick(repls) + (if (chance(0.2)) ", 1" else "") + ")"
                2 -> "\$split($s, $re)"
                3 -> "\$contains($s, $re)"
                else -> "$s ~> $re"
            }
        },
        "dates" to {
            when (int(4)) {
                0 -> "\$fromMillis(" + pick(millis) + (if (chance(0.8)) ", " + pick(pictDate) else "") + (if (chance(0.3)) ", " + pick(tz) else "") + ")"
                1 -> "\$toMillis(" + pick(dateStrs) + ")"
                2 -> pick(dateParse).let { (p, v) -> "\$toMillis($v, $p)" }
                else -> "\$fromMillis(\$toMillis(" + pick(dateStrs) + "), " + pick(pictDate) + ")"
            }
        },
        "sort" to {
            pick(listOf("\$sort(", "\$sort(")) + pick(listOf("[3,1,2]", "[\"b\",\"a\",\"C\"]", "[1,\"a\"]", "systems.components.cost", "systems.components.name", "[2,0.5,1e21,-0]", "[{\"a\":1}]")) +
                (if (chance(0.5)) ", function(\$l, \$r){" + pick(listOf("\$l > \$r", "\$l >= \$r", "\$l < \$r", "true", "\$l.a > \$r.a")) + "}" else "") + ")"
        },
        "keys" to {
            when (int(5)) {
                0 -> "\$keys(" + pick(listOf("meta", "\$", "systems", "{\"b\":1,\"2\":2,\"1\":3,\"01\":4}", "systems.owner")) + ")"
                1 -> "\$spread(" + pick(listOf("meta", "{\"b\":1,\"10\":2,\"a\":3}", "systems[0]")) + ")"
                2 -> "\$each(" + pick(listOf("meta", "{\"z\":1,\"3\":2}")) + ", function(\$v, \$k){\$k & \$v})"
                3 -> "\$merge([" + pick(listOf("meta", "{\"1\":1}", "{\"b\":2,\"0\":0}")) + ", {\"a\":1, \"5\":5}])"
                else -> "\$string(" + pick(listOf("meta", "\$", "{\"b\":1,\"2\":[1,{\"1\":2}]}")) + (if (chance(0.3)) ", true" else "") + ")"
            }
        },
        "grouping" to { path(1) + "{" + pick(listOf("type", "name", "id", "\$string(cost)", "owner.team", "\"k\"")) + ": " + pick(listOf("\$count(\$)", "\$sum(cost)", "name", "\$", "[name]", "{\"n\": name}")) + "}" },
        "nulls" to {
            pick(strFns + agg) + "(" + pick(listOf("null", "nope", "systems.nope", "owner", "[]", "[null]", "{}", "\$nothing")) + ")"
        },
        "functions" to {
            when (int(6)) {
                0 -> "\$map(" + atom(1) + ", " + pick(listOf("\$string", "\$uppercase", "\$substring(?, 0, 1)", "function(\$v, \$i, \$a){\$i}", "\$number")) + ")"
                1 -> "(\$f := function(\$x){" + expr(1) + "}; \$f(" + atom(1) + "))"
                2 -> atom(1) + " ~> " + pick(listOf("\$string", "\$count", "\$sum", "\$reverse", "\$uppercase ~> \$trim", "\$substring(?, 1)"))
                3 -> "\$single(" + pick(listOf("[1,2,3]", "systems", "[]", "[5]")) + (if (chance(0.5)) ", function(\$v){" + pick(listOf("\$v = 2", "true", "\$v.id = \"s0\"")) + "}" else "") + ")"
                4 -> "\$sift(" + pick(listOf("meta", "\$", "{\"a\":1,\"b\":null}")) + ", function(\$v, \$k){" + pick(listOf("\$k = \"a\"", "\$v", "true", "false")) + "})"
                else -> "(\$f := function(\$n){ \$n <= 1 ? \$n : \$f(\$n - 1) + \$f(\$n - 2) }; \$f(" + int(15) + "))"
            }
        },
        "transform" to {
            pick(listOf("\$", "systems[0]", "{\"a\":{\"b\":1}}", "meta")) + " ~> |" + pick(listOf("components", "owner", "\$", "a", "**")) + "|" +
                pick(listOf("{\"x\":1}", "{\"name\": name & \"!\"}", "{}", "5", "{\"cost\": cost * 2}")) + (if (chance(0.4)) ", " + pick(listOf("[\"id\"]", "\"tags\"", "5")) else "") + "|"
        },
    )

    fun next(index: Int, prefix: String): FuzzCase {
        val (zone, gen) = pick(zones)
        val expr = gen()
        val input = if (zone in setOf("numbers", "strings", "regex", "dates") && chance(0.7)) null else doc()
        return FuzzCase("$prefix-$index", zone, expr, input)
    }
}
