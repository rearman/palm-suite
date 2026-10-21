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
		val st = state(f)
		val menu = JPopupMenu()
		menu.add(editItem("Undo", 'U', TextUndo.manager(f).canUndo()) {
			TextUndo.manager(f).undo()
			TextUndo.onChange()
		})
		menu.addSeparator()
		menu.add(editItem("Cut", 't', st.cut) { f.cut() })
		menu.add(editItem("Copy", 'C', st.copy) { f.copy() })
		menu.add(editItem("Paste", 'P', st.paste) { f.paste() })
		menu.add(editItem("Delete", 'D', st.delete) { f.replaceSelection("") })
		menu.addSeparator()
		menu.add(editItem("Select All", 'A', f.document.length > 0) { f.selectAll() })
		return menu
	}

	/** The enabled state of Cut, Copy, Paste and Delete for [f]. A password field gives no Cut or Copy. */
	fun state(f: JTextComponent): TextEditState {
		val hasSelection = f.selectionStart != f.selectionEnd
		val plain = f !is JPasswordField
		return TextEditState(
			cut = f.isEditable && hasSelection && plain,
			copy = hasSelection && plain,
			paste = f.isEditable && clipboardHasText(),
			delete = f.isEditable && hasSelection,
		)
	}
}

/** The enabled flags of the Cut, Copy, Paste and Delete items of a text field. */
internal class TextEditState(val cut: Boolean, val copy: Boolean, val paste: Boolean, val delete: Boolean)

/** True when the system clipboard holds text. */
internal fun clipboardHasText(): Boolean = runCatching {
	Toolkit.getDefaultToolkit().systemClipboard.getContents(null)?.isDataFlavorSupported(DataFlavor.stringFlavor) == true
}.getOrDefault(false)
