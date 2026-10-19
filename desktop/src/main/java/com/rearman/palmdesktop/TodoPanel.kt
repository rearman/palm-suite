package com.rearman.palmdesktop

import com.rearman.datebook.Fmt
import com.rearman.datebook.Todo
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.GridLayout
import java.awt.Insets
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar
import java.util.Date
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.border.LineBorder
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/** The To Do List view: a table of items, and a details panel for the selected item. */
class TodoPanel : JPanel(BorderLayout()), AppView {
	private class Choice(val id: Int, val label: String) {
		override fun toString() = label
	}

	private val sortNames = arrayOf(
		"Priority, then Due Date",
		"Due Date, then Priority",
		"Category, then Priority",
		"Category, then Due Date"
	)
	private val dueNames = arrayOf("No Date", "Today", "Tomorrow", "One week later", "Choose Date...")

	private var rows: List<Todo> = emptyList()   // the items the table shows
	private var selectedId: Long? = null         // the item selected in the table
	private var loadedId: Long? = null           // the item whose values are in the details panel
	private var busy = false                     // true while code changes the controls
	private var pendingDue = -1L                 // due date chosen in the panel, saved by Apply
	private var pendingNote = ""                 // note chosen in the panel, saved by Apply
	private val controls = ArrayList<JComponent>()
	private val group = ButtonGroup()

	private val filterBox = JComboBox<Choice>()
	private val categoryBox = JComboBox<Choice>()
	private val todoText = JTextArea(3, 20)
	private val radios = (1..5).map { JRadioButton("$it").apply { font = Theme.font } }
	private val dueBox = JComboBox(dueNames)
	private val dueField = JTextField(8)
	private val noteField = JTextField(12)
	private val noteButton = JButton(Icons.note())
	private val privateBox = JCheckBox()
	private val completeBox = JCheckBox()
	private val applyButton = JButton("Apply")
	private val model = Model()
	private val table = JTable(model)

	private inner class Model : AbstractTableModel() {
		private val names = arrayOf("Done", "Priority", "To Do", "Due Date", "Category")

		override fun getRowCount() = rows.size
		override fun getColumnCount() = names.size
		override fun getColumnName(c: Int) = names[c]
		override fun getColumnClass(c: Int): Class<*> = if (c == 0) Boolean::class.javaObjectType else Any::class.java
		override fun isCellEditable(r: Int, c: Int) = c == 0

		override fun getValueAt(r: Int, c: Int): Any {
			val t = rows[r]
			return when (c) {
				0 -> t.done
				1 -> t.priority
				2 -> t.title
				3 -> t.due
				else -> App.store.categoryName(t.category)
			}
		}

		override fun setValueAt(v: Any?, r: Int, c: Int) {
			if (c != 0 || v !is Boolean) return
			val t = rows[r]
			t.done = v
			App.store.save(t)
			if (selectedId == t.id) loadedId = null   // reload the Complete box
			App.refresh()
		}
	}

	private inner class Cell : DefaultTableCellRenderer() {
		override fun getTableCellRendererComponent(
			t: JTable, value: Any?, sel: Boolean, focus: Boolean, row: Int, col: Int
		): Component {
			super.getTableCellRendererComponent(t, value, sel, focus, row, col)
			val item = rows[row]
			text = when (col) {
				2 -> item.title.replace('\n', ' ')
				3 -> if (item.due == -1L) "-" else Fmt.shortDate(item.due)
				else -> value?.toString() ?: ""
			}
			icon = if (col == 2 && item.isPrivate) Icons.privateRecord() else null
			if (!sel && col == 3 && isOverdue(item)) foreground = Theme.dateRed
			return this
		}
	}

	init {
		val tab = JLabel("To Do List").apply {
			font = Theme.bold
			border = BorderFactory.createCompoundBorder(LineBorder(Theme.grid), EmptyBorder(2, 8, 2, 8))
		}
		val top = JPanel(BorderLayout())
		top.border = EmptyBorder(4, 4, 4, 4)
		top.add(tab, BorderLayout.WEST)
		filterBox.font = Theme.font
		filterBox.addActionListener {
			val choice = filterBox.selectedItem as? Choice ?: return@addActionListener
			if (busy) return@addActionListener
			Settings.setInt("todoCategory", choice.id)
			rebuild()
		}
		top.add(filterBox, BorderLayout.EAST)

		table.font = Theme.font
		table.tableHeader.font = Theme.bold
		table.rowHeight = 18
		table.showGrid = false
		table.intercellSpacing = Dimension(0, 0)
		table.selectionBackground = Theme.select
		table.selectionForeground = Color.WHITE
		table.selectionMode = ListSelectionModel.SINGLE_SELECTION
		val cell = Cell()
		val widths = intArrayOf(45, 60, 300, 75, 110)
		for (c in widths.indices) {
			table.columnModel.getColumn(c).preferredWidth = widths[c]
			if (c > 0) table.columnModel.getColumn(c).cellRenderer = cell
		}
		table.selectionModel.addListSelectionListener { e ->
			if (e.valueIsAdjusting || busy) return@addListSelectionListener
			rows.getOrNull(table.selectedRow)?.let { loadDetails(it) }
		}

		val showButton = JButton("Show...")
		showButton.addActionListener { showOptions() }
		val newButton = JButton("New")
		newButton.addActionListener { newRecord() }
		val bottom = JPanel(FlowLayout(FlowLayout.RIGHT))
		bottom.add(showButton)
		bottom.add(newButton)

		val center = JPanel(BorderLayout())
		center.add(JScrollPane(table), BorderLayout.CENTER)
		center.add(bottom, BorderLayout.SOUTH)

		dueBox.font = Theme.font
		dueBox.addActionListener {
			if (busy) return@addActionListener
			val day = today()
			when (dueBox.selectedIndex) {
				0 -> pendingDue = -1L
				1 -> pendingDue = day
				2 -> pendingDue = day + 1
				3 -> pendingDue = day + 7
				4 -> if (!chooseDate()) quiet { dueBox.selectedIndex = dueIndex(pendingDue) }
			}
			dueField.text = dueText(pendingDue)
		}
		noteButton.addActionListener {
			val text = NoteDialog.edit(this, "To Do Note", pendingNote)
			if (text != null) {
				pendingNote = text
				noteField.text = firstLine(text)
			}
		}
		applyButton.addActionListener { applyFields() }

		controls.add(todoText)
		controls.addAll(radios)
		controls.add(dueBox)
		controls.add(categoryBox)
		controls.add(noteButton)
		controls.add(privateBox)
		controls.add(completeBox)
		controls.add(applyButton)

		add(top, BorderLayout.NORTH)
		add(center, BorderLayout.CENTER)
		add(buildDetails(), BorderLayout.EAST)

		App.onRefresh { onRefresh() }
		onRefresh()
	}

	private fun buildDetails(): JPanel {
		val p = JPanel(GridBagLayout())
		p.preferredSize = Dimension(280, 0)
		val c = GridBagConstraints()
		c.insets = Insets(3, 4, 3, 4)
		c.anchor = GridBagConstraints.WEST
		c.fill = GridBagConstraints.HORIZONTAL
		var row = 0
		fun addRow(label: String, comp: JComponent) {
			c.gridy = row
			row++
			c.gridx = 0
			c.weightx = 0.0
			p.add(JLabel(label).apply { font = Theme.font }, c)
			c.gridx = 1
			c.weightx = 1.0
			p.add(comp, c)
		}

		todoText.lineWrap = true
		todoText.wrapStyleWord = true
		todoText.font = Theme.font
		addRow("To Do:", JScrollPane(todoText))

		val prio = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
		for (r in radios) {
			group.add(r)
			prio.add(r)
		}
		addRow("Priority:", prio)

		val due = JPanel(BorderLayout(4, 0))
		dueField.isEditable = false
		due.add(dueBox, BorderLayout.WEST)
		due.add(dueField, BorderLayout.CENTER)
		addRow("Due:", due)

		categoryBox.font = Theme.font
		addRow("Category:", categoryBox)

		val note = JPanel(BorderLayout(4, 0))
		noteField.isEditable = false
		note.add(noteField, BorderLayout.CENTER)
		note.add(noteButton, BorderLayout.EAST)
		addRow("Note:", note)

		addRow("Private:", privateBox)
		addRow("Complete:", completeBox)

		applyButton.font = Theme.font
		c.gridy = row
		c.gridx = 1
		c.anchor = GridBagConstraints.EAST
		c.weightx = 0.0
		p.add(applyButton, c)

		c.gridy = row + 1
		c.gridx = 0
		c.gridwidth = 2
		c.weighty = 1.0
		c.fill = GridBagConstraints.BOTH
		p.add(JPanel(), c)
		return p
	}

	override fun newRecord() {
		val filter = Settings.getInt("todoCategory", -1)
		val t = Todo(title = "", priority = 1, category = if (filter == -1) 0 else filter)
		App.store.save(t)
		reveal(t)
		selectedId = t.id
		loadedId = null
		App.refresh()
		todoText.requestFocusInWindow()
	}

	override fun editSelected() {
		todoText.requestFocusInWindow()
	}

	override fun deleteSelected() {
		val t = selectedTodo() ?: return
		val answer = JOptionPane.showConfirmDialog(this, "Delete this To Do item?", "Delete", JOptionPane.YES_NO_OPTION)
		if (answer != JOptionPane.YES_OPTION) return
		selectedId = null
		App.store.delete(t)
		App.refresh()
	}

	override fun select(record: Any) {
		val t = record as? Todo ?: return
		reveal(t)
		selectedId = t.id
		loadedId = null
		onRefresh()
		val idx = rows.indexOfFirst { it.id == t.id }
		if (idx >= 0) table.scrollRectToVisible(table.getCellRect(idx, 0, true))
		table.requestFocusInWindow()
	}

	override fun printLines(): Pair<String, List<String>> {
		val lines = rows.map { t ->
			val box = if (t.done) "[x]" else "[ ]"
			val due = if (t.due == -1L) "-" else Fmt.shortDate(t.due)
			"$box ${t.priority} ${t.title.replace('\n', ' ')} $due ${App.store.categoryName(t.category)}"
		}
		return Pair("To Do List", lines)
	}

	private fun onRefresh() {
		quiet {
			rebuildFilterBox()
			rebuildCategoryBox()
		}
		rebuild()
	}

	/** Rebuilds the table and keeps the selection by id. */
	private fun rebuild() {
		val keep = selectedId
		rows = visible()
		val idx = if (keep == null) -1 else rows.indexOfFirst { it.id == keep }
		quiet {
			model.fireTableDataChanged()
			if (idx >= 0) table.setRowSelectionInterval(idx, idx) else table.clearSelection()
		}
		when {
			idx < 0 -> clearDetails()
			loadedId != keep -> loadDetails(rows[idx])
		}
	}

	private fun visible(): List<Todo> {
		val cat = Settings.getInt("todoCategory", -1)
		val showDone = Settings.getBool("todoShowDone", true)
		val onlyDue = Settings.getBool("todoOnlyDue", false)
		return App.store.todos
			.filter { t ->
				(App.showPrivate || !t.isPrivate) &&
					(cat == -1 || t.category == cat) &&
					(showDone || !t.done) &&
					(!onlyDue || isDueByToday(t))
			}
			.sortedWith(order())
	}

	private fun order(): Comparator<Todo> {
		val byPriority = compareBy<Todo> { it.priority }
		val byDue = compareBy<Todo> { if (it.due == -1L) Long.MAX_VALUE else it.due }
		val byCategory = compareBy<Todo> { App.store.categoryName(it.category).lowercase() }
		return when (Settings.getInt("todoSort", 0)) {
			1 -> byDue.thenComparing(byPriority)
			2 -> byCategory.thenComparing(byPriority)
			3 -> byCategory.thenComparing(byDue)
			else -> byPriority.thenComparing(byDue)
		}
	}

	/** Makes the item visible in the table by changing the filters when needed. */
	private fun reveal(t: Todo) {
		val cat = Settings.getInt("todoCategory", -1)
		if (cat != -1 && t.category != cat) Settings.setInt("todoCategory", -1)
		if (t.done && !Settings.getBool("todoShowDone", true)) Settings.setBool("todoShowDone", true)
		if (Settings.getBool("todoOnlyDue", false) && !isDueByToday(t)) Settings.setBool("todoOnlyDue", false)
	}

	private fun choices(withAll: Boolean): ArrayList<Choice> {
		val items = ArrayList<Choice>()
		if (withAll) items.add(Choice(-1, "All"))
		App.store.categoriesFor(0).mapTo(items) { Choice(it.id, it.name) }
		items.add(Choice(0, "Unfiled"))
		return items
	}

	private fun rebuildFilterBox() {
		val items = choices(true)
		val cur = Settings.getInt("todoCategory", -1)
		val pick = items.firstOrNull { it.id == cur } ?: items[0]
		if (pick.id != cur) Settings.setInt("todoCategory", pick.id)
		filterBox.model = DefaultComboBoxModel(items.toTypedArray())
		filterBox.selectedItem = pick
	}

	private fun rebuildCategoryBox() {
		val cur = (categoryBox.selectedItem as? Choice)?.id ?: 0
		val items = choices(false)
		categoryBox.model = DefaultComboBoxModel(items.toTypedArray())
		categoryBox.selectedItem = items.firstOrNull { it.id == cur } ?: items.last()
	}

	private fun selectCategory(id: Int) {
		val items = (0 until categoryBox.itemCount).map { categoryBox.getItemAt(it) }
		categoryBox.selectedItem = items.firstOrNull { it.id == id } ?: items.last()
	}

	private fun loadDetails(t: Todo) {
		quiet {
			selectedId = t.id
			loadedId = t.id
			todoText.text = t.title
			radios[t.priority.coerceIn(1, 5) - 1].isSelected = true
			pendingDue = t.due
			dueBox.selectedIndex = dueIndex(t.due)
			dueField.text = dueText(t.due)
			selectCategory(t.category)
			pendingNote = t.note
			noteField.text = firstLine(t.note)
			privateBox.isSelected = t.isPrivate
			completeBox.isSelected = t.done
		}
		setControlsEnabled(true)
	}

	private fun clearDetails() {
		selectedId = null
		loadedId = null
		quiet {
			todoText.text = ""
			group.clearSelection()
			pendingDue = -1L
			dueBox.selectedIndex = 0
			dueField.text = ""
			selectCategory(0)
			pendingNote = ""
			noteField.text = ""
			privateBox.isSelected = false
			completeBox.isSelected = false
		}
		setControlsEnabled(false)
	}

	private fun setControlsEnabled(on: Boolean) {
		for (ctl in controls) ctl.isEnabled = on
	}

	private fun applyFields() {
		val t = selectedTodo() ?: return
		t.title = todoText.text.trim()
		t.priority = (radios.indexOfFirst { it.isSelected } + 1).coerceAtLeast(1)
		t.due = pendingDue
		t.category = (categoryBox.selectedItem as Choice).id
		t.note = pendingNote
		t.isPrivate = privateBox.isSelected
		t.done = completeBox.isSelected
		App.store.save(t)
		App.refresh()
	}

	private fun showOptions() {
		val sort = JComboBox(sortNames)
		sort.selectedIndex = Settings.getInt("todoSort", 0).coerceIn(0, 3)
		val done = JCheckBox("Show Completed Items", Settings.getBool("todoShowDone", true))
		val due = JCheckBox("Show Only Due Items", Settings.getBool("todoOnlyDue", false))
		val p = JPanel(GridLayout(0, 1, 4, 4))
		p.add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
			add(JLabel("Sort by:").apply { font = Theme.font })
			add(sort)
		})
		p.add(done)
		p.add(due)
		val answer = JOptionPane.showConfirmDialog(this, p, "Show", JOptionPane.OK_CANCEL_OPTION)
		if (answer != JOptionPane.OK_OPTION) return
		Settings.setInt("todoSort", sort.selectedIndex)
		Settings.setBool("todoShowDone", done.isSelected)
		Settings.setBool("todoOnlyDue", due.isSelected)
		App.refresh()
	}

	/** Shows the date picker. Returns false when the user cancels. */
	private fun chooseDate(): Boolean {
		val start = if (pendingDue >= 0) pendingDue else today()
		val spinner = JSpinner(SpinnerDateModel(toDate(start), null, null, Calendar.DAY_OF_MONTH))
		spinner.editor = JSpinner.DateEditor(spinner, "M/d/yy")
		val p = JPanel(FlowLayout(FlowLayout.LEFT))
		p.add(JLabel("Due date:").apply { font = Theme.font })
		p.add(spinner)
		val answer = JOptionPane.showConfirmDialog(this, p, "Choose Date", JOptionPane.OK_CANCEL_OPTION)
		if (answer != JOptionPane.OK_OPTION) return false
		pendingDue = toDay(spinner.value as Date)
		return true
	}

	private inline fun quiet(block: () -> Unit) {
		val old = busy
		busy = true
		try {
			block()
		} finally {
			busy = old
		}
	}

	private fun selectedTodo(): Todo? = App.store.todos.firstOrNull { it.id == selectedId }

	private fun today() = LocalDate.now().toEpochDay()
	private fun isOverdue(t: Todo) = !t.done && t.due != -1L && t.due < today()
	private fun isDueByToday(t: Todo) = t.due != -1L && t.due <= today()
	private fun dueText(due: Long) = if (due == -1L) "" else Fmt.shortDate(due)
	private fun firstLine(s: String) = s.lineSequence().firstOrNull() ?: ""

	private fun dueIndex(due: Long): Int {
		val day = today()
		return when {
			due == -1L -> 0
			due == day -> 1
			due == day + 1 -> 2
			due == day + 7 -> 3
			else -> 4
		}
	}

	private fun toDate(day: Long): Date =
		Date.from(LocalDate.ofEpochDay(day).atStartOfDay(ZoneId.systemDefault()).toInstant())

	private fun toDay(d: Date): Long = d.toInstant().atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()
}
