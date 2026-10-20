package com.rearman.datebook

import org.json.JSONArray
import org.json.JSONObject

/** Plaintext of a sync blob, as JSON (sync-server/PROTOCOL.md section 6). */
object SyncCodec {
    fun event(e: Event): String = JSONObject()
        .put("kind", "event")
        .put("title", e.title)
        .put("note", e.note)
        .put("date", e.date)
        .put("untimed", e.untimed)
        .put("start", e.start)
        .put("end", e.end)
        .put("alarm", e.alarm)
        .put("alarmAdvance", e.alarmAdvance)
        .put("alarmUnit", e.alarmUnit)
        .put("repeat", e.repeat)
        .put("every", e.every)
        .put("repeatEnd", e.repeatEnd)
        .put("weekDays", e.weekDays)
        .put("exceptions", JSONArray(e.exceptions.sorted()))
        .put("private", e.isPrivate)
        .toString()

    fun todo(t: Todo, categoryUid: String): String = JSONObject()
        .put("kind", "todo")
        .put("title", t.title)
        .put("note", t.note)
        .put("priority", t.priority)
        .put("due", t.due)
        .put("done", t.done)
        .put("category", categoryUid)
        .put("private", t.isPrivate)
        .toString()

    fun memo(m: Memo, categoryUid: String): String = JSONObject()
        .put("kind", "memo")
        .put("text", m.text)
        .put("category", categoryUid)
        .put("private", m.isPrivate)
        .put("pos", m.pos)
        .toString()

    fun address(a: Address, categoryUid: String): String = JSONObject()
        .put("kind", "address")
        .put("lastName", a.lastName)
        .put("firstName", a.firstName)
        .put("title", a.title)
        .put("company", a.company)
        .put("phoneLabels", JSONArray(a.phoneLabels))
        .put("phones", JSONArray(a.phones))
        .put("address", a.address)
        .put("city", a.city)
        .put("state", a.state)
        .put("zip", a.zip)
        .put("country", a.country)
        .put("custom", JSONArray(a.custom))
        .put("note", a.note)
        .put("showPhone", a.showPhone)
        .put("category", categoryUid)
        .put("private", a.isPrivate)
        .toString()

    fun category(c: Category): String = JSONObject()
        .put("kind", "category")
        .put("name", c.name)
        .put("app", c.app)
        .toString()

    /** The ShortCuts list as (name, text) pairs. */
    fun shortcuts(list: List<Pair<String, String>>): String {
        val items = JSONArray()
        for ((name, text) in list) items.put(JSONObject().put("name", name).put("text", text))
        return JSONObject().put("kind", "shortcuts").put("list", items).toString()
    }

    /** The "kind" of a blob, or "" when the text is not JSON or has no kind. */
    fun kind(text: String): String =
        try {
            JSONObject(text).optString("kind")
        } catch (e: Exception) {
            ""
        }

    fun toEvent(text: String, uid: String): Event {
        val o = JSONObject(text)
        val exceptions = mutableSetOf<Long>()
        val arr = o.optJSONArray("exceptions")
        if (arr != null) for (i in 0 until arr.length()) exceptions.add(arr.optLong(i))
        return Event(
            title = o.optString("title"),
            note = o.optString("note"),
            date = o.optLong("date", 0),
            untimed = o.optBoolean("untimed"),
            start = o.optInt("start", 480),
            end = o.optInt("end", 540),
            alarm = o.optBoolean("alarm"),
            alarmAdvance = o.optInt("alarmAdvance", 5),
            alarmUnit = o.optInt("alarmUnit"),
            repeat = o.optInt("repeat"),
            every = o.optInt("every", 1),
            repeatEnd = o.optLong("repeatEnd", -1),
            weekDays = o.optInt("weekDays"),
            exceptions = exceptions,
            isPrivate = o.optBoolean("private"),
            uid = uid
        )
    }

    fun toTodo(text: String, uid: String, categoryId: (String) -> Int): Todo {
        val o = JSONObject(text)
        return Todo(
            title = o.optString("title"),
            note = o.optString("note"),
            priority = o.optInt("priority", 1),
            due = o.optLong("due", -1),
            done = o.optBoolean("done"),
            category = categoryId(o.optString("category")),
            isPrivate = o.optBoolean("private"),
            uid = uid
        )
    }

    fun toMemo(text: String, uid: String, categoryId: (String) -> Int): Memo {
        val o = JSONObject(text)
        return Memo(
            text = o.optString("text"),
            category = categoryId(o.optString("category")),
            isPrivate = o.optBoolean("private"),
            pos = o.optInt("pos"),
            uid = uid
        )
    }

    fun toAddress(text: String, uid: String, categoryId: (String) -> Int): Address {
        val o = JSONObject(text)
        val labels = o.optJSONArray("phoneLabels")
        val phones = o.optJSONArray("phones")
        val custom = o.optJSONArray("custom")
        return Address(
            lastName = o.optString("lastName"),
            firstName = o.optString("firstName"),
            title = o.optString("title"),
            company = o.optString("company"),
            phoneLabels = List(5) { (labels?.optInt(it, it) ?: it).coerceIn(0, 7) },
            phones = List(5) { phones?.optString(it, "") ?: "" },
            address = o.optString("address"),
            city = o.optString("city"),
            state = o.optString("state"),
            zip = o.optString("zip"),
            country = o.optString("country"),
            custom = List(4) { custom?.optString(it, "") ?: "" },
            note = o.optString("note"),
            showPhone = o.optInt("showPhone").coerceIn(0, 4),
            category = categoryId(o.optString("category")),
            isPrivate = o.optBoolean("private"),
            uid = uid
        )
    }

    fun toCategory(text: String, uid: String, id: Int): Category {
        val o = JSONObject(text)
        return Category(id = id, name = o.optString("name"), app = o.optInt("app"), uid = uid)
    }

    fun toShortcuts(text: String): List<Pair<String, String>> {
        val arr = JSONObject(text).optJSONArray("list") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val s = arr.getJSONObject(i)
            s.optString("name") to s.optString("text")
        }
    }
}
