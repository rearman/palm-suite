package com.rearman.datebook

import org.json.JSONArray
import org.json.JSONObject

/**
 * Plaintext of a sync blob, as JSON (sync-server/PROTOCOL.md section 6).
 *
 * Writers write format 2: short keys, the kind in "k" ("e", "t", "m", "a", "c", "s"),
 * and no field that holds its default. Readers accept format 2 and format 1, which
 * uses the long keys and the kind in "kind" ("event", "todo", ...).
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
            val o = JSONObject(text)
            if (o.isV2()) longKind[o.optString("k")].orEmpty() else o.optString("kind")
        } catch (e: Exception) {
            ""
        }

    fun toEvent(text: String, uid: String): Event {
        val o = JSONObject(text)
        val v2 = o.isV2()
        val exceptions = mutableSetOf<Long>()
        val arr = o.optJSONArray(key(v2, "x", "exceptions"))
        if (arr != null) for (i in 0 until arr.length()) exceptions.add(arr.optLong(i))
        return Event(
            title = o.optString(key(v2, "t", "title")),
            note = o.optString(key(v2, "n", "note")),
            date = o.optLong(key(v2, "d", "date"), 0),
            untimed = o.optBoolean(key(v2, "u", "untimed")),
            start = o.optInt(key(v2, "s", "start"), 480),
            end = o.optInt(key(v2, "e", "end"), 540),
            alarm = o.optBoolean(key(v2, "a", "alarm")),
            alarmAdvance = o.optInt(key(v2, "aa", "alarmAdvance"), 5),
            alarmUnit = o.optInt(key(v2, "au", "alarmUnit")),
            repeat = o.optInt(key(v2, "r", "repeat")),
            every = o.optInt(key(v2, "ev", "every"), 1),
            repeatEnd = o.optLong(key(v2, "re", "repeatEnd"), -1),
            weekDays = o.optInt(key(v2, "w", "weekDays")),
            exceptions = exceptions,
            isPrivate = o.optBoolean(key(v2, "p", "private")),
            uid = uid
        )
    }

    fun toTodo(text: String, uid: String, categoryId: (String) -> Int): Todo {
        val o = JSONObject(text)
        val v2 = o.isV2()
        return Todo(
            title = o.optString(key(v2, "t", "title")),
            note = o.optString(key(v2, "n", "note")),
            priority = o.optInt(key(v2, "pr", "priority"), 1),
            due = o.optLong(key(v2, "du", "due"), -1),
            done = o.optBoolean(key(v2, "dn", "done")),
            category = categoryId(o.optString(key(v2, "c", "category"))),
            isPrivate = o.optBoolean(key(v2, "p", "private")),
            uid = uid
        )
    }

    fun toMemo(text: String, uid: String, categoryId: (String) -> Int): Memo {
        val o = JSONObject(text)
        val v2 = o.isV2()
        return Memo(
            text = o.optString(key(v2, "t", "text")),
            category = categoryId(o.optString(key(v2, "c", "category"))),
            isPrivate = o.optBoolean(key(v2, "p", "private")),
            pos = o.optInt(key(v2, "o", "pos")),
            uid = uid
        )
    }

    fun toAddress(text: String, uid: String, categoryId: (String) -> Int): Address {
        val o = JSONObject(text)
        val v2 = o.isV2()
        val labels = o.optJSONArray(key(v2, "pl", "phoneLabels"))
        val phones = o.optJSONArray(key(v2, "ph", "phones"))
        val custom = o.optJSONArray(key(v2, "cu", "custom"))
        return Address(
            lastName = o.optString(key(v2, "ln", "lastName")),
            firstName = o.optString(key(v2, "fn", "firstName")),
            title = o.optString(key(v2, "ti", "title")),
            company = o.optString(key(v2, "co", "company")),
            phoneLabels = List(5) { (labels?.optInt(it, it) ?: it).coerceIn(0, 7) },
            phones = List(5) { phones?.optString(it, "") ?: "" },
            address = o.optString(key(v2, "ad", "address")),
            city = o.optString(key(v2, "ci", "city")),
            state = o.optString(key(v2, "st", "state")),
            zip = o.optString(key(v2, "z", "zip")),
            country = o.optString(key(v2, "cn", "country")),
            custom = List(4) { custom?.optString(it, "") ?: "" },
            note = o.optString(key(v2, "n", "note")),
            showPhone = o.optInt(key(v2, "sp", "showPhone")).coerceIn(0, 4),
            category = categoryId(o.optString(key(v2, "c", "category"))),
            isPrivate = o.optBoolean(key(v2, "p", "private")),
            uid = uid
        )
    }

    fun toCategory(text: String, uid: String, id: Int): Category {
        val o = JSONObject(text)
        val v2 = o.isV2()
        return Category(
            id = id,
            name = o.optString(key(v2, "n", "name")),
            app = o.optInt(key(v2, "ap", "app")),
            uid = uid
        )
    }

    fun toShortcuts(text: String): List<Pair<String, String>> {
        val o = JSONObject(text)
        val v2 = o.isV2()
        val arr = o.optJSONArray(key(v2, "l", "list")) ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val s = arr.getJSONObject(i)
            s.optString(key(v2, "n", "name")) to s.optString(key(v2, "t", "text"))
        }
    }

    /** True when the object is format 2 (it has the short kind key "k"). */
    private fun JSONObject.isV2(): Boolean = has("k")

    /** The format 2 key [f2] when [v2], else the format 1 key [f1]. */
    private fun key(v2: Boolean, f2: String, f1: String): String = if (v2) f2 else f1

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
