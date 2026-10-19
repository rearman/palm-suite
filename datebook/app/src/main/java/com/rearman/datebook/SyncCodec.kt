package com.rearman.datebook

import com.rearman.palmui.ShortCut
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

    fun category(c: Category): String = JSONObject()
        .put("kind", "category")
        .put("name", c.name)
        .put("app", c.app)
        .toString()

    fun shortcuts(list: List<ShortCut>): String {
        val items = JSONArray()
        for (s in list) items.put(JSONObject().put("name", s.name).put("text", s.text))
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

    fun toCategory(text: String, uid: String, id: Int): Category {
        val o = JSONObject(text)
        return Category(id = id, name = o.optString("name"), app = o.optInt("app"), uid = uid)
    }

    fun toShortcuts(text: String): List<ShortCut> {
        val arr = JSONObject(text).optJSONArray("list") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val s = arr.getJSONObject(i)
            ShortCut(s.optString("name"), s.optString("text"))
        }
    }
}
