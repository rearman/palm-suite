package com.rearman.palmdesktop

import com.rearman.datebook.Category
import com.rearman.datebook.Event
import com.rearman.datebook.Fmt
import com.rearman.datebook.Occ
import com.rearman.datebook.Todo
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.Insets
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.JToggleButton
import javax.swing.KeyStroke
import javax.swing.ListCellRenderer
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.border.EmptyBorder

/**
 * The Date view: the Day grid, the right pane (calendar and To Do list) and the view tabs.
 * The Week and Month views share the right pane and the tabs.
 */
class DatePanel : JPanel(BorderLayout()), AppView {

	private companion object {
		const val TIME_W = 52        // width of the time column
		const val UNTIMED_H = 18     // height of one untimed row
		const val HALF = 19          // pixels per half hour (Palm Desktop: 38 px per hour)
		const val PANE_W = 240       // width of the right pane
		const val CHANGE_TEXT = "Apply the changes to the current event only or to all occurrences?"
	}

	private val cards = CardLayout()
	private val cardPanel = JPanel(cards)
	private val weekView = WeekView()
	private val monthView = MonthView()
	private val titleLabel = JLabel("", SwingConstants.CENTER)
	private val grid = DayGrid(false)
	private val dayHeader = DayGrid(true)
	private val dayScroll = JScrollPane(grid)
	private val editBtn = button("Edit...") { editSelected() }.apply { mnemonic = KeyEvent.VK_I }
	private val yearLabel = JLabel("", SwingConstants.CENTER)
	private val monthButtons = List(12) { i -> JButton(Fmt.monthShort[i]) }
	private val calendar = MiniCalendar()
	private val todoModel = DefaultListModel<Todo>()
	// The rows take the width of the pane, so a long description is cut short and the list has no horizontal scroll bar.
	private val todoList = object : JList<Todo>(todoModel) {
		override fun getScrollableTracksViewportWidth(): Boolean = true
	}
	private val catCombo = JComboBox<String>()
	private val rightPane = JPanel(BorderLayout())
	private val tabGroup = ButtonGroup()
	// The keys of Palm Desktop: Alt+D, Alt+W and Alt+M. The launcher buttons use F2, F4 and F5.
	private val dayTab = VerticalTab("Day", 'D')
	private val weekTab = VerticalTab("Week", 'W')
	private val monthTab = VerticalTab("Month", 'M')
	private var shownCats: List<Category> = emptyList()
	private var catId: Int? = null
	private var updating = false
	private var occs: List<Occ> = emptyList()
	private var untimedH = UNTIMED_H   // height of the untimed rows: one row for each untimed event, at least one
	private var selectedId: Long? = null
	private var mode = "day"                 // the view shown: "day", "week" or "month"
	private var typing: JTextField? = null   // the field of a new event typed into an empty hour
	private var typingMin = 0               // start of that hour, in minutes after midnight
	private var typingDay = 0L              // the day the field was opened on
	private var typingUntimed = false       // true when the field types an untimed event in the header
	private var titleEdit: JTextField? = null   // the in-place title editor of a box
	private var titleOcc: Occ? = null          // the occurrence that editor changes
	private var drag: Drag? = null          // the box being moved or resized by the mouse
	private var suppressClick = false       // true after a drag, so its release does not also click

	/** A press on a box: the box moves to a new time or day, or its end is resized. [moved] after 4 px. */
	private class Drag(val occ: Occ, val box: Rectangle, val resize: Boolean, val grab: Int, val origin: Point) {
		var moved = false
		var newStart = 0
		var newEnd = 0
		var dropDay: Long? = null          // the calendar day under the mouse, or null
		var target: Rectangle? = null      // the outline in the grid, or null
	}

	/**
	 * A To Do row as Palm Desktop shows it, after the Show Options: check box, priority, description and
	 * a due date column. A selected row highlights the description only.
	 */
	private val todoRenderer = object : JPanel(BorderLayout(2, 0)), ListCellRenderer<Todo> {
		val check = JCheckBox()   // the list mouse listener reads its width to find a click on it
		val pri = JLabel()   // the popup of a click on it reads its bounds
		private val title = JLabel()
		val due = JLabel()
		private val noteMark = JLabel(Icons.note())
		private val privateMark = JLabel(Icons.privateRecord())

		init {
			background = Color.WHITE
			val lead = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0))
			lead.isOpaque = false
			check.isOpaque = false
			lead.add(check)
			lead.add(pri)
			for (l in listOf(pri, title, due, noteMark, privateMark)) l.font = Theme.font
			title.isOpaque = true
			due.preferredSize = Dimension(45, 0)
			// The due date, then the note and private marks, which show only when set.
			val marks = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
			marks.isOpaque = false
			marks.add(noteMark)
			marks.add(privateMark)
			val tail = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
			tail.isOpaque = false
			tail.add(due)
			tail.add(marks)
			add(lead, BorderLayout.WEST)
			add(title, BorderLayout.CENTER)
			add(tail, BorderLayout.EAST)
			border = EmptyBorder(0, 0, 0, 4)
		}

		override fun getListCellRendererComponent(
			list: JList<out Todo>?, value: Todo?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
		): java.awt.Component {
			val t = value ?: return this
			check.isSelected = t.done
			pri.isVisible = Settings.getBool("todoShowPri", false)
			pri.text = t.priority.toString()
			title.text = t.title.replace('\n', ' ')
			title.background = if (isSelected) Theme.select else Color.WHITE
			title.foreground = when {
				isSelected -> Color.YELLOW
				isOverdue(t) -> Theme.dateRed
				else -> Color.BLACK
			}
			due.isVisible = Settings.getBool("todoShowDue", true)
			due.text = if (t.due < 0) "" else dueLabel(t)
			noteMark.isVisible = t.note.isNotEmpty()
			privateMark.isVisible = t.isPrivate
			return this
		}
	}

	init {
		cardPanel.add(buildDayPanel(), "day")
		cardPanel.add(weekView, "week")
		cardPanel.add(monthView, "month")
		val east = JPanel(BorderLayout())
		east.add(buildRightPane(), BorderLayout.CENTER)
		east.add(buildTabs(), BorderLayout.EAST)
		add(cardPanel, BorderLayout.CENTER)
		add(east, BorderLayout.EAST)
		App.onRefresh { refreshAll() }
		showMode(Settings.get("dateMode", "day"))
		refreshAll()
		// The Day grid opens at the work day hour, as in Palm Desktop.
		SwingUtilities.invokeLater { dayScroll.viewport.viewPosition = Point(0, yOf(DateSettings.workDay * 60)) }
	}

	/** Shows the Day, Week or Month view and remembers the choice. */
	fun showMode(mode: String) {
		val name = when (mode) {
			"week", "month" -> mode
			else -> "day"
		}
		mode = name
		cards.show(cardPanel, name)
		dayTab.isSelected = name == "day"
		weekTab.isSelected = name == "week"
		monthTab.isSelected = name == "month"
		// Palm Desktop shows the calendar pane only in the Day view; Week and Month use the full width.
		rightPane.isVisible = name == "day"
		Settings.set("dateMode", name)
	}

	// AppView

	override fun newRecord() {
		EventDialog.edit(this, null, App.day, 8 * 60)
	}

	override fun editSelected() {
		val o = currentOcc() ?: return
		EventDialog.edit(this, o.event, o.day)
	}

	override fun deleteSelected() {
		val o = currentOcc() ?: return
		if (deleteOccurrence(this, o) && mode == "day") selectedId = null
	}

	override fun select(record: Any) {
		if (record !is Event) return
		App.day = record.date
		selectedId = record.id
		showMode("day")
		App.refresh()
	}

	override fun printLines(): Pair<String, List<String>> {
		val lines = dayOccs().map { o ->
			val time = if (o.event.untimed) "Untimed" else Fmt.time(o.event.start)
			"$time  ${o.event.title}"
		}
		return dateTitle(App.day) to lines
	}

	// Building

	/** Creates a push button with the UI font. */
	private fun button(text: String, action: () -> Unit): JButton {
		val b = JButton(text)
		b.font = Theme.font
		b.isFocusPainted = false
		b.addActionListener { action() }
		return b
	}

	private fun buildDayPanel(): JPanel {
		titleLabel.font = Theme.bold.deriveFont(14f)
		titleLabel.foreground = Theme.dateRed
		titleLabel.isOpaque = true
		titleLabel.background = Color.WHITE
		titleLabel.border = BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(Theme.grid), BorderFactory.createEmptyBorder(2, 0, 2, 0)
		)
		dayScroll.setColumnHeaderView(dayHeader)   // the untimed rows stay in view, as in Palm Desktop
		dayScroll.verticalScrollBar.unitIncrement = 20
		editBtn.isEnabled = false
		// Double click on the title picks the day, as Palm Desktop does.
		titleLabel.addMouseListener(object : MouseAdapter() {
			override fun mouseClicked(e: MouseEvent) {
				if (e.clickCount != 2) return
				DatePicker.pick(this@DatePanel, "day", App.day)?.let {
					App.day = it
					App.refresh()
				}
			}
		})
		val bar = JPanel(FlowLayout(FlowLayout.CENTER, 24, 5))
		bar.add(button("Today") {
			App.day = LocalDate.now().toEpochDay()
			App.refresh()
		}.apply { mnemonic = KeyEvent.VK_O })
		bar.add(editBtn)
		bar.add(button("New...") { newRecord() }.apply { mnemonic = KeyEvent.VK_N })
		val panel = JPanel(BorderLayout())
		panel.add(titleLabel, BorderLayout.NORTH)
		panel.add(dayScroll, BorderLayout.CENTER)
		panel.add(bar, BorderLayout.SOUTH)
		return panel
	}

	private fun buildRightPane(): JPanel {
		rightPane.preferredSize = Dimension(PANE_W, 0)
		val top = JPanel()
		top.layout = BoxLayout(top, BoxLayout.Y_AXIS)

		yearLabel.font = Theme.bold
		val yearRow = JPanel(BorderLayout())
		yearRow.add(arrowButton(SwingConstants.WEST) { setYear(-1) }, BorderLayout.WEST)
		yearRow.add(yearLabel, BorderLayout.CENTER)
		yearRow.add(arrowButton(SwingConstants.EAST) { setYear(1) }, BorderLayout.EAST)
		yearRow.maximumSize = Dimension(Int.MAX_VALUE, 26)

		val months = JPanel(GridLayout(2, 6))
		months.maximumSize = Dimension(Int.MAX_VALUE, 44)
		for ((i, b) in monthButtons.withIndex()) {
			b.font = Theme.font
			b.margin = Insets(0, 0, 0, 0)
			b.isFocusPainted = false
			b.isOpaque = true
			b.addActionListener { setMonth(i + 1) }
			months.add(b)
		}

		calendar.maximumSize = Dimension(Int.MAX_VALUE, 126)
		top.add(yearRow)
		top.add(months)
		top.add(calendar)

		val todoLabel = JLabel("To Do")
		// Palm Desktop shows the list under a selected "To Do" tab.
		todoLabel.font = Theme.font
		todoLabel.isOpaque = true
		todoLabel.background = Theme.select
		todoLabel.foreground = Color.WHITE
		todoLabel.border = BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(Color.BLACK), BorderFactory.createEmptyBorder(1, 8, 1, 8)
		)
		catCombo.font = Theme.font
		catCombo.addActionListener {
			if (!updating) {
				catId = shownCats.getOrNull(catCombo.selectedIndex - 1)?.id
				refreshAll()
			}
		}
		val head = JPanel(BorderLayout())
		head.add(todoLabel, BorderLayout.WEST)
		head.add(catCombo, BorderLayout.CENTER)

		todoList.font = Theme.font
		todoList.cellRenderer = todoRenderer
		todoList.addMouseListener(object : MouseAdapter() {
			override fun mouseClicked(e: MouseEvent) {
				val idx = todoList.locationToIndex(e.point)
				if (idx < 0 || todoList.getCellBounds(idx, idx)?.contains(e.point) != true) return
				val item = todoModel.getElementAt(idx)
				// A click on the check box marks the item done, or not done, as in Palm Desktop.
				if (e.x < todoRenderer.check.preferredSize.width + 4) {
					markDone(item, !item.done)
					App.store.save(item)
					App.refresh()
				} else if (e.clickCount == 1 && rowPartBounds(idx, todoRenderer.pri)?.contains(e.point) == true) {
					priorityMenu(item).show(todoList, e.x, e.y)
				} else if (e.clickCount == 1 && rowPartBounds(idx, todoRenderer.due)?.contains(e.point) == true) {
					dueMenu(item).show(todoList, e.x, e.y)
				} else if (e.clickCount == 2) {
					App.show("todo", item)
				}
			}
		})
		val todoPart = JPanel(BorderLayout())
		todoPart.add(head, BorderLayout.NORTH)
		todoPart.add(JScrollPane(todoList), BorderLayout.CENTER)

		rightPane.add(top, BorderLayout.NORTH)
		rightPane.add(todoPart, BorderLayout.CENTER)
		return rightPane
	}

	private fun buildTabs(): JPanel {
		val column = JPanel(GridLayout(3, 1))
		for ((tab, name) in listOf(dayTab to "day", weekTab to "week", monthTab to "month")) {
			tab.addActionListener { showMode(name) }
			// A double click goes to today, as in Palm Desktop.
			tab.addMouseListener(object : MouseAdapter() {
				override fun mouseClicked(e: MouseEvent) {
					if (e.clickCount != 2) return
					App.day = LocalDate.now().toEpochDay()
					App.refresh()
				}
			})
			tabGroup.add(tab)
			column.add(tab)
		}
		val panel = JPanel(BorderLayout())
		panel.add(column, BorderLayout.NORTH)
		return panel
	}

	// Data and state

	/** Rebuilds every part of the panel from the store and App.day. */
	private fun refreshAll() {
		if (App.day != typingDay) endTyping(true)   // the field belongs to the day it was opened on
		endTitleEdit(true)
		val day = LocalDate.ofEpochDay(App.day)
		titleLabel.text = dateTitle(App.day)
		occs = dayOccs()
		// An open untimed field keeps its row.
		val typingRow = if (typing != null && typingUntimed) 1 else 0
		untimedH = maxOf(1, occs.count { it.event.untimed } + typingRow) * UNTIMED_H
		if (selectedOcc() == null) selectedId = null
		editBtn.isEnabled = selectedOcc() != null
		yearLabel.text = day.year.toString()
		for ((i, b) in monthButtons.withIndex()) styleMonth(b, i == day.monthValue - 1)
		rebuildCategories()
		rebuildTodos()
		dayHeader.revalidate()   // the untimed rows change the header height
		repaintDay()
		calendar.repaint()
	}

	private fun dayOccs(): List<Occ> =
		App.store.dayEvents(App.day).filter { App.showPrivate || !it.event.isPrivate }

	private fun selectedOcc(): Occ? = occs.firstOrNull { it.event.id == selectedId }

	/** The selected occurrence of the view shown: the Week view's own selection, else the Day selection. */
	private fun currentOcc(): Occ? = if (mode == "week") weekView.selectedOcc() else selectedOcc()

	private fun dateTitle(day: Long): String =
		LocalDate.ofEpochDay(day).format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.US))

	private fun styleMonth(b: JButton, on: Boolean) {
		b.background = if (on) Theme.select else Theme.panel
		b.foreground = if (on) Color.WHITE else Color.BLACK
	}

	private fun setYear(delta: Int) {
		App.day = LocalDate.ofEpochDay(App.day).plusYears(delta.toLong()).toEpochDay()
		App.refresh()
	}

	private fun setMonth(month: Int) {
		App.day = LocalDate.ofEpochDay(App.day).withMonth(month).toEpochDay()
		App.refresh()
	}

	private fun rebuildCategories() {
		updating = true
		try {
			shownCats = App.store.categories.toList()
			catCombo.removeAllItems()
			catCombo.addItem("All")
			shownCats.forEach { catCombo.addItem(it.name) }
			val idx = shownCats.indexOfFirst { it.id == catId }
			if (idx < 0) catId = null
			catCombo.selectedIndex = if (idx < 0) 0 else idx + 1
		} finally {
			updating = false
		}
	}

	private fun rebuildTodos() {
		val items = App.store.todos
			.filter { (catId == null || it.category == catId) && todoShown(it) }
			.sortedWith(todoOrder())
		todoModel.clear()
		items.forEach { todoModel.addElement(it) }
	}

	/** Bounds of [part] on row [idx] in list coordinates, with the row laid out at its cell size. Null when hidden. */
	private fun rowPartBounds(idx: Int, part: JComponent): Rectangle? {
		val cell = todoList.getCellBounds(idx, idx) ?: return null
		todoRenderer.getListCellRendererComponent(todoList, todoModel.getElementAt(idx), idx, false, false)
		todoRenderer.setBounds(0, 0, cell.width, cell.height)
		todoRenderer.doLayout()
		if (!part.isVisible) return null
		val r = SwingUtilities.convertRectangle(part.parent, part.bounds, todoRenderer)
		return Rectangle(cell.x + r.x, cell.y + r.y, r.width, r.height)
	}

	/** The popup of a To Do priority: 1 to 5. */
	private fun priorityMenu(t: Todo): JPopupMenu =
		popupOf((1..5).map { n -> "$n" to { saveTodo(t) { t.priority = n } } })

	/** The popup of a To Do due date. */
	private fun dueMenu(t: Todo): JPopupMenu {
		val today = LocalDate.now().toEpochDay()
		return popupOf(listOf(
			"Today" to { saveTodo(t) { t.due = today } },
			"Tomorrow" to { saveTodo(t) { t.due = today + 1 } },
			"One week later" to { saveTodo(t) { t.due = today + 7 } },
			"No date" to { saveTodo(t) { t.due = -1L } },
			"Choose date..." to { pickDue(t) }
		))
	}

	/** Asks a day in the picker and sets the due date of [t] to it. */
	private fun pickDue(t: Todo) {
		val day = DatePicker.pick(this, "day", if (t.due >= 0) t.due else LocalDate.now().toEpochDay()) ?: return
		saveTodo(t) { t.due = day }
	}

	/** Applies [change] to [t], saves it and redraws the panel. */
	private fun saveTodo(t: Todo, change: () -> Unit) {
		change()
		App.store.save(t)
		App.refresh()
	}

	/** A popup menu with one item for each of [items]. */
	private fun popupOf(items: List<Pair<String, () -> Unit>>): JPopupMenu {
		val menu = JPopupMenu()
		for ((text, action) in items) {
			menu.add(JMenuItem(text).apply {
				font = Theme.font
				addActionListener { action() }
			})
		}
		return menu
	}

	// Day grid geometry: HALF px per half hour. The untimed rows are in a fixed header above the grid.

	/** Pixel row on the grid of a time in minutes after midnight. */
	private fun yOf(min: Int): Int = min * HALF / 30

	/** Minutes after midnight at a pixel row of the grid (inverse of yOf). */
	private fun minAt(y: Int): Int = y * 30 / HALF

	private fun repaintDay() {
		dayHeader.repaint()
		grid.repaint()
	}

	// Typing a new event into an empty hour of the grid, as in Palm Desktop.

	/** Box of the hour being typed into, for a grid [width] in pixels, or null when no field is open. */
	private fun typingBox(width: Int): Rectangle? {
		if (typing == null) return null
		if (typingUntimed) {
			val row = occs.count { it.event.untimed } * UNTIMED_H
			return Rectangle(TIME_W + 2, row + 1, width - TIME_W - 4, UNTIMED_H - 2)
		}
		val top = yOf(typingMin)
		return Rectangle(TIME_W + 2, top + 2, width - TIME_W - 4, yOf(typingMin + 60) - top - 3)
	}

	/** Opens a field in the hour at [min] for the title of a new event. */
	private fun startTyping(min: Int, untimed: Boolean) {
		endTyping(true)
		selectedId = null
		editBtn.isEnabled = false
		typingMin = min
		typingUntimed = untimed
		typingDay = App.day
		if (untimed) untimedH = (occs.count { it.event.untimed } + 1) * UNTIMED_H   // the header grows by one row
		val f = JTextField()
		f.font = Theme.font
		f.border = null
		f.isOpaque = false
		f.addActionListener { endTyping(true) }
		f.registerKeyboardAction({ endTyping(false) }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_FOCUSED)
		f.addFocusListener(object : FocusAdapter() {
			override fun focusLost(e: FocusEvent) {
				if (!e.isTemporary) endTyping(true)
			}
		})
		f.addMouseListener(object : MouseAdapter() {
			override fun mouseClicked(e: MouseEvent) {
				// A double click on an empty hour opens the event dialog, as before.
				if (e.clickCount == 2 && !untimed) {
					endTyping(false)
					EventDialog.edit(this@DatePanel, null, App.day, min)
				}
			}
		})
		typing = f
		val parent: JPanel = if (untimed) dayHeader else grid
		parent.add(f)
		parent.revalidate()
		repaintDay()
		f.requestFocusInWindow()
	}

	/** Closes the field. With [save], a title that is not empty becomes a one-hour event. */
	private fun endTyping(save: Boolean) {
		val f = typing ?: return
		typing = null   // first, so the focus loss of the removed field does nothing
		if (typingUntimed) dayHeader.remove(f) else grid.remove(f)
		untimedH = maxOf(1, occs.count { it.event.untimed }) * UNTIMED_H   // the header shrinks back
		dayHeader.revalidate()
		repaintDay()
		val title = f.text.trim()
		if (!save || title.isEmpty()) return
		val ev = if (typingUntimed) {
			Event(title = title, date = typingDay, untimed = true)
		} else {
			Event(title = title, date = typingDay, start = typingMin, end = typingMin + 60, untimed = false)
		}
		applyAlarmPreset(ev)
		App.store.save(ev)
		selectedId = ev.id
		App.refresh()
	}

	/** Closes the in-place title editor. With [save], a changed title that is not empty is saved for its occurrence. */
	private fun endTitleEdit(save: Boolean) {
		val f = titleEdit ?: return
		val occ = titleOcc ?: return
		titleEdit = null   // first, so the focus loss of the removed editor does nothing
		titleOcc = null
		f.parent?.remove(f)
		repaintDay()
		val text = f.text.trim()
		if (!save || text.isEmpty() || text == occ.event.title) return
		changeOccurrence(this, occ, CHANGE_TEXT) { it.title = text }
	}

	/** The box of the header ([header] true) or the timed grid under [p], with its bounds, or null. */
	private fun boxAt(width: Int, p: Point, header: Boolean): Pair<Occ, Rectangle>? =
		layoutDay(width).firstOrNull { it.first.event.untimed == header && it.second.contains(p) }

	/** [min] rounded to the nearest half hour. */
	private fun roundHalf(min: Int): Int = (min + 15) / 30 * 30

	private fun overlaps(a: Event, b: Event): Boolean = a.start < b.end && b.start < a.end

	/**
	 * Bounds of each event for a [width] in pixels: an untimed event on its own full-width row of the
	 * header, as in Palm Desktop, and a timed event on the grid.
	 */
	private fun layoutDay(width: Int): List<Pair<Occ, Rectangle>> {
		val out = ArrayList<Pair<Occ, Rectangle>>()
		var row = 0
		for (o in occs) {
			if (!o.event.untimed) continue
			out.add(o to Rectangle(TIME_W + 2, row * UNTIMED_H + 1, width - TIME_W - 4, UNTIMED_H - 2))
			row++
		}
		val timed = occs.filter { !it.event.untimed }
		// Column of each event: the first column that is free at its start.
		val colEnd = ArrayList<Int>()
		val col = IntArray(timed.size)
		for ((i, o) in timed.withIndex()) {
			var c = colEnd.indexOfFirst { it <= o.event.start }
			if (c < 0) {
				c = colEnd.size
				colEnd.add(0)
			}
			colEnd[c] = o.event.end
			col[i] = c
		}
		val areaW = width - TIME_W - 2
		for ((i, o) in timed.withIndex()) {
			// Column count of an event: the widest column among the events it overlaps.
			var n = col[i] + 1
			for ((j, p) in timed.withIndex()) {
				if (overlaps(o.event, p.event)) n = maxOf(n, col[j] + 1)
			}
			val colW = areaW / n
			val top = yOf(o.event.start)
			val bottom = maxOf(yOf(o.event.end), top + HALF)
			// Palm Desktop keeps a 2 px gap between the box and the grid lines.
			out.add(o to Rectangle(TIME_W + 2 + col[i] * colW, top + 2, colW - 2, bottom - top - 3))
		}
		return out
	}

	/** Draws [s] centered in the box at x, y with size w by h. */
	private fun drawCentered(g: Graphics, s: String, x: Int, y: Int, w: Int, h: Int, font: Font, color: Color) {
		g.font = font
		val fm = g.fontMetrics
		g.color = color
		g.drawString(s, x + (w - fm.stringWidth(s)) / 2, y + (h + fm.ascent - fm.descent) / 2)
	}

	// Inner components

	/**
	 * The Day grid ([header] false: the time column and the timed events) or the fixed header above it
	 * ([header] true: one row for each untimed event, with the diamond mark).
	 */
	private inner class DayGrid(private val header: Boolean) : JPanel(), Scrollable {
		init {
			isOpaque = true
			background = Color.WHITE
			layout = null   // doLayout places the typing field and the title editor
			addMouseListener(object : MouseAdapter() {
				override fun mousePressed(e: MouseEvent) {
					suppressClick = false
					endTyping(true)
					endTitleEdit(true)
					if (e.isPopupTrigger) {
						popupAt(e)
						return
					}
					if (e.button != MouseEvent.BUTTON1) return
					if (!header) {
						// The blue bar under the selected box resizes it, so it is tested before the box.
						val sel = layoutDay(width).firstOrNull { it.first.event.id == selectedId && !it.first.event.untimed }
						if (sel != null && EventBox.onResizeBar(sel.second, e.point)) {
							drag = Drag(sel.first, sel.second, true, 0, e.point)
							return
						}
					}
					val (occ, r) = boxAt(width, e.point, header) ?: return
					if (EventBox.onHandle(r, e.point) || EventBox.markerAt(r, occ.event, e.point, header) != null) return
					drag = Drag(occ, r, false, e.y - r.y, e.point)
				}

				override fun mouseReleased(e: MouseEvent) {
					if (e.isPopupTrigger) {
						popupAt(e)
						return
					}
					val d = drag ?: return
					drag = null
					if (!d.moved) return
					suppressClick = true
					updateDrag(d, e.point)
					val ev = d.occ.event
					val day = d.dropDay
					val newStart = d.newStart
					val newEnd = d.newEnd
					when {
						day != null -> changeOccurrence(this@DatePanel, d.occ, CHANGE_TEXT) { it.date = day }
						d.resize -> if (newEnd != ev.end) changeOccurrence(this@DatePanel, d.occ, CHANGE_TEXT) { it.end = newEnd }
						!ev.untimed && newStart != ev.start -> changeOccurrence(this@DatePanel, d.occ, CHANGE_TEXT) {
							it.start = newStart
							it.end = newStart + (ev.end - ev.start)
						}
					}
					repaintDay()
				}

				override fun mouseClicked(e: MouseEvent) {
					if (suppressClick) {
						suppressClick = false
						return
					}
					if (e.button != MouseEvent.BUTTON1) return
					val hit = boxAt(width, e.point, header)
					if (hit != null) {
						val (occ, r) = hit
						val ev = occ.event
						selectedId = ev.id
						editBtn.isEnabled = true
						repaintDay()
						val marker = EventBox.markerAt(r, ev, e.point, header)
						when {
							e.clickCount == 2 && EventBox.onHandle(r, e.point) -> EventDialog.edit(this@DatePanel, ev, App.day)
							e.clickCount == 2 -> editTitle(occ, r)
							marker === EventBox.alarmIcon -> editAlarm(this@DatePanel, occ)
							marker === EventBox.noteIcon -> editNote(this@DatePanel, occ)
						}
					} else if (e.clickCount == 1) {
						// A click on empty space types a new event: an untimed row in the header, else the hour clicked.
						if (header) startTyping(0, true) else startTyping((minAt(e.y) / 60 * 60).coerceIn(0, 23 * 60), false)
					}
				}
			})
			addMouseMotionListener(object : MouseAdapter() {
				override fun mouseDragged(e: MouseEvent) {
					val d = drag ?: return
					if (!d.moved) {
						if (Math.max(Math.abs(e.x - d.origin.x), Math.abs(e.y - d.origin.y)) <= 4) return
						d.moved = true
					}
					updateDrag(d, e.point)
					repaint()
				}
			})
		}

		/** The menu of the box under a right click, or of the empty hour or header row. */
		private fun popupAt(e: MouseEvent) {
			val hit = boxAt(width, e.point, header)
			if (hit != null) {
				selectedId = hit.first.event.id
				editBtn.isEnabled = true
				repaintDay()
				eventMenu(this@DatePanel, hit.first).show(this, e.x, e.y)
			} else if (header) {
				slotMenu(this@DatePanel, App.day, null).show(this, e.x, e.y)
			} else {
				slotMenu(this@DatePanel, App.day, (minAt(e.y) / 60).coerceIn(0, 23) * 60).show(this, e.x, e.y)
			}
		}

		/** Sets the target of [d] for the mouse at [p] in this grid: a calendar day, or an outline in the grid. */
		private fun updateDrag(d: Drag, p: Point) {
			val ev = d.occ.event
			val cal = SwingUtilities.convertPoint(this, p, calendar)
			d.dropDay = if (!d.resize && calendar.contains(cal)) calendar.dayAt(cal) else null
			d.target = null
			if (d.dropDay != null || header) return
			if (d.resize) {
				d.newEnd = minOf(1440, maxOf(ev.start + 30, roundHalf(minAt(p.y))))
				val top = yOf(ev.start) + 2
				val bottom = maxOf(yOf(d.newEnd), yOf(ev.start) + HALF)
				d.target = Rectangle(d.box.x, top, d.box.width, bottom - top - 3)
			} else if (!ev.untimed) {
				val dur = ev.end - ev.start
				d.newStart = minOf(1440 - dur, maxOf(0, roundHalf(minAt(p.y - d.grab))))
				val top = yOf(d.newStart) + 2
				val bottom = maxOf(yOf(d.newStart + dur), yOf(d.newStart) + HALF)
				d.target = Rectangle(d.box.x, top, d.box.width, bottom - top - 3)
			}
		}

		/** Opens an in-place editor on the title of the box [occ] at [r]. Enter or focus loss saves it. */
		private fun editTitle(occ: Occ, r: Rectangle) {
			val f = JTextField(occ.event.title)
			f.font = Theme.font
			f.border = null
			f.isOpaque = false
			val x = EventBox.titleX(r, occ.event)
			val h = f.preferredSize.height
			val y = if (header) r.y + (r.height - h) / 2 else r.y + 1
			f.setBounds(x, y, maxOf(20, r.x + r.width - EventBox.BAR_W - x), h)
			f.addActionListener { endTitleEdit(true) }
			f.registerKeyboardAction({ endTitleEdit(false) }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_FOCUSED)
			f.addFocusListener(object : FocusAdapter() {
				override fun focusLost(e: FocusEvent) {
					if (!e.isTemporary) endTitleEdit(true)
				}
			})
			titleEdit = f
			titleOcc = occ
			add(f)
			f.selectAll()
			f.requestFocusInWindow()
			repaintDay()
		}

		// The header keeps SEL_H below its last row for the blue bar of a selected event.
		override fun getPreferredSize(): Dimension = Dimension(300, if (header) untimedH + EventBox.SEL_H else yOf(24 * 60) + 1)

		override fun paintComponent(g: Graphics) {
			super.paintComponent(g)
			g.color = Theme.panel
			g.fillRect(0, 0, TIME_W, height)
			g.font = Theme.font
			val fm = g.fontMetrics
			if (header) {
				g.color = Color.BLACK
				for (i in 0 until untimedH / UNTIMED_H) drawDiamond(g, TIME_W / 2, i * UNTIMED_H + UNTIMED_H / 2)
				g.color = Theme.grid
				g.drawLine(0, height - 1, width, height - 1)
			} else {
				for (h in 0 until 24) {
					val y = yOf(h * 60)
					g.color = Theme.grid
					g.drawLine(0, y, width, y)
					// The label and its tick sit in the middle of the hour, as in Palm Desktop.
					val mid = y + HALF
					val label = timeLabel(h * 60)
					g.color = Color.BLACK
					g.drawString(label, TIME_W - 10 - fm.stringWidth(label), mid + (fm.ascent - fm.descent) / 2)
					g.drawLine(TIME_W - 7, mid, TIME_W - 3, mid)
				}
				g.color = Theme.grid
				g.drawLine(0, yOf(24 * 60), width, yOf(24 * 60))
			}
			g.color = Theme.grid
			g.drawLine(TIME_W, 0, TIME_W, height)

			// The selected event last, so its blue bar is on top of its neighbors.
			for ((o, r) in layoutDay(width).sortedBy { it.first.event.id == selectedId }) {
				val ev = o.event
				if (ev.untimed != header) continue
				EventBox.paint(this, g, r, ev, ev.id == selectedId, header)
			}
			// The hour being typed into: a white box, drawn as selected. The text field is on top of it.
			if (typingUntimed == header) typingBox(width)?.let { EventBox.draw(g, it, Color.WHITE, true) }
			// The outline of a box being moved or resized.
			if (!header) drag?.target?.let {
				g.color = Color.BLACK
				g.drawRect(it.x, it.y, it.width - 1, it.height - 1)
			}
		}

		override fun doLayout() {
			val f = typing ?: return
			if (typingUntimed != header) return
			val r = typingBox(width) ?: return
			f.setBounds(r.x + 3, r.y + 1, r.width - EventBox.BAR_W - 4, f.preferredSize.height)
		}

		// The column header viewport takes its height from here, so the header returns its own size.
		override fun getPreferredScrollableViewportSize(): Dimension = if (header) preferredSize else Dimension(300, 400)
		override fun getScrollableUnitIncrement(visible: Rectangle, orientation: Int, direction: Int): Int = 10
		override fun getScrollableBlockIncrement(visible: Rectangle, orientation: Int, direction: Int): Int = visible.height
		override fun getScrollableTracksViewportWidth(): Boolean = true
		override fun getScrollableTracksViewportHeight(): Boolean = false
	}

	/** The month calendar in the right pane. Shows the month of App.day. */
	private inner class MiniCalendar : JPanel() {
		init {
			preferredSize = Dimension(231, 126)
			background = Color.WHITE
			addMouseListener(object : MouseAdapter() {
				override fun mouseClicked(e: MouseEvent) {
					val cw = width / 7
					val ch = height / 7
					if (cw == 0 || ch == 0) return
					val row = e.y / ch - 1
					if (row < 0) return
					val col = (e.x / cw).coerceAtMost(6)
					val start = gridStart()
					App.day = start.plusDays((row * 7 + col).toLong()).toEpochDay()
					App.refresh()
				}
			})
		}

		/** The epoch day of the cell at [p] in calendar coordinates, or null outside the day cells. Same math as the click. */
		fun dayAt(p: Point): Long? {
			val cw = width / 7
			val ch = height / 7
			if (cw == 0 || ch == 0) return null
			val row = p.y / ch - 1
			if (row < 0 || row > 5) return null
			val col = (p.x / cw).coerceAtMost(6)
			return gridStart().plusDays((row * 7 + col).toLong()).toEpochDay()
		}

		/** The first day of the week that opens the first row of the month shown. */
		private fun gridStart(): LocalDate {
			val first = LocalDate.ofEpochDay(App.day).withDayOfMonth(1)
			return first.minusDays(((first.dayOfWeek.value % 7 - DateSettings.weekBegins + 7) % 7).toLong())
		}

		override fun paintComponent(g: Graphics) {
			super.paintComponent(g)
			val cw = width / 7
			val ch = height / 7
			val sel = LocalDate.ofEpochDay(App.day)
			val today = LocalDate.now()
			for (c in 0 until 7) {
				drawCentered(g, Fmt.dowLetter[dowIndex(c)], c * cw, 0, cw, ch, Theme.bold, Color.BLACK)
			}
			val start = gridStart()
			for (i in 0 until 42) {
				val d = start.plusDays(i.toLong())
				val x = (i % 7) * cw
				val y = (i / 7 + 1) * ch
				val isSel = d == sel
				val isToday = d == today
				var color = if (d.month == sel.month) Color.BLACK else Color.GRAY
				if (isToday) color = Theme.dateRed
				if (isSel) {
					g.color = Theme.select
					g.fillRect(x, y, cw, ch)
					color = if (isToday) Theme.dateRed else Color.WHITE
				}
				drawCentered(g, d.dayOfMonth.toString(), x, y, cw, ch, Theme.font, color)
			}
		}
	}

	/** A vertical tab on the right edge, with its caption turned clockwise. */
	/** A tab on the right edge with its caption turned clockwise and [key] underlined (Alt+key selects it). */
	private class VerticalTab(private val caption: String, key: Char) : JToggleButton() {
		private val keyIndex = caption.indexOf(key)

		init {
			isFocusPainted = false
			font = Theme.font
			mnemonic = key.uppercaseChar().code
		}

		override fun getPreferredSize(): Dimension = Dimension(24, 58)

		override fun paintComponent(g: Graphics) {
			val g2 = g.create() as Graphics2D
			g2.color = if (isSelected) Color.WHITE else Theme.panel
			g2.fillRect(0, 0, width, height)
			g2.color = Theme.grid
			g2.drawRect(0, 0, width - 1, height - 1)
			g2.font = Theme.font
			val fm = g2.fontMetrics
			g2.color = Color.BLACK
			g2.translate(width / 2, height / 2)
			g2.rotate(Math.PI / 2)
			val x = -fm.stringWidth(caption) / 2
			val y = (fm.ascent - fm.descent) / 2
			g2.drawString(caption, x, y)
			if (keyIndex >= 0) {
				val ux = x + fm.stringWidth(caption.substring(0, keyIndex))
				g2.drawLine(ux, y + 1, ux + fm.charWidth(caption[keyIndex]) - 1, y + 1)
			}
			g2.dispose()
		}
	}
}
