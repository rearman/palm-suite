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
import java.awt.Paint
import java.awt.Point
import java.awt.Rectangle
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
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JToggleButton
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
		const val BAR_W = 9          // width of the gray bar on the right edge of an event
		const val SEL_H = 4          // height of the blue bar under a selected event
		const val PANE_W = 240       // width of the right pane
	}

	private val cards = CardLayout()
	private val cardPanel = JPanel(cards)
	private val weekView = WeekView()
	private val monthView = MonthView()
	private val titleLabel = JLabel("", SwingConstants.CENTER)
	private val grid = DayGrid(false)
	private val dayHeader = DayGrid(true)
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
	private val repeatIcon: Icon by lazy { Icons.repeat() }
	private val alarmIcon: Icon by lazy { Icons.alarm() }
	private val noteIcon: Icon by lazy { Icons.note() }
	private val privateIcon: Icon by lazy { Icons.privateRecord() }

	/**
	 * A To Do row as Palm Desktop shows it, after the Show Options: check box, priority, description and
	 * a due date column. A selected row highlights the description only.
	 */
	private val todoRenderer = object : JPanel(BorderLayout(2, 0)), ListCellRenderer<Todo> {
		val check = JCheckBox()   // the list mouse listener reads its width to find a click on it
		private val pri = JLabel()
		private val title = JLabel()
		private val due = JLabel()

		init {
			background = Color.WHITE
			val lead = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0))
			lead.isOpaque = false
			check.isOpaque = false
			lead.add(check)
			lead.add(pri)
			for (l in listOf(pri, title, due)) l.font = Theme.font
			title.isOpaque = true
			due.preferredSize = Dimension(45, 0)
			add(lead, BorderLayout.WEST)
			add(title, BorderLayout.CENTER)
			add(due, BorderLayout.EAST)
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
			title.foreground = if (isSelected) Color.YELLOW else Color.BLACK
			due.isVisible = Settings.getBool("todoShowDue", true)
			due.text = if (t.due < 0) "" else dueLabel(t)
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
	}

	/** Shows the Day, Week or Month view and remembers the choice. */
	fun showMode(mode: String) {
		val name = when (mode) {
			"week", "month" -> mode
			else -> "day"
		}
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
		val o = selectedOcc() ?: return
		EventDialog.edit(this, o.event, App.day)
	}

	override fun deleteSelected() {
		val ev = selectedOcc()?.event ?: return
		if (ev.repeats) {
			val choice = JOptionPane.showOptionDialog(
				this, "Delete only this one (Current) or All?", "Delete",
				JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null,
				arrayOf("Current", "All", "Cancel"), "Current"
			)
			when (choice) {
				0 -> {
					ev.exceptions.add(App.day)
					App.store.save(ev)
				}
				1 -> App.store.delete(ev)
				else -> return
			}
		} else {
			if (!confirmDelete(this, "Delete the event?")) return
			App.store.delete(ev)
		}
		selectedId = null
		App.refresh()
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
		val scroll = JScrollPane(grid)
		scroll.setColumnHeaderView(dayHeader)   // the untimed rows stay in view, as in Palm Desktop
		scroll.verticalScrollBar.unitIncrement = 20
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
		val bar = JPanel(FlowLayout(FlowLayout.RIGHT))
		bar.add(button("Today") {
			App.day = LocalDate.now().toEpochDay()
			App.refresh()
		}.apply { mnemonic = KeyEvent.VK_T })
		bar.add(editBtn)
		bar.add(button("New...") { newRecord() }.apply { mnemonic = KeyEvent.VK_N })
		val panel = JPanel(BorderLayout())
		panel.add(titleLabel, BorderLayout.NORTH)
		panel.add(scroll, BorderLayout.CENTER)
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
		for ((tab, mode) in listOf(dayTab to "day", weekTab to "week", monthTab to "month")) {
			tab.addActionListener { showMode(mode) }
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
		val day = LocalDate.ofEpochDay(App.day)
		titleLabel.text = dateTitle(App.day)
		occs = dayOccs()
		untimedH = maxOf(1, occs.count { it.event.untimed }) * UNTIMED_H
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

	// Day grid geometry: HALF px per half hour. The untimed rows are in a fixed header above the grid.

	/** Pixel row on the grid of a time in minutes after midnight. */
	private fun yOf(min: Int): Int = min * HALF / 30

	/** Minutes after midnight at a pixel row of the grid (inverse of yOf). */
	private fun minAt(y: Int): Int = y * 30 / HALF

	private fun repaintDay() {
		dayHeader.repaint()
		grid.repaint()
	}

	/**
	 * Draws an event box as Palm Desktop does: [fill] with a black outline and a gray bar on the right edge.
	 * A [selected] box has a darker bar and a blue bar under it.
	 */
	private fun drawBox(g: Graphics, r: Rectangle, fill: Paint, selected: Boolean) {
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

	private fun overlaps(a: Event, b: Event): Boolean = a.start < b.end && b.start < a.end

	/** Marker icons of an event: repeat, alarm, note, private. */
	private fun markers(e: Event): List<Icon> {
		val m = ArrayList<Icon>()
		if (e.repeats) m.add(repeatIcon)
		if (e.alarm) m.add(alarmIcon)
		if (e.note.isNotEmpty()) m.add(noteIcon)
		if (e.isPrivate) m.add(privateIcon)
		return m
	}

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
			addMouseListener(object : MouseAdapter() {
				override fun mouseClicked(e: MouseEvent) {
					val hit = layoutDay(width).firstOrNull { it.first.event.untimed == header && it.second.contains(e.point) }
					if (hit != null) {
						selectedId = hit.first.event.id
						editBtn.isEnabled = true
						repaintDay()
						if (e.clickCount == 2) EventDialog.edit(this@DatePanel, hit.first.event, App.day)
					} else if (!header && e.x >= TIME_W) {
						selectedId = null
						editBtn.isEnabled = false
						repaintDay()
						if (e.clickCount == 2) {
							val min = (minAt(e.y) / 30 * 30).coerceIn(0, 23 * 60 + 30)
							EventDialog.edit(this@DatePanel, null, App.day, min)
						}
					}
				}
			})
		}

		// The header keeps SEL_H below its last row for the blue bar of a selected event.
		override fun getPreferredSize(): Dimension = Dimension(300, if (header) untimedH + SEL_H else yOf(24 * 60) + 1)

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
					val label = Fmt.timeShort(h * 60)
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
				drawBox(g, r, Theme.eventFill, ev.id == selectedId)
				val g2 = g.create() as Graphics2D
				g2.clipRect(r.x, r.y, r.width - BAR_W, r.height)   // the title stops at the bar
				var x = r.x + 3
				for (icon in markers(ev)) {
					val iy = if (header) r.y + (r.height - icon.iconHeight) / 2 else r.y + 2
					icon.paintIcon(this, g2, x, iy)
					x += icon.iconWidth + 2
				}
				val ty = if (header) r.y + (r.height + fm.ascent - fm.descent) / 2 else r.y + fm.ascent + 1
				g2.color = Color.BLACK
				g2.font = Theme.font
				g2.drawString(ev.title, x, ty)
				g2.dispose()
			}
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

		/** Sunday that opens the first row of the month shown. */
		private fun gridStart(): LocalDate {
			val first = LocalDate.ofEpochDay(App.day).withDayOfMonth(1)
			return first.minusDays((first.dayOfWeek.value % 7).toLong())
		}

		override fun paintComponent(g: Graphics) {
			super.paintComponent(g)
			val cw = width / 7
			val ch = height / 7
			val sel = LocalDate.ofEpochDay(App.day)
			val today = LocalDate.now()
			for (c in 0 until 7) {
				drawCentered(g, Fmt.dowLetter[c], c * cw, 0, cw, ch, Theme.bold, Color.BLACK)
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
