package com.rearman.palmdesktop

import com.rearman.datebook.Fmt
import java.awt.AWTEvent
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.Insets
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.datatransfer.FlavorListener
import java.awt.event.AWTEventListener
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.print.PageFormat
import java.awt.print.PrinterJob
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.WeakHashMap
import javax.swing.AbstractAction
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
import javax.swing.JList
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JRadioButtonMenuItem
import javax.swing.JTable
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
	private val addressPanel = AddressPanel()
	private val todoPanel = TodoPanel()
	private val memoPanel = MemoPanel()
	private val panels = linkedMapOf<String, AppView>(
		"date" to datePanel, "address" to addressPanel, "todo" to todoPanel, "memo" to memoPanel
	)
	private val cards = CardLayout()
	private val center = JPanel(cards)
	private val launcherGroup = ButtonGroup()
	private val launcherButtons = HashMap<String, JToggleButton>()
	private val viewItems = HashMap<String, JRadioButtonMenuItem>()
	private val statusLabel = JLabel("Ready")
	private val positionLabel = JLabel()
	private val privateLabel = JLabel(Icons.privateRecord()).apply {
		toolTipText = "Private records are hidden"
		isVisible = !App.showPrivate
	}
	private val visibleIn = HashMap<JComponent, Set<String>>()
	private lateinit var categoriesItem: JMenuItem
	private lateinit var purgeItem: JMenuItem
	private lateinit var undoItem: JMenuItem
	private lateinit var undoButton: JButton
	private lateinit var cutItem: JMenuItem
	private lateinit var copyItem: JMenuItem
	private lateinit var pasteItem: JMenuItem
	private lateinit var cutButton: JButton
	private lateinit var copyButton: JButton
	private lateinit var pasteButton: JButton
	private var current = "date"

	init {
		defaultCloseOperation = EXIT_ON_CLOSE
		setSize(1000, 700)
		iconImages = Icons.appImages()
		TextUndo.install()
		TextMenu.install()
		jMenuBar = buildMenuBar()
		center.add(datePanel, "date")
		center.add(addressPanel, "address")
		center.add(todoPanel, "todo")
		center.add(memoPanel, "memo")
		contentPane.layout = BorderLayout()
		contentPane.add(buildToolBar(), BorderLayout.NORTH)
		contentPane.add(buildLauncher(), BorderLayout.WEST)
		center.border = centerBorder(6)
		contentPane.add(center, BorderLayout.CENTER)
		contentPane.add(buildStatusBar(), BorderLayout.SOUTH)
		TextUndo.onChange = { updateEditState() }
		updateEditState()
		// Selection and caret changes come from mouse and key releases. The listener runs before the
		// component handles the event, so the state is read after it.
		Toolkit.getDefaultToolkit().addAWTEventListener(AWTEventListener { e ->
			if (e.id == MouseEvent.MOUSE_RELEASED || e.id == KeyEvent.KEY_RELEASED) {
				SwingUtilities.invokeLater { updateEditState() }
			}
		}, AWTEvent.MOUSE_EVENT_MASK or AWTEvent.KEY_EVENT_MASK)
		Toolkit.getDefaultToolkit().systemClipboard.addFlavorListener(FlavorListener {
			if (SwingUtilities.isEventDispatchThread()) updateEditState()
			else SwingUtilities.invokeLater { updateEditState() }
		})
		rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
			.put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, InputEvent.SHIFT_DOWN_MASK), "deleteNoConfirm")
		rootPane.actionMap.put("deleteNoConfirm", object : AbstractAction() {
			override fun actionPerformed(e: ActionEvent) {
				deleteNoConfirm()
			}
		})
		App.onPosition = { updatePosition() }
		App.onRefresh { updatePosition() }
		// Coming back to the window syncs, as opening the app does.
		addWindowListener(object : WindowAdapter() {
			override fun windowActivated(e: WindowEvent) = App.sync.requestIfStale()
		})
		// The startView setting wins. Without it, the view of the last session opens.
		val start = Settings.get("startView", "").ifEmpty { Settings.get("view", "date") }
		switchTo(if (start in panels) start else "date")
	}

	/** Sets the text of the status bar. */
	fun setStatus(text: String) {
		statusLabel.text = text
	}

	/** Shows the selected date in the Date Book, and the position in the To Do List and Memo Pad. */
	private fun updatePosition() {
		positionLabel.text = if (current == "date") {
			LocalDate.ofEpochDay(App.day).format(DateTimeFormatter.ofPattern("M/d/yyyy"))
		} else {
			currentView().position()
		}
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
		// The Date view fills the bevel with its own workspace gray. The other views keep a 6 px margin.
		center.border = centerBorder(if (name == "date") 0 else 6)
		launcherButtons.getValue(name).isSelected = true
		viewItems.getValue(name).isSelected = true
		updateMenus()
		updatePosition()
	}

	private fun centerBorder(pad: Int) = BorderFactory.createCompoundBorder(
		BorderFactory.createEmptyBorder(3, 3, 3, 3),
		BorderFactory.createCompoundBorder(
			BorderFactory.createRaisedBevelBorder(),
			BorderFactory.createEmptyBorder(pad, pad, pad, pad)
		)
	)

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

	/** Shift+Delete: deletes the selection with no confirmation. Text fields keep their own delete. */
	private fun deleteNoConfirm() {
		if (KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner is JTextComponent) return
		App.noConfirm = true
		try {
			currentView().deleteSelected()
		} finally {
			App.noConfirm = false
		}
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
		privateLabel.isVisible = !on
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
		updateEditState()
	}

	/**
	 * Grays the Edit items that do not apply to the focus owner, as Palm Desktop does. A text field gets Cut and
	 * Copy with a selection, Paste when it is editable and the clipboard holds text. A record list gets them from
	 * its selection and from [RecordTransfer.canPaste]. Undo needs an edit to undo in the focused text field.
	 */
	private fun updateEditState() {
		val owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
		val field = owner as? JTextComponent
		val handler = (owner as? JComponent)?.transferHandler as? RecordTransfer<*>
		var cut = false
		var copy = false
		var paste = false
		if (field != null) {
			val s = TextMenu.state(field)
			cut = s.cut
			copy = s.copy
			paste = s.paste
		} else if (owner is JComponent && handler != null) {
			val selected = hasRecordSelection(owner)
			cut = selected
			copy = selected
			paste = handler.canPaste(owner)
		}
		cutItem.isEnabled = cut
		cutButton.isEnabled = cut
		copyItem.isEnabled = copy
		copyButton.isEnabled = copy
		pasteItem.isEnabled = paste
		pasteButton.isEnabled = paste
		val can = field != null && TextUndo.manager(field).canUndo()
		undoItem.isEnabled = can
		undoButton.isEnabled = can
	}

	private fun buildMenuBar(): JMenuBar {
		val bar = JMenuBar()
		bar.add(menu("File", 'F').also { m ->
			m.add(item("Save All", 'S', KeyEvent.VK_S, CTRL) { saveAll() })
			m.addSeparator()
			m.add(item("Import...", 'I') { Transfer.importFile(this) })
			m.add(item("Export...", 'E') { Transfer.exportFile(this, current) })
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
			m.add(item("Undo", 'U', KeyEvent.VK_Z, CTRL) { undo() }.also { undoItem = it })
			m.addSeparator()
			m.add(item("Cut", 't', KeyEvent.VK_X, CTRL) { transfer(TransferHandler.getCutAction()) }.also { cutItem = it })
			m.add(item("Copy", 'C', KeyEvent.VK_C, CTRL) { transfer(TransferHandler.getCopyAction()) }.also { copyItem = it })
			m.add(item("Paste", 'P', KeyEvent.VK_V, CTRL) { transfer(TransferHandler.getPasteAction()) }.also { pasteItem = it })
			m.add(item("Delete", 'D', KeyEvent.VK_DELETE) { currentView().deleteSelected() })
			m.addSeparator()
			m.add(item("Select All", 'A', KeyEvent.VK_A, CTRL) {
				when (val owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner) {
					is JTextComponent -> owner.selectAll()
					is JTable -> owner.selectAll()
					is JList<*> -> if (owner.model.size > 0) owner.setSelectionInterval(0, owner.model.size - 1)
				}
			})
			m.add(onlyIn(item("New Event...", 'N', KeyEvent.VK_N, CTRL) { newRecord() }, "date"))
			m.add(onlyIn(item("Edit Event...", 'E') { currentView().editSelected() }, "date"))
			m.add(onlyIn(item("Delete Event", 'l') { currentView().deleteSelected() }, "date"))
			m.add(onlyIn(item("New To Do", 'N', KeyEvent.VK_N, CTRL) { newRecord() }, "todo"))
			m.add(onlyIn(item("New Memo", 'N', KeyEvent.VK_N, CTRL) { newRecord() }, "memo"))
			m.add(onlyIn(item("New Address...", 'N', KeyEvent.VK_N, CTRL) { newRecord() }, "address"))
			m.addSeparator()
			m.add(item("Find...", 'F', KeyEvent.VK_F, CTRL) { FindDialog.show(this) })
		})
		bar.add(menu("View", 'V').also { m ->
			val views = ButtonGroup()
			viewRadio(m, views, "date", "Date Book", 'D', KeyEvent.VK_F2)
			viewRadio(m, views, "address", "Address Book", 'A', KeyEvent.VK_F3)
			viewRadio(m, views, "todo", "To Do List", 'o', KeyEvent.VK_F4)
			viewRadio(m, views, "memo", "Memo Pad", 'M', KeyEvent.VK_F5)
			m.addSeparator()
			val privacy = ButtonGroup()
			m.add(radio(privacy, "Show Private Records", 'S') { setShowPrivate(true) }
				.also { it.isSelected = App.showPrivate })
			m.add(radio(privacy, "Hide Private Records", 'H') { setShowPrivate(false) }
				.also { it.isSelected = !App.showPrivate })
			m.add(onlyIn(JPopupMenu.Separator(), "todo"))
			m.add(onlyIn(item("Show...", 'o') { (todoPanel as? TodoPanel)?.showOptions() }, "todo"))
			m.add(onlyIn(JPopupMenu.Separator(), "memo"))
			m.add(onlyIn(item("List By...", 'L') { (memoPanel as? MemoPanel)?.listBy() }, "memo"))
			m.add(onlyIn(JPopupMenu.Separator(), "address"))
			m.add(onlyIn(item("List By...", 'L') { showAddressListBy(this) }, "address"))
		})
		bar.add(menu("Tools", 'T').also { m ->
			categoriesItem = item("Categories...", 'C') {
				CategoriesDialog.show(this, when (current) { "memo" -> 1; "address" -> 2; else -> 0 })
			}
			m.add(categoriesItem)
			m.add(onlyIn(item("Custom Field Labels...", 'F') { CustomLabelsDialog.show(this) }, "address"))
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
		bar.add(toolButton(Icons.cut(), "Cut") { transfer(TransferHandler.getCutAction()) }.also { cutButton = it })
		bar.add(toolButton(Icons.copy(), "Copy") { transfer(TransferHandler.getCopyAction()) }.also { copyButton = it })
		bar.add(toolButton(Icons.paste(), "Paste") { transfer(TransferHandler.getPasteAction()) }.also { pasteButton = it })
		bar.add(toolButton(Icons.undo(), "Undo") { undo() }.also { undoButton = it })
		bar.addSeparator()
		bar.add(toolButton(Icons.find(), "Find") { FindDialog.show(this) })
		bar.addSeparator()
		bar.add(toolButton(Icons.newItem(), "New Item") { newRecord() })
		bar.add(Box.createHorizontalGlue())
		bar.add(privateLabel)
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
			BorderFactory.createEmptyBorder(6, 5, 6, 5)   // a 72 px bar around the 58 px buttons
		)
		addLauncher(bar, "date", "Date", Icons.date())
		addLauncher(bar, "address", "Address", Icons.address())
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
		button.font = Theme.launcher
		button.alignmentX = JComponent.CENTER_ALIGNMENT
		// Palm Desktop launcher buttons are 58 px wide on a 72 px bar. Small margins let "Address" fit.
		button.margin = java.awt.Insets(2, 2, 2, 2)
		button.preferredSize = Dimension(58, button.preferredSize.height)
		button.maximumSize = Dimension(58, 60)
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
			BorderFactory.createEmptyBorder(1, 4, 1, 6)
		)
		statusLabel.font = Theme.status
		positionLabel.font = Theme.status
		positionLabel.border = BorderFactory.createCompoundBorder(
			BorderFactory.createLoweredBevelBorder(),
			BorderFactory.createEmptyBorder(0, 3, 0, 3)
		)
		// The official status panels are 17 px tall. The 12 px status font fits in that height.
		positionLabel.preferredSize = Dimension(90, 17)
		bar.add(statusLabel, BorderLayout.WEST)
		bar.add(positionLabel, BorderLayout.EAST)
		return bar
	}
}

/**
 * One undo history per text field. A field gets its history the first time it takes the focus, so
 * Edit > Undo covers every edit made after that.
 */
internal object TextUndo {
	private val histories = WeakHashMap<JTextComponent, UndoManager>()

	/** Runs after the focus moves or a history changes. */
	var onChange: () -> Unit = {}

	/** Listens for focus changes. A text field gets its history the first time it takes the focus. */
	fun install() {
		KeyboardFocusManager.getCurrentKeyboardFocusManager().addPropertyChangeListener("focusOwner") { e ->
			(e.newValue as? JTextComponent)?.let { manager(it) }
			onChange()
		}
	}

	fun manager(field: JTextComponent): UndoManager = histories.getOrPut(field) {
		val history = UndoManager()
		field.document.addUndoableEditListener {
			history.addEdit(it.edit)
			onChange()
		}
		history
	}
}
