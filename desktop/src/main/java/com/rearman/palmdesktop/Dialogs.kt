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
import java.awt.Insets
import java.awt.event.ActionListener
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.LocalDate
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.WindowConstants
import javax.swing.border.EmptyBorder
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

// Palm Desktop dialogs: Edit Categories, Find, Purge and Sync Setup.
// Each dialog is modal and closes on Escape.

object CategoriesDialog {
	fun show(owner: Component, app: Int) {
		val d = dialog(owner, "Edit Categories")
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

		val newBtn = button("New...")
		val renameBtn = button("Rename...").apply { isEnabled = false }
		val deleteBtn = button("Delete").apply { isEnabled = false }
		val okBtn = button("OK")

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
		present(d, owner, JScrollPane(list).apply { preferredSize = Dimension(240, 180) }, bar(newBtn, renameBtn, deleteBtn, okBtn))
	}
}

object FindDialog {
	fun show(owner: Component) {
		val d = dialog(owner, "Find")
		val field = JTextField(24).withFont()
		val findBtn = button("Find")
		val results = DefaultListModel<Any>()
		val list = JList(results).withFont()
		list.selectionMode = ListSelectionModel.SINGLE_SELECTION
		list.cellRenderer = ResultRenderer()
		val goBtn = button("Go To").apply { isEnabled = false }
		val closeBtn = button("Close")

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
			add(label("Purge events older than:"))
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

object SyncDialog {
	fun show(owner: Component) {
		val sync = App.sync
		val d = dialog(owner, "Sync Setup")
		val server = JTextField(sync.server, 24).withFont()
		// An empty Token field keeps the token saved on this computer, if there is one.
		val savedToken = Settings.get("token", "")
		val token = HintPasswordField(if (savedToken.isNotEmpty()) "saved" else "").withFont()
		val pass = JPasswordField(24).withFont()
		val status = label(syncStatus())
		val okBtn = button("OK").apply { isEnabled = false }
		val syncNowBtn = button("Sync Now").apply { isVisible = sync.isOn }
		val turnOffBtn = button("Turn Off").apply { isVisible = sync.isOn }
		val cancelBtn = button("Cancel")
		var busy = false

		// OK needs both a token and a passphrase, and stays off while setup runs.
		fun refreshOk() {
			val hasToken = token.document.length > 0 || savedToken.isNotEmpty()
			okBtn.isEnabled = !busy && hasToken && pass.document.length > 0
		}
		val watch = object : DocumentListener {
			override fun insertUpdate(e: DocumentEvent) = refreshOk()
			override fun removeUpdate(e: DocumentEvent) = refreshOk()
			override fun changedUpdate(e: DocumentEvent) = refreshOk()
		}
		token.document.addDocumentListener(watch)
		pass.document.addDocumentListener(watch)

		okBtn.onClick {
			busy = true
			refreshOk()
			status.text = "Please wait."
			val typed = String(token.password).trim()
			sync.setUp(server.text.trim(), typed.ifEmpty { savedToken }, pass.password) { err ->
				busy = false
				if (err == null) {
					d.dispose()
					App.refresh()
				} else {
					status.text = syncStatus()
					refreshOk()
					JOptionPane.showMessageDialog(d, err, "Sync Setup", JOptionPane.ERROR_MESSAGE)
				}
			}
		}

		syncNowBtn.onClick {
			sync.syncNow { err ->
				SwingUtilities.invokeLater {
					status.text = syncStatus()
					JOptionPane.showMessageDialog(d, err ?: "Sync done.", "Sync Setup",
						if (err == null) JOptionPane.INFORMATION_MESSAGE else JOptionPane.ERROR_MESSAGE)
				}
			}
		}

		turnOffBtn.onClick {
			val answer = JOptionPane.showConfirmDialog(d, "Turn off sync on this computer? Your records stay here.", "Sync Setup", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE)
			if (answer != JOptionPane.YES_OPTION) return@onClick
			sync.turnOff()
			App.refresh()
			d.dispose()
		}

		cancelBtn.onClick { d.dispose() }
		d.rootPane.defaultButton = okBtn

		val form = formPanel(listOf("Server:" to server, "Token:" to token, "Passphrase:" to pass, "" to status))
		present(d, owner, form, bar(okBtn, syncNowBtn, turnOffBtn, cancelBtn))
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

private fun <T : Component> T.withFont(): T = apply { font = Theme.font }

private fun label(text: String): JLabel = JLabel(text).withFont()

private fun button(text: String): JButton = JButton(text).withFont()

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

private fun present(d: JDialog, owner: Component, content: JComponent, buttons: JPanel) {
	val body = JPanel(BorderLayout())
	body.border = EmptyBorder(8, 8, 8, 8)
	body.add(content, BorderLayout.CENTER)
	d.contentPane.add(body, BorderLayout.CENTER)
	d.contentPane.add(buttons, BorderLayout.SOUTH)
	d.pack()
	d.setLocationRelativeTo(owner)
	d.isVisible = true
}

// Label and field rows. Labels sit in column 0, fields stretch in column 1.
private fun formPanel(rows: List<Pair<String, JComponent>>): JPanel {
	val p = JPanel(GridBagLayout())
	rows.forEachIndexed { y, (name, comp) ->
		p.add(label(name), GridBagConstraints().apply {
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
