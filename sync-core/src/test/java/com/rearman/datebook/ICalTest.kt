package com.rearman.datebook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ICalTest {
    private val zone = ZoneId.of("America/Chicago")
    private fun day(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).toEpochDay()
    private fun keys(text: String) = ICal.parse(text, zone).events.map { it.dupKey() }

    @Test fun lastWeekFlagPicksTheLastWeekFromA4thWeekDate() {
        // October 25, 2026 is both the 4th and the last Sunday of October.
        val fourth = Event(title = "Club", date = day(2026, 10, 25), untimed = true, repeat = Repeat.MONTH_DAY)
        val last = fourth.copy(weekDays = Repeat.LAST_WEEK)
        // November 2026 has five Sundays: the 4th is the 22nd, the last the 29th.
        assertTrue(Recur.occursOn(fourth, day(2026, 11, 22)))
        assertTrue(!Recur.occursOn(fourth, day(2026, 11, 29)))
        assertTrue(Recur.occursOn(last, day(2026, 11, 29)))
        assertTrue(!Recur.occursOn(last, day(2026, 11, 22)))
        assertEquals("The last Sunday of every month", Recur.describe(last))
        val text = ICal.export(listOf(last))
        assertTrue(text.contains("RRULE:FREQ=MONTHLY;BYDAY=-1SU\r\n"))
        assertEquals(listOf(last.dupKey()), keys(text))
    }
}
