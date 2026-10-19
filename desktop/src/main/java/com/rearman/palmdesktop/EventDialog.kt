package com.rearman.palmdesktop

import com.rearman.datebook.Event
import com.rearman.datebook.Fmt
import com.rearman.datebook.Recur
import com.rearman.datebook.Repeat
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dialog
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.GridLayout
import java.awt.Insets
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyEvent
import java.time.DateTimeException
import java.time.LocalDate
import java.util.Calendar
import java.util.Date
import javax.swing.*

/** Edit Event dialog. A new event starts at [startMin], or at 8:00 am. */
object EventDialog {
	/** Opens Edit Event for [e], or for a new event on [day] when [e] is null. */
	fun edit(owner: Component, e: Event?, day: Long, startMin: Int? = null) {
		val start = startMin ?: 8 * 60
		val work = e?.deepCopy() ?: Event(date = day, start = start, end = start + 60, untimed = false)
		EventForm(owner, e, work, day).show()
	}
}

/** Change Repeat dialog. Writes the repeat fields to [e] on OK. */
object RepeatDialog {
	/** Returns true when the user pressed OK. */
	fun edit(owner: Component, e: Event): Boolean = RepeatForm(owner, e).show()
}

/** Note editor with word wrap. Returns the new text on OK, or null on Cancel. */
object NoteDialog {
	fun edit(owner: Component, title: String, text: String): String? {
		val dlg = modalDialog(owner, title)
		var result: String? = null
		val area = JTextArea(text, 10, 32).themed().apply { lineWrap = true; wrapStyleWord = true }
		val ok = JButton("OK").themed().apply { addActionListener { result = area.text; dlg.dispose() } }
		val cancel = JButton("Cancel").themed().apply { addActionListener { dlg.dispose() } }
		dlg.isResizable = true
		dlg.contentPane.add(JScrollPane(area), BorderLayout.CENTER)
		dlg.contentPane.add(buttonRow(ok, cancel), BorderLayout.SOUTH)
		dlg.rootPane.defaultButton = ok
		dlg.pack()
		dlg.setLocationRelativeTo(owner)
		dlg.isVisible = true
		return result
	}
}

/** The Edit Event form. [orig] is null for a new event. [work] is the copy the form edits. */
private class EventForm(
	private val owner: Component,
	private val orig: Event?,
	private val work: Event,
	private val occDay: Long
) {
	private val dlg = modalDialog(owner, "Edit Event")
	private val desc = JTextArea(work.title, 3, 28).themed().apply { lineWrap = true; wrapStyleWord = true }
	private val startField = readOnlyField()
	private val endField = readOnlyField()
	private val dateField = JTextField(Fmt.shortDate(occDay), 8).themed()
	private val dayName = JLabel(weekdayName(occDay)).themed()
	private val noteField = readOnlyField().apply { text = firstLine(work.note) }
	private val repeatBtn = JButton(repeatText()).themed()
	private val alarmBox = JCheckBox("Alarm", work.alarm).themed()
	private val alarmAmount = JSpinner(SpinnerNumberModel(work.alarmAdvance.coerceIn(0, 99), 0, 99, 1)).themed()
	private val alarmUnit = JComboBox(arrayOf("Minutes", "Hours", "Days")).themed()
		.apply { selectedIndex = work.alarmUnit.coerceIn(0, 2) }
	private val alarmLine = line(alarmAmount, alarmUnit).apply { isVisible = work.alarm }
	private val privateBox = JCheckBox("Private", work.isPrivate).themed()

	init {
		showTime()
		dateField.addFocusListener(object : FocusAdapter() {
			override fun focusLost(ev: FocusEvent) {
				parseShortDate(dateField.text)?.let { dayName.text = weekdayName(it) }
			}
		})
		alarmBox.addActionListener { alarmLine.isVisible = alarmBox.isSelected; dlg.pack() }
		buildLayout()
	}

	fun show() {
		dlg.isVisible = true
	}

	private fun buildLayout() {
		val clockBtn = JButton(Icons.clock()).apply { toolTipText = "Set time"; addActionListener { pickTime() } }
		val calBtn = JButton(Icons.calendar()).apply { toolTipText = "Choose date"; addActionListener { pickCalendarDate() } }
		val noteBtn = JButton(Icons.note()).apply { toolTipText = "Edit note"; addActionListener { pickNote() } }
		repeatBtn.addActionListener { pickRepeat() }
		repeatBtn.mnemonic = KeyEvent.VK_R
		alarmBox.mnemonic = KeyEvent.VK_A
		privateBox.mnemonic = KeyEvent.VK_P
		val form = JPanel(GridBagLayout())
		val rows = listOf(
			FormRow("Description:", JScrollPane(desc)).key('D', desc),
			FormRow("Time:", line(startField, JLabel(" to ").themed(), endField, clockBtn)).key('T', startField),
			FormRow("Date:", line(dateField, dayName, calBtn)).key('a', dateField),
			FormRow("Note:", line(noteField, noteBtn)).key('N', noteField),
			FormRow("Repeat:", repeatBtn),
			FormRow("", line(alarmBox, alarmLine)),
			FormRow("", privateBox)
		)
		rows.forEachIndexed { i, r -> r.addTo(form, i) }

		val ok = JButton("OK").themed().apply { addActionListener { save() } }
		val cancel = JButton("Cancel").themed().apply { addActionListener { dlg.dispose() } }
		val buttons = JPanel(BorderLayout())
		orig?.let { o ->
			buttons.add(JButton("Delete").themed().apply { mnemonic = KeyEvent.VK_L; addActionListener { delete(o) } }, BorderLayout.WEST)
		}
		buttons.add(buttonRow(ok, cancel), BorderLayout.EAST)

		dlg.contentPane.add(form, BorderLayout.CENTER)
		dlg.contentPane.add(buttons, BorderLayout.SOUTH)
		dlg.rootPane.defaultButton = ok
		dlg.pack()
		dlg.setLocationRelativeTo(owner)
	}

	private fun showTime() {
		startField.text = if (work.untimed) "None" else Fmt.time(work.start)
		endField.text = if (work.untimed) "None" else Fmt.time(work.end)
	}

	private fun repeatText(): String = if (work.repeat == Repeat.NONE) "None..." else Recur.describe(work)

	/** Time picker: two spinners, OK, No Time and Cancel. */
	private fun pickTime() {
		val startSpin = timeSpinner(work.start)
		val endSpin = timeSpinner(work.end)
		val panel = JPanel(GridLayout(2, 2, 6, 4)).apply {
			add(JLabel("Start:").themed())
			add(startSpin)
			add(JLabel("End:").themed())
			add(endSpin)
		}
		val answer = JOptionPane.showOptionDialog(
			dlg, panel, "Set Time", JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE,
			null, arrayOf("OK", "No Time", "Cancel"), "OK"
		)
		when (answer) {
			0 -> {
				val s = minutesOf(startSpin.value as Date)
				val en = minutesOf(endSpin.value as Date)
				if (en < s) {
					JOptionPane.showMessageDialog(dlg, "The end time is before the start time.", "Set Time", JOptionPane.WARNING_MESSAGE)
					return
				}
				work.untimed = false
				work.start = s
				work.end = en
			}
			1 -> { work.untimed = true }
			else -> return
		}
		showTime()
	}

	private fun pickCalendarDate() {
		val d = DatePicker.pick(dlg, "day", parseShortDate(dateField.text) ?: occDay) ?: return
		dateField.text = Fmt.shortDate(d)
		dayName.text = weekdayName(d)
	}

	private fun pickNote() {
		NoteDialog.edit(dlg, "Note", work.note)?.let {
			work.note = it
			noteField.text = firstLine(it)
		}
	}

	private fun pickRepeat() {
		if (RepeatDialog.edit(dlg, work)) repeatBtn.text = repeatText()
	}

	private fun save() {
		val newDay = parseShortDate(dateField.text)
		if (newDay == null) {
			JOptionPane.showMessageDialog(dlg, "Enter a date as M/D/YY.", "Edit Event", JOptionPane.WARNING_MESSAGE)
			return
		}
		work.title = desc.text.trim()
		work.alarm = alarmBox.isSelected
		work.alarmAdvance = alarmAmount.value as Int
		work.alarmUnit = alarmUnit.selectedIndex
		work.isPrivate = privateBox.isSelected
		val o = orig
		when {
			o == null -> { work.date = newDay; App.store.save(work) }
			work == o && newDay == occDay -> {}
			!o.repeats -> { work.date = newDay; App.store.save(work) }
			else -> if (!saveSeries(o, newDay)) return
		}
		App.refresh()
		dlg.dispose()
	}

	/** Asks Current or All for a changed occurrence of a series. Returns false on Cancel. */
	private fun saveSeries(o: Event, newDay: Long): Boolean {
		when (askScope("Edit Event", "Change the current event only, or all events in the series?")) {
			0 -> {
				App.store.save(o.deepCopy().apply { exceptions.add(occDay) })
				App.store.save(work.deepCopy().apply {
					id = 0
					uid = ""
					repeat = Repeat.NONE
					date = newDay
					every = 1
					repeatEnd = -1
					weekDays = 0
					exceptions = mutableSetOf()
				})
			}
			1 -> {
				val shift = newDay - occDay
				work.date += shift
				if (work.repeatEnd >= 0) work.repeatEnd += shift
				work.exceptions = work.exceptions.mapTo(mutableSetOf<Long>()) { it + shift }
				App.store.save(work)
			}
			else -> return false
		}
		return true
	}

	private fun delete(o: Event) {
		if (o.repeats) {
			when (askScope("Delete Event", "Delete the current event only, or all events in the series?")) {
				0 -> App.store.save(o.deepCopy().apply { exceptions.add(occDay) })
				1 -> App.store.delete(o)
				else -> return
			}
		} else {
			if (!confirmDelete(dlg, "Delete the event?")) return
			App.store.delete(o)
		}
		App.refresh()
		dlg.dispose()
	}

	/** Asks Current, All or Cancel. Returns 0, 1 or 2. */
	private fun askScope(title: String, text: String): Int =
		JOptionPane.showOptionDialog(
			dlg, text, title, JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
			null, arrayOf("Current", "All", "Cancel"), "Current"
		)
}

/** The Change Repeat form. Edits a copy of the repeat fields and writes them to [e] on OK. */
private class RepeatForm(private val owner: Component, private val e: Event) {
	private val dlg = modalDialog(owner, "Change Repeat")
	private var changed = false
	private var kind = e.repeat
	private var monthBy = if (e.repeat == Repeat.MONTH_DAY) Repeat.MONTH_DAY else Repeat.MONTH_DATE
	private var weekMask = if (e.weekDays == 0) 1 shl Recur.dow(LocalDate.ofEpochDay(e.date)) else e.weekDays
	private var end = e.repeatEnd

	private val noneRadio = JRadioButton("None").themed()
	private val dailyRadio = JRadioButton("Daily").themed()
	private val weeklyRadio = JRadioButton("Weekly").themed()
	private val monthlyRadio = JRadioButton("Monthly").themed()
	private val yearlyRadio = JRadioButton("Yearly").themed()
	private val hint = JTextArea("Click one of the buttons to the left to set a repeat interval.", 3, 18)
		.themed().apply { isEditable = false; isOpaque = false; lineWrap = true; wrapStyleWord = true }
	private val everySpin = JSpinner(SpinnerNumberModel(e.every.coerceIn(1, 99), 1, 99, 1)).themed()
	private val everyUnit = JLabel("Month").themed()
	private val endField = JTextField(10).themed().apply { isEditable = false }
	private val dayToggles = Array(7) { i ->
		val btn = JToggleButton(Fmt.dowLetter[i]).themed()
		btn.isSelected = (weekMask and (1 shl i)) != 0
		btn.addActionListener {
			val bit = 1 shl i
			weekMask = if (btn.isSelected) weekMask or bit else weekMask and bit.inv()
			refresh()
		}
		btn
	}
	private val byCombo = JComboBox(arrayOf("Date", "Day")).themed().apply {
		selectedIndex = if (monthBy == Repeat.MONTH_DAY) 1 else 0
		addActionListener {
			monthBy = if (selectedIndex == 1) Repeat.MONTH_DAY else Repeat.MONTH_DATE
			if (isMonthly()) kind = monthBy
			refresh()
		}
	}
	private val summary = JTextArea(2, 24).themed().apply { isEditable = false; lineWrap = true; wrapStyleWord = true }
	private val endMenu = JPopupMenu().apply {
		add(JMenuItem("No End Date").apply { addActionListener { end = -1; refresh() } })
		add(JMenuItem("Date...").apply { addActionListener { pickEnd() } })
	}
	private val everyRow = line(everySpin, everyUnit)
	private val endRow = line(endField, JButton(Icons.calendar()).themed().apply {
		toolTipText = "Choose end date"
		addActionListener { endMenu.show(this, 0, height) }
	})
	private val weekRow = line(*dayToggles)
	private val byRow = line(byCombo)

	init {
		buildLayout()
		refresh()
	}

	fun show(): Boolean {
		dlg.isVisible = true
		return changed
	}

	private fun buildLayout() {
		noneRadio.mnemonic = KeyEvent.VK_N
		dailyRadio.mnemonic = KeyEvent.VK_D
		weeklyRadio.mnemonic = KeyEvent.VK_W
		monthlyRadio.mnemonic = KeyEvent.VK_M
		yearlyRadio.mnemonic = KeyEvent.VK_Y
		val group = ButtonGroup()
		val left = JPanel(GridLayout(0, 1, 0, 6))
		for (r in listOf(noneRadio, dailyRadio, weeklyRadio, monthlyRadio, yearlyRadio)) {
			group.add(r)
			left.add(r)
		}
		val picked = when (kind) {
			Repeat.DAY -> dailyRadio
			Repeat.WEEK -> weeklyRadio
			Repeat.MONTH_DATE, Repeat.MONTH_DAY -> monthlyRadio
			Repeat.YEAR -> yearlyRadio
			else -> noneRadio
		}
		picked.isSelected = true
		noneRadio.addActionListener { setKind(Repeat.NONE) }
		dailyRadio.addActionListener { setKind(Repeat.DAY) }
		weeklyRadio.addActionListener { setKind(Repeat.WEEK) }
		monthlyRadio.addActionListener { setKind(monthBy) }
		yearlyRadio.addActionListener { setKind(Repeat.YEAR) }
		everySpin.addChangeListener { refresh() }

		val right = JPanel(GridBagLayout())
		FormRow("", hint).addTo(right, 0)
		FormRow("Every:", everyRow).key('E', everySpin).addTo(right, 1)
		FormRow("End on:", endRow).key('O', endField).addTo(right, 2)
		FormRow("Days:", weekRow).key('s', dayToggles[0]).addTo(right, 3)
		FormRow("By:", byRow).key('B', byCombo).addTo(right, 4)

		val top = JPanel(BorderLayout(12, 0))
		top.add(left, BorderLayout.WEST)
		top.add(right, BorderLayout.CENTER)
		val bottom = JPanel(BorderLayout(0, 6))
		bottom.add(JScrollPane(summary), BorderLayout.CENTER)
		val ok = JButton("OK").themed().apply { addActionListener { save() } }
		val cancel = JButton("Cancel").themed().apply { addActionListener { dlg.dispose() } }
		bottom.add(buttonRow(ok, cancel), BorderLayout.SOUTH)
		dlg.contentPane.add(top, BorderLayout.NORTH)
		dlg.contentPane.add(bottom, BorderLayout.CENTER)
		dlg.rootPane.defaultButton = ok
		dlg.pack()
		dlg.setLocationRelativeTo(owner)
	}

	private fun isMonthly(): Boolean = kind == Repeat.MONTH_DATE || kind == Repeat.MONTH_DAY

	private fun setKind(type: Int) {
		kind = type
		refresh()
	}

	private fun pickEnd() {
		val d = DatePicker.pick(dlg, "day", if (end >= 0) end else e.date) ?: return
		if (d < e.date) {
			JOptionPane.showMessageDialog(dlg, "The end date is before the first date.", "Change Repeat", JOptionPane.WARNING_MESSAGE)
			return
		}
		end = d
		refresh()
	}

	/** The repeat fields as the form shows them now. */
	private fun preview(): Event {
		val on = kind != Repeat.NONE
		val w = e.deepCopy()
		w.repeat = kind
		w.every = everySpin.value as Int
		w.repeatEnd = if (on) end else -1
		w.weekDays = if (on) (if (weekMask == 0) 1 shl Recur.dow(LocalDate.ofEpochDay(e.date)) else weekMask) else 0
		return w
	}

	/** Shows the rows that fit the kind, and the summary of the working copy. */
	private fun refresh() {
		val on = kind != Repeat.NONE
		hint.isVisible = !on
		everyRow.isVisible = on
		endRow.isVisible = on
		weekRow.isVisible = kind == Repeat.WEEK
		byRow.isVisible = isMonthly()
		everyUnit.text = when (kind) {
			Repeat.DAY -> "Day"
			Repeat.WEEK -> "Week"
			Repeat.YEAR -> "Year"
			else -> "Month"
		}
		endField.text = if (end < 0) "No End Date" else Fmt.shortDate(end)
		summary.text = Recur.describe(preview())
		dlg.pack()
	}

	private fun save() {
		val w = preview()
		e.repeat = w.repeat
		e.every = w.every
		e.repeatEnd = w.repeatEnd
		e.weekDays = w.weekDays
		changed = true
		dlg.dispose()
	}
}

/** One label and field row of a form. */
private class FormRow(text: String, private val field: Component) {
	private val label = JLabel(text).themed()

	/** Sets the Alt key that moves focus to [target]. */
	fun key(c: Char, target: Component): FormRow = apply {
		label.setDisplayedMnemonic(c)
		label.labelFor = target
	}

	fun addTo(panel: JPanel, y: Int) {
		panel.add(label, GridBagConstraints(0, y, 1, 1, 0.0, 0.0, GridBagConstraints.NORTHEAST, GridBagConstraints.NONE, Insets(3, 6, 3, 4), 0, 0))
		panel.add(field, GridBagConstraints(1, y, 1, 1, 1.0, 0.0, GridBagConstraints.WEST, GridBagConstraints.NONE, Insets(3, 4, 3, 6), 0, 0))
	}
}

/** A modal dialog over the window that holds [owner]. Escape closes it. */
private fun modalDialog(owner: Component, title: String): JDialog =
	JDialog(SwingUtilities.getWindowAncestor(owner), title, Dialog.ModalityType.APPLICATION_MODAL).apply {
		defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
		rootPane.registerKeyboardAction(
			{ dispose() }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW
		)
	}

private fun <T : JComponent> T.themed(): T = apply { font = Theme.font }

private fun line(vararg parts: Component): JPanel =
	JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply { parts.forEach { add(it) } }

private fun buttonRow(vararg buttons: JButton): JPanel =
	JPanel(FlowLayout(FlowLayout.RIGHT, 6, 6)).apply { buttons.forEach { add(it) } }

private fun readOnlyField(): JTextField = JTextField(9).themed().apply { isEditable = false }

private fun firstLine(text: String): String = text.lineSequence().firstOrNull().orEmpty()

private fun weekdayName(day: Long): String = Fmt.dowLong[Recur.dow(LocalDate.ofEpochDay(day))]

/** Parses a date field such as "10/19/26" to an epoch day, or null. Years 70 to 99 mean 19xx. */
private fun parseShortDate(text: String): Long? {
	val m = Regex("""\s*(\d{1,2})/(\d{1,2})/(\d{2}|\d{4})\s*""").matchEntire(text) ?: return null
	val yy = m.groupValues[3].toInt()
	val year = when {
		yy >= 100 -> yy
		yy >= 70 -> 1900 + yy
		else -> 2000 + yy
	}
	return try {
		LocalDate.of(year, m.groupValues[1].toInt(), m.groupValues[2].toInt()).toEpochDay()
	} catch (ex: DateTimeException) {
		null
	}
}

/** Minutes after midnight to a Date, for a time spinner. */
private fun timeOf(min: Int): Date =
	Calendar.getInstance().apply { clear(); set(Calendar.HOUR_OF_DAY, min / 60); set(Calendar.MINUTE, min % 60) }.time

/** Time spinner value to minutes after midnight. */
private fun minutesOf(d: Date): Int {
	val c = Calendar.getInstance().apply { time = d }
	return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
}

private fun timeSpinner(min: Int): JSpinner =
	JSpinner(SpinnerDateModel(timeOf(min), null, null, Calendar.MINUTE)).themed().apply {
		editor = JSpinner.DateEditor(this, if (Fmt.h24) "HH:mm" else "h:mm a")
	}
