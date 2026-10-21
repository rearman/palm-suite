package com.rearman.palmdesktop

import com.rearman.datebook.*
import org.json.JSONException
import java.awt.Component
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.ZoneId
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.filechooser.FileNameExtensionFilter

/** Import and export of Date Book records: iCalendar files, the Date Book backup file and vCard files. */
object Transfer {

	/** Exports the records of [view]: the visible addresses as vCard in the Address Book, else the Date Book. */
	fun exportFile(owner: Component, view: String) {
		val ics = FileNameExtensionFilter("iCalendar (*.ics)", "ics")
		val json = FileNameExtensionFilter("Date Book backup (*.json)", "json")
		val vcf = FileNameExtensionFilter("vCard (*.vcf)", "vcf")
		val addresses = view == "address"
		val c = JFileChooser()
		c.dialogTitle = "Export"
		c.isAcceptAllFileFilterUsed = false
		if (addresses) {
			c.addChoosableFileFilter(vcf)
			c.fileFilter = vcf
		} else {
			c.addChoosableFileFilter(ics)
			c.addChoosableFileFilter(json)
			c.fileFilter = ics
		}
		if (c.showSaveDialog(owner) != JFileChooser.APPROVE_OPTION) return

		val typed = c.selectedFile.path
		val file: File
		val text: String
		if (addresses) {
			file = if (typed.endsWith(".vcf", true)) File(typed) else File("$typed.vcf")
			text = VCard.export(visibleAddresses()) { App.store.categoryName(it) }
		} else {
			// The typed extension wins. Without one, the selected filter gives the format.
			val known = typed.endsWith(".json", true) || typed.endsWith(".ics", true)
			val asJson = when {
				typed.endsWith(".json", true) -> true
				typed.endsWith(".ics", true) -> false
				else -> c.fileFilter === json
			}
			file = if (known) File(typed) else File("$typed.${if (asJson) "json" else "ics"}")
			val visible = App.store.events.filter { App.showPrivate || !it.isPrivate }
			text = if (asJson) backupJson() else ICal.export(visible)
		}
		try {
			Files.writeString(file.toPath(), text, StandardCharsets.UTF_8)
		} catch (e: IOException) {
			JOptionPane.showMessageDialog(owner, "The file could not be saved.\n${e.message}", "Export", JOptionPane.ERROR_MESSAGE)
			return
		}
		App.status("Exported to ${file.name}.")
	}

	fun importFile(owner: Component) {
		val c = JFileChooser()
		c.dialogTitle = "Import"
		c.addChoosableFileFilter(FileNameExtensionFilter("iCalendar (*.ics)", "ics"))
		c.addChoosableFileFilter(FileNameExtensionFilter("Date Book backup (*.json)", "json"))
		c.addChoosableFileFilter(FileNameExtensionFilter("vCard (*.vcf)", "vcf"))
		if (c.showOpenDialog(owner) != JFileChooser.APPROVE_OPTION) return
		val text = try {
			Files.readString(c.selectedFile.toPath(), StandardCharsets.UTF_8)
		} catch (e: IOException) {
			JOptionPane.showMessageDialog(owner, "The file could not be read.\n${e.message}", "Import", JOptionPane.ERROR_MESSAGE)
			return
		}
		val body = text.removePrefix("﻿").trimStart()
		try {
			val message = when {
				body.startsWith("BEGIN:VCALENDAR", true) -> importIcs(text)
				body.startsWith("BEGIN:VCARD", true) -> importVcard(body)
				else -> importJson(body)
			}
			App.refresh()
			JOptionPane.showMessageDialog(owner, message, "Import", JOptionPane.INFORMATION_MESSAGE)
		} catch (e: JSONException) {
			JOptionPane.showMessageDialog(owner, "This file is not a Date Book backup or an iCalendar file.",
				"Import", JOptionPane.ERROR_MESSAGE)
		}
	}

	private fun importIcs(text: String): String {
		val parsed = ICal.parse(text, ZoneId.systemDefault())
		val store = App.store
		val have = store.events.mapTo(HashSet()) { it.dupKey() }
		var added = 0
		for (e in parsed.events) {
			if (e.dupKey() in have) continue
			store.save(e)
			added++
		}
		val skipped = parsed.events.size - added
		var message = "Added $added events. Skipped $skipped duplicate events."
		if (parsed.simplified > 0) message += " ${parsed.simplified} events were simplified to fit Date Book."
		return message
	}

	/** Adds the vCards in [text] as Address Book records. A category name is matched ignoring case, else Unfiled. */
	private fun importVcard(text: String): String {
		val parsed = VCard.parse(text)
		val store = App.store
		val have = store.addresses.mapTo(HashSet()) { it.dupKey() }
		var added = 0
		for (p in parsed) {
			val cat = store.addressCategories.firstOrNull { it.name.equals(p.categoryName, true) }?.id ?: 0
			val a = p.address.copy(id = 0, category = cat, uid = "")
			// A card equal to one already stored, or to one earlier in this file, is skipped.
			if (a.dupKey() in have) continue
			have.add(a.dupKey())
			store.save(a)
			added++
		}
		return "Added $added addresses. Skipped ${parsed.size - added} duplicate addresses."
	}

	private fun importJson(text: String): String {
		val store = App.store
		// Read every record before the first write, so that a bad record adds nothing.
		// A category that does not exist yet gets a provisional id (-1, -2, ...) and is created after the read.
		val made = mutableMapOf<Pair<Int, String>, Pair<String, Int>>()  // (app, name ignoring case) to (name, provisional id)
		val content = Backup.read(text) { name, app ->
			store.categoriesFor(app).firstOrNull { it.name.equals(name, true) }?.id
				?: made.getOrPut(app to name.lowercase()) { name to -(made.size + 1) }.second
		}
		val remap = made.entries.associate { (key, v) -> v.second to store.addCategory(v.first, key.first).id }
		fun realId(id: Int): Int = if (id < 0) remap[id] ?: 0 else id
		for (t in content.todos) t.category = realId(t.category)
		for (m in content.memos) m.category = realId(m.category)
		for (a in content.addresses) a.category = realId(a.category)

		var skipped = 0
		val haveEvents = store.events.mapTo(HashSet()) { it.dupKey() }
		var ne = 0
		for (e in content.events) {
			if (e.dupKey() in haveEvents) { skipped++; continue }
			store.save(e)
			ne++
		}
		val haveTodos = store.todos.mapTo(HashSet()) { it.dupKey() }
		var nt = 0
		for (t in content.todos) {
			if (t.dupKey() in haveTodos) { skipped++; continue }
			store.save(t)
			nt++
		}
		val haveMemos = store.memos.mapTo(HashSet()) { it.dupKey() }
		var nm = 0
		for (m in content.memos) {
			if (m.dupKey() in haveMemos) { skipped++; continue }
			store.save(m)
			nm++
		}
		val haveAddresses = store.addresses.mapTo(HashSet()) { it.dupKey() }
		var na = 0
		for (a in content.addresses) {
			if (a.dupKey() in haveAddresses) { skipped++; continue }
			store.save(a)
			na++
		}
		return "Added $ne events, $nt To Do items, $nm memos and $na addresses. Skipped $skipped duplicate records."
	}

	/** The backup file. Backup.write gives the same keys and values as the Android app. */
	private fun backupJson(): String {
		val s = App.store
		return Backup.write(
			Backup.Content(
				s.events, s.todos, s.memos, s.addresses,
				s.categories.map { it.name }, s.memoCategories.map { it.name }, s.addressCategories.map { it.name }
			),
			s::categoryName
		)
	}
}
