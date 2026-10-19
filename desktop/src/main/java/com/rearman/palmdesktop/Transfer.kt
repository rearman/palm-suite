package com.rearman.palmdesktop

import com.rearman.datebook.*
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.awt.Component
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.ZoneId
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.filechooser.FileNameExtensionFilter

/** Import and export of Date Book records: iCalendar files and the Date Book backup file. */
object Transfer {
	private const val FORMAT = "datebook-backup-1"

	fun exportFile(owner: Component) {
		val ics = FileNameExtensionFilter("iCalendar (*.ics)", "ics")
		val json = FileNameExtensionFilter("Date Book backup (*.json)", "json")
		val c = JFileChooser()
		c.dialogTitle = "Export"
		c.isAcceptAllFileFilterUsed = false
		c.addChoosableFileFilter(ics)
		c.addChoosableFileFilter(json)
		c.fileFilter = ics
		if (c.showSaveDialog(owner) != JFileChooser.APPROVE_OPTION) return

		// The typed extension wins. Without one, the selected filter gives the format.
		val typed = c.selectedFile.path
		val known = typed.endsWith(".json", true) || typed.endsWith(".ics", true)
		val asJson = when {
			typed.endsWith(".json", true) -> true
			typed.endsWith(".ics", true) -> false
			else -> c.fileFilter === json
		}
		val file = if (known) File(typed) else File("$typed.${if (asJson) "json" else "ics"}")

		val visible = App.store.events.filter { App.showPrivate || !it.isPrivate }
		val text = if (asJson) backupJson() else ICal.export(visible)
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
		if (c.showOpenDialog(owner) != JFileChooser.APPROVE_OPTION) return
		val text = try {
			Files.readString(c.selectedFile.toPath(), StandardCharsets.UTF_8)
		} catch (e: IOException) {
			JOptionPane.showMessageDialog(owner, "The file could not be read.\n${e.message}", "Import", JOptionPane.ERROR_MESSAGE)
			return
		}
		val body = text.removePrefix("﻿").trimStart()
		try {
			val message = if (body.startsWith("BEGIN:VCALENDAR", true)) importIcs(text) else importJson(body)
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

	private fun importJson(text: String): String {
		val root = JSONObject(text)
		if (root.optString("format") != FORMAT) throw JSONException("Not a Date Book backup.")

		// Read every record before the first write, so that a bad record adds nothing.
		val events = root.optJSONArray("events").objects().map(::eventFrom)
		val todos = root.optJSONArray("todos").objects().map { o ->
			Todo(
				title = o.optString("title"), note = o.optString("note"),
				priority = o.optInt("priority", 1), due = o.optLong("due", -1),
				done = o.optBoolean("done"), isPrivate = o.optBoolean("private")
			) to o.optString("category", "Unfiled")
		}
		val memos = root.optJSONArray("memos").objects().map { o ->
			Memo(text = o.optString("text"), isPrivate = o.optBoolean("private")) to o.optString("category", "Unfiled")
		}

		val store = App.store
		for (n in root.optJSONArray("categories").strings()) {
			if (store.categories.none { it.name.equals(n, true) }) store.addCategory(n, 0)
		}
		for (n in root.optJSONArray("memoCategories").strings()) {
			if (store.memoCategories.none { it.name.equals(n, true) }) store.addCategory(n, 1)
		}

		var skipped = 0
		val haveEvents = store.events.mapTo(HashSet()) { it.dupKey() }
		var ne = 0
		for (e in events) {
			if (e.dupKey() in haveEvents) { skipped++; continue }
			store.save(e)
			ne++
		}
		val haveTodos = store.todos.mapTo(HashSet()) { it.dupKey() }
		var nt = 0
		for ((t, catName) in todos) {
			t.category = store.categories.firstOrNull { it.name.equals(catName, true) }?.id ?: 0
			if (t.dupKey() in haveTodos) { skipped++; continue }
			store.save(t)
			nt++
		}
		val haveMemos = store.memos.mapTo(HashSet()) { it.dupKey() }
		var nm = 0
		for ((m, catName) in memos) {
			m.category = store.memoCategories.firstOrNull { it.name.equals(catName, true) }?.id ?: 0
			if (m.dupKey() in haveMemos) { skipped++; continue }
			store.save(m)
			nm++
		}
		return "Added $ne events, $nt To Do items and $nm memos. Skipped $skipped duplicate records."
	}

	private fun eventFrom(o: JSONObject) = Event(
		title = o.optString("title"), note = o.optString("note"),
		date = o.getLong("date"), untimed = o.optBoolean("untimed"),
		start = o.optInt("start", 480), end = o.optInt("end", 540),
		alarm = o.optBoolean("alarm"), alarmAdvance = o.optInt("alarmAdvance", 5),
		alarmUnit = o.optInt("alarmUnit"), repeat = o.optInt("repeat"),
		every = o.optInt("every", 1), repeatEnd = o.optLong("repeatEnd", -1),
		weekDays = o.optInt("weekDays"), exceptions = o.optJSONArray("exceptions").longs().toMutableSet(),
		isPrivate = o.optBoolean("private")
	)

	// The same keys and values as Store.exportJson in the Android app.

	private fun backupJson(): String {
		val s = App.store
		val root = JSONObject()
		root.put("format", FORMAT)
		root.put("categories", JSONArray(s.categories.map { it.name }))
		root.put("memoCategories", JSONArray(s.memoCategories.map { it.name }))
		root.put("events", JSONArray(s.events.map { eventJson(it) }))
		root.put("todos", JSONArray(s.todos.map { todoJson(it) }))
		root.put("memos", JSONArray(s.memos.map { memoJson(it) }))
		return root.toString(1)
	}

	private fun eventJson(e: Event) = JSONObject().apply {
		put("title", e.title); put("note", e.note); put("date", e.date); put("untimed", e.untimed)
		put("start", e.start); put("end", e.end); put("alarm", e.alarm)
		put("alarmAdvance", e.alarmAdvance); put("alarmUnit", e.alarmUnit)
		put("repeat", e.repeat); put("every", e.every); put("repeatEnd", e.repeatEnd)
		put("weekDays", e.weekDays); put("exceptions", JSONArray(e.exceptions.sorted()))
		put("private", e.isPrivate)
	}

	private fun todoJson(t: Todo) = JSONObject().apply {
		put("title", t.title); put("note", t.note); put("priority", t.priority); put("due", t.due)
		put("done", t.done); put("category", App.store.categoryName(t.category)); put("private", t.isPrivate)
	}

	private fun memoJson(m: Memo) = JSONObject().apply {
		put("text", m.text); put("category", App.store.categoryName(m.category)); put("private", m.isPrivate)
	}

	private fun JSONArray?.objects(): List<JSONObject> =
		if (this == null) emptyList() else List(length()) { getJSONObject(it) }

	private fun JSONArray?.strings(): List<String> =
		if (this == null) emptyList() else List(length()) { getString(it) }

	private fun JSONArray?.longs(): List<Long> =
		if (this == null) emptyList() else List(length()) { getLong(it) }
}
