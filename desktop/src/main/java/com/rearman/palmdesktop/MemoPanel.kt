package com.rearman.palmdesktop

import com.rearman.datebook.*
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.GridLayout
import java.awt.Insets
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.*
import javax.swing.border.EmptyBorder

/** The Memo Pad view. */
class MemoPanel : JPanel(BorderLayout()), AppView {
	companion object {
		private const val ALL = -1
		private const val UNFILED = 0
	}

	private class Choice(val label: String, val id: Int) {
		override fun toString() = label
	}

	// The memo shown in the editor. Edits go to it on Apply or on a change of selection.
	private var current: Memo? = null
	private var filterId = Settings.getInt("memoCategory", ALL)
	// True while the code sets widgets. Listeners ignore those changes.
	private var busy = false

	private val filterCombo = JComboBox<Choice>()
	private val listModel = DefaultListModel<Memo>()
	private val list = JList(listModel)
	private val editor = JTextArea()
	private val category = JComboBox<Choice>()
	private val privateBox = JCheckBox()
	private val applyButton = JButton("Apply")

	init {
		val title = JLabel("Memo Pad").apply {
			font = Theme.caption
			border = BorderFactory.createCompoundBorder(Theme.sunken, BorderFactory.createEmptyBorder(1, 16, 1, 16))
		}
		val top = JPanel(BorderLayout()).apply {
			border = EmptyBorder(4, 4, 4, 4)
			add(title, BorderLayout.WEST)
			add(filterCombo, BorderLayout.EAST)
		}

		list.font = Theme.font
		list.fixedCellHeight = 16
		list.cellRenderer = MemoCell()
		list.addListSelectionListener {
			if (!it.valueIsAdjusting) {
				App.positionChanged()
				if (!busy) onPick(list.selectedValue)
			}
		}
		// A printable key starts a new memo that holds the typed character, as on the Palm.
		list.addKeyListener(object : KeyAdapter() {
			override fun keyTyped(e: KeyEvent) {
				if (e.isControlDown || e.isAltDown || e.keyChar.isISOControl()) return
				newRecord()
				editor.text = e.keyChar.toString()
				editor.caretPosition = editor.text.length
				e.consume()
			}
		})

		editor.lineWrap = true
		editor.wrapStyleWord = true
		editor.font = Theme.memo
		category.font = Theme.font
		applyButton.font = Theme.font
		applyButton.mnemonic = KeyEvent.VK_A
		applyButton.addActionListener { applyEdits() }
		filterCombo.addActionListener { if (!busy) setFilter(choiceId(filterCombo)) }
		val form = JPanel(GridBagLayout())
		val gbc = GridBagConstraints().apply { insets = Insets(2, 4, 2, 4); anchor = GridBagConstraints.EAST }
		form.add(JLabel("Category:").apply { font = Theme.font; setDisplayedMnemonic('C'); labelFor = category }, gbc)
		gbc.gridx = 1; gbc.anchor = GridBagConstraints.WEST; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
		form.add(category, gbc)
		gbc.gridx = 0; gbc.gridy = 1; gbc.anchor = GridBagConstraints.EAST; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0
		form.add(JLabel("Private:").apply { font = Theme.font; setDisplayedMnemonic('v'); labelFor = privateBox }, gbc)
		gbc.gridx = 1; gbc.anchor = GridBagConstraints.WEST
		form.add(privateBox, gbc)
		gbc.gridx = 2; gbc.gridy = 1; gbc.anchor = GridBagConstraints.WEST
		form.add(applyButton, gbc)
		val right = JPanel(BorderLayout()).apply {
			border = BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, Theme.grid), BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, Color.WHITE), EmptyBorder(8, 8, 4, 8)))
			add(JScrollPane(editor), BorderLayout.CENTER)
			add(form, BorderLayout.SOUTH)
		}

		val listByButton = JButton("List by...").apply { font = Theme.font; mnemonic = KeyEvent.VK_L; addActionListener { listBy() } }
		val newButton = JButton("New").apply { font = Theme.font; mnemonic = KeyEvent.VK_N; addActionListener { newRecord() } }
		val bottom = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 5)).apply {
			add(listByButton)
			add(newButton)
		}
		val listPanel = JPanel(BorderLayout()).apply {
			add(top, BorderLayout.NORTH)
			add(JScrollPane(list), BorderLayout.CENTER)
			add(bottom, BorderLayout.SOUTH)
		}
		add(detailSplit(listPanel, right, "memoSplit", 315), BorderLayout.CENTER)

		refreshCombos()
		loadEditor(null)
		reflow(null, false)
		App.onRefresh { reflow(current, hasChanges()) }
	}

	override fun newRecord() {
		saveIfChanged()
		val m = Memo(text = "", category = if (filterId > UNFILED) filterId else UNFILED)
		App.store.save(m)
		loadEditor(m)
		App.refresh()
		editor.requestFocusInWindow()
	}

	override fun editSelected() {
		if (current != null) editor.requestFocusInWindow()
	}

	override fun deleteSelected() {
		val m = current ?: return
		if (!confirmDelete(this, "Delete the memo?")) return
		loadEditor(null)
		App.store.delete(m)
		App.refresh()
	}

	override fun select(record: Any) {
		val m = record as? Memo ?: return
		saveIfChanged()
		if (filterId != ALL && m.category != filterId) {
			filterCombo.selectedItem = choiceFor(filterCombo, ALL)
		}
		loadEditor(m)
		reflow(m, false)
	}

	override fun printLines(): Pair<String, List<String>> {
		if (current == null) {
			val titles = visibleMemos().mapIndexed { i, m -> "${i + 1}. ${m.title}" }
			return "Memo Pad" to titles
		}
		val body = editor.text
		return body.lineSequence().firstOrNull()?.trim().orEmpty() to body.lines()
	}

	override fun position(): String {
		val i = list.selectedIndex
		return if (i < 0) "" else "${i + 1} of ${listModel.size()}"
	}

	/** Rebuilds the list and the combo boxes. Keeps the editor when it holds unsaved text. */
	private fun reflow(old: Memo?, dirtyEditor: Boolean) {
		refreshCombos()
		val hit = rebuildList(old)
		when {
			hit === old -> Unit
			dirtyEditor && hit != null -> current = hit
			else -> loadEditor(hit)
		}
	}

	private fun onPick(next: Memo?) {
		if (next?.id == current?.id) return
		saveIfChanged()
		loadEditor(next)
		pick(next)
	}

	private fun setFilter(id: Int) {
		saveIfChanged()
		filterId = id
		Settings.setInt("memoCategory", id)
		reflow(current, false)
	}

	fun listBy() {
		val manual = JRadioButton("Order on handheld").apply { mnemonic = KeyEvent.VK_O; font = Theme.font }
		val alpha = JRadioButton("Alphabetical").apply { mnemonic = KeyEvent.VK_A; font = Theme.font }
		ButtonGroup().apply { add(manual); add(alpha) }
		if (Settings.get("memoSort", "0") == "1") alpha.isSelected = true else manual.isSelected = true
		val panel = JPanel(GridLayout(0, 1)).apply {
			border = BorderFactory.createTitledBorder("Record Order")
			add(manual)
			add(alpha)
		}
		val ok = JOptionPane.showConfirmDialog(this, panel, "List By", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
		if (ok != JOptionPane.OK_OPTION) return
		Settings.set("memoSort", if (alpha.isSelected) "1" else "0")
		App.refresh()
	}

	private fun visibleMemos(): List<Memo> {
		val shown = App.store.memos.filter { App.showPrivate || !it.isPrivate }
			.filter { filterId == ALL || it.category == filterId }
		return memoOrder(shown, Settings.get("memoSort", "0") == "1")
	}

	/** Fills the list with the visible memos and selects [want] when it is shown. */
	private fun rebuildList(want: Memo?): Memo? {
		val shown = visibleMemos()
		quiet {
			listModel.clear()
			shown.forEach { listModel.addElement(it) }
		}
		val hit = want?.let { w -> shown.firstOrNull { it.id == w.id } }
		pick(hit)
		App.positionChanged()
		return hit
	}

	private fun pick(m: Memo?) {
		val index = if (m == null) -1 else (0 until listModel.size()).firstOrNull { listModel.get(it).id == m.id } ?: -1
		quiet { list.selectedIndex = index }
	}

	private fun refreshCombos() {
		val keep = choiceId(category)
		quiet {
			val filters = listOf(Choice("All", ALL)) + App.store.memoCategories.map { Choice(it.name, it.id) } + Choice("Unfiled", UNFILED)
			filterCombo.model = CategoryModel(filters, Choice(EDIT_CATEGORIES, -2)) { CategoriesDialog.show(this, 1) }
			filterId = choiceFor(filterCombo, filterId).id
			filterCombo.selectedItem = choiceFor(filterCombo, filterId)
			val cats = listOf(Choice("Unfiled", UNFILED)) + App.store.memoCategories.map { Choice(it.name, it.id) }
			category.model = CategoryModel(cats, Choice(EDIT_CATEGORIES, -2)) { CategoriesDialog.show(this, 1) }
			category.selectedItem = choiceFor(category, keep)
		}
	}

	private fun loadEditor(m: Memo?) {
		current = m
		quiet {
			editor.text = m?.text ?: ""
			editor.caretPosition = 0
			category.selectedItem = choiceFor(category, m?.category ?: UNFILED)
			privateBox.isSelected = m?.isPrivate ?: false
		}
		val on = m != null
		editor.isEnabled = on
		category.isEnabled = on
		privateBox.isEnabled = on
		applyButton.isEnabled = on
	}

	private fun hasChanges(): Boolean {
		val m = current ?: return false
		return editor.text != m.text || choiceId(category) != m.category || privateBox.isSelected != m.isPrivate
	}

	private fun saveIfChanged() {
		if (hasChanges()) applyEdits()
	}

	/** Writes the editor to the current memo. A memo with blank text is deleted, as on the Palm. */
	private fun applyEdits() {
		val m = current ?: return
		m.text = editor.text
		m.category = choiceId(category)
		m.isPrivate = privateBox.isSelected
		if (m.text.isBlank()) App.store.delete(m) else App.store.save(m)
		App.refresh()
	}

	private fun choiceId(box: JComboBox<Choice>): Int = (box.selectedItem as? Choice)?.id ?: UNFILED

	/** The item with [id], or the first item when no item has it. */
	private fun choiceFor(box: JComboBox<Choice>, id: Int): Choice {
		for (i in 0 until box.itemCount) {
			val c = box.getItemAt(i)
			if (c.id == id) return c
		}
		return box.getItemAt(0)
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

	/** One line of the Memo List: the memo icon, the first line, and the key icon at the right for a private memo. */
	private inner class MemoCell : JPanel(BorderLayout()), ListCellRenderer<Memo> {
		private val titleLabel = JLabel().apply { font = Theme.font; iconTextGap = 4 }
		private val keyLabel = JLabel()
		private val memoIcon = Icons.note()

		init {
			isOpaque = true
			add(titleLabel, BorderLayout.CENTER)
			add(keyLabel, BorderLayout.EAST)
		}

		override fun getListCellRendererComponent(
			list: JList<out Memo>, value: Memo?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
		): Component {
			if (value == null) return this
			titleLabel.text = value.title
			titleLabel.icon = memoIcon
			keyLabel.icon = if (value.isPrivate) Icons.privateRecord() else null
			background = if (isSelected) list.selectionBackground else list.background
			foreground = if (isSelected) list.selectionForeground else list.foreground
			titleLabel.foreground = foreground
			return this
		}
	}
}
