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
import java.awt.Graphics
import java.awt.Insets
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import javax.swing.*
import javax.swing.border.BevelBorder
import javax.swing.border.EmptyBorder
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/** The To Do List view: a table of items, and a details panel for the selected item. */
class TodoPanel : JPanel(BorderLayout()), AppView {
	private class Choice(val id: Int, val label: String) {
		override fun toString() = label
	}

	private val sortNames = arrayOf(
		"Priority, Due Date",
		"Due Date, Priority",
		"Category, Priority",
		"Category, Due Date"
	)
	private val dueNames = arrayOf("Today", "Tomorrow", "One week later", "No date", "Choose date...")
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
		/** The kind of each column: 0 Done, 1 Priority, 2 To Do, 3 Due Date, 4 Category, 5 Note and Private marks. */
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
				5 -> ""
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
		private val noteIcon by lazy { Icons.note() }
		private val privateIcon by lazy { Icons.privateRecord() }

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
			icon = if (kind == 5) marks(item) else null
			// Set on every cell: DefaultTableCellRenderer keeps the last value as the unselected color.
			foreground = when {
				sel && isOverdue(item) -> Color.YELLOW   // a selected overdue item is yellow, as in Palm Desktop
				sel -> Color.WHITE
				isOverdue(item) -> Theme.dateRed
				else -> Color.BLACK
			}
			return this
		}

		/** The note and private icons of [t], side by side, or null when it has neither. */
		private fun marks(t: Todo): Icon? {
			val list = ArrayList<Icon>()
			if (t.note.isNotEmpty()) list.add(noteIcon)
			if (t.isPrivate) list.add(privateIcon)
			return if (list.isEmpty()) null else IconRow(list)
		}
	}

	init {
		val tab = JLabel("To Do List").apply {
			font = Theme.caption
			border = BorderFactory.createCompoundBorder(Theme.sunken, BorderFactory.createEmptyBorder(1, 16, 1, 16))
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
		table.rowHeight = 16
		table.setShowGrid(false)
		table.intercellSpacing = Dimension(0, 0)
		table.background = Color.WHITE
		table.selectionBackground = Theme.select
		table.selectionForeground = Color.WHITE
		table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
		table.tableHeader = null
		// Palm Desktop draws the Done boxes as plain black squares.
		(table.getDefaultRenderer(Boolean::class.javaObjectType) as? javax.swing.JCheckBox)?.icon = Theme.plainCheck
		((table.getDefaultEditor(Boolean::class.javaObjectType) as? javax.swing.DefaultCellEditor)?.component as? javax.swing.JCheckBox)?.icon = Theme.plainCheck
		// Typing starts a new item (see keyTyped), so the table must not start a cell editor.
		table.putClientProperty("JTable.autoStartsEdit", false)
		table.selectionModel.addListSelectionListener { e ->
			if (e.valueIsAdjusting) return@addListSelectionListener
			App.positionChanged()
			if (busy) return@addListSelectionListener
			rows.getOrNull(table.selectedRow)?.let { loadDetails(it) }
		}
		table.addMouseListener(object : MouseAdapter() {
			override fun mouseClicked(e: MouseEvent) {
				if (e.clickCount != 1 || !SwingUtilities.isLeftMouseButton(e)) return
				val r = table.rowAtPoint(e.point)
				val c = table.columnAtPoint(e.point)
				if (r < 0 || c < 0) return
				val menu = when (model.cols[c]) {
					1 -> priorityMenu(rows[r])
					3 -> dueMenu(rows[r])
					4 -> categoryMenu(rows[r])
					else -> return
				}
				menu.show(table, e.x, e.y)
			}
		})
		table.addKeyListener(object : KeyAdapter() {
			override fun keyTyped(e: KeyEvent) {
				if (e.isControlDown || e.isAltDown || e.keyChar.isISOControl()) return
				newRecord()
				todoText.text = e.keyChar.toString()
				todoText.caretPosition = 1
				e.consume()
			}
		})

		val scroll = JScrollPane(table)
		scroll.viewport.background = Color.WHITE
		scroll.border = BorderFactory.createBevelBorder(BevelBorder.LOWERED)

		val showButton = JButton("Show...")
		showButton.mnemonic = KeyEvent.VK_O
		showButton.addActionListener { showOptions() }
		val newButton = JButton("New")
		newButton.mnemonic = KeyEvent.VK_N
		newButton.addActionListener { newRecord() }
		val bottom = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 5))
		bottom.add(showButton)
		bottom.add(newButton)

		val center = JPanel(BorderLayout())
		center.add(top, BorderLayout.NORTH)
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
			dueFor(pick, pendingDue)?.let { pendingDue = it }
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

		add(center, BorderLayout.CENTER)
		add(buildDetails(), BorderLayout.EAST)

		App.onRefresh { onRefresh() }
		onRefresh()
	}

	private fun buildDetails(): JPanel {
		val p = JPanel(GridBagLayout())
		p.preferredSize = Dimension(315, 0)
		// The raised edge between the list and the details, as in the Memo Pad.
		p.border = BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 1, 0, 0, Theme.grid),
			BorderFactory.createMatteBorder(0, 1, 0, 0, Color.WHITE)
		)
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

	override fun position(): String {
		val row = table.selectedRow
		return if (row < 0) "" else "${row + 1} of ${rows.size}"
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
		App.positionChanged()
	}

	private fun columnKinds(): IntArray {
		val kinds = arrayListOf(0)
		if (Settings.getBool("todoShowPri", false)) kinds.add(1)
		kinds.add(2)
		if (Settings.getBool("todoShowDue", true)) kinds.add(3)
		if (Settings.getBool("todoShowCat", true)) kinds.add(4)
		kinds.add(5)
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
				5 -> 34
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
		filterBox.model = CategoryModel(items, Choice(-2, EDIT_CATEGORIES)) { CategoriesDialog.show(this, 0) }
		filterBox.selectedItem = pick
	}

	private fun rebuildCategoryBox() {
		val cur = (categoryBox.selectedItem as? Choice)?.id ?: 0
		val items = choices(false)
		categoryBox.model = CategoryModel(items, Choice(-2, EDIT_CATEGORIES)) { CategoriesDialog.show(this, 0) }
		categoryBox.selectedItem = items.firstOrNull { it.id == cur } ?: items.last()
	}

	private fun selectCategory(id: Int) {
		val items = (0 until categoryBox.itemCount).map { categoryBox.getItemAt(it) }
		categoryBox.selectedItem = items.firstOrNull { it.id == id } ?: items.first { it.id == 0 }   // else Unfiled
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

	/** The due date that Due menu row [pick] gives, or null when the date picker is cancelled. */
	private fun dueFor(pick: Int, current: Long): Long? {
		val day = today()
		return when (pick) {
			0 -> day
			1 -> day + 1
			2 -> day + 7
			3 -> -1L
			else -> DatePicker.pick(this, "day", if (current >= 0) current else day)
		}
	}

	/** Saves an item changed from a cell popup, and reloads the details panel when it shows the item. */
	private fun saveFromCell(t: Todo) {
		App.store.save(t)
		if (selectedId == t.id) loadedId = null
		App.refresh()
	}

	private fun priorityMenu(t: Todo) = JPopupMenu().apply {
		for (n in 1..5) add(JMenuItem("$n").apply {
			addActionListener {
				t.priority = n
				saveFromCell(t)
			}
		})
	}

	private fun dueMenu(t: Todo) = JPopupMenu().apply {
		dueNames.forEachIndexed { i, name ->
			add(JMenuItem(name).apply {
				addActionListener {
					val due = dueFor(i, t.due) ?: return@addActionListener
					t.due = due
					saveFromCell(t)
				}
			})
		}
	}

	private fun categoryMenu(t: Todo) = JPopupMenu().apply {
		for (c in choices(false)) add(JMenuItem(c.label).apply {
			addActionListener {
				t.category = c.id
				saveFromCell(t)
			}
		})
		add(JMenuItem(EDIT_CATEGORIES).apply { addActionListener { CategoriesDialog.show(this@TodoPanel, 0) } })
	}

	/** The Show Options dialog: sort order and the view filters, and which columns the list shows. */
	fun showOptions() {
		val dlg = JDialog(SwingUtilities.getWindowAncestor(this), "Show Options", Dialog.ModalityType.APPLICATION_MODAL)
		var ok = false
		val sort = JComboBox(sortNames)
		sort.font = Theme.font
		sort.selectedIndex = todoSortIndex()
		val done = check("Show &completed items", Settings.getBool("todoShowDone", false))
		val onlyDue = check("Show only due &items", Settings.getBool("todoOnlyDue", false))
		val record = check("&Record completion date", Settings.getBool("todoRecordDone", false))
		val dues = check("Show &due dates", Settings.getBool("todoShowDue", true))
		val pri = check("Show &priorities", Settings.getBool("todoShowPri", false))
		val cat = check("Show cate&gories", Settings.getBool("todoShowCat", true))

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
		sortRow.add(label("Sort by", 'S', sort))
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
		Settings.setInt("todoSortBy", sort.selectedIndex)
		Settings.setBool("todoShowDone", done.isSelected)
		Settings.setBool("todoOnlyDue", onlyDue.isSelected)
		Settings.setBool("todoRecordDone", record.isSelected)
		Settings.setBool("todoShowDue", dues.isSelected)
		Settings.setBool("todoShowPri", pri.isSelected)
		Settings.setBool("todoShowCat", cat.isSelected)
		App.refresh()
	}

	/** A check box whose mnemonic is the character after the first '&' in [text]. */
	private fun check(text: String, on: Boolean): JCheckBox {
		val amp = text.indexOf('&')
		return JCheckBox(text.replace("&", ""), on).apply {
			font = Theme.font
			mnemonic = text[amp + 1].uppercaseChar().code
			displayedMnemonicIndex = amp
			alignmentX = Component.LEFT_ALIGNMENT
		}
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

/** Paints icons side by side with a 2 px gap. */
private class IconRow(private val icons: List<Icon>) : Icon {
	override fun getIconWidth() = icons.sumOf { it.iconWidth } + 2 * (icons.size - 1)
	override fun getIconHeight() = icons.maxOfOrNull { it.iconHeight } ?: 0

	override fun paintIcon(c: Component, g: Graphics, x: Int, y: Int) {
		var dx = x
		for (i in icons) {
			i.paintIcon(c, g, dx, y)
			dx += i.iconWidth + 2
		}
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

/** True when [t] has no due date, or a due date of today or earlier. */
fun isDueByToday(t: Todo) = t.due < 0 || t.due <= LocalDate.now().toEpochDay()

/** True when the private setting and the Show Options (completed items, only due items) let [t] show. */
fun todoShown(t: Todo): Boolean =
	(App.showPrivate || !t.isPrivate) &&
		(Settings.getBool("todoShowDone", false) || !t.done) &&
		(!Settings.getBool("todoOnlyDue", false) || isDueByToday(t))

/**
 * The Sort by index of the Show Options (0 Priority, Due Date; 1 Due Date, Priority;
 * 2 Category, Priority; 3 Category, Due Date). Reads the old "todoSort" index when "todoSortBy" is not set.
 */
fun todoSortIndex(): Int {
	val saved = Settings.getInt("todoSortBy", -1)
	if (saved >= 0) return saved.coerceAtMost(3)
	// The old order put Due Date, Priority first: its first two entries swap places.
	return when (val old = Settings.getInt("todoSort", 0)) {
		0 -> 1
		1 -> 0
		else -> old.coerceIn(2, 3)
	}
}

/** The sort order of the Show Options. */
fun todoOrder(): Comparator<Todo> {
	val byPriority = compareBy<Todo> { it.priority }
	val byDue = compareBy<Todo> { if (it.due < 0) Long.MAX_VALUE else it.due }
	val byCategory = compareBy<Todo> { App.store.categoryName(it.category).lowercase() }
	return when (todoSortIndex()) {
		1 -> byDue.thenComparing(byPriority)
		2 -> byCategory.thenComparing(byPriority)
		3 -> byCategory.thenComparing(byDue)
		else -> byPriority.thenComparing(byDue)
	}
}

/** The due text of a To Do: M/d, with "!" when overdue, or "-" when no date. */
fun dueLabel(t: Todo): String {
	if (t.due < 0) return "-"
	val d = LocalDate.ofEpochDay(t.due)
	return "${d.monthValue}/${d.dayOfMonth}" + if (isOverdue(t)) "!" else ""
}
