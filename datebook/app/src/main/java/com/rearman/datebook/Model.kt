package com.rearman.datebook

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Repeat types, as in the Palm "Change Repeat" dialog. */
object Repeat {
    const val NONE = 0
    const val DAY = 1
    const val WEEK = 2
    const val MONTH_DAY = 3   // e.g. "3rd Monday of every month"
    const val MONTH_DATE = 4  // e.g. "the 12th of every month"
    const val YEAR = 5
}

/** One Date Book record. A repeating record holds all of its occurrences. */
data class Event(
    var id: Long = 0,
    var title: String = "",
    var note: String = "",
    var date: Long = LocalDate.now().toEpochDay(), // first occurrence (epoch day)
    var untimed: Boolean = false,
    var start: Int = 8 * 60,                       // minutes after midnight
    var end: Int = 9 * 60,
    var alarm: Boolean = false,
    var alarmAdvance: Int = 5,
    var alarmUnit: Int = 0,                        // 0 minutes, 1 hours, 2 days
    var repeat: Int = Repeat.NONE,
    var every: Int = 1,
    var repeatEnd: Long = -1,                      // epoch day, -1 = no end date
    var weekDays: Int = 0,                         // bit 0 = Sunday ... bit 6 = Saturday
    var exceptions: MutableSet<Long> = mutableSetOf(),
    var isPrivate: Boolean = false
) {
    val repeats: Boolean get() = repeat != Repeat.NONE

    fun deepCopy(): Event = copy(exceptions = exceptions.toMutableSet())

    fun advanceMinutes(): Long = when (alarmUnit) {
        0 -> alarmAdvance.toLong()
        1 -> alarmAdvance * 60L
        else -> alarmAdvance * 1440L
    }
}

/** One To Do List record. */
data class Todo(
    var id: Long = 0,
    var title: String = "",
    var note: String = "",
    var priority: Int = 1,
    var due: Long = -1,          // epoch day, -1 = no date
    var done: Boolean = false,
    var category: Int = 0,       // 0 = Unfiled
    var isPrivate: Boolean = false
)

/** A category of one app: [app] 0 is To Do, 1 is Memo Pad. */
data class Category(val id: Int, var name: String, val app: Int = 0)

/** A Memo Pad record. Its first line is its title in the Memo List. */
data class Memo(
    var id: Long = 0,
    var text: String = "",
    var category: Int = 0,
    var isPrivate: Boolean = false,
    var pos: Int = 0
) {
    val title: String get() = text.lineSequence().firstOrNull()?.trim().orEmpty()
}

/** Memo List order: by first line (ignoring case) when [alphabetic], else by manual position. */
fun memoOrder(memos: List<Memo>, alphabetic: Boolean): List<Memo> =
    if (alphabetic) memos.sortedWith(compareBy<Memo>({ it.title.lowercase() }, { it.pos }, { it.id }))
    else memos.sortedWith(compareBy<Memo>({ it.pos }, { it.id }))

/**
 * Moves the memo at [from] to [to] in [ordered] (the full list in manual order) and numbers
 * the positions 0 up in the new order. Returns the memos whose position changed.
 */
fun moveMemo(ordered: List<Memo>, from: Int, to: Int): List<Memo> {
    val list = ordered.toMutableList()
    list.add(to, list.removeAt(from))
    val changed = mutableListOf<Memo>()
    list.forEachIndexed { i, m -> if (m.pos != i) { m.pos = i; changed.add(m) } }
    return changed
}

/** An event as it appears on one particular day. */
data class Occ(val event: Event, val day: Long)

/**
 * Start times of the empty Day view lines, one for each hour from [dayStart] to [dayEnd]
 * that no event overlaps. With [ends] (Show Time Bars), an hour line shows when no event
 * covers the hour mark itself, and a line also shows at each event end between hours.
 */
fun freeTimes(timed: List<Occ>, dayStart: Int, dayEnd: Int, ends: Boolean): List<Int> {
    fun busy(a: Int, b: Int) = timed.any { it.event.start < b && maxOf(it.event.end, it.event.start + 1) > a }
    val out = mutableListOf<Int>()
    for (hr in dayStart until dayEnd) {
        val a = hr * 60
        if (!busy(a, if (ends) a + 1 else a + 60)) out.add(a)
    }
    if (ends) for (o in timed) {
        val t = o.event.end
        if (t % 60 != 0 && t < 24 * 60 && !busy(t, t + 1) && t !in out) out.add(t)
    }
    return out.sorted()
}

object Recur {
    /** Day of week with Sunday = 0. */
    fun dow(d: LocalDate): Int = d.dayOfWeek.value % 7

    /** Week of the month, 1..5. */
    fun weekIndex(d: LocalDate): Int = (d.dayOfMonth - 1) / 7 + 1

    fun isLastWeek(d: LocalDate): Boolean = d.plusDays(7).monthValue != d.monthValue

    private fun monthsBetween(a: LocalDate, b: LocalDate): Long =
        ((b.year - a.year) * 12 + (b.monthValue - a.monthValue)).toLong()

    fun occursOn(e: Event, day: Long): Boolean {
        if (day < e.date) return false
        if (e.repeat == Repeat.NONE) return day == e.date
        if (e.repeatEnd >= 0 && day > e.repeatEnd) return false
        if (e.exceptions.contains(day)) return false
        val s = LocalDate.ofEpochDay(e.date)
        val d = LocalDate.ofEpochDay(day)
        val n = maxOf(1, e.every).toLong()
        return when (e.repeat) {
            Repeat.DAY -> (day - e.date) % n == 0L
            Repeat.WEEK -> {
                val mask = if (e.weekDays == 0) (1 shl dow(s)) else e.weekDays
                if ((mask and (1 shl dow(d))) == 0) {
                    false
                } else {
                    val weekOfStart = e.date - dow(s)
                    val weekOfDay = day - dow(d)
                    ((weekOfDay - weekOfStart) / 7) % n == 0L
                }
            }
            Repeat.MONTH_DATE ->
                d.dayOfMonth == s.dayOfMonth && monthsBetween(s, d) % n == 0L
            Repeat.MONTH_DAY -> {
                if (dow(d) != dow(s)) false
                else if (monthsBetween(s, d) % n != 0L) false
                else if (weekIndex(s) == 5) isLastWeek(d)
                else weekIndex(d) == weekIndex(s)
            }
            Repeat.YEAR ->
                d.monthValue == s.monthValue && d.dayOfMonth == s.dayOfMonth &&
                    (d.year - s.year) % n == 0L
            else -> false
        }
    }

    /** True when the event repeats every day (used by the Month view option). */
    fun isDaily(e: Event): Boolean =
        (e.repeat == Repeat.DAY && e.every == 1) ||
            (e.repeat == Repeat.WEEK && e.every == 1 && e.weekDays == 0x7F)

    /** The last day the event can occur, or Long.MAX_VALUE if it has no end. */
    fun lastDay(e: Event): Long = when {
        e.repeat == Repeat.NONE -> e.date
        e.repeatEnd >= 0 -> e.repeatEnd
        else -> Long.MAX_VALUE
    }

    private val ordinal = arrayOf("", "1st", "2nd", "3rd", "4th", "last")

    fun describe(e: Event): String {
        val s = LocalDate.ofEpochDay(e.date)
        val n = maxOf(1, e.every)
        val base = when (e.repeat) {
            Repeat.NONE -> return "None"
            Repeat.DAY -> if (n == 1) "Every day" else "Every $n days"
            Repeat.WEEK -> {
                val mask = if (e.weekDays == 0) (1 shl dow(s)) else e.weekDays
                val names = (0..6).filter { (mask and (1 shl it)) != 0 }
                    .joinToString(", ") { Fmt.dowShort[it] }
                (if (n == 1) "Every week on " else "Every $n weeks on ") + names
            }
            Repeat.MONTH_DAY -> {
                val w = ordinal[weekIndex(s)]
                "The $w ${Fmt.dowLong[dow(s)]} of " +
                    (if (n == 1) "every month" else "every $n months")
            }
            Repeat.MONTH_DATE ->
                "The ${Fmt.ordinal(s.dayOfMonth)} of " +
                    (if (n == 1) "every month" else "every $n months")
            Repeat.YEAR ->
                (if (n == 1) "Every year on " else "Every $n years on ") +
                    s.format(DateTimeFormatter.ofPattern("MMM d", Locale.US))
            else -> ""
        }
        return if (e.repeatEnd >= 0) "$base until ${Fmt.shortDate(e.repeatEnd)}" else base
    }

    fun shortName(e: Event): String = when (e.repeat) {
        Repeat.NONE -> "None"
        Repeat.DAY -> "Daily"
        Repeat.WEEK -> "Weekly"
        Repeat.MONTH_DAY, Repeat.MONTH_DATE -> "Monthly"
        Repeat.YEAR -> "Yearly"
        else -> "None"
    }
}

object Fmt {
    var h24 = false

    val dowShort = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    val dowLong = arrayOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
    val dowLetter = arrayOf("S", "M", "T", "W", "T", "F", "S")
    val monthShort = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    fun ordinal(n: Int): String {
        val suf = if (n in 11..13) "th" else when (n % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
        return "$n$suf"
    }

    fun ld(day: Long): LocalDate = LocalDate.ofEpochDay(day)

    /** Day view title, "YY MMM D": "26 Oct 13", "26 Oct 5". */
    fun dayTitle(day: Long): String {
        val d = ld(day)
        return "${"%02d".format(d.year % 100)} ${monthShort[d.monthValue - 1]} ${d.dayOfMonth}"
    }

    /** "10/12/26". */
    fun shortDate(day: Long): String {
        val d = ld(day)
        return "${d.monthValue}/${d.dayOfMonth}/${"%02d".format(d.year % 100)}"
    }

    /** "Mon 10/12/26". */
    fun dowDate(day: Long): String = "${dowShort[Recur.dow(ld(day))]} ${shortDate(day)}"

    /** Time with am/pm (or 24 h): "8:00 am". */
    fun time(min: Int): String {
        val h = (min / 60) % 24
        val m = min % 60
        if (h24) return "%d:%02d".format(h, m)
        val h12 = if (h % 12 == 0) 12 else h % 12
        return "%d:%02d %s".format(h12, m, if (h < 12) "am" else "pm")
    }

    /** Short time for the Day view time column: "8:00". */
    fun timeShort(min: Int): String {
        val h = (min / 60) % 24
        val m = min % 60
        if (h24) return "%d:%02d".format(h, m)
        val h12 = if (h % 12 == 0) 12 else h % 12
        return "%d:%02d".format(h12, m)
    }

    fun range(e: Event): String =
        if (e.untimed) "No time" else "${time(e.start)} - ${time(e.end)}"
}

/**
 * Splits a repeating series at [occDay] for "this & all Future". Returns the shortened original,
 * or null when [occDay] is the first occurrence, so the original is deleted. Also returns the new
 * series that starts at [work]. Its exceptions at or after [occDay] move with it.
 */
fun splitFuture(orig: Event, occDay: Long, work: Event): Pair<Event?, Event> {
    val shortened: Event? = if (occDay <= orig.date) null else orig.deepCopy().apply {
        repeatEnd = occDay - 1
        exceptions.removeAll { it >= occDay }
    }
    val shift = work.date - occDay
    val series = work.deepCopy().apply {
        id = 0
        exceptions = work.exceptions.filter { it >= occDay }.map { it + shift }.toMutableSet()
    }
    return shortened to series
}

/** One menu command: its name, its Palm command letter (null for none) and its action. */
class MenuItem(val name: String, val key: Char? = null, val action: () -> Unit)

/** The first command in these menus with the command letter [key], in any case, or null. */
fun List<Pair<String, List<MenuItem>>>.command(key: Char): MenuItem? =
    asSequence().flatMap { it.second.asSequence() }.firstOrNull { it.key?.equals(key, ignoreCase = true) == true }
