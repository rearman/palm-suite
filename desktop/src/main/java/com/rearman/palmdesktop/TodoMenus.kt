package com.rearman.palmdesktop

import com.rearman.datebook.Todo
import java.awt.Component
import java.time.LocalDate
import javax.swing.JMenuItem
import javax.swing.JPopupMenu

/** The Due menu choices of a To Do row, in menu order. */
internal val DUE_NAMES = arrayOf("Today", "Tomorrow", "One week later", "No date", "Choose date...")

/**
 * The due date that Due menu row [pick] gives: 0 today, 1 tomorrow, 2 a week later, 3 no date (-1). Row 4 asks a
 * day in the picker, which opens on [current] or else today. Null when the picker is cancelled.
 */
internal fun dueFor(owner: Component, pick: Int, current: Long): Long? {
	val today = LocalDate.now().toEpochDay()
	return when (pick) {
		0 -> today
		1 -> today + 1
		2 -> today + 7
		3 -> -1L
		else -> DatePicker.pick(owner, "day", if (current >= 0) current else today)
	}
}

/** The popup of a To Do priority: 1 to 5. A pick sets the priority of [t] and calls [save] with it. */
internal fun priorityPopup(t: Todo, save: (Todo) -> Unit): JPopupMenu = JPopupMenu().apply {
	for (n in 1..5) {
		add(JMenuItem("$n").apply {
			font = Theme.font
			addActionListener {
				t.priority = n
				save(t)
			}
		})
	}
}

/**
 * The popup of a To Do due date, one item for each of [DUE_NAMES]. A pick runs [dueFor] from [owner]. When it gives
 * a date, the due date of [t] is set and [save] is called with it.
 */
internal fun duePopup(owner: Component, t: Todo, save: (Todo) -> Unit): JPopupMenu = JPopupMenu().apply {
	DUE_NAMES.forEachIndexed { i, name ->
		add(JMenuItem(name).apply {
			font = Theme.font
			addActionListener {
				val due = dueFor(owner, i, t.due) ?: return@addActionListener
				t.due = due
				save(t)
			}
		})
	}
}
