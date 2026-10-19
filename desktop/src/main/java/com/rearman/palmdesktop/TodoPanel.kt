package com.rearman.palmdesktop

import com.rearman.datebook.Fmt
import com.rearman.datebook.Todo
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
import java.awt.event.KeyEvent
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import javax.swing.*
import javax.swing.border.BevelBorder
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
		"Due Date, Priority",
		"Priority, Due Date",
		"Category, Priority",
		"Category, Due Date"
	)
	private val dueNames = arrayOf("Today", "Tomorrow", "One week later", "No Date", "Choose Date...")
	private val dayRenderer = DefaultListCellRenderer()

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
	private val radios = (1..5).map { n -> JRadioButton("$n").apply { font = Theme.font; mnemonic = KeyEvent.VK_0 + n } }
	private val dueBox = JComboBox(dueNames)
	private val dueField = JTextField(9)
	private val noteField = JTextField(12)
	private val noteButton = JButton(Icons.note())
	private val privateBox = JCheckBox()
	private val completeBox = JCheckBox()
	private val applyButton = JButton("Apply")
	private val model = Model()
	private val cell = Cell()
	private val table = JTable(model)

	/** The table model. Its columns depend on the Settings "todoShowPri", "todoShowDue" and "todoShowCat". */
	private inner class Model : AbstractTableModel() {
		/** The kind of each column: 0 Done, 1 Priority, 2 To Do, 3 Due Date, 4 Category. */
		var cols = intArrayOf(0, 2)

		/** Sets the column kinds. The table makes new columns only when the kinds change. */
		fun useColumns(kinds: IntArray) {
			if (kinds.contentEquals(cols)) return
			cols = kinds
			fireTableStructureChanged()
		}

		override fun getRowCount() = rows.size
		override fun getColumnCount() = cols.size
		override fun getColumnName(c: Int) = ""
		override fun getColumnClass(c: Int): Class<*> = if (cols[c] == 0) Boolean::class.javaObjectType else Any::class.java
		override fun isCellEditable(r: Int, c: Int) = cols[c] == 0

		override fun getValueAt(r: Int, c: Int): Any {
			val t = rows[r]
			return when (cols[c]) {
				0 -> t.done
				1 -> t.priority
				2 -> t.title
				3 -> t.due
				else -> App.store.categoryName(t.category)
			}
		}

		override fun setValueAt(v: Any?, r: Int, c: Int) {
			if (cols[c] != 0 || v !is Boolean) return
			val t = rows[r]
			markDone(t, v)
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
			val kind = model.cols[col]
			text = when (kind) {
				2 -> item.title.replace('\n', ' ')
				3 -> dueLabel(item)
				else -> value?.toString() ?: ""
			}
			icon = if (kind == 2 && item.isPrivate) Icons.privateRecord() else null
			// Set on every cell: DefaultTableCellRenderer keeps the last value as the unselected color.
			foreground = when {
				sel -> Color.WHITE
				isOverdue(item) -> Theme.dateRed
				else -> Color.BLACK
			}
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
		table.rowHeight = 18
		table.setShowGrid(false)
		table.intercellSpacing = Dimension(0, 0)
		table.background = Color.WHITE
		table.selectionBackground = Theme.select
		table.selectionForeground = Color.WHITE
		table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
		table.tableHeader = null
		table.selectionModel.addListSelectionListener { e ->
			if (e.valueIsAdjusting || busy) return@addListSelectionListener
			rows.getOrNull(table.selectedRow)?.let { loadDetails(it) }
		}

		val scroll = JScrollPane(table)
		scroll.viewport.background = Color.WHITE
		scroll.border = BorderFactory.createBevelBorder(BevelBorder.LOWERED)

		val showButton = JButton("Show...")
		showButton.mnemonic = KeyEvent.VK_W
		showButton.addActionListener { showOptions() }
		val newButton = JButton("New")
		newButton.mnemonic = KeyEvent.VK_N
		newButton.addActionListener { newRecord() }
		val bottom = JPanel(FlowLayout(FlowLayout.RIGHT))
		bottom.add(showButton)
		bottom.add(newButton)

		val center = JPanel(BorderLayout())
		center.add(scroll, BorderLayout.CENTER)
		center.add(bottom, BorderLayout.SOUTH)

		dueBox.font = Theme.font
		// The closed box shows the due date, not the item text.
		dueBox.renderer = ListCellRenderer<Any?> { list, value, index, sel, focus ->
			val shown: Any? = if (index < 0) dateText(pendingDue) else value
			dayRenderer.getListCellRendererComponent(list, shown, index, sel, focus)
		}
		dueBox.addActionListener {
			val pick = dueBox.selectedIndex
			if (busy || pick < 0) return@addActionListener
			val day = today()
			when (pick) {
				0 -> pendingDue = day
				1 -> pendingDue = day + 1
				2 -> pendingDue = day + 7
				3 -> pendingDue = -1L
				else -> DatePicker.pick(this, "day", if (pendingDue >= 0) pendingDue else day)?.let { pendingDue = it }
			}
			quiet { dueBox.selectedIndex = -1 }
			showDue()
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
		fun addRow(text: String, key: Char, target: Component, comp: JComponent) {
			c.gridy = row
			row++
			c.gridx = 0
			c.weightx = 0.0
			p.add(label(text, key, target), c)
			c.gridx = 1
			c.weightx = 1.0
			p.add(comp, c)
		}

		todoText.lineWrap = true
		todoText.wrapStyleWord = true
		todoText.font = Theme.font
		addRow("To Do:", 'T', todoText, JScrollPane(todoText))

		val prio = JPanel(GridLayout(1, 5, 0, 0))
		prio.border = BorderFactory.createBevelBorder(BevelBorder.LOWERED)
		for (r in radios) {
			group.add(r)
			prio.add(r)
		}
		addRow("Priority:", 'r', radios[0], prio)

		val due = JPanel(BorderLayout(4, 0))
		dueBox.selectedIndex = -1
		dueField.isEditable = false
		dueField.font = Theme.font
		dueField.border = BorderFactory.createBevelBorder(BevelBorder.LOWERED)
		// The box has no selected item at times, so give it a width that fits its longest choice.
		dueBox.preferredSize = Dimension(130, dueBox.preferredSize.height)
		due.add(dueBox, BorderLayout.WEST)
		due.add(dueField, BorderLayout.CENTER)
		addRow("Due:", 'u', dueBox, due)

		categoryBox.font = Theme.font
		addRow("Category:", 'g', categoryBox, categoryBox)

		val note = JPanel(BorderLayout(4, 0))
		noteField.isEditable = false
		note.add(noteField, BorderLayout.CENTER)
		note.add(noteButton, BorderLayout.EAST)
		addRow("Note:", 'e', noteField, note)

		addRow("Private:", 'v', privateBox, privateBox)

		applyButton.font = Theme.font
		applyButton.mnemonic = KeyEvent.VK_A
		val complete = JPanel(BorderLayout(4, 0))
		complete.add(completeBox, BorderLayout.WEST)
		complete.add(applyButton, BorderLayout.EAST)
		addRow("Complete:", 'm', completeBox, complete)

		c.gridy = row
		c.gridx = 0
		c.gridwidth = 2
		c.weighty = 1.0
		c.fill = GridBagConstraints.BOTH
		p.add(JPanel(), c)
		return p
	}

	/** A label that is right-aligned and shows its mnemonic, which moves the focus to [target]. */
	private fun label(text: String, key: Char, target: Component): JLabel = JLabel(text, JLabel.RIGHT).apply {
		font = Theme.font
		displayedMnemonic = key.uppercaseChar().code
		displayedMnemonicIndex = text.indexOf(key, ignoreCase = true)
		labelFor = target
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
		if (!confirmDelete(this, "Delete the To Do item?")) return
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

	/** Rebuilds the columns and the table, and keeps the selection by id. */
	private fun rebuild() {
		val keep = selectedId
		rows = visible()
		val idx = if (keep == null) -1 else rows.indexOfFirst { it.id == keep }
		quiet {
			model.useColumns(columnKinds())
			configureColumns()
			model.fireTableDataChanged()
			if (idx >= 0) table.setRowSelectionInterval(idx, idx) else table.clearSelection()
		}
		when {
			idx < 0 -> clearDetails()
			loadedId != keep -> loadDetails(rows[idx])
		}
	}

	private fun columnKinds(): IntArray {
		val kinds = arrayListOf(0)
		if (Settings.getBool("todoShowPri", false)) kinds.add(1)
		kinds.add(2)
		if (Settings.getBool("todoShowDue", true)) kinds.add(3)
		if (Settings.getBool("todoShowCat", true)) kinds.add(4)
		return kinds.toIntArray()
	}

	/** Sets the fixed widths and the renderer of each column. The To Do text gets the rest. */
	private fun configureColumns() {
		for (c in 0 until table.columnModel.columnCount) {
			val col = table.columnModel.getColumn(c)
			val kind = model.cols[c]
			val fixed = when (kind) {
				0, 1 -> 20
				3 -> 45
				4 -> 50
				else -> 0
			}
			if (fixed > 0) {
				col.preferredWidth = fixed
				col.maxWidth = fixed
			}
			if (kind != 0) col.cellRenderer = cell
		}
	}

	private fun visible(): List<Todo> {
		val cat = Settings.getInt("todoCategory", -1)
		return App.store.todos
			.filter { t -> (cat == -1 || t.category == cat) && todoShown(t) }
			.sortedWith(todoOrder())
	}

	/** Makes the item visible in the table by changing the filters when needed. */
	private fun reveal(t: Todo) {
		val cat = Settings.getInt("todoCategory", -1)
		if (cat != -1 && t.category != cat) Settings.setInt("todoCategory", -1)
		if (t.done && !Settings.getBool("todoShowDone", false)) Settings.setBool("todoShowDone", true)
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
			showDue()
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
			showDue()
			selectCategory(0)
			pendingNote = ""
			noteField.text = ""
			privateBox.isSelected = false
			completeBox.isSelected = false
		}
		setControlsEnabled(false)
	}

	/** Shows the due date in the Due box and the day name in the field next to it. */
	private fun showDue() {
		dueBox.selectedIndex = -1
		dueBox.repaint()
		dueField.text = if (pendingDue < 0) "" else LocalDate.ofEpochDay(pendingDue).dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US)
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
		markDone(t, completeBox.isSelected)
		App.store.save(t)
		loadedId = null   // reload the panel from the saved item
		App.refresh()
	}

	/** The Show Options dialog: sort order and the view filters, and which columns the list shows. */
	fun showOptions() {
		val dlg = JDialog(SwingUtilities.getWindowAncestor(this), "Show Options", Dialog.ModalityType.APPLICATION_MODAL)
		var ok = false
		val sort = JComboBox(sortNames)
		sort.font = Theme.font
		sort.selectedIndex = Settings.getInt("todoSort", 0).coerceIn(0, 3)
		val done = check("Show completed items", 'c', Settings.getBool("todoShowDone", false))
		val onlyDue = check("Show only due items", 'o', Settings.getBool("todoOnlyDue", false))
		val record = check("Record completion date", 'R', Settings.getBool("todoRecordDone", false))
		val dues = check("Show due dates", 'd', Settings.getBool("todoShowDue", true))
		val pri = check("Show priorities", 'p', Settings.getBool("todoShowPri", false))
		val cat = check("Show categories", 'a', Settings.getBool("todoShowCat", true))

		val view = JPanel()
		view.layout = BoxLayout(view, BoxLayout.Y_AXIS)
		view.border = BorderFactory.createCompoundBorder(BorderFactory.createTitledBorder("View Options"), EmptyBorder(4, 6, 6, 6))
		view.add(done)
		view.add(onlyDue)
		view.add(record)
		view.add(Box.createVerticalStrut(10))
		view.add(dues)
		view.add(pri)
		view.add(cat)

		val okButton = JButton("OK").apply { addActionListener { ok = true; dlg.dispose() } }
		val cancelButton = JButton("Cancel").apply { addActionListener { dlg.dispose() } }
		dlg.rootPane.defaultButton = okButton
		dlg.rootPane.registerKeyboardAction({ dlg.dispose() }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW)

		val sortRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
		sortRow.add(label("Sort by:", 'S', sort))
		sortRow.add(sort)
		val left = JPanel(BorderLayout(0, 10))
		left.add(sortRow, BorderLayout.NORTH)
		left.add(view, BorderLayout.CENTER)
		val buttons = JPanel(GridLayout(0, 1, 0, 6))
		buttons.add(okButton)
		buttons.add(cancelButton)
		val body = JPanel(BorderLayout(12, 0))
		body.border = EmptyBorder(12, 12, 12, 12)
		body.add(left, BorderLayout.CENTER)
		body.add(JPanel(BorderLayout()).apply { add(buttons, BorderLayout.NORTH) }, BorderLayout.EAST)
		dlg.contentPane = body
		dlg.pack()
		dlg.setLocationRelativeTo(this)
		dlg.isVisible = true   // modal: returns when the dialog closes

		if (!ok) return
		Settings.setInt("todoSort", sort.selectedIndex)
		Settings.setBool("todoShowDone", done.isSelected)
		Settings.setBool("todoOnlyDue", onlyDue.isSelected)
		Settings.setBool("todoRecordDone", record.isSelected)
		Settings.setBool("todoShowDue", dues.isSelected)
		Settings.setBool("todoShowPri", pri.isSelected)
		Settings.setBool("todoShowCat", cat.isSelected)
		App.refresh()
	}

	private fun check(text: String, key: Char, on: Boolean): JCheckBox = JCheckBox(text, on).apply {
		font = Theme.font
		mnemonic = key.uppercaseChar().code
		alignmentX = Component.LEFT_ALIGNMENT
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
	private fun firstLine(s: String) = s.lineSequence().firstOrNull() ?: ""

	/** The due text of the Due box: M/d/yyyy, or empty when no date. */
	private fun dateText(due: Long): String {
		if (due < 0) return ""
		val d = LocalDate.ofEpochDay(due)
		return "${d.monthValue}/${d.dayOfMonth}/${d.year}"
	}
}

// The To Do List and the To Do part of the Day view share these, so both follow the Show Options.

/** True when [t] is not done and its due date is before today. */
fun isOverdue(t: Todo) = !t.done && t.due >= 0 && t.due < LocalDate.now().toEpochDay()

/** Sets the done flag. A new completion sets the due date to today when Settings "todoRecordDone" is on. */
fun markDone(t: Todo, done: Boolean) {
	if (done && !t.done && Settings.getBool("todoRecordDone", false)) t.due = LocalDate.now().toEpochDay()
	t.done = done
}

/** True when [t] has a due date of today or earlier. */
fun isDueByToday(t: Todo) = t.due >= 0 && t.due <= LocalDate.now().toEpochDay()

/** True when the private setting and the Show Options (completed items, only due items) let [t] show. */
fun todoShown(t: Todo): Boolean =
	(App.showPrivate || !t.isPrivate) &&
		(Settings.getBool("todoShowDone", false) || !t.done) &&
		(!Settings.getBool("todoOnlyDue", false) || isDueByToday(t))

/** The sort order of the Show Options. */
fun todoOrder(): Comparator<Todo> {
	val byPriority = compareBy<Todo> { it.priority }
	val byDue = compareBy<Todo> { if (it.due < 0) Long.MAX_VALUE else it.due }
	val byCategory = compareBy<Todo> { App.store.categoryName(it.category).lowercase() }
	return when (Settings.getInt("todoSort", 0)) {
		1 -> byPriority.thenComparing(byDue)
		2 -> byCategory.thenComparing(byPriority)
		3 -> byCategory.thenComparing(byDue)
		else -> byDue.thenComparing(byPriority)
	}
}

/** The due text of a To Do: M/d, with "!" when overdue, or "-" when no date. */
fun dueLabel(t: Todo): String {
	if (t.due < 0) return "-"
	val d = LocalDate.ofEpochDay(t.due)
	return "${d.monthValue}/${d.dayOfMonth}" + if (isOverdue(t)) "!" else ""
}
