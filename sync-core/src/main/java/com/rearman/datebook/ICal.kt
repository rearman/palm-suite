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

    // ---- export ----

    fun export(events: List<Event>, now: Instant = Instant.now()): String {
        val out = StringBuilder()
        fun line(s: String) = fold(s, out)
        fun at(day: LocalDate, min: Int) = timeFmt.format(day.atStartOfDay().plusMinutes(min.toLong()))
        val stamp = timeFmt.format(now.atOffset(ZoneOffset.UTC)) + "Z"
        line("BEGIN:VCALENDAR")
        line("VERSION:2.0")
        line("PRODID:-//rearman//Date Book//EN")
        for (e in events) {
            val d = LocalDate.ofEpochDay(Recur.firstDay(e))
            line("BEGIN:VEVENT")
            line("UID:${e.id}@com.rearman.datebook")
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
        if (e.repeatEnd >= 0) {
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

    fun parse(text: String, zone: ZoneId = ZoneId.systemDefault()): Parsed {
        val items = mutableListOf<Item>()
        val stack = ArrayDeque<String>()
        var props = mutableListOf<Prop>()
        var trigger: Prop? = null
        for (l in unfold(text)) {
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
        val uid = first("UID")?.value?.trim().orEmpty()
        val rid = first("RECURRENCE-ID")?.let { time(it.params, it.value, zone) }?.toLocalDate()?.toEpochDay()
        if (first("STATUS")?.value?.trim().equals("CANCELLED", true)) return Item(uid, rid, emptyList(), false)
        val end = first("DTEND")?.let { time(it.params, it.value, zone) }
            ?: first("DURATION")?.let { d -> durRe.matchEntire(d.value.trim())?.let { start.plusMinutes(minutes(it)) } }
            ?: if (untimed) start.plusDays(1) else start
        val e = Event(
            title = first("SUMMARY")?.let { unescape(it.value) }.orEmpty(),
            note = first("DESCRIPTION")?.let { unescape(it.value) }.orEmpty(),
            date = start.toLocalDate().toEpochDay(), untimed = untimed,
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
        if (rid == null && rule != null && !setRule(e, rule.value, zone)) simplified = true
        val days = end.toLocalDate().toEpochDay() - e.date
        if (untimed && days > 1) {
            // A multi-day untimed event becomes a daily repeat over its days.
            if (rule == null && rid == null) { e.repeat = Repeat.DAY; e.repeatEnd = e.date + days - 1 } else simplified = true
        }
        if (e.repeats) for (x in props) if (x.name == "EXDATE") for (v in x.value.split(',')) {
            time(x.params, v, zone)?.let { e.exceptions.add(it.toLocalDate().toEpochDay()) }
        }
        if (first("RDATE") != null) simplified = true
        val events = mutableListOf(e)
        if (span > 0) {
            // A timed event that goes past midnight becomes one event for each day it covers.
            // Shortcut: at most 31 days. Upgrade path: an end date in Event.
            val mask = if (e.weekDays == 0) 1 shl Recur.dow(start.toLocalDate()) else e.weekDays
            for (k in 1..minOf(span, 30L)) {
                // Date Book counts weeks from Sunday, so a part that moves past Saturday is off by one week.
                if (e.repeat == Repeat.WEEK && e.every > 1 && (0..6).any { (mask and (1 shl it)) != 0 && it + k > 6 }) simplified = true
                events.add(
                    e.copy(
                        date = e.date + k, start = 0, end = if (k == span) lastEnd else 24 * 60 - 1, alarm = false,
                        repeatEnd = if (e.repeatEnd >= 0) e.repeatEnd + k else -1,
                        weekDays = if (e.repeat == Repeat.WEEK) rotate(mask, k) else e.weekDays,
                        exceptions = e.exceptions.mapTo(mutableSetOf()) { it + k }
                    )
                )
            }
            if (span > 30 || (e.repeats && e.repeat != Repeat.DAY && e.repeat != Repeat.WEEK)) simplified = true
        }
        return Item(uid, rid, events, simplified)
    }

    /** The weekday bits of [mask], moved [k] days later. */
    private fun rotate(mask: Int, k: Long): Int {
        val s = (k % 7).toInt()
        return ((mask shl s) or (mask ushr (7 - s))) and 0x7F
    }

    /** Sets the repeat of [e] from an RRULE value. False when Date Book cannot show the rule: [e] then stays a one-time event. */
    private fun setRule(e: Event, value: String, zone: ZoneId): Boolean {
        val r = value.trim().split(';').mapNotNull { part ->
            val i = part.indexOf('=')
            if (i > 0) part.substring(0, i).uppercase() to part.substring(i + 1).uppercase() else null
        }.toMap()
        if (r.keys.any { it !in ruleParts }) return false
        val s = LocalDate.ofEpochDay(e.date)
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
                monthDay == null && days.size == 1 && days[0] == weekCode(e.copy(weekDays = 0)) -> Repeat.MONTH_DAY
                // "-1SU" from a first date in both the 4th and the last week: the last week.
                monthDay == null && days.size == 1 && days[0] == weekCode(e.copy(weekDays = Repeat.LAST_WEEK)) -> {
                    weekDays = Repeat.LAST_WEEK; Repeat.MONTH_DAY
                }
                else -> return false
            }
            "YEARLY" -> if (days == null) Repeat.YEAR else return false
            else -> return false
        }
        val t = e.copy(repeat = repeat, every = every, weekDays = weekDays, repeatEnd = -1)
        r["UNTIL"]?.let { u -> t.repeatEnd = time(emptyMap(), u, zone)?.toLocalDate()?.toEpochDay() ?: return false }
        r["COUNT"]?.let { c -> t.repeatEnd = countEnd(t, c.toIntOrNull()?.takeIf { it > 0 } ?: return false) }
        e.repeat = t.repeat
        e.every = t.every
        e.weekDays = t.weekDays
        e.repeatEnd = t.repeatEnd
        return true
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

    /** A DATE or DATE-TIME value as a local time in [zone], or null when it does not parse. */
    private fun time(params: Map<String, String>, value: String, zone: ZoneId): LocalDateTime? = try {
        val v = value.trim()
        when {
            isDate(params, v) -> LocalDate.parse(v.take(8), dayFmt).atStartOfDay()
            v.endsWith("Z") -> LocalDateTime.parse(v.dropLast(1), timeFmt)
                .atOffset(ZoneOffset.UTC).atZoneSameInstant(zone).toLocalDateTime()
            else -> {
                val t = LocalDateTime.parse(v, timeFmt)
                // Shortcut: a TZID that is not an IANA zone name (Outlook writes Windows names) reads as
                // local time. Upgrade path: read the offsets from the VTIMEZONE of the file.
                val tz = params["TZID"]?.let { runCatching { ZoneId.of(it) }.getOrNull() }
                if (tz == null) t else t.atZone(tz).withZoneSameInstant(zone).toLocalDateTime()
            }
        }
    } catch (ex: Exception) {
        null
    }

    /** Signed minutes of a DURATION match. */
    private fun minutes(m: MatchResult): Long {
        val g = m.groupValues
        fun n(i: Int) = g[i].toLongOrNull() ?: 0
        val v = n(2) * 10080 + n(3) * 1440 + n(4) * 60 + n(5) + n(6) / 60
        return if (g[1] == "-") -v else v
    }

    /** A TRIGGER value as Date Book (advance, unit), in the unit of the file. */
    private fun advance(value: String): Pair<Int, Int> {
        val m = durRe.matchEntire(value.trim()) ?: return 0 to 0
        val before = -minutes(m)
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

    private fun unfold(text: String): List<String> {
        val out = mutableListOf<String>()
        for (raw in text.removePrefix("\uFEFF").split('\n')) {
            val l = raw.removeSuffix("\r")
            if ((l.startsWith(' ') || l.startsWith('\t')) && out.isNotEmpty()) out[out.lastIndex] += l.substring(1)
            else if (l.isNotBlank()) out.add(l)
        }
        return out
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
