package com.rearman.datebook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SplitFutureTest {
    // A daily series from day 100 with exceptions on days 103 and 107.
    private fun series() = Event(date = 100, repeat = Repeat.DAY, exceptions = mutableSetOf(103L, 107L))

    @Test fun splitMidSeriesEndsOriginalTheDayBefore() {
        val orig = series()
        val (short, future) = splitFuture(orig, 105, orig.deepCopy().apply { date = 105 })
        assertEquals(104L, short!!.repeatEnd)
        assertEquals(100L, short.date)
        assertEquals(105L, future.date)
        assertEquals(0L, future.id)
    }

    @Test fun splitAtFirstOccurrenceGivesNoOriginal() {
        val orig = series()
        assertNull(splitFuture(orig, 100, orig.deepCopy()).first)
    }

    @Test fun exceptionsStayOrMoveWithTheSplit() {
        val orig = series()
        val (short, future) = splitFuture(orig, 105, orig.deepCopy().apply { date = 105 })
        assertEquals(setOf(103L), short!!.exceptions)
        assertEquals(setOf(107L), future.exceptions)
    }

    @Test fun movedFutureSeriesShiftsItsExceptions() {
        // The new series starts one day later, so its exception on 107 moves to 108.
        val orig = series()
        val (_, future) = splitFuture(orig, 105, orig.deepCopy().apply { date = 106 })
        assertEquals(setOf(108L), future.exceptions)
    }
}
