package com.rearman.datebook

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

// Fmt.dayAndMinutes: the local day and minutes of one time, so the two agree at midnight.
class FmtTest {
    @Test
    fun dayAndMinutesTakeBothFromOneTime() {
        val day = LocalDate.of(2026, 11, 11).toEpochDay()
        val midnight = Recur.millis(day + 1, 0)
        assertEquals(day to 24 * 60 - 1, Fmt.dayAndMinutes(midnight - 1))
        assertEquals(day + 1 to 0, Fmt.dayAndMinutes(midnight))
        assertEquals(day to 754, Fmt.dayAndMinutes(Recur.millis(day, 754) + 59_999))
    }

    @Test
    fun digitsStayAsciiInEveryLocale() {
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-EG"))
            assertEquals("9:00 am", Fmt.time(540))
            assertEquals("9:00", Fmt.timeShort(540))
            assertEquals("26 Oct 5", Fmt.dayTitle(LocalDate.of(2026, 10, 5).toEpochDay()))
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }
}
