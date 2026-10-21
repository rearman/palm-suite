package com.rearman.palmdesktop

import com.rearman.datebook.Event
import com.rearman.datebook.Fmt
import com.rearman.datebook.Occ
import com.rearman.datebook.Recur
import com.rearman.datebook.Repeat
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dialog
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Paint
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.KeyEvent
import javax.swing.BorderFactory
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JSpinner
import javax.swing.KeyStroke
import javax.swing.SpinnerNumberModel
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

// Parts shared by the Date Book views: the time labels, the week and the event boxes, and the
// Current, All and Cancel questions for a repeating event.

/** Time of day in minutes after midnight, as "8:00a" or "13:30" (see [Fmt.h24]). */
internal fun timeLabel(min: Int): String {
	val h = min / 60 % 24
	val m = min % 60
	if (Fmt.h24) return "%d:%02d".format(h, m)
	val h12 = (h + 11) % 12 + 1
	return "%d:%02d%s".format(h12, m, if (h < 12) "a" else "p")
}

/** Date Book options from Settings. */
internal object DateSettings {
	/** Hour that the day grids scroll to. */
	val workDay: Int get() = Settings.getInt("workDay", 8).coerceIn(0, 23)

	/** First day of the week: 0 Sunday, 1 Monday. */
	val weekBegins: Int get() = Settings.getInt("weekStart", 0).coerceIn(0, 1)

	val alarmPreset: Boolean get() = Settings.getBool("alarmPreset", false)
	val alarmPresetAmount: Int get() = Settings.getInt("alarmPresetAmount", 5).coerceIn(1, 99)

	/** 0 minutes, 1 hours, 2 days. */
	val alarmPresetUnit: Int get() = Settings.getInt("alarmPresetUnit", 0).coerceIn(0, 2)
}

/** Epoch day of the first day of the week that holds [day]. */
internal fun weekStartOf(day: Long, begins: Int = DateSettings.weekBegins): Long =
	day - (Recur.dow(Fmt.ld(day)) - begins + 7) % 7

/** Index into Fmt.dowShort, dowLong and dowLetter for the grid column [col]. */
internal fun dowIndex(col: Int): Int = (col + DateSettings.weekBegins) % 7

/** Sets the alarm of a new event from the Settings preset, when it is on. */
internal fun applyAlarmPreset(e: Event) {
	if (!DateSettings.alarmPreset) return
	e.alarm = true
	e.alarmAdvance = DateSettings.alarmPresetAmount
	e.alarmUnit = DateSettings.alarmPresetUnit
}

/** Draws and hit-tests the boxes of the Date Book events. */
internal object EventBox {
	const val BAR_W = 9          // width of the gray bar on the right edge of an event
	const val SEL_H = 4          // height of the blue bar under a selected event

	private val repeatIcon: Icon by lazy { Icons.repeat() }
	val alarmIcon: Icon by lazy { Icons.alarm() }
	val noteIcon: Icon by lazy { Icons.note() }
	private val privateIcon: Icon by lazy { Icons.privateRecord() }

	/**
	 * Draws an event box as Palm Desktop does: [fill] with a black outline and a gray bar on the right edge.
	 * A [selected] box has a darker bar and a blue bar under it.
	 */
	fun draw(g: Graphics, r: Rectangle, fill: Paint, selected: Boolean) {
		val g2 = g.create() as Graphics2D
		g2.paint = fill
		g2.fillRect(r.x, r.y, r.width, r.height)
		g2.color = if (selected) Color.GRAY else Color.LIGHT_GRAY
		g2.fillRect(r.x + r.width - BAR_W, r.y, BAR_W, r.height)
		g2.color = Color.BLACK
		g2.drawRect(r.x, r.y, r.width - 1, r.height - 1)
		g2.drawLine(r.x + r.width - BAR_W, r.y, r.x + r.width - BAR_W, r.y + r.height - 1)
		if (selected) {
			g2.color = Theme.select
			g2.fillRect(r.x, r.y + r.height, r.width, SEL_H)
		}
		g2.dispose()
	}

	/** Marker icons of an event: repeat, alarm, note, private. */
	fun markers(e: Event): List<Icon> {
		val m = ArrayList<Icon>()
		if (e.repeats) m.add(repeatIcon)
		if (e.alarm) m.add(alarmIcon)
		if (e.note.isNotEmpty()) m.add(noteIcon)
		if (e.isPrivate) m.add(privateIcon)
		return m
	}

	/** Draws the box of [e] in [r], then its markers and title. [centered] is the untimed header row. */
	fun paint(c: Component, g: Graphics, r: Rectangle, e: Event, selected: Boolean, centered: Boolean) {
		draw(g, r, Theme.eventFill, selected)
		val g2 = g.create() as Graphics2D
		g2.clipRect(r.x, r.y, r.width - BAR_W, r.height)   // the title stops at the bar
		var x = r.x + 3
		for (icon in markers(e)) {
			val iy = if (centered) r.y + (r.height - icon.iconHeight) / 2 else r.y + 2
			icon.paintIcon(c, g2, x, iy)
			x += icon.iconWidth + 2
		}
		g2.font = Theme.view
		val fm = g2.fontMetrics
		val ty = if (centered) r.y + (r.height + fm.ascent - fm.descent) / 2 else r.y + fm.ascent + 1
		g2.color = Color.BLACK
		g2.drawString(e.title, x, ty)
		g2.dispose()
	}

	/** X where the title of [e] starts, after its marker icons. */
	fun titleX(r: Rectangle, e: Event): Int = markers(e).fold(r.x + 3) { x, icon -> x + icon.iconWidth + 2 }

	/** The marker icon of [e] under [p], or null. Compare the result with [alarmIcon] or [noteIcon] by identity. */
	fun markerAt(r: Rectangle, e: Event, p: Point, centered: Boolean): Icon? {
		var x = r.x + 3
		for (icon in markers(e)) {
			val iy = if (centered) r.y + (r.height - icon.iconHeight) / 2 else r.y + 2
			if (Rectangle(x, iy, icon.iconWidth, icon.iconHeight).contains(p)) return icon
			x += icon.iconWidth + 2
		}
		return null
	}

	/** True when [p] is on the gray bar at the right edge of the box [r]. */
	fun onHandle(r: Rectangle, p: Point): Boolean =
		r.contains(p) && p.x >= r.x + r.width - BAR_W

	/** True when [p] is on the blue bar under the selected box [r]. */
	fun onResizeBar(r: Rectangle, p: Point): Boolean =
		p.x in r.x until r.x + r.width && p.y in r.y + r.height until r.y + r.height + SEL_H + 2

	/** The cursor of the grid at [p]: the resize cursor on the blue bar under the selected timed box [sel], else the default. */
	fun cursorAt(sel: Rectangle?, p: Point): Cursor =
		if (sel != null && onResizeBar(sel, p)) Cursor.getPredefinedCursor(Cursor.S_RESIZE_CURSOR) else Cursor.getDefaultCursor()

	val DRAG_NAVY = Color(0, 0, 128)   // the outline of a moved or dragged event, as in Palm Desktop

	/** Draws [r] as Palm Desktop draws a drag outline: navy, 3 px thick, inside [r]. */
	fun drawOutline(g: Graphics, r: Rectangle) {
		g.color = DRAG_NAVY
		for (i in 0 until 3) g.drawRect(r.x + i, r.y + i, r.width - 1 - 2 * i, r.height - 1 - 2 * i)
	}
}

/** Asks Current or All for a repeating event. Returns 0 Current, 1 All, 2 Cancel or closed. */
internal fun askRepeatScope(owner: Component, text: String): Int {
	val answer = JOptionPane.showOptionDialog(
		owner, text, "Repeating Event", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
		null, arrayOf("Current", "All", "Cancel"), "All"
	)
	return if (answer == 0 || answer == 1) answer else 2
}

/**
 * Applies [change] to the occurrence [occ]. A repeating event asks Current or All first. Returns
 * false on Cancel.
 */
internal fun changeOccurrence(owner: Component, occ: Occ, text: String, change: (Event) -> Unit): Boolean {
	val copy = occ.event.deepCopy().apply { date = occ.day }
	change(copy)
	val newDay = copy.date
	if (!occ.event.repeats) {
		App.store.save(copy)
		App.refresh()
		return true
	}
	when (askRepeatScope(owner, text)) {
		0 -> {
			App.store.save(occ.event.deepCopy().apply { exceptions.add(occ.day) })
			App.store.save(copy.deepCopy().apply {
				id = 0
				uid = ""
				repeat = Repeat.NONE
				every = 1
				repeatEnd = -1
				weekDays = 0
				exceptions = mutableSetOf()
			})
		}
		1 -> {
			val shift = newDay - occ.day
			copy.date = occ.event.date + shift
			if (copy.repeatEnd >= 0) copy.repeatEnd += shift
			copy.exceptions = copy.exceptions.mapTo(mutableSetOf<Long>()) { it + shift }
			App.store.save(copy)
		}
		else -> return false
	}
	App.refresh()
	return true
}

/** Deletes the occurrence [occ]. A repeating event asks Current or All first. Returns false on Cancel. */
internal fun deleteOccurrence(owner: Component, occ: Occ): Boolean {
	if (occ.event.repeats) {
		when (askRepeatScope(owner, "Delete the current event only or all occurrences?")) {
			0 -> App.store.save(occ.event.deepCopy().apply { exceptions.add(occ.day) })
			1 -> App.store.delete(occ.event)
			else -> return false
		}
	} else {
		if (!confirmDelete(owner, "Delete selected event?")) return false
		App.store.delete(occ.event)
	}
	App.refresh()
	return true
}

/** Alarm Settings dialog. Writes the alarm fields of [e] on OK. Does not save. */
internal object AlarmDialog {
	/** Returns true when the user pressed OK. */
	fun edit(owner: Component, e: Event): Boolean {
		val dlg = JDialog(SwingUtilities.getWindowAncestor(owner), "Alarm Settings", Dialog.ModalityType.APPLICATION_MODAL)
		var ok = false
		val box = JCheckBox("Alarm:", e.alarm).apply { font = Theme.font; mnemonic = KeyEvent.VK_A }
		val amount = JSpinner(SpinnerNumberModel(e.alarmAdvance.coerceIn(1, 99), 1, 99, 1)).apply { font = Theme.font }
		val unit = JComboBox(arrayOf("Minutes", "Hours", "Days")).apply {
			font = Theme.font
			selectedIndex = e.alarmUnit.coerceIn(0, 2)
		}
		amount.isEnabled = box.isSelected
		unit.isEnabled = box.isSelected
		box.addActionListener {
			amount.isEnabled = box.isSelected
			unit.isEnabled = box.isSelected
		}
		val okBtn = JButton("OK").apply {
			font = Theme.font
			mnemonic = KeyEvent.VK_O
			addActionListener { ok = true; dlg.dispose() }
		}
		val cancel = JButton("Cancel").apply {
			font = Theme.font
			addActionListener { dlg.dispose() }
		}
		val row = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
			border = BorderFactory.createEmptyBorder(8, 8, 0, 8)
			add(box)
			add(amount)
			add(unit)
		}
		val buttons = JPanel(FlowLayout(FlowLayout.CENTER, 6, 6)).apply {
			add(okBtn)
			add(cancel)
		}
		dlg.contentPane.add(row, BorderLayout.CENTER)
		dlg.contentPane.add(buttons, BorderLayout.SOUTH)
		dlg.rootPane.defaultButton = okBtn
		dlg.rootPane.registerKeyboardAction(
			{ dlg.dispose() }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW
		)
		dlg.defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
		dlg.pack()
		dlg.setLocationRelativeTo(owner)
		dlg.isVisible = true
		if (!ok) return false
		e.alarm = box.isSelected
		e.alarmAdvance = amount.value as Int
		e.alarmUnit = unit.selectedIndex
		return true
	}
}

/** Opens the note of [occ] and saves a change. A click on the note icon of a box does this too. */
internal fun editNote(owner: Component, occ: Occ) {
	NoteDialog.edit(owner, "Note", occ.event.note)?.let { text ->
		changeOccurrence(owner, occ, "Change the note for the current event only or for all occurrences?") {
			it.note = text
		}
	}
}

/** Opens Alarm Settings for [occ] and saves a change. A click on the alarm icon of a box does this too. */
internal fun editAlarm(owner: Component, occ: Occ) {
	val work = occ.event.deepCopy()
	if (!AlarmDialog.edit(owner, work)) return
	changeOccurrence(owner, occ, "Change the alarm for the current event only or for all occurrences?") {
		it.alarm = work.alarm
		it.alarmAdvance = work.alarmAdvance
		it.alarmUnit = work.alarmUnit
	}
}

/** The context menu of an event box in the Date Book. */
internal fun eventMenu(owner: Component, occ: Occ): JPopupMenu {
	val menu = JPopupMenu()
	menu.add(menuItem("Delete", KeyEvent.VK_D) { deleteOccurrence(owner, occ) })
	menu.addSeparator()
	menu.add(menuItem("Note...", KeyEvent.VK_N) { editNote(owner, occ) })
	menu.add(menuItem("Alarm...", KeyEvent.VK_A) { editAlarm(owner, occ) })
	menu.addSeparator()
	menu.add(menuItem("Edit...", KeyEvent.VK_E) { EventDialog.edit(owner, occ.event, occ.day) })
	return menu
}

/** The context menu of an empty hour in the Date Book. [startMin] is null for an untimed event. */
internal fun slotMenu(owner: Component, day: Long, startMin: Int?): JPopupMenu {
	val menu = JPopupMenu()
	menu.add(menuItem("New Event", KeyEvent.VK_N) { EventDialog.edit(owner, null, day, startMin) })
	return menu
}

private fun menuItem(text: String, key: Int, action: () -> Unit): JMenuItem =
	JMenuItem(text).apply {
		font = Theme.font
		mnemonic = key
		addActionListener { action() }
	}
