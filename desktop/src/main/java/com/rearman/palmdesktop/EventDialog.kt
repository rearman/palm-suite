package com.rearman.palmdesktop

import com.rearman.datebook.Event
import com.rearman.datebook.Fmt
import com.rearman.datebook.Recur
import com.rearman.datebook.Repeat
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dialog
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.GridLayout
import java.awt.Insets
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.time.DateTimeException
import java.time.LocalDate
import javax.swing.*
import javax.swing.plaf.basic.BasicToggleButtonUI

/** Edit Event dialog. A new event starts at [startMin], or at 8:00 am. */
object EventDialog {
	/** Opens Edit Event for [e], or for a new event on [day] when [e] is null. [title] is the description of a new event. */
	fun edit(owner: Component, e: Event?, day: Long, startMin: Int? = null, title: String = "") {
		val start = startMin ?: 8 * 60
		val work = e?.deepCopy() ?: Event(title = title, date = day, start = start, end = start + 60, untimed = startMin == null)
		if (e == null) applyAlarmPreset(work)
		EventForm(owner, e, work, day).show()
	}
}

/** Change Repeat dialog. Writes the repeat fields to [e] on OK. */
object RepeatDialog {
	/** Returns true when the user pressed OK. */
	fun edit(owner: Component, e: Event): Boolean = RepeatForm(owner, e).show()
}

/** Note Editor with word wrap and a toolbar. Returns the new text on OK, or null on Cancel. */
object NoteDialog {
	fun edit(owner: Component, title: String, text: String): String? {
		val dlg = modalDialog(owner, title)
		var result: String? = null
		val area = JTextArea(text, 10, 32).themed().apply { lineWrap = true; wrapStyleWord = true }
		val history = TextUndo.manager(area)
		val cut = iconButton(Icons.cut(), "Cut") { area.cut() }
		val copy = iconButton(Icons.copy(), "Copy") { area.copy() }
		val paste = iconButton(Icons.paste(), "Paste") { area.paste() }
		val undo = iconButton(Icons.undo(), "Undo") { if (history.canUndo()) history.undo() }
		// Grays the buttons that do not apply, as the main toolbar does. Each edit and selection change moves the caret.
		// The refresh runs after the event, so the undo history already holds the edit. Paste is checked again when
		// the dialog gets the focus back, since the clipboard can change in another window.
		fun refresh() {
			val st = TextMenu.state(area)
			cut.isEnabled = st.cut
			copy.isEnabled = st.copy
			paste.isEnabled = st.paste
			undo.isEnabled = history.canUndo()
		}
		area.addCaretListener { SwingUtilities.invokeLater { refresh() } }
		dlg.addWindowListener(object : WindowAdapter() {
			override fun windowActivated(e: WindowEvent) = refresh()
		})
		refresh()
		val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 2, 2)).apply {
			add(cut)
			add(copy)
			add(paste)
			add(undo)
		}
		val scroll = JScrollPane(area, ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER)
		val ok = formButton("OK") { result = area.text; dlg.dispose() }
		val cancel = formButton("Cancel") { dlg.dispose() }
		dlg.isResizable = true
		dlg.contentPane.add(toolbar, BorderLayout.NORTH)
		dlg.contentPane.add(scroll, BorderLayout.CENTER)
		dlg.contentPane.add(JPanel(FlowLayout(FlowLayout.CENTER, 6, 6)).apply { add(ok); add(cancel) }, BorderLayout.SOUTH)
		dlg.rootPane.defaultButton = ok
		dlg.pack()
		// The official Note Editor is 273 x 300 px. The text area takes the space, and the dialog stays resizable.
		dlg.setSize(273, 300)
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
	private val desc = JTextArea(work.title, 4, 20).themed().apply { lineWrap = true; wrapStyleWord = true }
	private val startField = JTextField(6).themed()
	private val endField = JTextField(6).themed()
	private val dateField = JTextField(fullDate(occDay), 10).themed()
	private val dayName = JLabel(weekdayName(occDay)).themed().apply {
		border = Theme.sunken
		horizontalAlignment = SwingConstants.CENTER
	}
	private val noteField = readOnlyField().apply { text = firstLine(work.note) }
	private val repeatBtn = JButton().themed()
	private val alarmBox = JCheckBox("", work.alarm).themed()
	private val alarmAmount = countSpinner(work.alarmAdvance, 1).themed()
	private val alarmUnit = JComboBox(arrayOf("Minutes before", "Hours before", "Days before")).themed()
		.apply { selectedIndex = work.alarmUnit.coerceIn(0, 2) }
	private val alarmLine = line(alarmAmount, alarmUnit).apply { isVisible = work.alarm }
	private val privateBox = JCheckBox("", work.isPrivate).themed()

	init {
		showTime(work)
		showRepeat()
		desc.caretPosition = desc.text.length   // a title typed in the Month view goes on after its first key
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
		val clockBtn = iconButton(Icons.clock(), "Set time") { pickTime() }
		val calBtn = iconButton(Icons.calendar(), "Choose date") { pickCalendarDate() }
		val noteBtn = iconButton(Icons.note(), "Edit note") { pickNote() }
		repeatBtn.addActionListener { pickRepeat() }
		val toLabel = JLabel(" to ").themed().apply { setDisplayedMnemonic('t'); labelFor = endField }
		val dayWidth = dayName.getFontMetrics(dayName.font).stringWidth("Wednesday") + 8
		dayName.preferredSize = Dimension(dayWidth, dayName.preferredSize.height)
		val form = JPanel(GridBagLayout())
		val scroll = JScrollPane(desc, ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER)
		FormRow("Description:", scroll).key('s', desc).addCompact(form, 0, wide = true)
		FormRow("Time:", line(startField, toLabel, endField)).key('i', startField).addCompact(form, 1, clockBtn)
		FormRow("Date:", line(dateField, dayName)).key('a', dateField).addCompact(form, 2, calBtn)
		FormRow("Note:", noteField).key('e', noteField).addCompact(form, 3, noteBtn)
		FormRow("Repeat:", line(repeatBtn)).key('R', repeatBtn).addCompact(form, 4)
		FormRow("Alarm:", line(alarmBox, alarmLine)).key('l', alarmBox).addCompact(form, 5)
		FormRow("Private:", privateBox).key('P', privateBox).addCompact(form, 6)

		val ok = formButton("OK") { save() }
		val cancel = formButton("Cancel") { dlg.dispose() }
		val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 6, 6)).apply {
			add(ok)
			add(cancel)
			add(Box.createRigidArea(Dimension(68, 1)))
		}

		dlg.contentPane.add(form, BorderLayout.CENTER)
		dlg.contentPane.add(buttons, BorderLayout.SOUTH)
		dlg.rootPane.defaultButton = ok
		dlg.pack()
		dlg.setLocationRelativeTo(owner)
	}

	/** Fills the time fields from [t]. Both are empty when [t] is untimed. */
	private fun showTime(t: Event) {
		startField.text = if (t.untimed) NO_TIME else Fmt.time(t.start)
		endField.text = if (t.untimed) NO_TIME else Fmt.time(t.end)
	}

	/** Sets the Repeat button text and tooltip from the type of the working copy. */
	private fun showRepeat() {
		repeatBtn.text = when (work.repeat) {
			Repeat.DAY -> "Daily..."
			Repeat.WEEK -> "Weekly..."
			Repeat.MONTH_DAY, Repeat.MONTH_DATE -> "Monthly..."
			Repeat.YEAR -> "Yearly..."
			else -> "None..."
		}
		repeatBtn.toolTipText = if (work.repeats) describeDesktop(work) else null
	}

	/** Select Time dialog, run on a copy of the time fields. The fields take the copy on OK or No Time. */
	private fun pickTime() {
		val s = parseTime(startField.text)
		val t = work.deepCopy().apply {
			untimed = s == null
			start = s ?: 8 * 60
			end = if (untimed) start + 60 else (parseTime(endField.text) ?: minOf(start + 60, 1439))
		}
		if (selectTime(dlg, t)) showTime(t)
	}

	private fun pickCalendarDate() {
		val d = DatePicker.pick(dlg, "day", parseShortDate(dateField.text) ?: occDay) ?: return
		dateField.text = fullDate(d)
		dayName.text = weekdayName(d)
	}

	private fun pickNote() {
		NoteDialog.edit(dlg, "Note Editor", work.note)?.let {
			work.note = it
			noteField.text = firstLine(it)
		}
	}

	private fun pickRepeat() {
		if (RepeatDialog.edit(dlg, work)) showRepeat()
	}

	private fun save() {
		val newDay = parseShortDate(dateField.text)
		if (newDay == null) {
			JOptionPane.showMessageDialog(dlg, "Enter a date as M/D/YY.", "Edit Event", JOptionPane.WARNING_MESSAGE)
			return
		}
		val timed = !isNoTime(startField.text)
		val s = parseTime(startField.text)
		val en = if (isNoTime(endField.text)) s?.let { minOf(it + 60, 1439) } else parseTime(endField.text)
		if (timed) {
			if (s == null || en == null) {
				JOptionPane.showMessageDialog(dlg, "Invalid time entered.", "Edit Event", JOptionPane.WARNING_MESSAGE)
				return
			}
			if (en < s) {
				JOptionPane.showMessageDialog(dlg, "The end time is before the start time.", "Edit Event", JOptionPane.WARNING_MESSAGE)
				return
			}
			work.start = s
			work.end = en
		}
		work.untimed = !timed
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
		when (askRepeatScope(dlg, "Apply the changes to the current event only or to all occurrences?")) {
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
	private val everySpin = countSpinner(e.every, 1).themed()
	private val everyLabel = repeatLabel("Every:", 'E', everySpin)
	private val everyUnit = JLabel("Months").themed()
	private val endField = JTextField(10).themed().apply { isEditable = false }
	private val endLabel = repeatLabel("End on:", 'O', endField)
	private val calBtn: JButton = iconButton(Icons.calendar(), "Choose end date", 20) { endMenu.show(calBtn, 0, calBtn.height) }
	private val dayToggles = Array(7) { i ->
		val btn = JToggleButton(Fmt.dowLetter[i]).themed().apply {
			setUI(BasicToggleButtonUI())
			isBorderPainted = false
			isContentAreaFilled = true
			isOpaque = true
			margin = Insets(0, 0, 0, 0)
		}
		btn.isSelected = (weekMask and (1 shl i)) != 0
		btn.addActionListener {
			val bit = 1 shl i
			weekMask = if (btn.isSelected) weekMask or bit else weekMask and bit.inv()
			refresh()
		}
		btn
	}
	/** The seven day cells in week order. The black background shows as the 1 px lines between the cells and around them. */
	private val weekStrip = JPanel(GridLayout(1, 7, 1, 0)).apply {
		background = Color.BLACK
		border = BorderFactory.createEmptyBorder(1, 1, 1, 1)
		for (c in 0 until 7) add(dayToggles[dowIndex(c)])
	}
	private val daysLabel = repeatLabel("Days:", 's', dayToggles[0])
	private val byCombo = JComboBox(arrayOf("Date", "Day")).themed().apply {
		selectedIndex = if (monthBy == Repeat.MONTH_DAY) 1 else 0
		addActionListener {
			monthBy = if (selectedIndex == 1) Repeat.MONTH_DAY else Repeat.MONTH_DATE
			if (isMonthly()) kind = monthBy
			refresh()
		}
	}
	private val byLabel = repeatLabel("By:", 'B', byCombo)
	private val summary = JTextArea(3, 20).themed().apply {
		isEditable = false
		background = Theme.panel
		lineWrap = true
		wrapStyleWord = true
	}
	private val endMenu = JPopupMenu().apply {
		add(JMenuItem("No End Date").apply { addActionListener { end = -1; refresh() } })
		add(JMenuItem("Date...").apply { addActionListener { pickEnd() } })
	}

	/** A label with an Alt key that moves focus to [target]. */
	private fun repeatLabel(text: String, key: Char, target: Component): JLabel =
		JLabel(text).themed().apply { setDisplayedMnemonic(key); labelFor = target }

	init {
		buildLayout()
		refresh()
	}

	fun show(): Boolean {
		dlg.isVisible = true
		return changed
	}

	/** Places every part at a fixed position. The form keeps one size for every kind, as Palm Desktop does. */
	private fun buildLayout() {
		noneRadio.mnemonic = KeyEvent.VK_N
		dailyRadio.mnemonic = KeyEvent.VK_D
		weeklyRadio.mnemonic = KeyEvent.VK_W
		monthlyRadio.mnemonic = KeyEvent.VK_M
		yearlyRadio.mnemonic = KeyEvent.VK_Y
		val group = ButtonGroup()
		val content = JPanel(null)
		for ((i, r) in listOf(noneRadio, dailyRadio, weeklyRadio, monthlyRadio, yearlyRadio).withIndex()) {
			group.add(r)
			content.add(r)
			r.setBounds(10, 16 + 24 * i, 72, 20)
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

		hint.setBounds(96, 40, 157, 56)
		everyLabel.setBounds(96, 18, 44, 17)
		everySpin.setBounds(144, 16, 40, 21)
		everyUnit.setBounds(190, 18, 66, 17)
		endLabel.setBounds(96, 44, 44, 17)
		endField.setBounds(144, 42, 80, 21)
		calBtn.setBounds(224, 42, 20, 20)
		daysLabel.setBounds(96, 70, 44, 17)
		weekStrip.setBounds(144, 69, 99, 18)
		byLabel.setBounds(96, 70, 44, 17)
		byCombo.setBounds(144, 68, 100, 21)
		val summaryBox = JScrollPane(summary).apply {
			border = Theme.sunken
			viewport.background = Theme.panel
		}
		summaryBox.setBounds(10, 136, 243, 52)
		val ok = formButton("OK") { save() }.apply { setBounds(10, 194, 68, 23) }
		val cancel = formButton("Cancel") { dlg.dispose() }.apply { setBounds(82, 194, 68, 23) }
		for (c in listOf(hint, everyLabel, everySpin, everyUnit, endLabel, endField, calBtn, daysLabel, weekStrip, byLabel, byCombo, summaryBox, ok, cancel)) {
			content.add(c)
		}
		dlg.contentPane.add(content, BorderLayout.CENTER)
		dlg.rootPane.defaultButton = ok
		dlg.isResizable = false
		dlg.setSize(269, 248)
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

	/** Shows the rows that fit the kind, and the summary of the working copy. Hidden rows take no room. */
	private fun refresh() {
		val on = kind != Repeat.NONE
		val n = everySpin.value as Int
		hint.isVisible = !on
		everyLabel.isVisible = on
		everySpin.isVisible = on
		everyUnit.isVisible = on
		endLabel.isVisible = on
		endField.isVisible = on
		calBtn.isVisible = on
		daysLabel.isVisible = kind == Repeat.WEEK
		weekStrip.isVisible = kind == Repeat.WEEK
		byLabel.isVisible = isMonthly()
		byCombo.isVisible = isMonthly()
		everyUnit.text = when (kind) {
			Repeat.DAY -> if (n == 1) "Day" else "Days"
			Repeat.WEEK -> if (n == 1) "Week" else "Weeks"
			Repeat.YEAR -> if (n == 1) "Year" else "Years"
			else -> if (n == 1) "Month" else "Months"
		}
		endField.text = if (end < 0) "No End Date" else Fmt.shortDate(end)
		summary.text = if (on) describeDesktop(preview()) else ""
		for (b in dayToggles) {
			b.background = if (b.isSelected) Theme.select else Color.WHITE
			b.foreground = if (b.isSelected) Color.WHITE else Color.BLACK
		}
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

	/** Compact row of the Edit Event form: the field fills column 1, [side] sits at the right edge in column 2. [wide] lets the field span both. */
	fun addCompact(panel: JPanel, y: Int, side: Component? = null, wide: Boolean = false) {
		panel.add(label, GridBagConstraints(0, y, 1, 1, 0.0, 0.0, GridBagConstraints.NORTHEAST, GridBagConstraints.NONE, Insets(2, 6, 2, 4), 0, 0))
		val span = if (wide) 2 else 1
		panel.add(field, GridBagConstraints(1, y, span, 1, 1.0, 0.0, GridBagConstraints.WEST, GridBagConstraints.HORIZONTAL, Insets(2, 4, 2, if (side == null) 6 else 4), 0, 0))
		if (side != null) panel.add(side, GridBagConstraints(2, y, 1, 1, 0.0, 0.0, GridBagConstraints.WEST, GridBagConstraints.NONE, Insets(2, 0, 2, 6), 0, 0))
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

private fun readOnlyField(): JTextField = JTextField(9).themed().apply { isEditable = false }

/** The text of an untimed start or end field. */
private const val NO_TIME = "None"

/** True when a time field says no time: blank or "None". */
private fun isNoTime(text: String): Boolean = text.isBlank() || text.trim().equals(NO_TIME, ignoreCase = true)

/** An icon button, [size] px square. The Edit Event form uses 22 px at the right edge of its column. */
private fun iconButton(icon: Icon, tip: String, size: Int = 22, action: () -> Unit): JButton =
	JButton(icon).apply {
		toolTipText = tip
		margin = Insets(0, 0, 0, 0)
		preferredSize = Dimension(size, size)
		addActionListener { action() }
	}

/** An OK or Cancel button of the Palm Desktop forms: 68 px wide. */
private fun formButton(text: String, action: () -> Unit): JButton =
	JButton(text).themed().apply {
		addActionListener { action() }
		preferredSize = Dimension(68, preferredSize.height)
	}

/** The Windows short date with a four-digit year and no leading zeros: "5/3/2026". */
private fun fullDate(day: Long): String {
	val d = LocalDate.ofEpochDay(day)
	return "${d.monthValue}/${d.dayOfMonth}/${d.year}"
}

private fun firstLine(text: String): String = text.lineSequence().firstOrNull().orEmpty()

private fun weekdayName(day: Long): String = Fmt.dowLong[Recur.dow(LocalDate.ofEpochDay(day))]

/** Parses a date field such as "10/21/2026" or "10/19/26" to an epoch day, or null. Two-digit years 70 to 99 mean 19xx. */
private fun parseShortDate(text: String): Long? {
	val m = Regex("""\s*(\d{1,2})/(\d{1,2})/(\d{2}|\d{4})\s*""").matchEntire(text) ?: return null
	val digits = m.groupValues[3]
	val yy = digits.toInt()
	val year = when {
		digits.length == 4 -> yy
		yy >= 70 -> 1900 + yy
		else -> 2000 + yy
	}
	return try {
		LocalDate.of(year, m.groupValues[1].toInt(), m.groupValues[2].toInt()).toEpochDay()
	} catch (ex: DateTimeException) {
		null
	}
}

/**
 * Parses a time field such as "8:00a", "8a", "1:30 PM", "13:30" or "830" to minutes after midnight, or
 * null. Spaces and case do not count. With no suffix the hour is 24 h.
 */
internal fun parseTime(text: String): Int? {
	val s = text.filterNot { it.isWhitespace() }.lowercase()
	val m = Regex("""(?:(\d{1,2}):(\d{2})|(\d{1,2})|(\d{3,4}))(a|am|p|pm)?""").matchEntire(s) ?: return null
	val g = m.groupValues
	val h: Int
	val min: Int
	when {
		g[1].isNotEmpty() -> { h = g[1].toInt(); min = g[2].toInt() }
		g[3].isNotEmpty() -> { h = g[3].toInt(); min = 0 }
		else -> { h = g[4].dropLast(2).toInt(); min = g[4].takeLast(2).toInt() }
	}
	if (min > 59) return null
	val hour = when {
		g[5].isEmpty() -> h
		h !in 1..12 -> return null
		g[5].startsWith("a") -> h % 12
		else -> h % 12 + 12
	}
	return if (hour > 23) null else hour * 60 + min
}

/** Select Time dialog. Edits [t] and returns true on OK or No Time. Cancel returns false. */
private fun selectTime(owner: Component, t: Event): Boolean {
	val dlg = modalDialog(owner, "Select Time")
	var ok = false
	var editStart = true
	var syncing = false
	val startRadio = JRadioButton().themed().apply { mnemonic = KeyEvent.VK_S }
	val endRadio = JRadioButton().themed().apply { mnemonic = KeyEvent.VK_E }
	val startLabel = JLabel("Start time:").themed().apply { labelFor = startRadio }
	val endLabel = JLabel("End time:").themed().apply { labelFor = endRadio }
	val startShow = readOnlyField().apply { columns = 6 }
	val endShow = readOnlyField().apply { columns = 6 }
	val noTime = JButton("No Time").themed().apply { mnemonic = KeyEvent.VK_N }
	/** Two digits and no minutes, in 24 h or 12 h to match the Time setting. */
	fun hourLabel(h: Int): String = "%02d".format(if (Fmt.h24) h else (h + 11) % 12 + 1)
	val hours = JList(Array(24) { hourLabel(it) }).themed().apply {
		selectionMode = ListSelectionModel.SINGLE_SELECTION
		visibleRowCount = 12
		fixedCellHeight = 13
	}
	val minutes = JList(Array(12) { "%02d".format(it * 5) }).themed().apply {
		selectionMode = ListSelectionModel.SINGLE_SELECTION
		visibleRowCount = 12
		fixedCellHeight = 13
	}

	fun activeTime(): Int = if (editStart) t.start else t.end

	/** Shows both times and selects their hour and minute in the lists. */
	fun refresh() {
		startShow.text = Fmt.time(t.start)
		endShow.text = Fmt.time(t.end)
		syncing = true
		hours.selectedIndex = activeTime() / 60
		minutes.selectedIndex = activeTime() % 60 / 5
		syncing = false
	}

	/** Puts [min] on the checked radio. A new start moves the end. A new end before the start is refused. */
	fun place(min: Int) {
		if (editStart) {
			val len = maxOf(t.end - t.start, 0)
			t.start = min
			t.end = minOf(min + len, 1439)
		} else if (min < t.start) {
			dlg.toolkit.beep()
			refresh()
			return
		} else {
			t.end = min
		}
		t.untimed = false
		refresh()
	}

	hours.addListSelectionListener { ev ->
		if (!ev.valueIsAdjusting && !syncing) place(hours.selectedIndex * 60 + activeTime() % 60)
	}
	minutes.addListSelectionListener { ev ->
		if (!ev.valueIsAdjusting && !syncing) place(activeTime() / 60 * 60 + minutes.selectedIndex * 5)
	}
	ButtonGroup().apply { add(startRadio); add(endRadio) }
	startRadio.addActionListener { editStart = true; refresh() }
	endRadio.addActionListener { editStart = false; refresh() }
	startRadio.isSelected = true
	noTime.addActionListener { t.untimed = true; ok = true; dlg.dispose() }
	val okBtn = formButton("OK") { ok = true; dlg.dispose() }
	val cancel = formButton("Cancel") { dlg.dispose() }

	// Top-aligned stack at natural heights: BoxLayout would stretch the rows, so each row is capped at its preferred height.
	val rows = JPanel().apply {
		layout = BoxLayout(this, BoxLayout.Y_AXIS)
		listOf<JComponent>(startLabel, line(startRadio, startShow), endLabel, line(endRadio, endShow), line(noTime)).forEach {
			add(it.apply { alignmentX = Component.LEFT_ALIGNMENT; maximumSize = preferredSize })
		}
	}
	val left = JPanel(BorderLayout()).apply { add(rows, BorderLayout.NORTH) }
	val right = JPanel(GridLayout(1, 2, 8, 0)).apply {
		add(JScrollPane(hours))
		add(JScrollPane(minutes))
	}
	val top = JPanel(BorderLayout(12, 0)).apply {
		add(left, BorderLayout.WEST)
		add(right, BorderLayout.CENTER)
	}
	dlg.contentPane.add(top, BorderLayout.CENTER)
	dlg.contentPane.add(JPanel(FlowLayout(FlowLayout.CENTER, 6, 6)).apply { add(okBtn); add(cancel) }, BorderLayout.SOUTH)
	dlg.rootPane.defaultButton = okBtn
	refresh()
	dlg.pack()
	// The official Select Time dialog is 207 x 248 px. The hour and minute lists take the space above the buttons.
	dlg.setSize(207, 248)
	dlg.setLocationRelativeTo(owner)
	dlg.isVisible = true
	return ok
}
