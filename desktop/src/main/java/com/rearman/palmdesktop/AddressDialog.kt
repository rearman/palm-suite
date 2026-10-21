package com.rearman.palmdesktop

import com.rearman.datebook.Address
import org.json.JSONArray
import org.json.JSONException
import java.awt.Component
import java.awt.Container
import java.awt.Dialog
import java.awt.Dimension
import java.awt.event.KeyEvent
import javax.swing.*

/** Edit Address dialog, after the Palm Desktop 3.1.1 Edit Address dialog. */
object AddressDialog {
	/** Opens Edit Address for [a]. A null [a] starts a new address in the category of the Address List. */
	fun edit(owner: Component, a: Address?) {
		AddressForm(owner, a).show()
	}
}

/** The Edit Address form. [orig] is null for a new address. The form fields hold the working copy. */
private class AddressForm(private val owner: Component, private var orig: Address?) {
	private val dlg = modalDialog(owner, "Edit Address")
	private val cats = App.store.categoriesFor(2).toList()
	private val catIds = listOf(0) + cats.map { it.id }
	private val labelNames = Address.LABELS.map { "$it:" }.toTypedArray()
	private val customNames = customLabels()

	private val lastNameField = JTextField().themed()
	private val firstNameField = JTextField().themed()
	private val titleField = JTextField().themed()
	private val companyField = JTextField().themed()
	private val showGroup = ButtonGroup()
	private val showRadios = List(5) { i ->
		JRadioButton().themed().apply {
			showGroup.add(this)
			accessibleContext.accessibleName = "Show phone ${i + 1}"
		}
	}
	private val labelBoxes = List(5) { JComboBox(labelNames).themed() }
	private val phoneFields = List(5) { JTextField().themed() }
	private val defaultBox = JCheckBox("Save as default").themed().apply {
		mnemonic = KeyEvent.VK_V
		displayedMnemonicIndex = 2
	}

	private val addressArea = wrapArea(4)
	private val cityField = JTextField().themed()
	private val stateField = JTextField().themed()
	private val zipField = JTextField().themed()
	private val countryField = JTextField().themed()
	private val customFields = List(4) { JTextField().themed() }

	private val noteArea = wrapArea(10)

	private val catBox = JComboBox((listOf("Unfiled") + cats.map { it.name }).toTypedArray()).themed().apply {
		accessibleContext.accessibleName = "Category"
		maximumRowCount = 30
	}
	private val privateBox = JCheckBox("Private").themed().apply {
		mnemonic = KeyEvent.VK_P
		displayedMnemonicIndex = 0
	}
	private val okBtn = JButton("OK").themed().apply {
		mnemonic = KeyEvent.VK_O
		addActionListener { save(); dlg.dispose() }
	}
	private val cancelBtn = JButton("Cancel").themed().apply {
		mnemonic = KeyEvent.VK_C
		addActionListener { dlg.dispose() }
	}
	private val newBtn = JButton("New").themed().apply {
		mnemonic = KeyEvent.VK_W
		displayedMnemonicIndex = 2
		addActionListener { save(); clearForNew() }
	}

	init {
		fill(orig ?: Address(category = newCategory(), phoneLabels = defaultLabels()))
		buildLayout()
	}

	fun show() {
		dlg.isVisible = true
	}

	private fun buildLayout() {
		val namePage = JPanel(null).themed().apply {
			put(keyLabel("Last name:", 'L', lastNameField), 14, 10, 66, 21)
			put(lastNameField, 83, 10, 221, 21)
			put(keyLabel("First name:", 'F', firstNameField), 14, 33, 66, 21)
			put(firstNameField, 83, 33, 221, 21)
			put(keyLabel("Title:", 'T', titleField), 14, 55, 66, 21)
			put(titleField, 83, 55, 221, 21)
			put(keyLabel("Company:", 'y', companyField), 14, 78, 66, 21)
			put(companyField, 83, 78, 221, 21)
			put(keyLabel("Show in list:", 'h', showRadios[0]), 14, 107, 90, 21)
			val rows = intArrayOf(127, 151, 177, 203, 229)
			for (i in 0 until 5) {
				put(showRadios[i], 60, rows[i], 18, 21)
				put(labelBoxes[i], 83, rows[i], 72, 21)
				put(phoneFields[i], 158, rows[i], 144, 21)
			}
			put(defaultBox, 60, 260, 130, 18)
		}

		val addressPage = JPanel(null).themed().apply {
			put(keyLabel("Address:", 'd', addressArea), 14, 10, 66, 21)
			put(JScrollPane(addressArea), 83, 10, 221, 88)
			put(keyLabel("City:", 'i', cityField), 14, 101, 66, 21)
			put(cityField, 83, 101, 221, 21)
			put(keyLabel("State:", 'S', stateField), 14, 122, 66, 21)
			put(stateField, 83, 122, 221, 21)
			put(keyLabel("Zip Code:", 'Z', zipField), 14, 143, 66, 21)
			put(zipField, 83, 143, 221, 21)
			put(keyLabel("Country:", 'u', countryField), 14, 164, 66, 21)
			put(countryField, 83, 164, 221, 21)
			val customY = intArrayOf(198, 219, 241, 262)
			for (i in 0 until 4) {
				put(JLabel("${customNames[i]}:").themed(), 14, customY[i], 66, 21)
				put(customFields[i], 84, customY[i], 221, 21)
			}
		}

		val notePage = JPanel(null).themed().apply {
			put(JScrollPane(noteArea), 11, 37, 290, 250)
		}

		val tabs = JTabbedPane().themed().apply {
			addTab("Name", namePage)
			addTab("Address", addressPage)
			addTab("Note", notePage)
			setMnemonicAt(0, KeyEvent.VK_N)
			setMnemonicAt(1, KeyEvent.VK_A)
			setMnemonicAt(2, KeyEvent.VK_O)
			setDisplayedMnemonicIndexAt(0, 0)
			setDisplayedMnemonicIndexAt(1, 0)
			setDisplayedMnemonicIndexAt(2, 1)
		}

		val pane = dlg.contentPane
		pane.layout = null
		pane.preferredSize = Dimension(454, 365)
		pane.put(tabs, 9, 28, 326, 328)
		pane.put(okBtn, 342, 47, 105, 24)
		pane.put(cancelBtn, 342, 77, 105, 24)
		pane.put(newBtn, 342, 107, 105, 24)
		pane.put(catBox, 342, 311, 105, 22)
		pane.put(privateBox, 342, 341, 105, 21)
		dlg.rootPane.defaultButton = okBtn
		dlg.isResizable = false
		dlg.pack()
		dlg.setLocationRelativeTo(owner)
	}

	/** Fills every field from [a]. */
	private fun fill(a: Address) {
		lastNameField.text = a.lastName
		firstNameField.text = a.firstName
		titleField.text = a.title
		companyField.text = a.company
		for (i in 0 until 5) {
			labelBoxes[i].selectedIndex = a.phoneLabels[i]
			phoneFields[i].text = a.phones[i]
		}
		showRadios[a.showPhone.coerceIn(0, 4)].isSelected = true
		addressArea.text = a.address
		cityField.text = a.city
		stateField.text = a.state
		zipField.text = a.zip
		countryField.text = a.country
		for (i in 0 until 4) customFields[i].text = a.custom[i]
		noteArea.text = a.note
		catBox.selectedIndex = catIds.indexOf(a.category).coerceAtLeast(0)
		privateBox.isSelected = a.isPrivate
	}

	/** The form as a record. An old record keeps its id and uid. */
	private fun collect(): Address = (orig?.copy() ?: Address()).apply {
		lastName = lastNameField.text.trim()
		firstName = firstNameField.text.trim()
		title = titleField.text.trim()
		company = companyField.text.trim()
		phoneLabels = labelBoxes.map { it.selectedIndex }
		phones = phoneFields.map { it.text.trim() }
		address = addressArea.text
		city = cityField.text.trim()
		state = stateField.text.trim()
		zip = zipField.text.trim()
		country = countryField.text.trim()
		custom = customFields.map { it.text.trim() }
		note = noteArea.text
		showPhone = showRadios.indexOfFirst { it.isSelected }.coerceAtLeast(0)
		category = catIds[catBox.selectedIndex]
		isPrivate = privateBox.isSelected
	}

	/**
	 * Saves the form. An old record is always saved. A new record is saved only when it has text.
	 * "Save as default" stores the five label choices for the next new address.
	 */
	private fun save() {
		if (defaultBox.isSelected) saveDefaultLabels(labelBoxes.map { it.selectedIndex })
		val a = collect()
		if (orig == null && a.isEmpty) return
		App.store.save(a)
		App.refresh()
	}

	/** Clears the form for another new address. The category stays. */
	private fun clearForNew() {
		val cat = catIds[catBox.selectedIndex]
		orig = null
		fill(Address(category = cat, phoneLabels = defaultLabels()))
	}

	/** The category of a new address: the one the Address List shows, else Unfiled. */
	private fun newCategory(): Int {
		val shown = Settings.getInt("addressCategory", -1)
		return if (shown > 0) shown else 0
	}
}

/** The five label choices of a new address. Work, Home, Fax, Other and E-mail when none is saved or the value is bad. */
private fun defaultLabels(): List<Int> {
	val text = Settings.get("addressDefaultLabels", "")
	if (text.isEmpty()) return listOf(0, 1, 2, 3, 4)
	return try {
		val arr = JSONArray(text)
		List(5) { arr.optInt(it, it).coerceIn(0, Address.LABELS.size - 1) }
	} catch (ex: JSONException) {
		listOf(0, 1, 2, 3, 4)
	}
}

/** Saves the five label choices as a JSON array in Settings "addressDefaultLabels". */
private fun saveDefaultLabels(labels: List<Int>) {
	Settings.set("addressDefaultLabels", JSONArray(labels).toString())
}

/** A modal dialog over the window that holds [owner]. Escape closes it. */
private fun modalDialog(owner: Component, title: String): JDialog =
	JDialog(SwingUtilities.getWindowAncestor(owner), title, Dialog.ModalityType.APPLICATION_MODAL).apply {
		defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
		rootPane.registerKeyboardAction(
			{ dispose() }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW
		)
	}

/** A label with a mnemonic that moves the focus to [target]. The letter [key] is underlined. */
private fun keyLabel(text: String, key: Char, target: Component): JLabel = JLabel(text).themed().apply {
	displayedMnemonic = key.uppercaseChar().code
	displayedMnemonicIndex = text.indexOf(key, ignoreCase = true)
	labelFor = target
}

/** A multi-line field that wraps its lines, in a scroll pane when it is added. */
private fun wrapArea(rows: Int): JTextArea = JTextArea(rows, 20).themed().apply {
	lineWrap = true
	wrapStyleWord = true
}

/** Adds [c] at the given position and size in pixels. The container has no layout. */
private fun Container.put(c: Component, x: Int, y: Int, w: Int, h: Int) {
	add(c)
	c.setBounds(x, y, w, h)
}

private fun <T : JComponent> T.themed(): T = apply { font = Theme.font }
