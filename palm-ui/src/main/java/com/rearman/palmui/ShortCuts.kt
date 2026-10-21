package com.rearman.palmui

import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.widget.EditText
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A Palm ShortCut: the name typed after the trigger, and the text it gives. */
data class ShortCut(val name: String, val text: String)

object ShortCuts {
	const val TRIGGER = '^'
	const val DATE = "{date}"
	const val TIME = "{time}"
	/** The Palm OS 3.5 default list. */
	val defaults: List<ShortCut> = listOf(
		ShortCut("br", "Breakfast"), ShortCut("di", "Dinner"), ShortCut("ds", DATE),
		ShortCut("dts", "$DATE $TIME"), ShortCut("lu", "Lunch"), ShortCut("me", "Meeting"), ShortCut("ts", TIME),
	)
	/** The active list. Empty (no expansion) until an app sets it. */
	var list: List<ShortCut> = emptyList()
	var date: () -> String = { LocalDate.now().format(DateTimeFormatter.ofPattern("M/d/yy", Locale.US)) }
	var time: () -> String = {
		LocalTime.now().format(DateTimeFormatter.ofPattern("h:mm a", Locale.US)).lowercase(Locale.US)
	}

	/**
	 * The ShortCut that ends at [cursor]: [text] has TRIGGER at index t, then the exact name of a
	 * ShortCut in [list] from t+1 to [cursor]. Returns t and the ShortCut, or null.
	 * Looks back as far as the longest name in [list], plus TRIGGER. The name has no whitespace and no TRIGGER.
	 */
	fun find(text: CharSequence, cursor: Int, list: List<ShortCut> = this.list): Pair<Int, ShortCut>? {
		if (cursor < 1 || cursor > text.length) return null
		val longest = list.maxOfOrNull { it.name.length } ?: return null
		for (i in cursor - 1 downTo maxOf(0, cursor - longest - 1)) {
			val c = text[i]
			if (c.isWhitespace()) return null
			if (c == TRIGGER) {
				val name = text.substring(i + 1, cursor)
				if (name.isEmpty()) return null
				return list.firstOrNull { it.name == name }?.let { i to it }
			}
		}
		return null
	}

	/** [text] with each DATE and TIME changed to date() and time(). */
	fun expand(text: String): String = text.replace(DATE, date()).replace(TIME, time())
}

/**
 * Palm ShortCut entry for this field: TRIGGER and a ShortCut name change to the ShortCut text.
 * Ctrl+Space puts TRIGGER at the cursor. Does nothing while ShortCuts.list is empty.
 * Limit: the expansion runs only when the typed character ends the name. A name pasted in
 * one step is not expanded. The list is scanned in order, so a long list is slow to search.
 */
fun EditText.palmShortCuts() {
	var inserted: Int? = null // end of the text the last edit inserted
	var busy = false // true while this watcher changes the text
	addTextChangedListener(object : TextWatcher {
		override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}

		override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
			inserted = if (count > 0) start + count else null
		}

		override fun afterTextChanged(e: Editable) {
			val end = inserted ?: return
			inserted = null
			if (ShortCuts.list.isEmpty() || busy || end != selectionEnd) return
			val (t, sc) = ShortCuts.find(e, end) ?: return
			busy = true
			try {
				e.replace(t, end, ShortCuts.expand(sc.text))
			} finally {
				busy = false
			}
		}
	})
	setOnKeyListener { _, keyCode, event ->
		if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_SPACE &&
			event.isCtrlPressed && ShortCuts.list.isNotEmpty()
		) {
			text.replace(selectionStart, selectionEnd, ShortCuts.TRIGGER.toString())
			true
		} else {
			false
		}
	}
}
