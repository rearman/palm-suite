package com.rearman.palmdesktop

import com.rearman.datebook.Event
import com.rearman.datebook.Fmt
import com.rearman.datebook.Recur
import com.rearman.datebook.Repeat
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/*
 * The summary text of the Palm Desktop 3.1.1 "Change Repeat" dialog.
 *
 * Measured from the official app:
 *	every 1 day "Every day", every 2 days "Every other day"
 *	weekly, Tuesday, every 1 "Every Tuesday", every 2 "Every other Tuesday"
 *	weekly, Mon and Tue, every 3 "Every 3rd week on Mon, and Tue"
 *	monthly by date, the 20th, every 1 "The 20th of every month", every 3 "The 20th of every 3rd month"
 *	yearly, October 20 "October 20 every year"
 *	no repeat ""
 *
 * Forms not measured follow that pattern: the frequency word is empty for 1, "other" for 2 and
 * the ordinal for 3 and up. Monthly by day takes its week number and "last" rule from Recur.describe.
 * Android keeps its own wording in Recur.describe (sync-core, Palm OS handheld).
 */
internal fun describeDesktop(e: Event): String {
	if (e.repeat == Repeat.NONE) return ""
	val s = LocalDate.ofEpochDay(e.date)
	val n = maxOf(1, e.every)
	val freq = when (n) {
		1 -> ""
		2 -> "other"
		else -> Fmt.ordinal(n)
	}
	val base = when (e.repeat) {
		Repeat.DAY -> words("Every", freq, "day")
		Repeat.WEEK -> {
			val mask = if (e.weekDays == 0) (1 shl Recur.dow(s)) else e.weekDays
			val days = (0..6).filter { (mask and (1 shl it)) != 0 }
			if (days.size == 1) {
				words("Every", freq, Fmt.dowLong[days[0]])
			} else {
				words("Every", freq, "week on " + listText(days.map { Fmt.dowShort[it] }))
			}
		}
		Repeat.MONTH_DAY -> {
			val w = Recur.weekIndex(s)
			val weekWord = if (w == 5) "last" else Fmt.ordinal(w)
			"The $weekWord ${Fmt.dowLong[Recur.dow(s)]} of " + words("every", freq, "month")
		}
		Repeat.MONTH_DATE -> "The ${Fmt.ordinal(s.dayOfMonth)} of " + words("every", freq, "month")
		Repeat.YEAR -> {
			val month = s.month.getDisplayName(TextStyle.FULL, Locale.US)
			"$month ${s.dayOfMonth} " + words("every", freq, "year")
		}
		else -> ""
	}
	return if (e.repeatEnd >= 0) "$base until ${Fmt.shortDate(e.repeatEnd)}" else base
}

/** Joins the words that are not empty, with one space between them. */
private fun words(vararg parts: String): String = parts.filter { it.isNotEmpty() }.joinToString(" ")

/** "Mon, and Tue" for two names, "Mon, Wed, and Fri" for three. Names must not be empty. */
private fun listText(names: List<String>): String =
	if (names.size == 1) names[0] else names.dropLast(1).joinToString(", ") + ", and " + names.last()
