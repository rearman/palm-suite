package com.rearman.palmdesktop

import com.rearman.datebook.Address
import com.rearman.datebook.Category
import com.rearman.datebook.Event
import com.rearman.datebook.Fmt
import com.rearman.datebook.Occ
import com.rearman.datebook.Todo
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.Insets
import java.awt.Point
import java.awt.Rectangle
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDropEvent
import java.awt.dnd.DropTargetEvent
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
import javax.swing.TransferHandler
import javax.swing.border.AbstractBorder
import javax.swing.border.EmptyBorder
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.plaf.basic.BasicGraphicsUtils

/**
 * The Date view: the Day grid, the right pane (calendar, then the Address or To Do list) and the view tabs.
 * The Week and Month views share the right pane and the tabs.
 */
class DatePanel : JPanel(BorderLayout()), AppView {

	private companion object {
		const val TIME_W = 52        // width of the time column
		const val UNTIMED_H = 18     // height of one untimed row
		const val HALF = 19          // pixels per half hour (Palm Desktop: 38 px per hour)
		val DRAG_NAVY = Color(0, 0, 128)   // the outline of a moved or dragged event, as in Palm Desktop
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
	private val catCombo = JComboBox<CatChoice>()
	// The right pane shows the To Do list or the Address list, as Settings "datePane" says.
	private val addressModel = DefaultListModel<Address>()
	private val addressList = object : JList<Address>(addressModel) {
		override fun getScrollableTracksViewportWidth(): Boolean = true
	}
	private val listCards = CardLayout()
	private val listPanel = JPanel(listCards)
	private val lookRow = JPanel(BorderLayout(4, 0))
	private val lookUp = JTextField()
	private val addressSeg = segment("Address", "address")
	private val todoSeg = segment("To Do", "todo")
	private val rightPane = JPanel(BorderLayout())
	private val tabGroup = ButtonGroup()
	// The keys of Palm Desktop: Alt+D, Alt+W and Alt+M. The launcher buttons use F2, F4 and F5.
	private val dayTab = VerticalTab("Day", 'D')
	private val weekTab = VerticalTab("Week", 'W')
	private val monthTab = VerticalTab("Month", 'M')
	private lateinit var tabStrip: JPanel   // holds the tabs; set by buildTabs
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
			check.icon = Theme.plainCheck
			check.border = EmptyBorder(0, 2, 0, 0)
			lead.add(check)
			lead.add(pri)
			for (l in listOf(pri, title, due, noteMark, privateMark)) l.font = Theme.font
			title.isOpaque = true
			due.preferredSize = Dimension(45, due.getFontMetrics(Theme.font).height)
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
			// A selected item is yellow when it is overdue (red), white otherwise, as in Palm Desktop.
			title.foreground = when {
				isSelected && isOverdue(t) -> Color.YELLOW
				isSelected -> Color.WHITE
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
		// The views and the calendar pane sit on a raised panel over the workspace gray, and the tabs
		// sit on the gray beside it, as in Palm Desktop.
		val body = JPanel(BorderLayout())
		body.border = BorderFactory.createCompoundBorder(
			OpenRightBevel,
			EmptyBorder(6, 6, 6, 6)
		)
		body.add(cardPanel, BorderLayout.CENTER)
		body.add(buildRightPane(), BorderLayout.EAST)
		background = Theme.workspace
		border = EmptyBorder(4, 4, 3, 3)
		add(body, BorderLayout.CENTER)
		add(buildTabs(), BorderLayout.EAST)
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
		this.mode = name
		cards.show(cardPanel, name)
		dayTab.isSelected = name == "day"
		weekTab.isSelected = name == "week"
		monthTab.isSelected = name == "month"
		val sel = when (name) {
			"week" -> weekTab
			"month" -> monthTab
			else -> dayTab
		}
		tabStrip.setComponentZOrder(sel, 0)   // index 0 paints on top, so the selected tab joins the body
		tabStrip.revalidate()
		tabStrip.repaint()
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
		titleLabel.font = Theme.title
		titleLabel.foreground = Theme.dateRed
		titleLabel.isOpaque = true
		titleLabel.background = Color.WHITE
		titleLabel.border = BorderFactory.createCompoundBorder(
			BorderFactory.createLoweredBevelBorder(), BorderFactory.createEmptyBorder(1, 0, 1, 0)
		)
		dayScroll.setColumnHeaderView(dayHeader)   // the untimed rows stay in view, as in Palm Desktop
		dayScroll.verticalScrollBar.unitIncrement = 20
		dayScroll.border = BorderFactory.createLineBorder(Color.BLACK)
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
		val bar = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 5))
		bar.add(button("Today") {
			App.day = LocalDate.now().toEpochDay()
			App.refresh()
		}.apply { mnemonic = KeyEvent.VK_O })
		bar.add(editBtn)
		bar.add(button("New...") { newRecord() }.apply { mnemonic = KeyEvent.VK_N })
		val panel = JPanel(BorderLayout())
		val titleRow = JPanel(BorderLayout())
		titleRow.border = EmptyBorder(0, 0, 4, 0)   // a 4 px gap between the title and the grid
		titleRow.add(titleLabel, BorderLayout.CENTER)
		panel.add(titleRow, BorderLayout.NORTH)
		panel.add(dayScroll, BorderLayout.CENTER)
		panel.add(bar, BorderLayout.SOUTH)
		return panel
	}

	private fun buildRightPane(): JPanel {
		rightPane.preferredSize = Dimension(PANE_W, 0)
		val top = JPanel()
		top.layout = BoxLayout(top, BoxLayout.Y_AXIS)

		yearLabel.font = Theme.bold
		yearLabel.horizontalAlignment = SwingConstants.CENTER
		yearLabel.isOpaque = true
		yearLabel.background = Color.WHITE
		yearLabel.border = BorderFactory.createLoweredBevelBorder()
		val yearRow = JPanel(BorderLayout(4, 0))
		yearRow.border = EmptyBorder(2, 0, 4, 0)
		yearRow.add(arrowButton(SwingConstants.WEST) { setYear(-1) }, BorderLayout.WEST)
		yearRow.add(yearLabel, BorderLayout.CENTER)
		yearRow.add(arrowButton(SwingConstants.EAST) { setYear(1) }, BorderLayout.EAST)
		yearRow.maximumSize = Dimension(Int.MAX_VALUE, 26)

		val months = JPanel(GridLayout(2, 6))
		months.border = BorderFactory.createMatteBorder(1, 1, 0, 0, Color.BLACK)
		months.maximumSize = Dimension(Int.MAX_VALUE, 44)
		for ((i, b) in monthButtons.withIndex()) {
			b.font = Theme.font
			b.margin = Insets(0, 0, 0, 0)
			b.isFocusPainted = false
			b.isOpaque = true
			b.isContentAreaFilled = true
			b.border = BorderFactory.createMatteBorder(0, 0, 1, 1, Color.BLACK)
			b.addActionListener { setMonth(i + 1) }
			months.add(b)
		}

		val calendarPart = JPanel(BorderLayout())
		calendarPart.border = BorderFactory.createCompoundBorder(
			EmptyBorder(4, 0, 4, 0), BorderFactory.createLoweredBevelBorder()
		)
		calendarPart.add(calendar, BorderLayout.CENTER)
		calendarPart.maximumSize = Dimension(Int.MAX_VALUE, 126 + 8 + 4)
		top.add(yearRow)
		top.add(months)
		top.add(calendarPart)

		// The two-segment switch of Palm Desktop: 113 by 21 px, the selected segment in the highlight color.
		val switchBox = JPanel(GridLayout(1, 2))
		switchBox.border = BorderFactory.createLineBorder(Color.BLACK)
		switchBox.preferredSize = Dimension(113, 21)
		switchBox.add(addressSeg)
		switchBox.add(todoSeg)
		val switchRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
		switchRow.add(switchBox)
		catCombo.font = Theme.font
		catCombo.addActionListener {
			if (!updating) {
				val choice = catCombo.selectedItem as? CatChoice ?: return@addActionListener
				if (addressMode()) {
					// The category is shared with the Address view.
					Settings.setInt("addressCategory", choice.id)
					App.refresh()
				} else {
					catId = shownCats.getOrNull(catCombo.selectedIndex - 1)?.id
					refreshAll()
				}
			}
		}
		val head = JPanel(BorderLayout(4, 0))
		head.add(switchRow, BorderLayout.WEST)
		head.add(catCombo, BorderLayout.CENTER)

		todoList.font = Theme.font
		todoList.fixedCellHeight = 16
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
		// A To Do item or an address dragged to the Day grid becomes an event, as in Palm Desktop.
		todoList.dragEnabled = true
		todoList.transferHandler = TitleExport { todoList.selectedValue?.title }
		addressList.font = Theme.font
		addressList.fixedCellHeight = 16
		addressList.cellRenderer = AddressRowRenderer()
		addressList.addMouseListener(object : MouseAdapter() {
			override fun mouseClicked(e: MouseEvent) {
				if (e.clickCount != 2) return
				val idx = addressList.locationToIndex(e.point)
				if (idx < 0 || addressList.getCellBounds(idx, idx)?.contains(e.point) != true) return
				AddressDialog.edit(this@DatePanel, addressModel.getElementAt(idx))
			}
		})
		addressList.dragEnabled = true
		addressList.transferHandler = TitleExport { addressList.selectedValue?.let { eventTitle(it) } }
		// The Look up field: typing selects the first address whose list name starts with the text.
		lookUp.font = Theme.font
		lookUp.preferredSize = Dimension(10, 20)
		lookUp.document.addDocumentListener(object : DocumentListener {
			override fun insertUpdate(e: DocumentEvent) = seekAddress()
			override fun removeUpdate(e: DocumentEvent) = seekAddress()
			override fun changedUpdate(e: DocumentEvent) = seekAddress()
		})
		val lookLabel = JLabel("Look up:")
		lookLabel.font = Theme.font
		lookLabel.preferredSize = Dimension(44, 20)
		lookRow.add(lookLabel, BorderLayout.WEST)
		lookRow.add(lookUp, BorderLayout.CENTER)

		listPanel.add(JScrollPane(todoList).apply { border = BorderFactory.createLoweredBevelBorder() }, "todo")
		listPanel.add(JScrollPane(addressList).apply { border = BorderFactory.createLoweredBevelBorder() }, "address")
		val listPart = JPanel(BorderLayout())
		listPart.add(head, BorderLayout.NORTH)
		listPart.add(listPanel, BorderLayout.CENTER)
		listPart.add(lookRow, BorderLayout.SOUTH)

		rightPane.add(top, BorderLayout.NORTH)
		rightPane.add(listPart, BorderLayout.CENTER)
		return rightPane
	}

	/** One segment of the Address | To Do switch. A click shows its list in the right pane. */
	private fun segment(text: String, pane: String): JLabel = JLabel(text, SwingConstants.CENTER).apply {
		font = Theme.font
		isOpaque = true
		addMouseListener(object : MouseAdapter() {
			override fun mouseClicked(e: MouseEvent) {
				if (e.button != MouseEvent.BUTTON1) return
				Settings.set("datePane", pane)
				App.refresh()
			}
		})
	}

	private fun buildTabs(): JPanel {
		// The tabs overlap, so the strip paints them in its own order and draws the body edge they join.
		tabStrip = object : JPanel(null) {
			init {
				isOpaque = true
				background = Theme.workspace
				preferredSize = Dimension(24, 0)
			}

			override fun isOptimizedDrawingEnabled(): Boolean = false

			override fun paintComponent(g: Graphics) {
				super.paintComponent(g)
				val g2 = g.create() as Graphics2D
				// The right edge of the body: white at the top, shadow gray, black at the bottom.
				g2.color = Color.WHITE
				g2.fillRect(0, 0, 1, 1)
				g2.color = Theme.shadow
				g2.fillRect(0, 1, 1, height - 2)
				g2.color = Color.BLACK
				g2.fillRect(0, height - 1, 1, 1)
				g2.fillRect(1, 0, 1, height)
				g2.dispose()
			}

			// The selected tab is 4 px longer and starts 2 px higher, so it covers the body edge.
			override fun doLayout() {
				var y = 2
				for (tab in listOf(dayTab, weekTab, monthTab)) {
					val len = tab.length()
					if (tab.isSelected) tab.setBounds(0, y - 2, 24, len + 4) else tab.setBounds(1, y, 21, len)
					y += len
				}
			}
		}
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
			tabStrip.add(tab)
		}
		return tabStrip
	}

	// Data and state

	/** Sets the header height: a row for each untimed event, and one more for an open untimed field. */
	private fun updateUntimedH() {
		val extra = if (typing != null && typingUntimed) 1 else 0
		untimedH = maxOf(1, occs.count { it.event.untimed } + extra) * UNTIMED_H
	}

	/** Rebuilds every part of the panel from the store and App.day. */
	private fun refreshAll() {
		if (App.day != typingDay) endTyping(true)   // the field belongs to the day it was opened on
		endTitleEdit(true)
		val day = LocalDate.ofEpochDay(App.day)
		titleLabel.text = dateTitle(App.day)
		occs = dayOccs()
		updateUntimedH()
		if (selectedOcc() == null) selectedId = null
		editBtn.isEnabled = selectedOcc() != null
		yearLabel.text = day.year.toString()
		for ((i, b) in monthButtons.withIndex()) styleMonth(b, i == day.monthValue - 1)
		rebuildCategories()
		rebuildTodos()
		rebuildAddresses()
		showListPane()
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
		b.background = if (on) Theme.select else Color.WHITE
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

	/** True when the right pane shows the Address list (Settings "datePane"). */
	private fun addressMode(): Boolean = Settings.get("datePane", "todo") == "address"

	/** The category drop-down: the To Do categories, or in Address mode the Address categories and Unfiled. */
	private fun rebuildCategories() {
		updating = true
		try {
			if (addressMode()) {
				val items = listOf(CatChoice(-1, "All")) + App.store.categoriesFor(2).map { CatChoice(it.id, it.name) } +
					CatChoice(0, "Unfiled")
				catCombo.model = CategoryModel(items, CatChoice(-2, EDIT_CATEGORIES)) { CategoriesDialog.show(this, 2) }
				val id = Settings.getInt("addressCategory", -1)
				catCombo.selectedItem = items.firstOrNull { it.id == id } ?: items[0]
			} else {
				shownCats = App.store.categories.toList()
				val items = listOf(CatChoice(-1, "All")) + shownCats.map { CatChoice(it.id, it.name) }
				catCombo.model = CategoryModel(items, CatChoice(-2, EDIT_CATEGORIES)) { CategoriesDialog.show(this, 0) }
				val idx = shownCats.indexOfFirst { it.id == catId }
				if (idx < 0) catId = null
				catCombo.selectedIndex = if (idx < 0) 0 else idx + 1
			}
		} finally {
			updating = false
		}
	}

	/** Shows the list of the pane mode, its switch segment, and the Look up row in Address mode. */
	private fun showListPane() {
		val addr = addressMode()
		listCards.show(listPanel, if (addr) "address" else "todo")
		lookRow.isVisible = addr
		styleSegment(addressSeg, addr)
		styleSegment(todoSeg, !addr)
	}

	private fun styleSegment(label: JLabel, on: Boolean) {
		label.background = if (on) Theme.select else Color.WHITE
		label.foreground = if (on) Color.WHITE else Color.BLACK
	}

	/** Rebuilds the Address list from visibleAddresses(), keeping the selected address by id. */
	private fun rebuildAddresses() {
		val keep = addressList.selectedValue?.id
		addressModel.clear()
		visibleAddresses().forEach { addressModel.addElement(it) }
		addressList.selectedIndex = (0 until addressModel.size()).indexOfFirst { addressModel.getElementAt(it).id == keep }
	}

	/** Selects the first address whose list name starts with the typed text, ignoring case, and scrolls to it. */
	private fun seekAddress() {
		val text = lookUp.text
		if (text.isEmpty()) return
		val byCompany = Settings.getInt("addressListBy", 0) == 1
		val idx = (0 until addressModel.size()).indexOfFirst {
			addressModel.getElementAt(it).listName(byCompany).startsWith(text, ignoreCase = true)
		}
		if (idx < 0) return
		addressList.selectedIndex = idx
		addressList.ensureIndexIsVisible(idx)
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

	/** Start of an event dropped at pixel row [y]: the half hour under the pointer. The event lasts one hour. */
	private fun dropMin(y: Int): Int = (minAt(y) / 30 * 30).coerceIn(0, 23 * 60)

	private fun repaintDay() {
		dayHeader.repaint()
		grid.repaint()
	}

	// Typing a new event into an empty hour of the grid, as in Palm Desktop.

	/** Box of a one-hour event from [min] in a grid [width] in pixels. */
	private fun hourBox(min: Int, width: Int): Rectangle {
		val top = yOf(min)
		return Rectangle(TIME_W + 2, top + 2, width - TIME_W - 4, yOf(min + 60) - top - 3)
	}

	/** The outline of a dragged event from [start] to [end] minutes in a grid [width] in pixels, as Palm Desktop draws it. At least a half hour tall. */
	private fun outlineBox(start: Int, end: Int, width: Int): Rectangle {
		val top = yOf(start)
		return Rectangle(TIME_W + 3, top + 1, width - TIME_W - 5, maxOf(yOf(end), top + HALF) - top - 2)
	}

	/** Draws [r] as Palm Desktop draws a drag outline: navy, 3 px thick, inside [r]. */
	private fun drawOutline(g: Graphics, r: Rectangle) {
		g.color = DRAG_NAVY
		for (i in 0 until 3) g.drawRect(r.x + i, r.y + i, r.width - 1 - 2 * i, r.height - 1 - 2 * i)
	}

	/** Box of the row below the last untimed event in a header [width] in pixels. */
	private fun untimedBox(width: Int): Rectangle =
		Rectangle(TIME_W + 2, occs.count { it.event.untimed } * UNTIMED_H + 1, width - TIME_W - 4, UNTIMED_H - 2)

	/** Box of the hour being typed into, for a grid [width] in pixels, or null when no field is open. */
	private fun typingBox(width: Int): Rectangle? {
		if (typing == null) return null
		return if (typingUntimed) untimedBox(width) else hourBox(typingMin, width)
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
		updateUntimedH()   // the header shrinks back
		dayHeader.revalidate()
		repaintDay()
		val title = f.text.trim()
		if (!save || title.isEmpty()) return
		addEvent(title, typingDay, typingMin, typingUntimed)
	}

	/** Saves a new event titled [title] on [day]: untimed, or one hour from [min]. It becomes the selected event. */
	private fun addEvent(title: String, day: Long, min: Int, untimed: Boolean) {
		val ev = if (untimed) {
			Event(title = title, date = day, untimed = true)
		} else {
			Event(title = title, date = day, start = min, end = min + 60, untimed = false)
		}
		applyAlarmPreset(ev)
		App.store.save(ev)
		selectedId = ev.id
		App.refresh()
	}

	/** Gives the selected row of a right pane list as the title of a new event. */
	private class TitleExport(private val title: () -> String?) : TransferHandler() {
		override fun getSourceActions(c: JComponent): Int = COPY
		override fun createTransferable(c: JComponent): Transferable? = title()?.let { TitleTransfer(it) }
	}

	/** A dragged event title. Its flavor is local to this app, so text from other programs is not dropped. */
	private class TitleTransfer(private val title: String) : Transferable {
		companion object {
			val FLAVOR = DataFlavor(DataFlavor.javaJVMLocalObjectMimeType + ";class=java.lang.String")
		}
		override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(FLAVOR)
		override fun isDataFlavorSupported(f: DataFlavor): Boolean = f == FLAVOR
		override fun getTransferData(f: DataFlavor): Any = title
	}

	/**
	 * Takes a dragged title: an untimed event on the [header], else a one-hour event from the half hour under the drop.
	 * While the drag hovers, the grid shows the outline of that event.
	 */
	private inner class DayDrop(private val header: Boolean) : TransferHandler() {
		override fun canImport(s: TransferHandler.TransferSupport): Boolean {
			val ok = s.isDrop && s.isDataFlavorSupported(TitleTransfer.FLAVOR)
			(s.component as? DayGrid)?.showDrop(if (ok) s.dropLocation.dropPoint.y else null)
			return ok
		}

		override fun importData(s: TransferHandler.TransferSupport): Boolean {
			if (!canImport(s)) return false
			val title = (s.transferable.getTransferData(TitleTransfer.FLAVOR) as String).trim()
			if (title.isEmpty()) return false
			val y = s.dropLocation.dropPoint.y
			(s.component as? DayGrid)?.showDrop(null)
			endTyping(true)
			endTitleEdit(true)
			addEvent(title, App.day, dropMin(y), header)
			return true
		}
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
		private var dropBox: Rectangle? = null   // the outline of a dragged title over this grid

		init {
			isOpaque = true
			background = Color.WHITE
			layout = null   // doLayout places the typing field and the title editor
			transferHandler = DayDrop(header)
			// The outline goes when the drag leaves or drops.
			dropTarget.addDropTargetListener(object : DropTargetAdapter() {
				override fun dragExit(e: DropTargetEvent) = showDrop(null)
				override fun drop(e: DropTargetDropEvent) = showDrop(null)
			})
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
						val sel = selBox()
						if (sel != null && EventBox.onResizeBar(sel, e.point)) {
							drag = Drag(selectedOcc() ?: return, sel, true, 0, e.point)
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
					if (!header) cursor = EventBox.cursorAt(selBox(), e.point)
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
					if (!header) cursor = EventBox.cursorAt(selBox(), e.point)
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
				override fun mouseMoved(e: MouseEvent) {
					if (!header) cursor = EventBox.cursorAt(selBox(), e.point)
				}

				override fun mouseDragged(e: MouseEvent) {
					val d = drag ?: return
					if (d.resize) cursor = Cursor.getPredefinedCursor(Cursor.S_RESIZE_CURSOR)
					if (!d.moved) {
						if (Math.max(Math.abs(e.x - d.origin.x), Math.abs(e.y - d.origin.y)) <= 4) return
						d.moved = true
					}
					updateDrag(d, e.point)
					repaint()
				}
			})
		}

		/** The box of the selected timed event in this grid, or null. */
		private fun selBox(): Rectangle? = layoutDay(width).firstOrNull { it.first.event.id == selectedId && !it.first.event.untimed }?.second

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
				d.target = outlineBox(ev.start, d.newEnd, width)
			} else if (!ev.untimed) {
				val dur = ev.end - ev.start
				d.newStart = minOf(1440 - dur, maxOf(0, roundHalf(minAt(p.y - d.grab))))
				d.target = outlineBox(d.newStart, d.newStart + dur, width)
			}
		}

		/** Opens an in-place editor on the title of the box [occ] at [r]. Enter or focus loss saves it. */
		private fun editTitle(occ: Occ, r: Rectangle) {
			val f = JTextField(occ.event.title)
			f.font = Theme.view
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

		/** Shows the outline of the event a drop at pixel row [y] makes, or hides it when [y] is null. */
		fun showDrop(y: Int?) {
			val box = when {
				y == null || header -> null   // no outline over the untimed header, as in Palm Desktop
				else -> outlineBox(dropMin(y), dropMin(y) + 60, width)
			}
			if (box == dropBox) return
			dropBox = box
			repaint()
		}

		// The header keeps SEL_H below its last row for the blue bar of a selected event.
		override fun getPreferredSize(): Dimension = Dimension(300, if (header) untimedH + EventBox.SEL_H else yOf(24 * 60) + 1)

		override fun paintComponent(g: Graphics) {
			super.paintComponent(g)
			g.font = Theme.time
			val fm = g.fontMetrics
			if (header) {
				// Each untimed row is a raised cell with its diamond. The last cell also takes the height below
				// the rows, so one bevel ends the column. A single black line parts the rows from the hours.
				val rows = untimedH / UNTIMED_H
				for (i in 0 until rows) {
					val cellH = if (i == rows - 1) height - i * UNTIMED_H else UNTIMED_H
					raisedCell(g, 0, i * UNTIMED_H, TIME_W, cellH)
					g.color = Color.BLACK
					drawDiamond(g, TIME_W / 2, i * UNTIMED_H + UNTIMED_H / 2)
				}
				g.color = Color.BLACK
				g.drawLine(TIME_W, height - 1, width, height - 1)
			} else {
				for (h in 0 until 24) {
					val y = yOf(h * 60)
					raisedCell(g, 0, y, TIME_W, yOf((h + 1) * 60) - y)
					g.color = Theme.hourLine
					g.drawLine(TIME_W, y, width, y)
					// The label and its tick sit in the middle of the hour, as in Palm Desktop.
					val mid = y + HALF
					val label = timeLabel(h * 60)
					g.color = Color.BLACK
					g.drawString(label, TIME_W - 10 - fm.stringWidth(label), mid + (fm.ascent - fm.descent) / 2)
					g.drawLine(TIME_W - 7, mid, TIME_W - 3, mid)
				}
				g.color = Theme.hourLine
				g.drawLine(TIME_W, yOf(24 * 60), width, yOf(24 * 60))
			}
			g.color = Color.BLACK
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
			if (!header) drag?.target?.let { drawOutline(g, it) }
			// The outline of the event a dragged address or to-do makes.
			dropBox?.let { drawOutline(g, it) }
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
			g.color = Theme.panel
			g.fillRect(0, 0, width, ch)
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
				drawCentered(g, d.dayOfMonth.toString(), x, y, cw, ch, if (isToday) Theme.bold else Theme.font, color)
			}
		}
	}

	/** An item of a category drop-down: the category id and its name. */
	private class CatChoice(val id: Int, private val label: String) {
		override fun toString(): String = label
	}

	/**
	 * The raised bevel of the body without its right side. The tab strip draws that edge, so the
	 * selected tab can join the body.
	 */
	private object OpenRightBevel : AbstractBorder() {
		override fun getBorderInsets(c: Component, insets: Insets): Insets {
			insets.set(2, 2, 2, 0)
			return insets
		}

		override fun getBorderInsets(c: Component): Insets = Insets(2, 2, 2, 0)

		override fun paintBorder(c: Component, g: Graphics, x: Int, y: Int, w: Int, h: Int) {
			val g2 = g.create() as Graphics2D
			g2.color = Color.WHITE
			g2.drawLine(x, y, x + w - 1, y)
			g2.drawLine(x, y, x, y + h - 2)
			g2.color = Theme.panel
			g2.drawLine(x + 1, y + 1, x + w - 1, y + 1)
			g2.drawLine(x + 1, y + 1, x + 1, y + h - 3)
			g2.color = Theme.shadow
			g2.drawLine(x + 1, y + h - 2, x + w - 1, y + h - 2)
			g2.color = Color.BLACK
			g2.drawLine(x, y + h - 1, x + w - 1, y + h - 1)
			g2.dispose()
		}
	}

	/** A tab on the right edge with its caption turned clockwise and [key] underlined (Alt+key selects it). */
	private class VerticalTab(private val caption: String, key: Char) : JToggleButton() {
		private val keyIndex = caption.indexOf(key)

		init {
			isOpaque = false
			isContentAreaFilled = false
			isBorderPainted = false
			isFocusPainted = false
			font = Theme.font
			mnemonic = key.uppercaseChar().code
		}

		/** Tab length along the strip: the caption plus the Windows tab padding. */
		fun length(): Int = getFontMetrics(Theme.font).stringWidth(caption) + 28

		override fun paintComponent(g: Graphics) {
			val w = width
			val h = height
			val s = isSelected
			val g2 = g.create() as Graphics2D
			g2.color = Theme.panel
			g2.fillRect(0, 0, w - 2, h - 2)
			g2.color = Color.WHITE
			g2.drawLine(if (s) 0 else 1, 0, w - 4, 0)
			g2.color = Theme.shadow
			g2.fillRect(w - 3, 0, 1, 1)
			g2.drawLine(w - 2, 1, w - 2, h - 3)
			g2.color = Color.BLACK
			g2.drawLine(w - 1, 3, w - 1, h - 3)
			g2.color = Theme.shadow
			g2.drawLine(if (s) 2 else 1, h - 3, w - 2, h - 3)
			g2.color = Color.BLACK
			g2.drawLine(if (s) 2 else 0, h - 2, w - 2, h - 2)
			if (s) {
				// The body edge line meets the bottom of the selected tab.
				g2.color = Theme.shadow
				g2.fillRect(0, h - 2, 1, 1)
				g2.color = Theme.panel
				g2.fillRect(1, h - 2, 1, 1)
			}
			if (isFocusOwner) {
				g2.color = Color.BLACK
				BasicGraphicsUtils.drawDashedRect(g2, 3, 2, w - 6, h - 6)
			}
			g2.color = Color.BLACK
			g2.font = Theme.font
			val fm = g2.fontMetrics
			g2.translate((w - 2) / 2, (h - 2) / 2)
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
