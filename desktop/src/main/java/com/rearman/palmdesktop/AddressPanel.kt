package com.rearman.palmdesktop

import com.rearman.datebook.Address
import com.rearman.datebook.addressOrder
import org.json.JSONArray
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/** The Address Book view: the Address List, the card of the selected address, and the Look up field. */
class AddressPanel : JPanel(BorderLayout()), AppView {
	companion object {
		private const val ALL = -1
		private const val UNFILED = 0
		// The list name takes this share of the list width. The number column starts there.
		private const val NAME_SHARE = 0.86
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
		list.cellRenderer = AddressRowRenderer(200)
		// The renderer takes the list width, so it is made again on each resize.
		list.addComponentListener(object : ComponentAdapter() {
			override fun componentResized(e: ComponentEvent) {
				list.cellRenderer = AddressRowRenderer((list.width * NAME_SHARE).toInt())
			}
		})
		list.addListSelectionListener {
			if (it.valueIsAdjusting) return@addListSelectionListener
			if (!busy) {
				selectedId = list.selectedValue?.id
				showCard()
			}
			updateButtons()
			App.positionChanged()
		}
		// A double click edits the row. The Delete key deletes it.
		list.addMouseListener(object : MouseAdapter() {
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
		add(listPanel, BorderLayout.CENTER)

		// The card pane, with the raised edge between the list and the card as in the Memo Pad.
		val right = JPanel(BorderLayout()).apply {
			preferredSize = Dimension(315, 0)
			border = BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(0, 1, 0, 0, Theme.grid),
				BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, Color.WHITE), EmptyBorder(8, 8, 4, 8))
			)
			add(card, BorderLayout.CENTER)
		}
		add(right, BorderLayout.EAST)

		App.onRefresh { reload() }
		reload()
	}

	override fun newRecord() {
		AddressDialog.edit(this, null)
	}

	override fun editSelected() {
		val a = list.selectedValue ?: return
		AddressDialog.edit(this, a)
	}

	override fun deleteSelected() {
		val a = list.selectedValue ?: return
		if (!confirmDelete(this, "Delete the address?")) return
		selectedId = null
		App.store.delete(a)
		App.refresh()
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
		card.address = list.selectedValue
	}

	private fun updateButtons() {
		editButton.isEnabled = list.selectedValue != null
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
			background = Color.WHITE
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
 * One row of the Address List: the list name in [nameWidth] px, then the number and its letter.
 * A selected row is filled from the left edge to the end of the number.
 */
class AddressRowRenderer(private val nameWidth: Int) : ListCellRenderer<Address> {
	private val row = Row()

	override fun getListCellRendererComponent(
		list: JList<out Address>, value: Address?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
	): Component {
		row.rowName = value?.listName(listByCompany()) ?: ""
		row.rowNumber = value?.listPhone()?.let { "${it.first} ${it.second}" } ?: ""
		row.rowSelected = isSelected
		return row
	}

	private inner class Row : JPanel() {
		var rowName = ""
		var rowNumber = ""
		var rowSelected = false

		init {
			font = Theme.font
		}

		override fun paintComponent(g: Graphics) {
			g.color = Color.WHITE
			g.fillRect(0, 0, width, height)
			val fm = getFontMetrics(font)
			val baseline = (height - fm.height) / 2 + fm.ascent
			if (rowSelected) {
				val end = if (rowNumber.isEmpty()) nameWidth else nameWidth + fm.stringWidth(rowNumber) + 4
				g.color = Theme.select
				g.fillRect(0, 0, end, height)
			}
			g.color = if (rowSelected) Color.WHITE else Color.BLACK
			// The name stops 4 px short of the number column.
			val clip = g.create()
			clip.clipRect(0, 0, nameWidth - 4, height)
			clip.drawString(rowName, 3, baseline)
			clip.dispose()
			g.drawString(rowNumber, nameWidth, baseline)
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
