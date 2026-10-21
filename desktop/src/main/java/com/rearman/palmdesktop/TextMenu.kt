package com.rearman.palmdesktop

import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.event.AWTEventListener
import java.awt.event.MouseEvent
import javax.swing.JPasswordField
import javax.swing.JPopupMenu
import javax.swing.text.JTextComponent

/** The right-click menu of every text field, as the Windows edit menu of Palm Desktop: Undo; Cut, Copy, Paste, Delete; Select All. */
internal object TextMenu {
	/** Shows the menu on a popup trigger over a text field that has no menu of its own. */
	fun install() {
		Toolkit.getDefaultToolkit().addAWTEventListener(AWTEventListener { event ->
			val e = event as? MouseEvent ?: return@AWTEventListener
			// The popup trigger is a press on some systems and a release on others, so both are checked.
			val pressOrRelease = e.id == MouseEvent.MOUSE_PRESSED || e.id == MouseEvent.MOUSE_RELEASED
			val f = e.component as? JTextComponent ?: return@AWTEventListener
			if (!pressOrRelease || !e.isPopupTrigger) return@AWTEventListener
			if (f.componentPopupMenu != null || !f.isEnabled) return@AWTEventListener
			f.requestFocusInWindow()
			textMenu(f).show(f, e.x, e.y)
			e.consume()
		}, AWTEvent.MOUSE_EVENT_MASK)
	}

	/** Builds the menu for [f]. Each item is enabled from the state of [f] when the menu opens. */
	private fun textMenu(f: JTextComponent): JPopupMenu {
		val hasSelection = f.selectionStart != f.selectionEnd
		val plain = f !is JPasswordField
		val menu = JPopupMenu()
		menu.add(editItem("Undo", 'U', TextUndo.manager(f).canUndo()) {
			TextUndo.manager(f).undo()
			TextUndo.onChange()
		})
		menu.addSeparator()
		menu.add(editItem("Cut", 't', f.isEditable && hasSelection && plain) { f.cut() })
		menu.add(editItem("Copy", 'C', hasSelection && plain) { f.copy() })
		menu.add(editItem("Paste", 'P', f.isEditable && clipboardHasText()) { f.paste() })
		menu.add(editItem("Delete", 'D', f.isEditable && hasSelection) { f.replaceSelection("") })
		menu.addSeparator()
		menu.add(editItem("Select All", 'A', f.document.length > 0) { f.selectAll() })
		return menu
	}

	/** True when the system clipboard holds text. */
	private fun clipboardHasText(): Boolean = runCatching {
		Toolkit.getDefaultToolkit().systemClipboard.getContents(null)?.isDataFlavorSupported(DataFlavor.stringFlavor) == true
	}.getOrDefault(false)
}
