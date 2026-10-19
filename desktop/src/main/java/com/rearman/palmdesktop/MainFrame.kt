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
import java.awt.print.PageFormat
import java.awt.print.PrinterJob
import java.time.LocalDate
import java.util.WeakHashMap
import javax.swing.AbstractButton
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
import javax.swing.JPopupMenu
import javax.swing.JRadioButtonMenuItem
import javax.swing.JToggleButton
import javax.swing.JToolBar
import javax.swing.KeyStroke
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
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
	private val viewItems = HashMap<String, JRadioButtonMenuItem>()
	private val statusLabel = JLabel("Ready")
	private val visibleIn = HashMap<JComponent, Set<String>>()
	private lateinit var categoriesItem: JMenuItem
	private lateinit var purgeItem: JMenuItem
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
		// The startView setting wins. Without it, the view of the last session opens.
		val start = Settings.get("startView", "").ifEmpty { Settings.get("view", "date") }
		switchTo(if (start in panels) start else "date")
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
		viewItems.getValue(name).isSelected = true
		updateMenus()
	}

	/** Shows or hides the menu items that belong to one view, and sets the items that change with it. */
	private fun updateMenus() {
		for ((item, views) in visibleIn) item.isVisible = current in views
		categoriesItem.isEnabled = current != "date"
		purgeItem.text = if (current == "todo") "Purge Completed To Do's..." else "Purge Events..."
	}

	private fun currentView(): AppView = panels.getValue(current)

	private fun newRecord() {
		currentView().newRecord()
	}

	private fun printView() {
		Printing.print(this, currentView())
	}

	private fun saveAll() {
		App.store.persist()
		setStatus("Saved.")
	}

	private fun setShowPrivate(on: Boolean) {
		App.showPrivate = on
		Settings.setBool("showPrivate", on)
		App.refresh()
	}

	private fun syncNow() {
		App.sync.syncNow { error ->
			SwingUtilities.invokeLater {
				if (error != null) JOptionPane.showMessageDialog(this, error, "Sync", JOptionPane.ERROR_MESSAGE)
				else setStatus("Sync done.")
			}
		}
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
		bar.add(menu("File", 'F').also { m ->
			m.add(item("Save All", 'S', KeyEvent.VK_S, CTRL) { saveAll() })
			m.addSeparator()
			m.add(item("Import...", 'I') { Transfer.importFile(this) })
			m.add(item("Export...", 'E') { Transfer.exportFile(this) })
			m.addSeparator()
			m.add(item("Page Setup...", 'u') {
				val job = PrinterJob.getPrinterJob()
				Printing.pageFormat = job.pageDialog(Printing.pageFormat ?: job.defaultPage())
			})
			m.add(item("Print...", 'P', KeyEvent.VK_P, CTRL) { printView() })
			m.addSeparator()
			m.add(item("Exit", 'x') { processWindowEvent(WindowEvent(this, WindowEvent.WINDOW_CLOSING)) })
		})
		bar.add(menu("Edit", 'E').also { m ->
			m.add(item("Undo", 'U', KeyEvent.VK_Z, CTRL) { undo() })
			m.addSeparator()
			m.add(item("Cut", 't', KeyEvent.VK_X, CTRL) { transfer(TransferHandler.getCutAction()) })
			m.add(item("Copy", 'C', KeyEvent.VK_C, CTRL) { transfer(TransferHandler.getCopyAction()) })
			m.add(item("Paste", 'P', KeyEvent.VK_V, CTRL) { transfer(TransferHandler.getPasteAction()) })
			m.add(item("Delete", 'D', KeyEvent.VK_DELETE) { currentView().deleteSelected() })
			m.addSeparator()
			m.add(item("Select All", 'A', KeyEvent.VK_A, CTRL) {
				(KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner as? JTextComponent)?.selectAll()
			})
			m.add(onlyIn(item("New Event...", 'N', KeyEvent.VK_N, CTRL) { newRecord() }, "date"))
			m.add(onlyIn(item("Edit Event...", 'E') { currentView().editSelected() }, "date"))
			m.add(onlyIn(item("Delete Event", 'l') { currentView().deleteSelected() }, "date"))
			m.add(onlyIn(item("New To Do", 'N', KeyEvent.VK_N, CTRL) { newRecord() }, "todo"))
			m.add(onlyIn(item("New Memo", 'N', KeyEvent.VK_N, CTRL) { newRecord() }, "memo"))
			m.addSeparator()
			m.add(item("Find...", 'F', KeyEvent.VK_F, CTRL) { FindDialog.show(this) })
		})
		bar.add(menu("View", 'V').also { m ->
			val views = ButtonGroup()
			viewRadio(m, views, "date", "Date Book", 'D', KeyEvent.VK_F2)
			viewRadio(m, views, "todo", "To Do List", 'o', KeyEvent.VK_F4)
			viewRadio(m, views, "memo", "Memo Pad", 'M', KeyEvent.VK_F5)
			m.addSeparator()
			val privacy = ButtonGroup()
			m.add(radio(privacy, "Show Private Records", 'S') { setShowPrivate(true) }
				.also { it.isSelected = App.showPrivate })
			m.add(radio(privacy, "Hide Private Records", 'H') { setShowPrivate(false) }
				.also { it.isSelected = !App.showPrivate })
			m.add(onlyIn(JPopupMenu.Separator(), "todo"))
			m.add(onlyIn(item("Show...", 'w') { (todoPanel as? TodoPanel)?.showOptions() }, "todo"))
		})
		bar.add(menu("Tools", 'T').also { m ->
			categoriesItem = item("Categories...", 'C') {
				CategoriesDialog.show(this, if (current == "memo") 1 else 0)
			}
			m.add(categoriesItem)
			m.addSeparator()
			purgeItem = item("Purge Events...", 'P') {
				PurgeDialog.show(this, if (current == "todo") "todos" else "events")
			}
			m.add(onlyIn(purgeItem, "date", "todo"))
			m.add(onlyIn(JPopupMenu.Separator(), "date", "todo"))
			m.add(item("Options...", 'O') { OptionsDialog.show(this) })
			m.add(item("Sync Now", 'N') { syncNow() })
		})
		bar.add(menu("Help", 'H').also { m ->
			m.add(item("About Palm Desktop...", 'A') {
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

	/** Shows [item] only in the views listed in [views]. The view menus call updateMenus to apply it. */
	private fun <T : JComponent> onlyIn(item: T, vararg views: String): T {
		visibleIn[item] = views.toSet()
		return item
	}

	private fun menu(text: String, c: Char): JMenu {
		val m = JMenu(text)
		mnemonic(m, text, c)
		return m
	}

	private fun item(text: String, c: Char, key: Int = 0, modifiers: Int = 0, action: () -> Unit): JMenuItem {
		val menuItem = JMenuItem(text)
		mnemonic(menuItem, text, c)
		if (key != 0) menuItem.accelerator = KeyStroke.getKeyStroke(key, modifiers)
		menuItem.addActionListener { action() }
		return menuItem
	}

	private fun radio(group: ButtonGroup, text: String, c: Char, key: Int = 0, action: () -> Unit): JRadioButtonMenuItem {
		val menuItem = JRadioButtonMenuItem(text)
		mnemonic(menuItem, text, c)
		if (key != 0) menuItem.accelerator = KeyStroke.getKeyStroke(key, 0)
		menuItem.addActionListener { action() }
		group.add(menuItem)
		return menuItem
	}

	/** Adds a view radio item. The check mark follows the current view. */
	private fun viewRadio(m: JMenu, group: ButtonGroup, name: String, text: String, c: Char, key: Int) {
		val menuItem = radio(group, text, c, key) { showView(name, null) }
		viewItems[name] = menuItem
		m.add(menuItem)
	}

	/** Sets the Alt mnemonic of a button to [c], underlined in [text]. */
	private fun mnemonic(button: AbstractButton, text: String, c: Char) {
		button.mnemonic = c.uppercaseChar().code
		button.displayedMnemonicIndex = text.indexOf(c)
	}

	private fun buildToolBar(): JToolBar {
		val bar = JToolBar()
		bar.isFloatable = false
		bar.add(toolButton(Icons.save(), "Save All") { saveAll() })
		bar.add(toolButton(Icons.print(), "Print") { printView() })
		bar.addSeparator()
		bar.add(toolButton(Icons.cut(), "Cut") { transfer(TransferHandler.getCutAction()) })
		bar.add(toolButton(Icons.copy(), "Copy") { transfer(TransferHandler.getCopyAction()) })
		bar.add(toolButton(Icons.paste(), "Paste") { transfer(TransferHandler.getPasteAction()) })
		bar.add(toolButton(Icons.undo(), "Undo") { undo() })
		bar.addSeparator()
		bar.add(toolButton(Icons.find(), "Find") { FindDialog.show(this) })
		bar.addSeparator()
		bar.add(toolButton(Icons.note(), "New") { newRecord() })
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

	/** Adds a launcher button. It has no Alt key: F2, F4 and F5 from the View menu select the views, as in Palm Desktop. */
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
