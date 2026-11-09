package com.rearman.datebook

import org.junit.Assert.assertEquals
import org.junit.Test

class MemoTest {
    private fun memo(id: Long, text: String, pos: Int = 0) = Memo(id = id, text = text, pos = pos)

    @Test fun titleIsTrimmedFirstLine() {
        assertEquals("Shopping", memo(1, "  Shopping  \nmilk, eggs").title)
        assertEquals("", memo(2, "").title)
    }

    @Test fun moveBackToTopRenumbers() {
        val ordered = listOf(memo(1, "a", 0), memo(2, "b", 1), memo(3, "c", 2), memo(4, "d", 3))
        moveMemo(ordered, from = 3, to = 0)
        assertEquals(listOf(4L, 1L, 2L, 3L), memoOrder(ordered, alphabetic = false).map { it.id })
        assertEquals(listOf(0, 1, 2, 3), memoOrder(ordered, alphabetic = false).map { it.pos })
    }
}
