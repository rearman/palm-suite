package com.rearman.palmdesktop

import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.event.ActionEvent
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.TransferHandler
import javax.swing.TransferHandler.TransferSupport

/** What Cut, Copy and Paste need from a record list. [name] is "todo", "memo" or "address". */
internal class RecordKind<T : Any>(
	val name: String,
	val selected: () -> List<T>,            // the selected records, in list order
	val copyOf: (T) -> T,                   // a detached copy, so later edits do not change the clipboard
	val text: (T) -> String,                // the text of one record on the system clipboard
	val pasteRecords: (List<T>) -> Unit,    // saves copies with new ids and refreshes
	val pasteText: ((String) -> Unit)?,     // makes records from text; null when the list takes records only
	val delete: (List<T>) -> Unit,          // deletes with no question and refreshes (the end of a Cut)
)

/** The records of one list on the system clipboard. [text] is the string flavor, for other programs. */
internal class RecordClip(val kind: String, val records: List<Any>, private val text: String) : Transferable {
	companion object {
		val FLAVOR = DataFlavor(DataFlavor.javaJVMLocalObjectMimeType + ";class=com.rearman.palmdesktop.RecordClip")
	}

	override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(FLAVOR, DataFlavor.stringFlavor)

	override fun isDataFlavorSupported(flavor: DataFlavor): Boolean =
		flavor == FLAVOR || flavor == DataFlavor.stringFlavor

	override fun getTransferData(flavor: DataFlavor): Any = when (flavor) {
		FLAVOR -> this
		DataFlavor.stringFlavor -> text
		else -> throw UnsupportedFlavorException(flavor)
	}
}

/**
 * The Cut, Copy and Paste of a record list. Cut and Copy put a [RecordClip] on the system clipboard. Paste takes
 * a clip of the same kind as records, or any text as new records through [RecordKind.pasteText]. Records from
 * the clipboard are pasted again as copies each time, so [RecordKind.pasteRecords] must save copies with new ids.
 */
internal class RecordTransfer<T : Any>(private val kind: RecordKind<T>) : TransferHandler() {
	override fun getSourceActions(c: JComponent): Int = COPY_OR_MOVE

	override fun createTransferable(c: JComponent): Transferable? {
		val records = kind.selected()
		if (records.isEmpty()) return null
		return RecordClip(kind.name, records.map(kind.copyOf), records.joinToString("\n") { kind.text(it) })
	}

	/** A Cut is a move: the source records are deleted when the transfer ends. */
	@Suppress("UNCHECKED_CAST")
	override fun exportDone(source: JComponent, data: Transferable?, action: Int) {
		if (action == MOVE && data is RecordClip) kind.delete(data.records as List<T>)
	}

	/** The clip on [t] when it is a record clip of this kind, else null. */
	private fun matchingClip(t: Transferable): RecordClip? {
		if (!t.isDataFlavorSupported(RecordClip.FLAVOR)) return null
		return (t.getTransferData(RecordClip.FLAVOR) as? RecordClip)?.takeIf { it.kind == kind.name }
	}

	override fun canImport(s: TransferSupport): Boolean = try {
		val t = s.transferable
		matchingClip(t) != null || (kind.pasteText != null && t.isDataFlavorSupported(DataFlavor.stringFlavor))
	} catch (e: Exception) {
		false
	}

	@Suppress("UNCHECKED_CAST")
	override fun importData(s: TransferSupport): Boolean {
		if (!canImport(s)) return false
		val t = s.transferable
		val clip = matchingClip(t)
		if (clip != null) kind.pasteRecords(clip.records as List<T>)
		else kind.pasteText!!.invoke(t.getTransferData(DataFlavor.stringFlavor) as String)
		return true
	}

	/** True when Paste would take the system clipboard into [c]. */
	fun canPaste(c: JComponent): Boolean = runCatching {
		val clip = Toolkit.getDefaultToolkit().systemClipboard.getContents(null)
		clip != null && canImport(TransferSupport(c, clip))
	}.getOrDefault(false)
}

/**
 * The right-click menu of a record list, as in Palm Desktop: Cut, Copy, Paste, Delete, then Select All, then
 * [extra] after a separator. Cut, Copy and Delete need a selection. Select All needs rows. [extra] items are added as given.
 */
internal fun listMenu(
	list: JComponent,
	transfer: RecordTransfer<*>,
	hasSelection: Boolean,
	hasRows: Boolean,
	delete: () -> Unit,
	selectAll: () -> Unit,
	extra: List<JMenuItem> = emptyList(),
): JPopupMenu {
	fun fire(action: Action, command: String) =
		action.actionPerformed(ActionEvent(list, ActionEvent.ACTION_PERFORMED, command))
	val menu = JPopupMenu()
	menu.add(editItem("Cut", 't', hasSelection) { fire(TransferHandler.getCutAction(), "cut") })
	menu.add(editItem("Copy", 'C', hasSelection) { fire(TransferHandler.getCopyAction(), "copy") })
	menu.add(editItem("Paste", 'P', transfer.canPaste(list)) { fire(TransferHandler.getPasteAction(), "paste") })
	menu.add(editItem("Delete", 'D', hasSelection) { delete() })
	menu.addSeparator()
	menu.add(editItem("Select All", 'A', hasRows) { selectAll() })
	if (extra.isNotEmpty()) {
		menu.addSeparator()
		extra.forEach { menu.add(it) }
	}
	return menu
}

/** A menu item in the Palm Desktop font, with the Alt mnemonic [mnemonic] underlined in [text]. */
internal fun editItem(text: String, mnemonic: Char, enabled: Boolean, action: () -> Unit): JMenuItem {
	val item = JMenuItem(text)
	item.font = Theme.font
	item.mnemonic = mnemonic.uppercaseChar().code
	item.displayedMnemonicIndex = text.indexOf(mnemonic)
	item.isEnabled = enabled
	item.addActionListener { action() }
	return item
}
