package com.rearman.palmdesktop

import org.json.JSONObject
import java.time.LocalDate

/** One line of the synced records list. */
data class Row(val type: String, val description: String, val details: String)

/**
 * The list rows of synced records, sorted by type, then by description. Each record is its uid and
 * its plaintext (sync-server/PROTOCOL.md section 6). The category records give the names of the
 * categories that To Do items and memos refer to by uid.
 */
fun rows(records: List<Pair<String, String>>): List<Row> {
	val parsed = records.mapNotNull { (uid, plain) -> parse(plain)?.let { uid to it } }
	val categories = parsed.filter { it.second.optString("kind") == "category" }
		.associate { (uid, o) -> uid to o.optString("name") }
	return parsed.mapNotNull { describe(it.second, categories) }
		.sortedWith(compareBy({ it.type }, { it.description.lowercase() }))
}

/** One row for a record, or null for a kind this app does not know. */
fun describe(o: JSONObject, categories: Map<String, String>): Row? {
	fun category() = categories[o.optString("category")] ?: "Unfiled"
	return when (o.optString("kind")) {
		"event" -> Row("Event", o.optString("title"), eventTime(o))
		"todo" -> {
			val due = o.optLong("due", -1)
			val parts = mutableListOf(
				if (due >= 0) "Due ${LocalDate.ofEpochDay(due)}" else "No due date",
				"Priority ${o.optInt("priority", 1)}",
				category()
			)
			if (o.optBoolean("done")) parts.add("Done")
			Row("To Do", o.optString("title"), parts.joinToString(", "))
		}
		"memo" -> Row("Memo", o.optString("text").lineSequence().firstOrNull()?.trim().orEmpty(), category())
		"category" -> Row("Category", o.optString("name"), if (o.optInt("app") == 1) "Memo Pad" else "To Do List")
		"shortcuts" -> Row("ShortCuts", "${o.optJSONArray("list")?.length() ?: 0} ShortCuts", "")
		else -> null
	}
}

private fun parse(plain: String): JSONObject? =
	try {
		JSONObject(plain)
	} catch (e: Exception) {
		null
	}

/** "2026-10-19 9:00 to 10:30, weekly". An untimed event shows "no time". */
private fun eventTime(o: JSONObject): String {
	val day = LocalDate.ofEpochDay(o.optLong("date"))
	val time = if (o.optBoolean("untimed")) "no time"
	else "${clock(o.optInt("start", 480))} to ${clock(o.optInt("end", 540))}"
	val repeat = when (o.optInt("repeat")) {
		1 -> ", daily"
		2 -> ", weekly"
		3, 4 -> ", monthly"
		5 -> ", yearly"
		else -> ""
	}
	return "$day $time$repeat"
}

private fun clock(min: Int) = "%d:%02d".format(min / 60, min % 60)
