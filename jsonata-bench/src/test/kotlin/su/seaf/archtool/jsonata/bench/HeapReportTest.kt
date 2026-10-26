package su.seaf.archtool.jsonata.bench

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The heap table of the stand: minimums at the parse floor are marked (review RV3-21). */
class HeapReportTest {
    @Test
    fun `a query whose minimum is that of parsing is marked`() {
        val parse = HeapMin(150, 171)
        assertEquals("| (только разбор) | 200000 | 150 | 171 | 0.88 |", heapRow(PARSE_ROW, 200_000, parse, null))
        // V8 at its parse floor (within the resolution), the JVM above it
        assertEquals("| path_filter | 200000 | 183 | 163 (= разбор) | 1.12 |", heapRow("path_filter", 200_000, HeapMin(183, 163), parse))
        assertEquals("| group_nested | 200000 | 152 (= разбор) | 290 | 0.52 |", heapRow("group_nested", 200_000, HeapMin(152, 290), parse))
        assertEquals("| sort_fn | 200000 | 400 | — | — |", heapRow("sort_fn", 200_000, HeapMin(400, null), parse))
        assertTrue(atParseFloor(171, 171))
        assertTrue(atParseFloor(175, 171))
        assertFalse(atParseFloor(190, 171))
        assertTrue(atParseFloor(10, 8)) // at least 4 MB
        assertFalse(atParseFloor(null, 171))
    }
}
