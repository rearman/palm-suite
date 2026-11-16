package com.rearman.datebook

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * iCalendar (RFC 5545) files of Date Book events. Date Book has no time zones, so export writes
 * floating local times, and import changes UTC and TZID times to the device time zone.
 */
object ICal {
    private val dayFmt = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val timeFmt = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val dayCodes = listOf("SU", "MO", "TU", "WE", "TH", "FR", "SA")
    private val ruleParts = setOf("FREQ", "INTERVAL", "UNTIL", "COUNT", "BYDAY", "BYMONTHDAY", "BYMONTH", "WKST")
    private val durRe = Regex("([+-])?P(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?")

    // The days that export writes: a date has four digits of year, 0001 to 9999.
    private val exportFirst = LocalDate.of(1, 1, 1).toEpochDay()
    private val exportLast = LocalDate.of(9999, 12, 31).toEpochDay()

    // ---- export ----

    /**
     * Writes [events] as one VCALENDAR. An event that never occurs, or that starts outside the years 1 to 9999,
     * is left out; so is an exception outside them, and an end past them writes no UNTIL.
     */
    fun export(events: List<Event>, now: Instant = Instant.now()): String {
        val out = StringBuilder()
        fun line(s: String) = fold(s, out)
        fun at(day: LocalDate, min: Int) = timeFmt.format(day.atStartOfDay().plusMinutes(min.toLong()))
        val stamp = timeFmt.format(now.atOffset(ZoneOffset.UTC)) + "Z"
        line("BEGIN:VCALENDAR")
        line("VERSION:2.0")
        line("PRODID:-//rearman//Date Book//EN")
        for ((index, e) in events.withIndex()) {
            if (e.date !in exportFirst..exportLast) continue
            val first = Recur.firstDay(e)
            if (first > exportLast) continue
            if (e.repeats && !Recur.occursOn(e.copy(exceptions = mutableSetOf()), first)) continue
            val d = LocalDate.ofEpochDay(first)
            // The sync uid is the same on every device. A record without one keeps its row id, and an unsaved
            // record gets one from this export.
            val uid = when {
                e.uid.isNotEmpty() -> e.uid
                e.id != 0L -> "${e.id}"
                else -> "new-$stamp-$index"
            }
            line("BEGIN:VEVENT")
            line("UID:${escape(uid)}@com.rearman.datebook")
            line("DTSTAMP:$stamp")
            if (e.untimed) {
                line("DTSTART;VALUE=DATE:${dayFmt.format(d)}")
                line("DTEND;VALUE=DATE:${dayFmt.format(d.plusDays(1))}")
            } else {
                line("DTSTART:${at(d, e.start)}")
                line("DTEND:${at(d, maxOf(e.start, e.end))}")
            }
            line("SUMMARY:${escape(e.title)}")
            if (e.note.isNotEmpty()) line("DESCRIPTION:${escape(e.note)}")
            if (e.isPrivate) line("CLASS:PRIVATE")
            rule(e)?.let { line("RRULE:$it") }
            if (e.repeats) for (x in e.exceptions.sorted()) {
                if (x !in exportFirst..exportLast) continue
                val xd = LocalDate.ofEpochDay(x)
                line(if (e.untimed) "EXDATE;VALUE=DATE:${dayFmt.format(xd)}" else "EXDATE:${at(xd, e.start)}")
            }
            if (e.alarm) {
                val n = e.alarmAdvance
                line("BEGIN:VALARM")
                line("ACTION:DISPLAY")
                line("DESCRIPTION:${escape(e.title)}")
                line("TRIGGER:-" + when (e.alarmUnit) { 0 -> "PT${n}M"; 1 -> "PT${n}H"; else -> "P${n}D" })
                line("END:VALARM")
            }
            line("END:VEVENT")
        }
        line("END:VCALENDAR")
        return out.toString()
    }

    /** BYDAY of a "Nth weekday of the month" repeat: "3MO", or "-1MO" for the last one. */
    private fun weekCode(e: Event): String {
        val w = Recur.monthWeek(e)
        return (if (w == 5) "-1" else "$w") + dayCodes[Recur.dow(LocalDate.ofEpochDay(e.date))]
    }

    /** The RRULE value of [e], or null for a one-time event. */
    private fun rule(e: Event): String? {
        val s = LocalDate.ofEpochDay(e.date)
        val r = StringBuilder("FREQ=")
        when (e.repeat) {
            Repeat.DAY -> r.append("DAILY")
            Repeat.WEEK -> {
                val mask = if (e.weekDays == 0) 1 shl Recur.dow(s) else e.weekDays
                val days = (0..6).filter { (mask and (1 shl it)) != 0 }.joinToString(",") { dayCodes[it] }
                r.append("WEEKLY;WKST=SU;BYDAY=$days")
            }
            Repeat.MONTH_DAY -> r.append("MONTHLY;BYDAY=${weekCode(e)}")
            Repeat.MONTH_DATE -> r.append("MONTHLY;BYMONTHDAY=${s.dayOfMonth}")
            Repeat.YEAR -> r.append("YEARLY")
            else -> return null
        }
        if (e.every > 1) r.append(";INTERVAL=${e.every}")
        if (e.repeatEnd in 0..exportLast) {
            val u = LocalDate.ofEpochDay(e.repeatEnd)
            r.append(";UNTIL=").append(if (e.untimed) dayFmt.format(u) else timeFmt.format(u.atTime(23, 59, 59)))
        }
        return r.toString()
    }

    internal fun escape(s: String) =
        s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\r", "").replace("\n", "\\n")

    /** Appends [s] as content lines of at most 75 octets, each with a CRLF end. */
    internal fun fold(s: String, out: StringBuilder) {
        var n = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val len = Character.charCount(cp)
            val bytes = when { cp < 0x80 -> 1; cp < 0x800 -> 2; cp < 0x10000 -> 3; else -> 4 }
            if (n + bytes > 75) { out.append("\r\n "); n = 1 }
            out.append(s, i, i + len)
            n += bytes
            i += len
        }
        out.append("\r\n")
    }

    // ---- import ----

    /** The events of a file, and the number of file events that Date Book could not show in full. */
    class Parsed(val events: List<Event>, val simplified: Int)

    private class Prop(val name: String, val params: Map<String, String>, val value: String)

    /**
     * One VEVENT, as one Date Book event for each day it covers ([events] index = days after the
     * first). An item with a [recurrenceDay] changes one occurrence of the series with the same [uid].
     */
    private class Item(val uid: String, val recurrenceDay: Long?, val events: List<Event>, val simplified: Boolean)

    /** Reads [text] as the UTF-8 bytes of a file. Pass the bytes of a file when there are any. */
    fun parse(text: String, zone: ZoneId = ZoneId.systemDefault()): Parsed = parse(text.toByteArray(Charsets.UTF_8), zone)

    /** Reads the events of a file. The lines are unfolded as bytes, then read as UTF-8. */
    fun parse(bytes: ByteArray, zone: ZoneId = ZoneId.systemDefault()): Parsed {
        val items = mutableListOf<Item>()
        val stack = ArrayDeque<String>()
        var props = mutableListOf<Prop>()
        var trigger: Prop? = null
        for (l in unfold(bytes)) {
            val p = prop(l) ?: continue
            when (p.name) {
                "BEGIN" -> {
                    stack.addLast(p.value.trim().uppercase())
                    if (stack.last() == "VEVENT") { props = mutableListOf(); trigger = null }
                }
                "END" -> if (stack.removeLastOrNull() == "VEVENT") item(props, trigger, zone)?.let { items.add(it) }
                else -> when {
                    stack.lastOrNull() == "VEVENT" -> props.add(p)
                    stack.lastOrNull() == "VALARM" && stack.getOrNull(stack.size - 2) == "VEVENT" &&
                        p.name == "TRIGGER" && trigger == null -> { trigger = p }
                }
            }
        }
        // A changed or cancelled occurrence becomes an exception of its series, in each day part.
        for (o in items) {
            val day = o.recurrenceDay ?: continue
            if (o.uid.isEmpty()) continue
            val series = items.firstOrNull { it.recurrenceDay == null && it.uid == o.uid } ?: continue
            series.events.forEachIndexed { k, ev -> if (ev.repeats) ev.exceptions.add(day + k) }
        }
        return Parsed(items.flatMap { it.events }, items.count { it.simplified })
    }

    private fun item(props: List<Prop>, trigger: Prop?, zone: ZoneId): Item? {
        fun first(n: String) = props.firstOrNull { it.name == n }
        val ds = first("DTSTART") ?: return null
        val start = time(ds.params, ds.value, zone) ?: return null
        val untimed = isDate(ds.params, ds.value)
        // The zone and the time that DTSTART names. A UTC or TZID start can fall on another day here.
        val srcZone = sourceZone(ds.params, ds.value)
        val written = wall(ds.params, ds.value) ?: return null
        val day0 = start.toLocalDate().toEpochDay()
        val src = if (srcZone == null) start.toLocalDate() else written.toLocalDate()
        val delta = day0 - src.toEpochDay()
        val uid = first("UID")?.value?.trim().orEmpty()
        val rid = first("RECURRENCE-ID")?.let { time(it.params, it.value, zone) }?.toLocalDate()?.toEpochDay()
        if (first("STATUS")?.value?.trim().equals("CANCELLED", true)) return Item(uid, rid, emptyList(), false)
        // A DURATION whose end falls past the dates of LocalDateTime counts as none.
        val end = first("DTEND")?.let { time(it.params, it.value, zone) }
            ?: first("DURATION")?.let { d ->
                durRe.matchEntire(d.value.trim())?.let { m -> minutes(m)?.let { runCatching { start.plusMinutes(it) }.getOrNull() } }
            }
            ?: if (untimed) start.plusDays(1) else start
        val e = Event(
            title = first("SUMMARY")?.let { unescape(it.value) }.orEmpty(),
            note = first("DESCRIPTION")?.let { unescape(it.value) }.orEmpty(),
            date = day0, untimed = untimed,
            isPrivate = first("CLASS")?.value?.trim()?.uppercase() in setOf("PRIVATE", "CONFIDENTIAL")
        )
        var simplified = false
        // Days that a timed event covers after its first day, and its end time on the last one.
        // An end at midnight covers no part of that day.
        var span = 0L
        var lastEnd = 0
        if (!untimed) {
            val midnight = end > start && end.toLocalTime() == LocalTime.MIDNIGHT
            span = maxOf(0L, end.toLocalDate().toEpochDay() - (if (midnight) 1 else 0) - e.date)
            lastEnd = if (midnight) 24 * 60 - 1 else end.hour * 60 + end.minute
            e.start = start.hour * 60 + start.minute
            e.end = if (span > 0) 24 * 60 - 1 else maxOf(e.start, lastEnd)
        }
        if (trigger != null) {
            val (n, unit) = advance(trigger.value)
            e.alarm = true
            e.alarmAdvance = n
            e.alarmUnit = unit
        }
        val rule = first("RRULE")
        val parts = rule?.let { ruleMap(it.value) }
        if (rid == null && parts != null) {
            if (!setRule(e, parts, zone, src, start.toLocalTime())) simplified = true
            if (e.repeats && srcZone != null) {
                // Shortcut: the first year of the series only. Upgrade path: each year to its end.
                val from = written.atZone(srcZone).toInstant()
                var to = from.plusSeconds(366L * 86400)
                if (e.repeatEnd >= 0) {
                    val last = LocalDate.ofEpochDay(e.repeatEnd + 1).atStartOfDay(zone).toInstant()
                    if (last.isBefore(to)) to = last
                }
                if (!keepsOffset(srcZone, zone, from, to)) simplified = true
            }
        }
        val days = end.toLocalDate().toEpochDay() - day0
        if (untimed && days > 1) {
            // A multi-day untimed event becomes a daily repeat over its days.
            if (rule == null && rid == null) { e.repeat = Repeat.DAY; e.repeatEnd = e.date + days - 1 } else simplified = true
        }
        if (e.repeats) for (x in props) if (x.name == "EXDATE") for (v in x.value.split(',')) {
            time(x.params, v, zone)?.let { e.exceptions.add(it.toLocalDate().toEpochDay()) }
        }
        if (first("RDATE") != null) simplified = true
        val events = mutableListOf(e)
        val mask = if (e.weekDays == 0) 1 shl Recur.dow(LocalDate.ofEpochDay(e.date)) else e.weekDays
        if (e.repeat == Repeat.WEEK && e.every > 1 && parts != null) {
            // Date Book counts weeks from Sunday and the file from its WKST, in its own zone. Each day part
            // is checked; a part moves the days by one more day.
            val srcMask = Recur.rotateWeekDays(mask, -delta)
            val wkst = weekStart(parts)
            for (k in 0..minOf(span, 30L)) if (!sameWeeks(srcMask, wkst, delta + k)) simplified = true
        }
        if (span > 0) {
            // A timed event that goes past midnight becomes one event for each day it covers.
            // Shortcut: at most 31 days. Upgrade path: an end date in Event.
            for (k in 1..minOf(span, 30L)) {
                events.add(
                    e.copy(
                        date = e.date + k, start = 0, end = if (k == span) lastEnd else 24 * 60 - 1, alarm = false,
                        repeatEnd = if (e.repeatEnd >= 0) e.repeatEnd + k else -1,
                        weekDays = if (e.repeat == Repeat.WEEK) Recur.rotateWeekDays(mask, k) else e.weekDays,
                        exceptions = e.exceptions.mapTo(mutableSetOf()) { it + k }
                    )
                )
            }
            if (span > 30 || (e.repeats && e.repeat != Repeat.DAY && e.repeat != Repeat.WEEK)) simplified = true
        }
        return Item(uid, rid, events, simplified)
    }

    /** The parts of an RRULE value, by upper-case name, with upper-case values. */
    private fun ruleMap(value: String): Map<String, String> = value.trim().split(';').mapNotNull { part ->
        val i = part.indexOf('=')
        if (i > 0) part.substring(0, i).uppercase() to part.substring(i + 1).uppercase() else null
    }.toMap()

    /** The first day of a week of rule [r] (0 = Sunday): its WKST, else Monday, as RFC 5545 has it. */
    private fun weekStart(r: Map<String, String>): Int = dayCodes.indexOf(r["WKST"]).takeIf { it >= 0 } ?: 1

    /**
     * True when Date Book's weeks, which start on Sunday, group the days of [mask] as weeks that start on day
     * [weekStart] do, after the days move [shift] days later.
     */
    private fun sameWeeks(mask: Int, weekStart: Int, shift: Long): Boolean {
        var week: Long? = null
        for (d in 0..6) {
            if ((mask and (1 shl d)) == 0) continue
            val w = Math.floorDiv(weekStart + Math.floorMod(d - weekStart, 7) + shift, 7L)
            if (week != null && week != w) return false
            week = w
        }
        return true
    }

    /**
     * True when zones [a] and [b] keep the offset between them that they have at [from] until [to]. Date Book
     * keeps one wall time for a series, so a series from a zone whose offset to the device zone changes moves.
     */
    private fun keepsOffset(a: ZoneId, b: ZoneId, from: Instant, to: Instant): Boolean {
        fun diff(t: Instant) = a.rules.getOffset(t).totalSeconds - b.rules.getOffset(t).totalSeconds
        val d0 = diff(from)
        var t = from
        while (true) {
            val next = listOfNotNull(a.rules.nextTransition(t)?.instant, b.rules.nextTransition(t)?.instant).minOrNull()
                ?: return true
            if (!next.isBefore(to)) return true
            if (diff(next) != d0) return false
            t = next
        }
    }

    /**
     * Sets the repeat of [e] from the RRULE parts [r]. The rule reads against [src], the DTSTART date as the file
     * writes it; [e].date is the start date in the device zone, which a UTC or TZID start can move by a day, and
     * [startTime] the start time there. True when Date Book shows the rule as the file means it; false when it
     * shows it with a change, or cannot show it: [e] then stays a one-time event.
     */
    private fun setRule(e: Event, r: Map<String, String>, zone: ZoneId, src: LocalDate, startTime: LocalTime): Boolean {
        if (r.keys.any { it !in ruleParts }) return false
        val s = src
        val local = LocalDate.ofEpochDay(e.date)
        val delta = e.date - s.toEpochDay()
        val freq = r["FREQ"]
        val every = r["INTERVAL"]?.toIntOrNull() ?: 1
        if (every < 1) return false
        val days = r["BYDAY"]?.split(',')?.map { it.removePrefix("+") }
        // Bit 8 marks a day with a number before it, such as "2MO".
        val mask = days?.fold(0) { m, d -> m or dayCodes.indexOf(d).let { if (it < 0) 0x100 else 1 shl it } }
        val monthDay = r["BYMONTHDAY"]
        if (monthDay != null && (freq != "MONTHLY" && freq != "YEARLY" || monthDay != "${s.dayOfMonth}")) return false
        val month = r["BYMONTH"]
        if (month != null && (freq != "YEARLY" || month != "${s.monthValue}")) return false
        val atSrc = e.copy(date = s.toEpochDay())
        var weekDays = 0
        val repeat = when (freq) {
            "DAILY" -> when {
                mask == null -> Repeat.DAY
                mask < 0x100 && every == 1 -> { weekDays = mask; Repeat.WEEK }
                else -> return false
            }
            "WEEKLY" -> when {
                mask == null -> Repeat.WEEK
                mask < 0x100 -> { weekDays = mask; Repeat.WEEK }
                else -> return false
            }
            "MONTHLY" -> when {
                days == null -> Repeat.MONTH_DATE
                monthDay == null && days.size == 1 && days[0] == weekCode(atSrc.copy(weekDays = 0)) -> Repeat.MONTH_DAY
                // "-1SU" from a first date in both the 4th and the last week: the last week.
                monthDay == null && days.size == 1 && days[0] == weekCode(atSrc.copy(weekDays = Repeat.LAST_WEEK)) -> {
                    weekDays = Repeat.LAST_WEEK; Repeat.MONTH_DAY
                }
                else -> return false
            }
            "YEARLY" -> if (days == null) Repeat.YEAR else return false
            else -> return false
        }
        var exact = true
        val t = e.copy(repeat = repeat, every = every, weekDays = weekDays, repeatEnd = -1)
        if (delta != 0L) {
            // The local date is a day off the file's: the week days move with it, and a month or year day
            // holds only when both days are in every month.
            val sameMonth = local.monthValue == s.monthValue
            val last = maxOf(s.dayOfMonth, local.dayOfMonth)
            when (repeat) {
                Repeat.WEEK -> if (weekDays != 0) t.weekDays = Recur.rotateWeekDays(weekDays, delta)
                Repeat.MONTH_DATE -> if (!sameMonth || last > 28) exact = false
                Repeat.MONTH_DAY -> exact = false
                Repeat.YEAR -> if (!sameMonth || (s.monthValue == 2 && last >= 29)) exact = false
            }
        }
        // RFC 5545 counts DTSTART as an occurrence even off the BYDAY days. Date Book cannot show that start
        // with the series, so the series starts on its first day on the rule.
        val offStart = repeat == Repeat.WEEK && weekDays != 0 && (weekDays and (1 shl Recur.dow(s))) == 0
        if (offStart) t.date = Recur.firstWeekDay(s.toEpochDay(), weekDays, every, weekStart(r)) + delta
        var startOnly = false
        r["UNTIL"]?.let { u ->
            val until = time(emptyMap(), u, zone) ?: return false
            // A DATE-TIME UNTIL is the last start that occurs, so a later start time ends the day before.
            val late = !isDate(emptyMap(), u) && startTime > until.toLocalTime()
            t.repeatEnd = until.toLocalDate().toEpochDay() - (if (late) 1 else 0)
        }
        val count = r["COUNT"]
        if (count != null) {
            val n = (count.toIntOrNull()?.takeIf { it > 0 } ?: return false) - (if (offStart) 1 else 0)
            if (n == 0) startOnly = true else t.repeatEnd = countEnd(t, n)
        }
        if (offStart) {
            // No occurrence on the rule: the start alone, a one-time event.
            if (startOnly || (t.repeatEnd >= 0 && t.repeatEnd < t.date)) return true
            exact = false
        }
        e.date = t.date
        e.repeat = t.repeat
        e.every = t.every
        e.weekDays = t.weekDays
        e.repeatEnd = t.repeatEnd
        return exact
    }

    /** The day of occurrence number [count] of [e]. Shortcut: a scan day by day, which stops after 100 years. */
    private fun countEnd(e: Event, count: Int): Long {
        var left = count
        val stop = e.date + 36525
        var d = e.date
        while (d < stop) {
            if (Recur.occursOn(e, d) && --left == 0) return d
            d++
        }
        return stop
    }

    private fun isDate(params: Map<String, String>, v: String) =
        params["VALUE"].equals("DATE", true) || v.trim().length == 8

    /** A DATE or DATE-TIME value as the file writes it, with no zone change, or null when it does not parse. */
    private fun wall(params: Map<String, String>, value: String): LocalDateTime? = try {
        val v = value.trim()
        when {
            isDate(params, v) -> LocalDate.parse(v.take(8), dayFmt).atStartOfDay()
            v.endsWith("Z") -> LocalDateTime.parse(v.dropLast(1), timeFmt)
            else -> LocalDateTime.parse(v, timeFmt)
        }
    } catch (ex: Exception) {
        null
    }

    /**
     * The zone of a DATE-TIME value: UTC for a "Z" time, else its TZID. Null for a DATE and a floating time.
     * Shortcut: a TZID that is not an IANA zone name (Outlook writes Windows names) reads as local time.
     * Upgrade path: read the offsets from the VTIMEZONE of the file.
     */
    private fun sourceZone(params: Map<String, String>, value: String): ZoneId? {
        val v = value.trim()
        return when {
            isDate(params, v) -> null
            v.endsWith("Z") -> ZoneOffset.UTC
            else -> params["TZID"]?.let { runCatching { ZoneId.of(it) }.getOrNull() }
        }
    }

    /** A DATE or DATE-TIME value as a local time in [zone], or null when it does not parse. */
    private fun time(params: Map<String, String>, value: String, zone: ZoneId): LocalDateTime? {
        val t = wall(params, value) ?: return null
        val src = sourceZone(params, value) ?: return t
        return try {
            t.atZone(src).withZoneSameInstant(zone).toLocalDateTime()
        } catch (ex: Exception) {
            null
        }
    }

    /** Signed minutes of a DURATION match, or null when they do not fit in a Long. */
    private fun minutes(m: MatchResult): Long? {
        val g = m.groupValues
        fun n(i: Int) = g[i].toLongOrNull() ?: 0
        val v = try {
            Math.addExact(
                Math.addExact(Math.multiplyExact(n(2), 10080L), Math.multiplyExact(n(3), 1440L)),
                Math.addExact(Math.addExact(Math.multiplyExact(n(4), 60L), n(5)), n(6) / 60)
            )
        } catch (ex: ArithmeticException) {
            return null
        }
        return if (g[1] == "-") -v else v
    }

    /** A TRIGGER value as Date Book (advance, unit), in the unit of the file. */
    private fun advance(value: String): Pair<Int, Int> {
        val m = durRe.matchEntire(value.trim()) ?: return 0 to 0
        val before = -(minutes(m) ?: return 0 to 0)
        if (before <= 0) return 0 to 0
        val g = m.groupValues
        val per = longArrayOf(1, 60, 1440)
        var unit = when {
            g[4].isEmpty() && g[5].isEmpty() && g[6].isEmpty() -> 2
            g[5].isEmpty() && g[6].isEmpty() -> 1
            else -> 0
        }
        return (before / per[unit]).toInt() to unit
    }

    /**
     * The lines of [bytes] as bytes, split at LF with the CR before it dropped, after a UTF-8 BOM. Folds are
     * joined as bytes, since a fold can split a UTF-8 sequence.
     */
    internal fun lines(bytes: ByteArray): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        var from = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
        while (from <= bytes.size) {
            var nl = from
            while (nl < bytes.size && bytes[nl] != '\n'.code.toByte()) nl++
            var stop = nl
            if (stop > from && bytes[stop - 1] == '\r'.code.toByte()) stop--
            out.add(bytes.copyOfRange(from, stop))
            from = nl + 1
        }
        return out
    }

    /** True when line [l] starts with a space or tab, so it continues the line before it. */
    internal fun continues(l: ByteArray) = l.isNotEmpty() && (l[0] == ' '.code.toByte() || l[0] == '\t'.code.toByte())

    /** The content lines of [bytes] with each fold joined, read as UTF-8. A blank line is dropped. */
    private fun unfold(bytes: ByteArray): List<String> {
        val out = mutableListOf<java.io.ByteArrayOutputStream>()
        for (l in lines(bytes)) {
            if (continues(l) && out.isNotEmpty()) out.last().write(l, 1, l.size - 1)
            else if (String(l, Charsets.UTF_8).isNotBlank()) out.add(java.io.ByteArrayOutputStream().apply { write(l) })
        }
        return out.map { it.toString(Charsets.UTF_8.name()) }
    }

    private fun prop(line: String): Prop? {
        var quoted = false
        var colon = -1
        for (i in line.indices) {
            if (line[i] == '"') quoted = !quoted
            else if (line[i] == ':' && !quoted) { colon = i; break }
        }
        if (colon < 0) return null
        val head = line.substring(0, colon).split(';')
        val params = head.drop(1).mapNotNull { p ->
            val i = p.indexOf('=')
            if (i > 0) p.substring(0, i).uppercase() to p.substring(i + 1).trim('"') else null
        }.toMap()
        return Prop(head[0].substringAfterLast('.').uppercase(), params, line.substring(colon + 1))
    }

    internal fun unescape(s: String): String {
        val b = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                val n = s[i + 1]
                b.append(if (n == 'n' || n == 'N') '\n' else n)
                i += 2
            } else {
                b.append(c)
                i++
            }
        }
        return b.toString()
    }
}
