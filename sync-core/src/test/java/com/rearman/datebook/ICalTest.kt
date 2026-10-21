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
    private fun cal(vararg lines: String) = (listOf("BEGIN:VCALENDAR", "VERSION:2.0") + lines + "END:VCALENDAR").joinToString("\r\n")

    @Test fun weeklyEventSurvivesExportAndImport() {
        val e = Event(
            id = 7, title = "Staff, meeting; room 2", note = "line 1\nline 2\\end " + "ö".repeat(60),
            date = day(2026, 10, 19), start = 9 * 60, end = 10 * 60 + 30,
            alarm = true, alarmAdvance = 15, repeat = Repeat.WEEK, every = 2,
            weekDays = (1 shl 1) or (1 shl 3), repeatEnd = day(2026, 12, 31),
            exceptions = mutableSetOf(day(2026, 11, 2)), isPrivate = true
        )
        val text = ICal.export(listOf(e))
        assertTrue(text.split("\r\n").all { it.toByteArray().size <= 75 })
        val back = ICal.parse(text, zone)
        assertEquals(0, back.simplified)
        assertEquals(listOf(e.dupKey()), back.events.map { it.dupKey() })
    }

    @Test fun untimedYearlyAndLastWeekdaySurvive() {
        val bday = Event(title = "Birthday", date = day(2027, 3, 5), untimed = true, repeat = Repeat.YEAR)
        val last = Event(title = "Review", date = day(2026, 10, 29), start = 14 * 60, end = 15 * 60, repeat = Repeat.MONTH_DAY)
        val text = ICal.export(listOf(bday, last))
        assertTrue(text.contains("RRULE:FREQ=MONTHLY;BYDAY=-1TH\r\n"))
        assertEquals(listOf(bday.dupKey(), last.dupKey()), keys(text))
    }

    @Test fun weeklyStartMovesToFirstOccurrence() {
        val e = Event(title = "Gym", date = day(2026, 10, 21), start = 8 * 60, repeat = Repeat.WEEK, every = 2, weekDays = 1 shl 1)
        assertEquals(day(2026, 11, 2), Recur.firstDay(e))
        assertTrue(ICal.export(listOf(e)).contains("DTSTART:20261102T080000\r\n"))
    }

    @Test fun googleStyleFileImports() {
        val text = cal(
            "BEGIN:VTIMEZONE",
            "TZID:America/New_York",
            "END:VTIMEZONE",
            "BEGIN:VEVENT",
            "DTSTART:20261020T140000Z",
            "DTEND:20261020T150000Z",
            "RRULE:FREQ=DAILY;COUNT=3",
            "UID:abc@google.com",
            "SUMMARY:Stand",
            "  up",
            "BEGIN:VALARM",
            "ACTION:DISPLAY",
            "TRIGGER:-P1D",
            "END:VALARM",
            "END:VEVENT",
            "BEGIN:VEVENT",
            "DTSTART;TZID=America/New_York:20261021T100000",
            "DURATION:PT45M",
            "RECURRENCE-ID:20261021T140000Z",
            "UID:abc@google.com",
            "SUMMARY:Stand up (moved)",
            "END:VEVENT",
            "BEGIN:VEVENT",
            "DTSTART;VALUE=DATE:20261101",
            "DTEND;VALUE=DATE:20261104",
            "SUMMARY:Trip",
            "END:VEVENT",
            "BEGIN:VEVENT",
            "DTSTART:20261105T080000",
            "RRULE:FREQ=MONTHLY;BYSETPOS=-1;BYDAY=MO,TU,WE,TH,FR",
            "SUMMARY:Report",
            "END:VEVENT"
        )
        val p = ICal.parse(text, zone)
        val want = listOf(
            Event(
                title = "Stand up", date = day(2026, 10, 20), start = 9 * 60, end = 10 * 60,
                alarm = true, alarmAdvance = 1, alarmUnit = 2, repeat = Repeat.DAY,
                repeatEnd = day(2026, 10, 22), exceptions = mutableSetOf(day(2026, 10, 21))
            ),
            Event(title = "Stand up (moved)", date = day(2026, 10, 21), start = 9 * 60, end = 9 * 60 + 45),
            Event(title = "Trip", date = day(2026, 11, 1), untimed = true, repeat = Repeat.DAY, repeatEnd = day(2026, 11, 3)),
            Event(title = "Report", date = day(2026, 11, 5), start = 8 * 60, end = 8 * 60)
        )
        assertEquals(want.map { it.dupKey() }, p.events.map { it.dupKey() })
        assertEquals(1, p.simplified)
    }

    @Test fun triggerOver99MinutesImportsInMinutes() {
        val p = ICal.parse(cal(
            "BEGIN:VEVENT", "DTSTART:20261106T100000", "DTEND:20261106T110000", "SUMMARY:Call",
            "BEGIN:VALARM", "TRIGGER:-PT150M", "END:VALARM", "END:VEVENT"
        ), zone)
        val want = listOf(Event(title = "Call", date = day(2026, 11, 6), start = 10 * 60, end = 11 * 60,
            alarm = true, alarmAdvance = 150, alarmUnit = 0))
        assertEquals(want.map { it.dupKey() }, p.events.map { it.dupKey() })
        assertEquals(150, p.events[0].alarmAdvance)
        assertEquals(0, p.events[0].alarmUnit)
    }

    @Test fun intervalOver99ImportsAsDailyRepeat() {
        val p = ICal.parse(cal(
            "BEGIN:VEVENT", "DTSTART:20261106T100000", "DTEND:20261106T110000", "SUMMARY:Water",
            "RRULE:FREQ=DAILY;INTERVAL=150", "END:VEVENT"
        ), zone)
        assertEquals(1, p.events.size)
        assertEquals(Repeat.DAY, p.events[0].repeat)
        assertEquals(150, p.events[0].every)
        assertEquals(0, p.simplified)
    }

    @Test fun overnightEventSplitsByDay() {
        val p = ICal.parse(cal(
            "BEGIN:VEVENT", "DTSTART:20261106T220000", "DTEND:20261108T060000", "SUMMARY:Retreat",
            "BEGIN:VALARM", "TRIGGER:-PT10M", "END:VALARM", "END:VEVENT",
            "BEGIN:VEVENT", "DTSTART:20261109T230000", "DTEND:20261110T000000", "SUMMARY:Late", "END:VEVENT"
        ), zone)
        val want = listOf(
            Event(title = "Retreat", date = day(2026, 11, 6), start = 22 * 60, end = 24 * 60 - 1, alarm = true, alarmAdvance = 10),
            Event(title = "Retreat", date = day(2026, 11, 7), start = 0, end = 24 * 60 - 1),
            Event(title = "Retreat", date = day(2026, 11, 8), start = 0, end = 6 * 60),
            Event(title = "Late", date = day(2026, 11, 9), start = 23 * 60, end = 24 * 60 - 1)
        )
        assertEquals(want.map { it.dupKey() }, p.events.map { it.dupKey() })
        assertEquals(0, p.simplified)
    }

    @Test fun overnightWeeklyShiftMovesItsDays() {
        // Fri and Sat 22:00 to 06:00 gives Fri+Sat 22:00 to 23:59 and Sat+Sun 00:00 to 06:00, with the exception moved too.
        val p = ICal.parse(cal(
            "BEGIN:VEVENT", "UID:shift", "DTSTART:20261023T220000", "DTEND:20261024T060000",
            "RRULE:FREQ=WEEKLY;BYDAY=FR,SA", "EXDATE:20261030T220000", "SUMMARY:Shift", "END:VEVENT",
            "BEGIN:VEVENT", "UID:shift", "RECURRENCE-ID:20261106T220000", "STATUS:CANCELLED",
            "DTSTART:20261106T220000", "END:VEVENT"
        ), zone)
        val fri = 1 shl 5
        val sat = 1 shl 6
        val want = listOf(
            Event(title = "Shift", date = day(2026, 10, 23), start = 22 * 60, end = 24 * 60 - 1, repeat = Repeat.WEEK,
                weekDays = fri or sat, exceptions = mutableSetOf(day(2026, 10, 30), day(2026, 11, 6))),
            Event(title = "Shift", date = day(2026, 10, 24), start = 0, end = 6 * 60, repeat = Repeat.WEEK,
                weekDays = sat or 1, exceptions = mutableSetOf(day(2026, 10, 31), day(2026, 11, 7)))
        )
        assertEquals(want.map { it.dupKey() }, p.events.map { it.dupKey() })
        assertEquals(0, p.simplified)
    }
}
