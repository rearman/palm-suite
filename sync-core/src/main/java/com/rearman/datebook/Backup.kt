package com.rearman.datebook

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The Date Book backup file (JSON). Android and the desktop app both write and read it.
 *
 * Top-level keys: "format", "categories" (To Do names), "memoCategories", "addressCategories",
 * "events", "todos", "memos" and "addresses". A record names its category, never its id.
 * Files that the desktop app writes have no "addresses" and no "addressCategories" key.
 */
object Backup {
    /** The format name that the file carries in its "format" key. */
    const val FORMAT = "datebook-backup-1"

    /** The category name of category 0. It is never stored as a category of its own. */
    private const val UNFILED = "Unfiled"

    /**
     * The records of one backup file, and the category names of each app, including empty categories.
     * Records read from a file have id 0 and uid "".
     */
    class Content(
        val events: List<Event>,
        val todos: List<Todo>,
        val memos: List<Memo>,
        val addresses: List<Address>,
        val todoCategories: List<String> = emptyList(),
        val memoCategories: List<String> = emptyList(),
        val addressCategories: List<String> = emptyList()
    )

    /**
     * Writes [c] as a backup file. [categoryName] gives the name of a category id, and 0 gives "Unfiled".
     * The category lists are the ones in [c], in order, plus any name that a record uses and the list lacks.
     */
    fun write(c: Content, categoryName: (Int) -> String): String {
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("categories", JSONArray(withNames(c.todoCategories, c.todos.map { it.category }, categoryName)))
        root.put("memoCategories", JSONArray(withNames(c.memoCategories, c.memos.map { it.category }, categoryName)))
        root.put("addressCategories", JSONArray(withNames(c.addressCategories, c.addresses.map { it.category }, categoryName)))
        root.put("events", JSONArray(c.events.map { eventJson(it) }))
        root.put("todos", JSONArray(c.todos.map { todoJson(it, categoryName) }))
        root.put("memos", JSONArray(c.memos.map { memoJson(it, categoryName) }))
        root.put("addresses", JSONArray(c.addresses.map { addressJson(it, categoryName) }))
        return root.toString(1)
    }

    /**
     * Reads a backup file written by either app. [categoryId] gets a category name and its app
     * (0 To Do, 1 Memo Pad, 2 Address Book) and returns the id, and the caller finds or creates
     * the category. "Unfiled" and a blank name give 0 without a call. The category lists of the file
     * also go through [categoryId], so a category with no records is created.
     * A missing key gives the default of the Android reader: no records, or the field default.
     * Throws [JSONException] when the text is not JSON or its "format" is not [FORMAT], and before
     * [categoryId] runs, so a bad file creates no category.
     */
    fun read(text: String, categoryId: (name: String, app: Int) -> Int): Content {
        val root = JSONObject(text)
        if (root.optString("format") != FORMAT) throw JSONException("Not a Date Book backup.")

        // Parse every record first, so that a bad record creates no category.
        val topNames = listOf(
            root.optJSONArray("categories").names().map { it to 0 },
            root.optJSONArray("memoCategories").names().map { it to 1 },
            root.optJSONArray("addressCategories").names().map { it to 2 }
        ).flatten()
        val events = root.optJSONArray("events").objects().map(::eventFrom)
        val todos = root.optJSONArray("todos").objects().map { o ->
            Todo(
                title = o.optString("title"), note = o.optString("note"),
                priority = o.optInt("priority", 1), due = o.optLong("due", -1),
                done = o.optBoolean("done"), isPrivate = o.optBoolean("private")
            ) to o.optString("category", UNFILED)
        }
        val memos = root.optJSONArray("memos").objects().map { o ->
            Memo(text = o.optString("text"), isPrivate = o.optBoolean("private")) to o.optString("category", UNFILED)
        }
        val addresses = root.optJSONArray("addresses").objects().map { o ->
            Address(
                lastName = o.optString("lastName"), firstName = o.optString("firstName"),
                title = o.optString("title"), company = o.optString("company"),
                phoneLabels = o.optJSONArray("phoneLabels").labels(5), phones = o.optJSONArray("phones").strings(5),
                address = o.optString("address"), city = o.optString("city"), state = o.optString("state"),
                zip = o.optString("zip"), country = o.optString("country"),
                custom = o.optJSONArray("custom").strings(4), note = o.optString("note"),
                showPhone = o.optInt("showPhone").coerceIn(0, 4),
                isPrivate = o.optBoolean("private")
            ) to o.optString("category", UNFILED)
        }

        fun idOf(name: String, app: Int): Int =
            if (name.isBlank() || name.equals(UNFILED, true)) 0 else categoryId(name, app)

        for ((name, app) in topNames) idOf(name, app)
        return Content(
            events = events,
            todos = todos.map { (t, name) -> t.apply { category = idOf(name, 0) } },
            memos = memos.map { (m, name) -> m.apply { category = idOf(name, 1) } },
            addresses = addresses.map { (a, name) -> a.apply { category = idOf(name, 2) } },
            todoCategories = topNames.filter { it.second == 0 }.map { it.first },
            memoCategories = topNames.filter { it.second == 1 }.map { it.first },
            addressCategories = topNames.filter { it.second == 2 }.map { it.first }
        )
    }

    // Writing

    /** [given], then the names of the categories [ids] use that [given] lacks. Unfiled and blank names are left out. */
    private fun withNames(given: List<String>, ids: List<Int>, categoryName: (Int) -> String): List<String> {
        val used = ids.filter { it != 0 }.map(categoryName)
            .filter { it.isNotBlank() && !it.equals(UNFILED, true) }
        return given + used.filter { u -> given.none { it.equals(u, true) } }.distinct()
    }

    private fun eventJson(e: Event) = JSONObject().apply {
        put("title", e.title); put("note", e.note); put("date", e.date); put("untimed", e.untimed)
        put("start", e.start); put("end", e.end); put("alarm", e.alarm)
        put("alarmAdvance", e.alarmAdvance); put("alarmUnit", e.alarmUnit)
        put("repeat", e.repeat); put("every", e.every); put("repeatEnd", e.repeatEnd)
        put("weekDays", e.weekDays); put("exceptions", JSONArray(e.exceptions.sorted()))
        put("private", e.isPrivate)
    }

    private fun todoJson(t: Todo, categoryName: (Int) -> String) = JSONObject().apply {
        put("title", t.title); put("note", t.note); put("priority", t.priority); put("due", t.due)
        put("done", t.done); put("category", categoryName(t.category)); put("private", t.isPrivate)
    }

    private fun memoJson(m: Memo, categoryName: (Int) -> String) = JSONObject().apply {
        put("text", m.text); put("category", categoryName(m.category)); put("private", m.isPrivate)
    }

    private fun addressJson(a: Address, categoryName: (Int) -> String) = JSONObject().apply {
        put("lastName", a.lastName); put("firstName", a.firstName); put("title", a.title); put("company", a.company)
        put("phoneLabels", JSONArray(a.phoneLabels)); put("phones", JSONArray(a.phones))
        put("address", a.address); put("city", a.city); put("state", a.state); put("zip", a.zip)
        put("country", a.country); put("custom", JSONArray(a.custom)); put("note", a.note)
        put("showPhone", a.showPhone); put("category", categoryName(a.category)); put("private", a.isPrivate)
    }

    // Reading

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

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else List(length()) { getJSONObject(it) }

    /** The strings of the array, padded with "" or cut to [n] entries. */
    private fun JSONArray?.strings(n: Int): List<String> =
        List(n) { i -> this?.optString(i, "") ?: "" }

    /** Every string of the array, in order. */
    private fun JSONArray?.names(): List<String> =
        if (this == null) emptyList() else List(length()) { getString(it) }

    private fun JSONArray?.longs(): List<Long> =
        if (this == null) emptyList() else List(length()) { getLong(it) }

    /** The phone label indexes, padded with the index itself and kept in the label range. */
    private fun JSONArray?.labels(n: Int): List<Int> =
        List(n) { i -> (this?.optInt(i, i) ?: i).coerceIn(0, Address.LABELS.size - 1) }
}
