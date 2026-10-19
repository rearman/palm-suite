package com.rearman.palmdesktop

import com.rearman.datebook.Fmt
import java.awt.AWTEvent
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.Insets
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.event.ActionEvent
import java.awt.event.AWTEventListener
import java.awt.event.FocusEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.WindowEvent
import java.time.LocalDate
import java.util.WeakHashMap
import javax.swing.Action
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JToggleButton
import javax.swing.JToolBar
import javax.swing.KeyStroke
import javax.swing.SwingConstants
import javax.swing.TransferHandler
import javax.swing.text.JTextComponent
import javax.swing.undo.UndoManager

private const val CTRL = InputEvent.CTRL_DOWN_MASK

/**
 * The main window: the menus, the toolbar, the launcher bar on the left, the three views in a card
 * layout in the middle and the status bar at the bottom.
 */
class MainFrame : JFrame("Palm Desktop") {
	private val datePanel = DatePanel()
	private val todoPanel = TodoPanel()
	private val memoPanel = MemoPanel()
	private val panels = linkedMapOf<String, AppView>("date" to datePanel, "todo" to todoPanel, "memo" to memoPanel)
	private val cards = CardLayout()
	private val center = JPanel(cards)
	private val launcherGroup = ButtonGroup()
	private val launcherButtons = HashMap<String, JToggleButton>()
	private val statusLabel = JLabel("Ready")
	private val privateItem = JMenuItem()
	private var current = "date"

	init {
		defaultCloseOperation = EXIT_ON_CLOSE
		setSize(1000, 700)
		TextUndo.install()
		jMenuBar = buildMenuBar()
		center.add(datePanel, "date")
		center.add(todoPanel, "todo")
		center.add(memoPanel, "memo")
		contentPane.layout = BorderLayout()
		contentPane.add(buildToolBar(), BorderLayout.NORTH)
		contentPane.add(buildLauncher(), BorderLayout.WEST)
		contentPane.add(center, BorderLayout.CENTER)
		contentPane.add(buildStatusBar(), BorderLayout.SOUTH)
		val start = Settings.get("view", "date")
		switchTo(if (start in panels) start else "date")
		updatePrivateItem()
	}

	/** Sets the text of the status bar. */
	fun setStatus(text: String) {
		statusLabel.text = text
	}

	/** Shows the view [name] and selects [select] in it, when [select] is not null. */
	fun showView(name: String, select: Any?) {
		if (name !in panels) return
		switchTo(name)
		Settings.set("view", name)
		if (select != null) panels.getValue(name).select(select)
	}

	private fun switchTo(name: String) {
		current = name
		cards.show(center, name)
		launcherButtons.getValue(name).isSelected = true
	}

	private fun currentView(): AppView = panels.getValue(current)

	private fun newRecord() {
		currentView().newRecord()
	}

	private fun printView() {
		Printing.print(this, currentView())
	}

	private fun showDate(mode: String) {
		showView("date", null)
		datePanel.showMode(mode)
	}

	private fun togglePrivate() {
		App.showPrivate = !App.showPrivate
		Settings.setBool("showPrivate", App.showPrivate)
		updatePrivateItem()
		App.refresh()
	}

	private fun updatePrivateItem() {
		privateItem.text = if (App.showPrivate) "Hide Private Records" else "Show Private Records"
	}

	/** Runs a clipboard action on the focused component, such as a text field. */
	private fun transfer(action: Action) {
		val focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner ?: return
		action.actionPerformed(ActionEvent(focus, ActionEvent.ACTION_PERFORMED, ""))
	}

	private fun undo() {
		val field = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner as? JTextComponent ?: return
		TextUndo.manager(field).takeIf { it.canUndo() }?.undo()
	}

	private fun buildMenuBar(): JMenuBar {
		val bar = JMenuBar()
		bar.add(JMenu("File").also { m ->
			m.add(item("New Record", KeyEvent.VK_N, CTRL) { newRecord() })
			m.add(item("Import...") { Transfer.importFile(this) })
			m.add(item("Export...") { Transfer.exportFile(this) })
			m.add(item("Print...", KeyEvent.VK_P, CTRL) { printView() })
			m.addSeparator()
			m.add(item("Exit") { processWindowEvent(WindowEvent(this, WindowEvent.WINDOW_CLOSING)) })
		})
		bar.add(JMenu("Edit").also { m ->
			m.add(item("Undo", KeyEvent.VK_Z, CTRL) { undo() })
			m.addSeparator()
			m.add(item("Cut", KeyEvent.VK_X, CTRL) { transfer(TransferHandler.getCutAction()) })
			m.add(item("Copy", KeyEvent.VK_C, CTRL) { transfer(TransferHandler.getCopyAction()) })
			m.add(item("Paste", KeyEvent.VK_V, CTRL) { transfer(TransferHandler.getPasteAction()) })
			m.addSeparator()
			m.add(item("Delete Record", KeyEvent.VK_DELETE) { currentView().deleteSelected() })
			m.add(item("Edit Record...") { currentView().editSelected() })
		})
		bar.add(JMenu("View").also { m ->
			m.add(item("Date", KeyEvent.VK_F2) { showView("date", null) })
			m.add(item("To Do", KeyEvent.VK_F3) { showView("todo", null) })
			m.add(item("Memo", KeyEvent.VK_F4) { showView("memo", null) })
			m.addSeparator()
			m.add(item("Day") { showDate("day") })
			m.add(item("Week") { showDate("week") })
			m.add(item("Month") { showDate("month") })
			m.addSeparator()
			privateItem.addActionListener { togglePrivate() }
			m.add(privateItem)
		})
		bar.add(JMenu("Tools").also { m ->
			m.add(item("Find...", KeyEvent.VK_F, CTRL) { FindDialog.show(this) })
			m.add(item("Purge...") { PurgeDialog.show(this, if (current == "todo") "todos" else "events") })
			m.add(item("Categories...") { CategoriesDialog.show(this, if (current == "memo") 1 else 0) })
			m.addSeparator()
			m.add(item("Sync Now") { App.sync.syncNow() })
			m.add(item("Sync Setup...") { SyncDialog.show(this) })
		})
		bar.add(JMenu("Help").also { m ->
			m.add(item("About Palm Desktop") {
				JOptionPane.showMessageDialog(
					this,
					"Palm Desktop for the palm-suite apps",
					"About Palm Desktop",
					JOptionPane.INFORMATION_MESSAGE
				)
			})
		})
		return bar
	}

	private fun item(text: String, key: Int = 0, modifiers: Int = 0, action: () -> Unit): JMenuItem {
		val menuItem = JMenuItem(text)
		if (key != 0) menuItem.accelerator = KeyStroke.getKeyStroke(key, modifiers)
		menuItem.addActionListener { action() }
		return menuItem
	}

	private fun buildToolBar(): JToolBar {
		val bar = JToolBar()
		bar.isFloatable = false
		bar.add(toolButton(Icons.print(), "Print") { printView() })
		bar.addSeparator()
		bar.add(toolButton(Icons.cut(), "Cut") { transfer(TransferHandler.getCutAction()) })
		bar.add(toolButton(Icons.copy(), "Copy") { transfer(TransferHandler.getCopyAction()) })
		bar.add(toolButton(Icons.paste(), "Paste") { transfer(TransferHandler.getPasteAction()) })
		bar.add(toolButton(Icons.undo(), "Undo") { undo() })
		bar.addSeparator()
		bar.add(toolButton(Icons.find(), "Find") { FindDialog.show(this) })
		return bar
	}

	private fun toolButton(icon: Icon, tip: String, action: () -> Unit): JButton {
		val button = JButton(icon)
		button.toolTipText = tip
		button.isFocusable = false
		button.margin = Insets(2, 2, 2, 2)
		button.addActionListener { action() }
		return button
	}

	/** The launcher bar: three toggle buttons with the icon above the label, on a raised gray bar. */
	private fun buildLauncher(): JPanel {
		val bar = JPanel()
		bar.layout = BoxLayout(bar, BoxLayout.Y_AXIS)
		bar.background = Theme.panel
		bar.border = BorderFactory.createCompoundBorder(
			BorderFactory.createRaisedBevelBorder(),
			BorderFactory.createEmptyBorder(6, 2, 6, 2)
		)
		addLauncher(bar, "date", "Date", Icons.date())
		addLauncher(bar, "todo", "To Do", Icons.todo())
		addLauncher(bar, "memo", "Memo", Icons.memo())
		return bar
	}

	private fun addLauncher(bar: JPanel, name: String, label: String, icon: Icon) {
		val button = JToggleButton(label, icon)
		button.verticalTextPosition = SwingConstants.BOTTOM
		button.horizontalTextPosition = SwingConstants.CENTER
		button.isFocusPainted = false
		button.font = Theme.font
		button.alignmentX = JComponent.CENTER_ALIGNMENT
		button.maximumSize = Dimension(72, 60)
		button.addActionListener { showView(name, null) }
		launcherGroup.add(button)
		launcherButtons[name] = button
		bar.add(button)
		bar.add(Box.createVerticalStrut(6))
	}

	private fun buildStatusBar(): JPanel {
		val bar = JPanel(BorderLayout())
		bar.border = BorderFactory.createCompoundBorder(
			BorderFactory.createEtchedBorder(),
			BorderFactory.createEmptyBorder(2, 4, 2, 6)
		)
		statusLabel.font = Theme.font
		val today = JLabel(LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("M/d/yyyy")))
		today.font = Theme.font
		bar.add(statusLabel, BorderLayout.WEST)
		bar.add(today, BorderLayout.EAST)
		return bar
	}
}

/**
 * One undo history per text field. A field gets its history the first time it takes the focus, so
 * Edit > Undo covers every edit made after that.
 */
private object TextUndo {
	private val histories = WeakHashMap<JTextComponent, UndoManager>()

	fun install() {
		Toolkit.getDefaultToolkit().addAWTEventListener(AWTEventListener { event ->
			val focus = event as? FocusEvent ?: return@AWTEventListener
			val field = focus.component as? JTextComponent
			if (focus.id == FocusEvent.FOCUS_GAINED && field != null) manager(field)
		}, AWTEvent.FOCUS_EVENT_MASK)
	}

	fun manager(field: JTextComponent): UndoManager = histories.getOrPut(field) {
		val history = UndoManager()
		field.document.addUndoableEditListener { history.addEdit(it.edit) }
		history
	}
}
