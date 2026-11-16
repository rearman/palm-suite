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

    private fun cal(vararg lines: String) =
        (listOf("BEGIN:VCALENDAR", "BEGIN:VEVENT") + lines + listOf("END:VEVENT", "END:VCALENDAR")).joinToString("\r\n")

    @Test fun utcStartOnTheDayBeforeMovesTheWeekDays() {
        // 03:00 UTC on Tuesday November 3, 2026 is 21:00 on Monday in Chicago.
        val p = ICal.parse(cal("DTSTART:20261103T030000Z", "RRULE:FREQ=WEEKLY;BYDAY=TU"), zone)
        val e = p.events.single()
        assertEquals(day(2026, 11, 2), e.date)
        assertEquals(21 * 60, e.start)
        assertEquals(1 shl 1, e.weekDays)
        assertTrue(Recur.occursOn(e, day(2026, 11, 9)))
        // UTC has no daylight saving time and Chicago has, so the wall time moves in March.
        assertEquals(1, p.simplified)
    }

    @Test fun utcStartOnTheMonthBeforeIsSimplified() {
        // 03:00 UTC on November 1, 2026 is 22:00 on October 31 in Chicago, a day that short months lack.
        val p = ICal.parse(cal("DTSTART:20261101T030000Z", "RRULE:FREQ=MONTHLY"), zone)
        assertEquals(day(2026, 10, 31), p.events.single().date)
        assertEquals(1, p.simplified)
    }

    @Test fun weekStartOfMondaySplitsSundayFromMonday() {
        val mixed = ICal.parse(cal("DTSTART:20260105T090000", "RRULE:FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,SU"), zone)
        assertEquals(1, mixed.simplified)
        val sunday = ICal.parse(cal("DTSTART:20260105T090000", "RRULE:FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,SU;WKST=SU"), zone)
        assertEquals(0, sunday.simplified)
        // With WKST=MO the first Sunday is in the week of Monday January 5.
        val e = ICal.parse(cal("DTSTART:20260105T090000", "RRULE:FREQ=WEEKLY;INTERVAL=2;BYDAY=SU"), zone).events.single()
        assertEquals(day(2026, 1, 11), e.date)
        assertTrue(Recur.occursOn(e, day(2026, 1, 25)))
        assertTrue(!Recur.occursOn(e, day(2026, 1, 18)))
    }

    @Test fun untilBeforeTheStartTimeEndsTheDayBefore() {
        val e = ICal.parse(cal("DTSTART:20261110T090000", "RRULE:FREQ=DAILY;UNTIL=20261115T080000"), zone).events.single()
        assertEquals(day(2026, 11, 14), e.repeatEnd)
    }

    @Test fun countWithAStartOffTheRuleCountsTheStart() {
        // Wednesday October 21, 2026, with Mondays: the start, then October 26 and November 2.
        val p = ICal.parse(cal("DTSTART:20261021T090000", "RRULE:FREQ=WEEKLY;BYDAY=MO;COUNT=3"), zone)
        val e = p.events.single()
        assertEquals(day(2026, 10, 26), e.date)
        assertEquals(day(2026, 11, 2), e.repeatEnd)
        assertEquals(1, p.simplified)
        val one = ICal.parse(cal("DTSTART:20261021T090000", "RRULE:FREQ=WEEKLY;BYDAY=MO;COUNT=1"), zone)
        assertEquals(Repeat.NONE, one.events.single().repeat)
        assertEquals(day(2026, 10, 21), one.events.single().date)
        assertEquals(0, one.simplified)
    }

    @Test fun exportLeavesOutAnEventThatNeverOccurs() {
        val never = Event(title = "Never", date = day(2026, 10, 21), repeat = Repeat.WEEK, weekDays = 1 shl 1, repeatEnd = day(2026, 10, 24))
        assertTrue(!ICal.export(listOf(never)).contains("BEGIN:VEVENT"))
    }

    @Test fun exportUidComesFromTheSyncUid() {
        val now = java.time.Instant.parse("2026-10-22T12:34:56Z")
        val text = ICal.export(listOf(Event(id = 5, uid = "abc-1", date = 20000), Event(date = 20000), Event(date = 20001)), now)
        assertTrue(text.contains("UID:abc-1@com.rearman.datebook\r\n"))
        assertTrue(text.contains("UID:new-20261022T123456Z-1@com.rearman.datebook\r\n"))
        assertTrue(text.contains("UID:new-20261022T123456Z-2@com.rearman.datebook\r\n"))
    }

    @Test fun exportSkipsDaysPastTheYear9999() {
        val e = Event(title = "Far", date = 20000, repeat = Repeat.DAY, repeatEnd = 4_000_000_000_000_000L, exceptions = mutableSetOf(4_000_000_000_000_000L))
        val text = ICal.export(listOf(e))
        assertTrue(text.contains("RRULE:FREQ=DAILY\r\n"))
        assertTrue(!text.contains("EXDATE"))
    }

    @Test fun durationPastTheDateRangeCountsAsNone() {
        for (d in listOf("P9999999999999999W", "P99999999999W")) {
            val e = ICal.parse(cal("DTSTART:20261110T090000", "DURATION:$d"), zone).events.single()
            assertEquals(9 * 60, e.end)
        }
        val a = ICal.parse(cal("DTSTART:20261110T090000", "BEGIN:VALARM", "TRIGGER:-P9999999999999999W", "END:VALARM"), zone)
        assertEquals(0, a.events.single().alarmAdvance)
    }

    @Test fun foldInsideAUtf8SequenceJoinsTheBytes() {
        val bytes = "BEGIN:VEVENT\r\nDTSTART:20261110T090000\r\nSUMMARY:M".toByteArray() + byteArrayOf(0xC3.toByte()) +
            "\r\n ".toByteArray() + byteArrayOf(0xBC.toByte()) + "ller\r\nEND:VEVENT\r\n".toByteArray()
        assertEquals("Müller", ICal.parse(bytes, zone).events.single().title)
    }

    @Test fun movedWeeklySeriesTurnsItsDaysAndKeepsItsExceptions() {
        // Mondays and Wednesdays from Monday November 2, 2026, without Wednesday November 4, moved a day later.
        val e = Event(date = day(2026, 11, 2), repeat = Repeat.WEEK, weekDays = (1 shl 1) or (1 shl 3), exceptions = mutableSetOf(day(2026, 11, 4)), repeatEnd = day(2026, 11, 30))
        val m = moveSeries(e, 1)
        assertEquals(day(2026, 11, 3), m.date)
        assertEquals(day(2026, 12, 1), m.repeatEnd)
        assertEquals((1 shl 2) or (1 shl 4), m.weekDays)
        assertEquals(setOf(day(2026, 11, 5)), m.exceptions)
        assertTrue(Recur.occursOn(m, day(2026, 11, 10)))
        assertTrue(Recur.occursOn(m, day(2026, 11, 12)))
        assertTrue(!Recur.occursOn(m, day(2026, 11, 5)))
        // A monthly series keeps its last-week flag.
        assertEquals(Repeat.LAST_WEEK, moveSeries(Event(date = 20000, repeat = Repeat.MONTH_DAY, weekDays = Repeat.LAST_WEEK), 7).weekDays)
        // A series with no end keeps none.
        assertEquals(-1L, moveSeries(Event(date = 20000, repeat = Repeat.DAY), 7).repeatEnd)
    }

    @Test fun firstDayOfAHugeIntervalIsDirect() {
        // Monday November 16, 2026, every 2147483647 weeks on Sunday: the Sunday of the second repeat week.
        val e = Event(date = day(2026, 11, 16), repeat = Repeat.WEEK, every = Int.MAX_VALUE, weekDays = 1)
        assertEquals(day(2026, 11, 15) + 7L * Int.MAX_VALUE, Recur.firstDay(e))
        assertEquals(1, Recur.rotateWeekDays(1 shl 1, -1))
        assertEquals(1, Recur.rotateWeekDays(1 shl 6, 1))
    }
}
