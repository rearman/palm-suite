package com.rearman.datebook

import org.junit.Assert.assertEquals
import org.junit.Test

class FreeTimesTest {
    private fun occ(start: Int, end: Int) = Occ(Event(start = start, end = end), 0)

    @Test fun endCoveredByAnotherEventGetsNoLine() {
        // 10:00 to 11:30 covers the 10:15 end. Its own end at 11:30 gets a line.
        assertEquals(listOf(540, 690), freeTimes(listOf(occ(570, 615), occ(600, 690)), 9, 12, ends = true))
    }

    @Test fun endAtStartOfNextEventGetsNoLine() {
        assertEquals(listOf(540, 660), freeTimes(listOf(occ(570, 615), occ(615, 660)), 9, 12, ends = true))
    }

    @Test fun sameEndTimeGivesOneLine() {
        assertEquals(listOf(540, 615, 660), freeTimes(listOf(occ(570, 615), occ(580, 615)), 9, 12, ends = true))
    }
}
