package com.rearman.datebook

import org.json.JSONArray
import org.json.JSONObject

/**
 * Plaintext of a sync blob, as JSON (sync-server/PROTOCOL.md section 6).
 *
 * Format 2: short keys, the kind in "k" ("e", "t", "m", "a", "c", "s"), and no field
 * that holds its default. A blob with no "k" has an unknown kind.
 */
object SyncCodec {
    private val longKind = mapOf(
        "e" to "event", "t" to "todo", "m" to "memo", "a" to "address", "c" to "category", "s" to "shortcuts"
    )

    fun event(e: Event): String {
        val o = JSONObject().put("k", "e")
        putStr(o, "t", e.title)
        putStr(o, "n", e.note)
        putLong(o, "d", e.date, 0)
        putBool(o, "u", e.untimed)
        putInt(o, "s", e.start, 480)
        putInt(o, "e", e.end, 540)
        putBool(o, "a", e.alarm)
        putInt(o, "aa", e.alarmAdvance, 5)
        putInt(o, "au", e.alarmUnit, 0)
        putInt(o, "r", e.repeat, 0)
        putInt(o, "ev", e.every, 1)
        putLong(o, "re", e.repeatEnd, -1)
        putInt(o, "w", e.weekDays, 0)
        if (e.exceptions.isNotEmpty()) o.put("x", JSONArray(e.exceptions.sorted()))
        putBool(o, "p", e.isPrivate)
        return o.toString()
    }

    fun todo(t: Todo, categoryUid: String): String {
        val o = JSONObject().put("k", "t")
        putStr(o, "t", t.title)
        putStr(o, "n", t.note)
        putInt(o, "pr", t.priority, 1)
        putLong(o, "du", t.due, -1)
        putBool(o, "dn", t.done)
        putStr(o, "c", categoryUid)
        putBool(o, "p", t.isPrivate)
        return o.toString()
    }

    fun memo(m: Memo, categoryUid: String): String {
        val o = JSONObject().put("k", "m")
        putStr(o, "t", m.text)
        putStr(o, "c", categoryUid)
        putBool(o, "p", m.isPrivate)
        putInt(o, "o", m.pos, 0)
        return o.toString()
    }

    fun address(a: Address, categoryUid: String): String {
        val o = JSONObject().put("k", "a")
        putStr(o, "ln", a.lastName)
        putStr(o, "fn", a.firstName)
        putStr(o, "ti", a.title)
        putStr(o, "co", a.company)
        // A list is written whole when one entry differs from its default.
        if (a.phoneLabels.indices.any { a.phoneLabels[it] != it }) o.put("pl", JSONArray(a.phoneLabels))
        if (a.phones.any { it.isNotEmpty() }) o.put("ph", JSONArray(a.phones))
        putStr(o, "ad", a.address)
        putStr(o, "ci", a.city)
        putStr(o, "st", a.state)
        putStr(o, "z", a.zip)
        putStr(o, "cn", a.country)
        if (a.custom.any { it.isNotEmpty() }) o.put("cu", JSONArray(a.custom))
        putStr(o, "n", a.note)
        putInt(o, "sp", a.showPhone, 0)
        putStr(o, "c", categoryUid)
        putBool(o, "p", a.isPrivate)
        return o.toString()
    }

    fun category(c: Category): String {
        val o = JSONObject().put("k", "c")
        putStr(o, "n", c.name)
        putInt(o, "ap", c.app, 0)
        return o.toString()
    }

    /** The ShortCuts list as (name, text) pairs. */
    fun shortcuts(list: List<Pair<String, String>>): String {
        val items = JSONArray()
        for ((name, text) in list) {
            val item = JSONObject()
            putStr(item, "n", name)
            putStr(item, "t", text)
            items.put(item)
        }
        return JSONObject().put("k", "s").put("l", items).toString()
    }

    /** The "kind" of a blob, or "" when the text is not JSON or has no kind. */
    fun kind(text: String): String =
        try {
            longKind[JSONObject(text).optString("k")].orEmpty()
        } catch (e: Exception) {
            ""
        }

    fun toEvent(text: String, uid: String): Event {
        val o = JSONObject(text)
        val exceptions = mutableSetOf<Long>()
        val arr = o.optJSONArray("x")
        if (arr != null) for (i in 0 until arr.length()) exceptions.add(arr.optLong(i))
        return Event(
            title = o.optString("t"),
            note = o.optString("n"),
            date = o.optLong("d", 0),
            untimed = o.optBoolean("u"),
            start = o.optInt("s", 480),
            end = o.optInt("e", 540),
            alarm = o.optBoolean("a"),
            alarmAdvance = o.optInt("aa", 5),
            alarmUnit = o.optInt("au"),
            repeat = o.optInt("r"),
            every = o.optInt("ev", 1),
            repeatEnd = o.optLong("re", -1),
            weekDays = o.optInt("w"),
            exceptions = exceptions,
            isPrivate = o.optBoolean("p"),
            uid = uid
        )
    }

    fun toTodo(text: String, uid: String, categoryId: (String) -> Int): Todo {
        val o = JSONObject(text)
        return Todo(
            title = o.optString("t"),
            note = o.optString("n"),
            priority = o.optInt("pr", 1),
            due = o.optLong("du", -1),
            done = o.optBoolean("dn"),
            category = categoryId(o.optString("c")),
            isPrivate = o.optBoolean("p"),
            uid = uid
        )
    }

    fun toMemo(text: String, uid: String, categoryId: (String) -> Int): Memo {
        val o = JSONObject(text)
        return Memo(
            text = o.optString("t"),
            category = categoryId(o.optString("c")),
            isPrivate = o.optBoolean("p"),
            pos = o.optInt("o"),
            uid = uid
        )
    }

    fun toAddress(text: String, uid: String, categoryId: (String) -> Int): Address {
        val o = JSONObject(text)
        val labels = o.optJSONArray("pl")
        val phones = o.optJSONArray("ph")
        val custom = o.optJSONArray("cu")
        return Address(
            lastName = o.optString("ln"),
            firstName = o.optString("fn"),
            title = o.optString("ti"),
            company = o.optString("co"),
            phoneLabels = List(5) { (labels?.optInt(it, it) ?: it).coerceIn(0, 7) },
            phones = List(5) { phones?.optString(it, "") ?: "" },
            address = o.optString("ad"),
            city = o.optString("ci"),
            state = o.optString("st"),
            zip = o.optString("z"),
            country = o.optString("cn"),
            custom = List(4) { custom?.optString(it, "") ?: "" },
            note = o.optString("n"),
            showPhone = o.optInt("sp").coerceIn(0, 4),
            category = categoryId(o.optString("c")),
            isPrivate = o.optBoolean("p"),
            uid = uid
        )
    }

    fun toCategory(text: String, uid: String, id: Int): Category {
        val o = JSONObject(text)
        return Category(
            id = id,
            name = o.optString("n"),
            app = o.optInt("ap"),
            uid = uid
        )
    }

    fun toShortcuts(text: String): List<Pair<String, String>> {
        val o = JSONObject(text)
        val arr = o.optJSONArray("l") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val s = arr.getJSONObject(i)
            s.optString("n") to s.optString("t")
        }
    }

    /** Writes a string field. An empty string is the default, so it is left out. */
    private fun putStr(o: JSONObject, key: String, value: String) {
        if (value.isNotEmpty()) o.put(key, value)
    }

    /** Writes an int field unless it holds [default]. */
    private fun putInt(o: JSONObject, key: String, value: Int, default: Int) {
        if (value != default) o.put(key, value)
    }

    /** Writes a long field unless it holds [default]. */
    private fun putLong(o: JSONObject, key: String, value: Long, default: Long) {
        if (value != default) o.put(key, value)
    }

    /** Writes a flag only when it is true. */
    private fun putBool(o: JSONObject, key: String, value: Boolean) {
        if (value) o.put(key, true)
    }
}
