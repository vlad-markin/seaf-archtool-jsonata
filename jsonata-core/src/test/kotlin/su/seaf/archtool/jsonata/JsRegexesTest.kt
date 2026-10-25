package su.seaf.archtool.jsonata

import su.seaf.archtool.jsonata.value.ArrayValue
import su.seaf.archtool.jsonata.value.BooleanValue
import su.seaf.archtool.jsonata.value.ObjectValue
import su.seaf.archtool.jsonata.value.StringValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** `JsRegexes`: JS regexes with and without the `u` flag (story 3 step 3.2); expectations from Node 22. */
class JsRegexesTest {
    // produced by `new RegExp(pattern, flags).test(input)` in Node 22
    private val node = """{"good":[["^\\p{L}+${"$"}","u",[["abc",true],["p{L}",false],["äö",true]]],["^\\p{L}+${"$"}","",[["abc",false],["p{L}",true]]],["^.${"$"}","u",[["😀",true],["a",true]]],["^.${"$"}","",[["😀",false]]],["^\\u{1F600}${"$"}","u",[["😀",true]]],["^\\uD83D\\uDE00${"$"}","u",[["😀",true]]],["^[😀-😂]${"$"}","u",[["😁",true],["a",false]]],["\\P{L}","u",[["abc",false],["a1",true]]],["^[\\p{Lu}\\d]+${"$"}","u",[["AB1",true],["ab",false]]],["^[^\\p{L}]+${"$"}","u",[["123",true],["a",false]]],["\\p{Script=Greek}","u",[["α",true],["a",false]]],["\\p{sc=Cyrl}","u",[["я",true]]],["^\\p{Lowercase_Letter}${"$"}","u",[["a",true],["A",false]]],["^\\p{ASCII}+${"$"}","u",[["abc",true],["é",false]]],["[\\P{ASCII}]","u",[["é",true],["a",false]]],["\\p{Emoji_Presentation}","u",[["😀",true],["a",false]]],["^a{2}${"$"}","u",[["aa",true]]],["(?<y>\\d)\\k<y>","u",[["11",true],["12",false]]],["^\\x41${"$"}","u",[["A",true]]],["^\\cJ${"$"}","u",[["\n",true]]],["^\\0${"$"}","u",[["\u0000",true]]],["^[\\-]${"$"}","u",[["-",true]]],["^\\/\\.\\*${"$"}","u",[["/.*",true]]],["^\\S${"$"}","u",[["😀",true]]],["\\bx\\b","u",[["a x b",true],["axb",false]]],["^ß${"$"}","iu",[["SS",false],["ß",true]]],["^k${"$"}","iu",[["K",true],["K",true]]],["^[a-z]${"$"}","iu",[["K",true]]]],"bad":[["\\a","u",false],["a{","u",false],["}","u",false],["]","u",false],["\\-","u",false],["\\012","u",false],["\\1","u",false],["\\p{Foo}","u",false],["\\p{L}x\\pL","u",false],["[","u",false],["[","",false],["\\u{110000}","u",false],["\\k<x>","u",false],["\\c1","u",false],["\\xZ","u",false],["a**","",false],["(?i)a","",false]]}"""

    @Test
    fun `matches like JavaScript`() {
        val data = Jsonata.compile(node).evaluate(null) as ObjectValue
        var count = 0
        for (case in (data["good"] as ArrayValue).toList()) {
            case as ArrayValue
            val pattern = (case[0] as StringValue).value
            val flags = (case[1] as StringValue).value
            val regex = JsRegexes.compile(pattern, flags)
            for (pair in (case[2] as ArrayValue).toList()) {
                pair as ArrayValue
                val input = (pair[0] as StringValue).value
                assertEquals((pair[1] as BooleanValue).value, regex.test(input), "/$pattern/$flags on \"$input\"")
                count++
            }
        }
        for (case in (data["bad"] as ArrayValue).toList()) {
            case as ArrayValue
            val pattern = (case[0] as StringValue).value
            val flags = (case[1] as StringValue).value
            assertFailsWith<IllegalArgumentException>("/$pattern/$flags") { JsRegexes.compile(pattern, flags) }
            count++
        }
        assertTrue(count > 50)
    }

    @Test
    fun `a poll can stop a long match`() {
        var polls = 0
        assertEquals(false, JsRegexes.compile("x").test("a".repeat(5000)) { polls++ })
        assertTrue(polls >= 4)
        assertFailsWith<IllegalStateException> { JsRegexes.compile("x").test("a".repeat(5000)) { throw IllegalStateException("stop") } }
    }
}
