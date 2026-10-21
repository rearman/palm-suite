package com.rearman.palmdesktop

import com.rearman.datebook.Address
import com.rearman.datebook.addressOrder
import org.json.JSONArray
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Desktop
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.net.URI
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/** The Address Book view: the Address List, the card of the selected address, and the Look up field. */
class AddressPanel : JPanel(BorderLayout()), AppView {
	companion object {
		private const val ALL = -1
		private const val UNFILED = 0
	}

	private class Choice(val label: String, val id: Int) {
		override fun toString() = label
	}

	private var selectedId: Long? = null   // the address the user selected, kept over a rebuild
	// True while the code sets widgets. Listeners ignore those changes.
	private var busy = false

	private val filterCombo = JComboBox<Choice>()
	private val listModel = DefaultListModel<Address>()
	private val list = JList(listModel)
	private val kind = RecordKind<Address>(
		name = "address",
		selected = { list.selectedValuesList },
		copyOf = { it.copy() },
		text = { a -> (listOf(a.listName(listByCompany())) + cardLines(a, customLabels())).joinToString("\n") },
		pasteRecords = { recs -> recs.forEach { App.store.save(it.copy(id = 0, uid = "")) }; App.refresh() },
		pasteText = null,
		delete = { recs -> recs.forEach { App.store.delete(it) }; App.refresh() },
	)
	private val transfer = RecordTransfer(kind)
	private val card = AddressCard()
	private val lookField = JTextField()
	private val editButton = JButton("Edit...")

	init {
		val title = JLabel("Address List").apply {
			font = Theme.caption
			border = BorderFactory.createCompoundBorder(Theme.sunken, BorderFactory.createEmptyBorder(1, 16, 1, 16))
		}
		val top = JPanel(BorderLayout()).apply {
			border = EmptyBorder(4, 4, 4, 4)
			add(title, BorderLayout.WEST)
			add(filterCombo, BorderLayout.EAST)
		}
		filterCombo.font = Theme.font
		filterCombo.addActionListener { if (!busy) setFilter((filterCombo.selectedItem as? Choice)?.id ?: ALL) }

		list.font = Theme.font
		list.fixedCellHeight = 16
		list.cellRenderer = AddressRowRenderer()
		list.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
		list.dragEnabled = false
		list.transferHandler = transfer
		list.addListSelectionListener {
			if (it.valueIsAdjusting) return@addListSelectionListener
			if (!busy) {
				selectedId = list.leadValue()?.id
				showCard()
			}
			updateButtons()
			App.positionChanged()
		}
		// A double click edits the lead row. The Delete key deletes the selected rows. A popup trigger opens the list menu.
		list.addMouseListener(object : MouseAdapter() {
			override fun mousePressed(e: MouseEvent) = showListMenu(e)
			override fun mouseReleased(e: MouseEvent) = showListMenu(e)
			override fun mouseClicked(e: MouseEvent) {
				if (e.clickCount == 2 && SwingUtilities.isLeftMouseButton(e)) editSelected()
			}
		})
		list.addKeyListener(object : KeyAdapter() {
			override fun keyPressed(e: KeyEvent) {
				if (e.keyCode != KeyEvent.VK_DELETE) return
				deleteSelected()
				e.consume()
			}
		})

		val scroll = JScrollPane(list)
		// The empty area below the rows is the viewport, so a popup there opens the same menu.
		scroll.viewport.addMouseListener(object : MouseAdapter() {
			override fun mousePressed(e: MouseEvent) = showListMenu(e)
			override fun mouseReleased(e: MouseEvent) = showListMenu(e)
		})

		val lookLabel = JLabel("Look up:").apply {
			font = Theme.font
			labelFor = lookField
		}
		lookField.font = Theme.font
		lookField.document.addDocumentListener(object : DocumentListener {
			override fun insertUpdate(e: DocumentEvent) = findByName(lookField.text)
			override fun removeUpdate(e: DocumentEvent) = findByName(lookField.text)
			override fun changedUpdate(e: DocumentEvent) = findByName(lookField.text)
		})
		val lookRow = JPanel(BorderLayout(4, 0)).apply {
			border = EmptyBorder(4, 4, 0, 4)
			add(lookLabel, BorderLayout.WEST)
			add(lookField, BorderLayout.CENTER)
		}

		val listByButton = JButton("List by...").apply {
			font = Theme.font
			mnemonic = KeyEvent.VK_L
			displayedMnemonicIndex = 0
			addActionListener { ListByDialog.show(this@AddressPanel) }
		}
		editButton.apply {
			font = Theme.font
			mnemonic = KeyEvent.VK_I
			displayedMnemonicIndex = 2
			isEnabled = false
			addActionListener { editSelected() }
		}
		val newButton = JButton("New...").apply {
			font = Theme.font
			mnemonic = KeyEvent.VK_N
			displayedMnemonicIndex = 0
			addActionListener { newRecord() }
		}
		val bottom = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 5)).apply {
			add(listByButton)
			add(editButton)
			add(newButton)
		}
		val south = JPanel(BorderLayout()).apply {
			add(lookRow, BorderLayout.NORTH)
			add(bottom, BorderLayout.SOUTH)
		}
		val listPanel = JPanel(BorderLayout()).apply {
			add(top, BorderLayout.NORTH)
			add(scroll, BorderLayout.CENTER)
			add(south, BorderLayout.SOUTH)
		}

		// The card pane. The divider draws the raised edge between the list and the card.
		val right = JPanel(BorderLayout()).apply {
			border = EmptyBorder(8, 8, 4, 8)
			add(card, BorderLayout.CENTER)
		}
		add(detailSplit(listPanel, right, "addressSplit", 302), BorderLayout.CENTER)   // 302 px: the AddressCard width of Palm Desktop

		App.onRefresh { reload() }
		reload()
	}

	override fun newRecord() {
		AddressDialog.edit(this, null)
	}

	override fun editSelected() {
		val a = list.leadValue() ?: return
		AddressDialog.edit(this, a)
	}

	override fun deleteSelected() {
		val picked = list.selectedValuesList
		if (picked.isEmpty()) return
		if (!confirmDelete(this, deleteQuestion(picked.size, "Delete the address?", "addresses"))) return
		selectedId = null
		kind.delete(picked)
	}

	override fun select(record: Any) {
		val a = record as? Address ?: return
		// The address shows only in its own category, so the filter goes to All when it does not match.
		val cat = Settings.getInt("addressCategory", ALL)
		if (cat != ALL && a.category != cat) Settings.setInt("addressCategory", ALL)
		selectedId = a.id
		App.refresh()
		val i = list.selectedIndex
		if (i >= 0) list.ensureIndexIsVisible(i)
		list.requestFocusInWindow()
	}

	override fun printLines(): Pair<String, List<String>> {
		val byCompany = listByCompany()
		val lines = visibleAddresses().map { a ->
			val name = a.listName(byCompany)
			val phone = a.listPhone()
			if (phone == null) name else "$name  ${phone.first} ${phone.second}"
		}
		return "Address List" to lines
	}

	override fun position(): String {
		val i = list.selectedIndex
		return if (i < 0) "" else "${i + 1} of ${listModel.size()}"
	}

	private fun reload() {
		refreshCombos()
		rebuildList()
	}

	/** Fills the list with the visible addresses and selects [selectedId] when it is shown. */
	private fun rebuildList() {
		val shown = visibleAddresses()
		quiet {
			listModel.clear()
			shown.forEach { listModel.addElement(it) }
			val idx = selectedId?.let { id -> shown.indexOfFirst { it.id == id } } ?: -1
			if (idx >= 0) {
				list.selectedIndex = idx
			} else if (shown.isNotEmpty()) {
				// As in Palm Desktop, the card always shows a record: the first one when none is selected.
				list.selectedIndex = 0
				selectedId = shown[0].id
			} else {
				list.clearSelection()
				selectedId = null
			}
		}
		showCard()
		updateButtons()
		App.positionChanged()
	}

	/** Rebuilds the category combo. The Settings "addressCategory" value picks its item. */
	private fun refreshCombos() {
		val cur = Settings.getInt("addressCategory", ALL)
		quiet {
			val items = listOf(Choice("All", ALL)) + App.store.categoriesFor(2).map { Choice(it.name, it.id) } + Choice("Unfiled", UNFILED)
			filterCombo.model = CategoryModel(items, Choice(EDIT_CATEGORIES, -2)) { CategoriesDialog.show(this, 2) }
			val pick = items.firstOrNull { it.id == cur }
			if (pick == null && cur != ALL) Settings.setInt("addressCategory", ALL)   // the category was deleted
			filterCombo.selectedItem = pick ?: items[0]
		}
	}

	private fun setFilter(id: Int) {
		Settings.setInt("addressCategory", id)
		App.refresh()
	}

	/** Selects the first address whose list name starts with [text], ignoring case. */
	private fun findByName(text: String) {
		if (text.isEmpty()) return
		val byCompany = listByCompany()
		val i = (0 until listModel.size()).firstOrNull {
			listModel.get(it).listName(byCompany).startsWith(text, ignoreCase = true)
		} ?: return
		list.selectedIndex = i
		list.ensureIndexIsVisible(i)
	}

	private fun showCard() {
		card.address = list.leadValue()
	}

	private fun updateButtons() {
		editButton.isEnabled = list.leadValue() != null
	}

	/** The phone slots of [a] that Dial offers: a non-blank number whose label is not E-mail. */
	private fun phoneSlots(a: Address): List<Int> =
		(0 until 5).filter { a.phones.getOrElse(it) { "" }.isNotBlank() && a.phoneLabels[it] != Address.EMAIL }

	/** Opens the list menu on a popup trigger. A right click on a row that is not selected selects that row alone. */
	private fun showListMenu(e: MouseEvent) {
		if (!e.isPopupTrigger) return
		val p = SwingUtilities.convertPoint(e.component, e.point, list)
		list.selectForPopup(p)
		val lead = list.leadValue()
		val menu = listMenu(
			list, transfer,
			delete = { deleteSelected() },
			selectAll = { list.setSelectionInterval(0, listModel.size() - 1) },
			extra = listOf(
				editItem("Edit...", 'E', lead != null) { editSelected() },
				editItem("Dial...", 'i', lead != null && phoneSlots(lead).isNotEmpty()) { lead?.let { dial(it) } },
			),
		)
		menu.show(e.component, e.x, e.y)
	}

	/**
	 * The Dial dialog: the phones of [a] that have a number, the list number first. Palm Desktop dialed through a
	 * modem; a tel: link goes to the calling app of the system.
	 */
	private fun dial(a: Address) {
		val slots = phoneSlots(a)
		if (slots.isEmpty()) return
		val combo = JComboBox(slots.map { "${Address.LABELS[a.phoneLabels[it]]}: ${a.phones[it]}" }.toTypedArray())
		combo.font = Theme.font
		combo.selectedIndex = slots.indexOf(a.showPhone).coerceAtLeast(0)
		val pick = JOptionPane.showOptionDialog(
			this, combo, "Dial", JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, arrayOf("Dial", "Cancel"), "Dial"
		)
		if (pick != 0) return
		val digits = a.phones[slots[combo.selectedIndex]].filter { it.isDigit() || it == '+' }
		val opened = runCatching {
			Desktop.isDesktopSupported() &&
				Desktop.getDesktop().isSupported(Desktop.Action.BROWSE) &&
				run { Desktop.getDesktop().browse(URI("tel:$digits")); true }
		}.getOrDefault(false)
		if (!opened) JOptionPane.showMessageDialog(this, "This computer has no app that makes phone calls.", "Dial", JOptionPane.INFORMATION_MESSAGE)
	}

	private inline fun quiet(block: () -> Unit) {
		val was = busy
		busy = true
		try {
			block()
		} finally {
			busy = was
		}
	}

	/** The details card of the selected address: the name and the category, then the filled fields. */
	private inner class AddressCard : JPanel() {
		var address: Address? = null
			set(value) {
				field = value
				repaint()
			}

		init {
			// Palm Desktop draws the card on the panel gray, inside a sunken edge.
			background = Theme.panel
			border = Theme.sunken
		}

		override fun paintComponent(g: Graphics) {
			super.paintComponent(g)
			val a = address ?: return
			val nameFm = getFontMetrics(Theme.cardName)
			val textFm = getFontMetrics(Theme.cardText)
			val pad = 6
			val name = listOf(a.firstName, a.lastName).filter { it.isNotBlank() }.joinToString(" ").ifBlank { a.company }
			g.color = Color.BLACK
			g.font = Theme.cardName
			var base = pad + nameFm.ascent
			g.drawString(name, pad, base)
			// The category sits at the right end of the name line.
			g.font = Theme.cardText
			val cat = App.store.categoryName(a.category)
			g.drawString(cat, width - pad - textFm.stringWidth(cat), base)
			base += nameFm.descent + 4 + textFm.ascent
			for (line in cardLines(a, customLabels())) {
				g.drawString(line, pad, base)
				base += textFm.height
			}
		}
	}
}

/**
 * One row of the Address List, laid out as Palm Desktop 3.1.1 does. The number column is as wide as
 * the widest number of the list and ends [RIGHT] px from the right edge; the name gets the rest. When
 * the list is too narrow, the number column takes at most [MAX_SHARE] of the room and both columns end
 * in "...", the name part by part ("Fitzger..., Alexan..."). The letter after the number always shows.
 * A selected row is filled from the left edge to the end of the number.
 */
class AddressRowRenderer : ListCellRenderer<Address> {
	private val row = Row()

	private companion object {
		const val RIGHT = 36          // gap after the number column, measured in Palm Desktop
		const val MAX_SHARE = 0.55    // the most of a narrow row the number column takes
	}

	override fun getListCellRendererComponent(
		list: JList<out Address>, value: Address?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
	): Component {
		val fm = list.getFontMetrics(Theme.font)
		val byCompany = listByCompany()
		row.rowName = value?.listName(byCompany) ?: ""
		row.rowNumber = value?.listPhone()?.first ?: ""
		row.rowLetter = value?.listPhone()?.second?.toString() ?: ""
		row.rowSelected = isSelected
		// The widest "number L" of the list sets the column. Limit: it measures every row for each
		// painted row, fine for a few hundred addresses; cache it by the model to go further.
		val model = list.model
		val widest = (0 until model.size).maxOfOrNull { i ->
			model.getElementAt(i).listPhone()?.let { fm.stringWidth("${it.first} ${it.second}") } ?: 0
		} ?: 0
		val room = (list.width - RIGHT).coerceAtLeast(0)
		row.numberWidth = minOf(widest, (room * MAX_SHARE).toInt())
		row.numberX = room - row.numberWidth
		return row
	}

	private inner class Row : JPanel() {
		var rowName = ""
		var rowNumber = ""
		var rowLetter = ""
		var rowSelected = false
		var numberX = 0
		var numberWidth = 0

		init {
			font = Theme.font
		}

		/** [text] cut to [w] px with "..." at its end, as Palm Desktop cuts a list column. */
		private fun fit(text: String, w: Int, fm: java.awt.FontMetrics): String {
			if (fm.stringWidth(text) <= w) return text
			var n = text.length
			while (n > 0 && fm.stringWidth(text.take(n) + "...") > w) n--
			return text.take(n) + "..."
		}

		/** The list name cut to [w] px. "Last, First" is cut part by part, so both parts keep their start. */
		private fun fitName(w: Int, fm: java.awt.FontMetrics): String {
			if (fm.stringWidth(rowName) <= w) return rowName
			val parts = rowName.split(", ", limit = 2)
			if (parts.size < 2) return fit(rowName, w, fm)
			val each = (w - fm.stringWidth(", ")) / 2
			return fit(parts[0], each, fm) + ", " + fit(parts[1], each, fm)
		}

		override fun paintComponent(g: Graphics) {
			g.color = Color.WHITE
			g.fillRect(0, 0, width, height)
			val fm = getFontMetrics(font)
			val baseline = (height - fm.height) / 2 + fm.ascent
			val letterW = if (rowLetter.isEmpty()) 0 else fm.stringWidth(" $rowLetter")
			val number = if (rowNumber.isEmpty()) "" else fit(rowNumber, numberWidth - letterW, fm) + " $rowLetter"
			if (rowSelected) {
				val end = if (number.isEmpty()) numberX else numberX + fm.stringWidth(number) + 4
				g.color = Theme.select
				g.fillRect(0, 0, end, height)
			}
			g.color = if (rowSelected) Color.WHITE else Color.BLACK
			// The name stops 6 px short of the number column.
			g.drawString(fitName(numberX - 9, fm), 3, baseline)
			g.drawString(number, numberX, baseline)
		}
	}
}

/** The four custom field labels, from the Settings "addressLabels" JSON array. A blank or missing label gives "Custom 1" to "Custom 4". */
fun customLabels(): List<String> {
	val saved = runCatching { JSONArray(Settings.get("addressLabels", "[]")) }.getOrNull()
	return List(4) { i ->
		val text = saved?.optString(i, "")?.trim().orEmpty()
		text.ifEmpty { "Custom ${i + 1}" }
	}
}

/** The addresses the Address List shows: the category of Settings "addressCategory" (-1 is All), private ones only when App.showPrivate, in list order. */
fun visibleAddresses(): List<Address> {
	val cat = Settings.getInt("addressCategory", -1)
	val shown = App.store.addresses.filter { (App.showPrivate || !it.isPrivate) && (cat == -1 || it.category == cat) }
	return addressOrder(shown, listByCompany())
}

/** True when the Address List sorts by company (Settings "addressListBy" 1). */
private fun listByCompany() = Settings.getInt("addressListBy", 0) == 1

/** The title of an event made by dragging [a] to the Day view: the name and the number of its Address List row. */
internal fun eventTitle(a: Address, byCompany: Boolean = listByCompany()): String =
	listOfNotNull(a.listName(byCompany), a.listPhone()?.first).joinToString(" ")

/** The text lines of the card below the name. Empty fields are skipped. */
private fun cardLines(a: Address, labels: List<String>): List<String> {
	val lines = mutableListOf<String>()
	fun add(text: String) {
		if (text.isNotBlank()) lines.add(text)
	}
	add(a.title)
	add(a.company)
	for (i in 0 until 5) {
		val number = a.phones.getOrElse(i) { "" }
		if (number.isNotBlank()) lines.add("${Address.LABELS[a.phoneLabels[i]]}:  $number")
	}
	a.address.lines().forEach { add(it) }
	val head = listOf(a.city, a.state).filter { it.isNotBlank() }.joinToString(", ")
	add(listOf(head, a.zip).filter { it.isNotBlank() }.joinToString(" "))
	add(a.country)
	for (i in 0 until 4) {
		val value = a.custom.getOrElse(i) { "" }
		if (value.isNotBlank()) lines.add("${labels[i]}: $value")
	}
	a.note.lines().forEach { add(it) }
	return lines
}
