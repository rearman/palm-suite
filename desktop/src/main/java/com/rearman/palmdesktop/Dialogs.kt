package com.rearman.palmdesktop

import com.rearman.datebook.Category
import com.rearman.datebook.Event
import com.rearman.datebook.Fmt
import com.rearman.datebook.Memo
import com.rearman.datebook.Todo
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dialog
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.GridLayout
import java.awt.Insets
import java.awt.event.ActionListener
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.LocalDate
import javax.swing.AbstractButton
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.WindowConstants
import javax.swing.border.EmptyBorder
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

// Palm Desktop dialogs: Options, Edit Categories, Find and Purge.
// Each dialog is modal, Enter runs the default button and Escape closes it.

// Asks before a record is deleted. Returns true at once when Settings "confirmDelete" is off.
fun confirmDelete(owner: Component, message: String): Boolean {
	if (!Settings.getBool("confirmDelete", true)) return true
	val answer = JOptionPane.showConfirmDialog(owner, message, "Delete", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE)
	return answer == JOptionPane.YES_OPTION
}

// Options has two tabs: General (startup view and delete confirmation) and Sync.
// General values are saved on OK, and also when Set Up or Turn Off closes the dialog.
object OptionsDialog {
	private val startViews = listOf("date" to "Date Book", "todo" to "To Do List", "memo" to "Memo Pad")

	fun show(owner: Component, tab: String = "general") {
		val d = dialog(owner, "Options")
		val tabs = JTabbedPane().withFont()

		val startCombo = JComboBox(startViews.map { it.second }.toTypedArray()).withFont()
		startCombo.selectedIndex = startViews.indexOfFirst { it.first == Settings.get("startView", "date") }.coerceAtLeast(0)
		val confirmBox = JCheckBox("Confirm before deleting records", Settings.getBool("confirmDelete", true)).withFont().key('C')
		val general = JPanel(BorderLayout(0, 8)).apply {
			add(formPanel(listOf(keyed("Startup application:", 'p', startCombo) to startCombo)), BorderLayout.NORTH)
			add(JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply { add(confirmBox) }, BorderLayout.CENTER)
		}

		fun save() {
			Settings.set("startView", startViews[startCombo.selectedIndex].first)
			Settings.setBool("confirmDelete", confirmBox.isSelected)
		}

		val sync = App.sync
		val server = JTextField(sync.server, 24).withFont()
		// An empty Token field keeps the token saved on this computer, if there is one.
		val savedToken = Settings.get("token", "")
		val token = HintPasswordField(if (savedToken.isNotEmpty()) "saved" else "").withFont()
		val pass = JPasswordField(24).withFont()
		val status = label(syncStatus())
		val setUpBtn = button("Set Up").key('U').apply { isEnabled = false }
		val syncNowBtn = button("Sync Now").key('N').apply { isVisible = sync.isOn }
		val turnOffBtn = button("Turn Off").key('O').apply { isVisible = sync.isOn }
		var busy = false

		// Set Up needs both a token and a passphrase, and stays off while setup runs.
		fun refreshSetUp() {
			val hasToken = token.document.length > 0 || savedToken.isNotEmpty()
			setUpBtn.isEnabled = !busy && hasToken && pass.document.length > 0
		}
		val watch = object : DocumentListener {
			override fun insertUpdate(e: DocumentEvent) = refreshSetUp()
			override fun removeUpdate(e: DocumentEvent) = refreshSetUp()
			override fun changedUpdate(e: DocumentEvent) = refreshSetUp()
		}
		token.document.addDocumentListener(watch)
		pass.document.addDocumentListener(watch)

		setUpBtn.onClick {
			busy = true
			refreshSetUp()
			status.text = "Please wait."
			val typed = String(token.password).trim()
			sync.setUp(server.text.trim(), typed.ifEmpty { savedToken }, pass.password) { err ->
				busy = false
				if (err == null) {
					save()
					d.dispose()
					App.refresh()
				} else {
					status.text = syncStatus()
					refreshSetUp()
					JOptionPane.showMessageDialog(d, err, "Options", JOptionPane.ERROR_MESSAGE)
				}
			}
		}

		syncNowBtn.onClick {
			sync.syncNow { err ->
				SwingUtilities.invokeLater {
					status.text = syncStatus()
					JOptionPane.showMessageDialog(d, err ?: "Sync done.", "Options",
						if (err == null) JOptionPane.INFORMATION_MESSAGE else JOptionPane.ERROR_MESSAGE)
				}
			}
		}

		turnOffBtn.onClick {
			val answer = JOptionPane.showConfirmDialog(d, "Turn off sync on this computer? Your records stay here.", "Options", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE)
			if (answer != JOptionPane.YES_OPTION) return@onClick
			sync.turnOff()
			save()
			App.refresh()
			d.dispose()
		}

		val syncPanel = JPanel(BorderLayout(0, 8)).apply {
			add(formPanel(listOf(
				keyed("Server:", 'e', server) to server,
				keyed("Token:", 'T', token) to token,
				keyed("Passphrase:", 'a', pass) to pass,
				label("") to status,
			)), BorderLayout.NORTH)
			add(bar(setUpBtn, syncNowBtn, turnOffBtn), BorderLayout.SOUTH)
		}

		tabs.addTab("General", general)
		tabs.addTab("Sync", syncPanel)
		tabs.setMnemonicAt(0, KeyEvent.VK_G)
		tabs.setDisplayedMnemonicIndexAt(0, 0)
		tabs.setMnemonicAt(1, KeyEvent.VK_S)
		tabs.setDisplayedMnemonicIndexAt(1, 0)
		tabs.selectedIndex = if (tab == "sync") 1 else 0

		val okBtn = button("OK")
		val cancelBtn = button("Cancel")
		okBtn.onClick {
			save()
			d.dispose()
		}
		cancelBtn.onClick { d.dispose() }
		d.rootPane.defaultButton = okBtn

		present(d, owner, tabs, bar(okBtn, cancelBtn))
	}

	private fun syncStatus(): String = if (App.sync.isOn) App.sync.lastResult else "Sync is off."

	// A password field that shows a gray hint while it is empty and unfocused.
	private class HintPasswordField(private val hint: String) : JPasswordField(24) {
		override fun paintComponent(g: Graphics) {
			super.paintComponent(g)
			if (hint.isEmpty() || document.length > 0 || isFocusOwner) return
			g.font = font
			g.color = Color.GRAY
			g.drawString(hint, insets.left + 2, (height + g.fontMetrics.ascent - g.fontMetrics.descent) / 2)
		}
	}
}

object SyncDialog {
	fun show(owner: Component) = OptionsDialog.show(owner, "sync")
}

object CategoriesDialog {
	fun show(owner: Component, app: Int) {
		val d = dialog(owner, if (app == 0) "Edit To Do Categories" else "Edit Memo Categories")
		val names = DefaultListModel<String>()
		val list = JList(names).withFont()
		list.selectionMode = ListSelectionModel.SINGLE_SELECTION

		fun reload() {
			val keep = list.selectedIndex
			names.clear()
			App.store.categoriesFor(app).forEach { names.addElement(it.name) }
			if (keep in 0 until names.size) list.selectedIndex = keep
		}

		fun selected(): Category? = App.store.categoriesFor(app).getOrNull(list.selectedIndex)

		fun changed() {
			App.store.persist()
			App.refresh()
			reload()
		}

		val okBtn = button("OK")
		val newBtn = button("New...").key('N')
		val deleteBtn = button("Delete...").key('D').apply { isEnabled = false }
		val renameBtn = button("Rename...").key('R').apply { isEnabled = false }

		list.addListSelectionListener {
			val has = list.selectedIndex >= 0
			renameBtn.isEnabled = has
			deleteBtn.isEnabled = has
		}

		newBtn.onClick {
			val name: String? = JOptionPane.showInputDialog(d, "Name:", "New Category", JOptionPane.PLAIN_MESSAGE)
			if (name.isNullOrBlank()) return@onClick
			if (App.store.addCategory(name.trim(), app) == null) {
				JOptionPane.showMessageDialog(d, "You can have at most 15 categories.", "New Category", JOptionPane.WARNING_MESSAGE)
			}
			changed()
		}

		renameBtn.onClick {
			val cat = selected() ?: return@onClick
			val name = JOptionPane.showInputDialog(d, "Name:", "Rename Category", JOptionPane.PLAIN_MESSAGE, null, null, cat.name) as String?
			if (name.isNullOrBlank()) return@onClick
			App.store.renameCategory(cat, name.trim())
			changed()
		}

		deleteBtn.onClick {
			val cat = selected() ?: return@onClick
			val answer = JOptionPane.showConfirmDialog(d, "Delete the category? Its records move to Unfiled.", "Delete Category", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE)
			if (answer != JOptionPane.YES_OPTION) return@onClick
			App.store.deleteCategory(cat)
			changed()
		}

		okBtn.onClick { d.dispose() }
		d.rootPane.defaultButton = okBtn

		reload()
		// The buttons stack on the right of the list, as in Palm Desktop.
		val side = JPanel(GridLayout(0, 1, 0, 6)).apply {
			add(okBtn)
			add(newBtn)
			add(deleteBtn)
			add(renameBtn)
		}
		val content = JPanel(BorderLayout(8, 0)).apply {
			add(JScrollPane(list).apply { preferredSize = Dimension(240, 180) }, BorderLayout.CENTER)
			add(JPanel(BorderLayout()).apply { add(side, BorderLayout.NORTH) }, BorderLayout.EAST)
		}
		present(d, owner, content, null)
	}
}

object FindDialog {
	fun show(owner: Component) {
		val d = dialog(owner, "Find")
		val field = JTextField(24).withFont()
		val findLabel = keyed("Find:", 'F', field)
		val findBtn = button("Find").key('n')
		val results = DefaultListModel<Any>()
		val list = JList(results).withFont()
		list.selectionMode = ListSelectionModel.SINGLE_SELECTION
		list.cellRenderer = ResultRenderer()
		val goBtn = button("Go To").key('G').apply { isEnabled = false }
		val closeBtn = button("Close").key('C')

		fun go(record: Any) {
			d.dispose()
			when (record) {
				is Event -> App.show("date", record)
				is Todo -> App.show("todo", record)
				is Memo -> App.show("memo", record)
			}
		}

		fun search() {
			results.clear()
			val text = field.text.trim()
			if (text.isEmpty()) return
			App.store.find(text).filter { App.showPrivate || !isPrivate(it) }.forEach { results.addElement(it) }
		}

		field.addActionListener { search() }
		findBtn.onClick { search() }
		list.addListSelectionListener { goBtn.isEnabled = list.selectedIndex >= 0 }
		list.addMouseListener(object : MouseAdapter() {
			override fun mouseClicked(e: MouseEvent) {
				if (e.clickCount == 2) list.selectedValue?.let { go(it) }
			}
		})
		goBtn.onClick { list.selectedValue?.let { go(it) } }
		closeBtn.onClick { d.dispose() }
		d.rootPane.defaultButton = findBtn

		val north = JPanel(BorderLayout(6, 0)).apply {
			add(findLabel, BorderLayout.WEST)
			add(field, BorderLayout.CENTER)
			add(findBtn, BorderLayout.EAST)
		}
		val content = JPanel(BorderLayout(0, 8)).apply {
			add(north, BorderLayout.NORTH)
			add(JScrollPane(list).apply { preferredSize = Dimension(360, 200) }, BorderLayout.CENTER)
		}
		present(d, owner, content, bar(goBtn, closeBtn))
	}

	private fun isPrivate(record: Any): Boolean = when (record) {
		is Event -> record.isPrivate
		is Todo -> record.isPrivate
		is Memo -> record.isPrivate
		else -> false
	}

	private fun describe(record: Any): String = when (record) {
		is Event -> "Date Book: ${record.title} (${Fmt.shortDate(record.date)})"
		is Todo -> "To Do: ${record.title}"
		is Memo -> "Memo: ${record.text.lineSequence().firstOrNull().orEmpty()}"
		else -> ""
	}

	private class ResultRenderer : DefaultListCellRenderer() {
		override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean): Component {
			super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
			font = Theme.font
			text = value?.let { describe(it) }.orEmpty()
			icon = if (value != null && isPrivate(value)) Icons.privateRecord() else null
			return this
		}
	}
}

object PurgeDialog {
	private val spans = listOf("1 week" to 7, "2 weeks" to 14, "3 weeks" to 21, "1 month" to 30)

	fun show(owner: Component, what: String) {
		if (what == "events") events(owner) else todos(owner)
	}

	private fun events(owner: Component) {
		val d = dialog(owner, "Purge Events")
		val combo = JComboBox(spans.map { it.first }.toTypedArray()).withFont()
		val okBtn = button("OK")
		val cancelBtn = button("Cancel")

		okBtn.onClick {
			val days = spans[combo.selectedIndex].second
			val n = App.store.purgeEvents(LocalDate.now().toEpochDay() - days)
			App.store.persist()
			d.dispose()
			App.refresh()
			JOptionPane.showMessageDialog(owner, "$n events purged.")
		}
		cancelBtn.onClick { d.dispose() }
		d.rootPane.defaultButton = okBtn

		val row = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
			add(keyed("Purge events older than:", 'o', combo))
			add(combo)
		}
		present(d, owner, row, bar(okBtn, cancelBtn))
	}

	private fun todos(owner: Component) {
		val d = dialog(owner, "Purge Completed To Do Items")
		val okBtn = button("OK")
		val cancelBtn = button("Cancel")

		okBtn.onClick {
			val n = App.store.purgeTodos()
			App.store.persist()
			d.dispose()
			App.refresh()
			JOptionPane.showMessageDialog(owner, "$n To Do items purged.")
		}
		cancelBtn.onClick { d.dispose() }
		d.rootPane.defaultButton = okBtn

		present(d, owner, label("Delete all completed To Do items?"), bar(okBtn, cancelBtn))
	}
}

private fun <T : Component> T.withFont(): T = apply { font = Theme.font }

private fun label(text: String): JLabel = JLabel(text).withFont()

// A label with a mnemonic that moves the focus to its field.
private fun keyed(text: String, key: Char, field: JComponent): JLabel = label(text).apply {
	labelFor = field
	displayedMnemonic = key.uppercaseChar().code
	val i = mnemonicIndex(text, key)
	if (i >= 0) displayedMnemonicIndex = i
}

private fun button(text: String): JButton = JButton(text).withFont()

// Sets the mnemonic of a button or check box and underlines its letter.
private fun <T : AbstractButton> T.key(c: Char): T = apply {
	mnemonic = c.uppercaseChar().code
	val i = mnemonicIndex(text, c)
	if (i >= 0) displayedMnemonicIndex = i
}

// The first match of c in text, matching the case first. -1 when text has no c.
private fun mnemonicIndex(text: String, c: Char): Int {
	val exact = text.indexOf(c)
	return if (exact >= 0) exact else text.indexOf(c, ignoreCase = true)
}

private fun JButton.onClick(action: () -> Unit) {
	addActionListener { action() }
}

private fun dialog(owner: Component, title: String): JDialog {
	val d = JDialog(SwingUtilities.getWindowAncestor(owner), title, Dialog.ModalityType.APPLICATION_MODAL)
	d.defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
	d.isResizable = false
	d.rootPane.registerKeyboardAction(ActionListener { d.dispose() }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW)
	return d
}

private fun bar(vararg items: JButton): JPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 6)).apply {
	items.forEach { add(it) }
}

// Adds the content and an optional button bar, then shows the dialog. A null bar adds nothing.
private fun present(d: JDialog, owner: Component, content: JComponent, buttons: JPanel?) {
	val body = JPanel(BorderLayout())
	body.border = EmptyBorder(8, 8, 8, 8)
	body.add(content, BorderLayout.CENTER)
	d.contentPane.add(body, BorderLayout.CENTER)
	buttons?.let { d.contentPane.add(it, BorderLayout.SOUTH) }
	d.pack()
	d.setLocationRelativeTo(owner)
	d.isVisible = true
}

// Label and field rows. Labels sit in column 0, fields stretch in column 1.
private fun formPanel(rows: List<Pair<JLabel, JComponent>>): JPanel {
	val p = JPanel(GridBagLayout())
	rows.forEachIndexed { y, (name, comp) ->
		p.add(name, GridBagConstraints().apply {
			gridx = 0
			gridy = y
			anchor = GridBagConstraints.WEST
			insets = Insets(4, 8, 4, 6)
		})
		p.add(comp, GridBagConstraints().apply {
			gridx = 1
			gridy = y
			fill = GridBagConstraints.HORIZONTAL
			weightx = 1.0
			insets = Insets(4, 0, 4, 8)
		})
	}
	return p
}
