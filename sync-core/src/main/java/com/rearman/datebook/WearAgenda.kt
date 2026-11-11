package com.rearman.datebook

import org.json.JSONArray
import org.json.JSONObject

/** One appointment of the watch agenda: times in minutes after midnight. */
data class AgendaEvent(val title: String, val start: Int, val end: Int, val untimed: Boolean, val isPrivate: Boolean = false)

/**
 * One open To Do of the watch agenda, with its due date (epoch day, -1 = no date). The watch
 * completes it by [uid]; a To Do with no uid (a masked one) has no check box there.
 */
data class AgendaTodo(
    val title: String,
    val due: Long,
    val priority: Int = 1,
    val isPrivate: Boolean = false,
    val uid: String = "",
)

/**
 * The agenda that the phone (WearBridge) sends to the watch app (datebook/wear) over the Wear
 * Data Layer item [PATH], as the JSON string [KEY]. Both sides use this one codec.
 *
 * Version 1: {"v":1, "generated":<epoch ms>, "today":<epoch day>,
 *  "days":[{"day":<epoch day>, "events":[{"title":s, "start":min, "end":min, "untimed":b, "private":b}]}],
 *  "todos":[{"title":s, "due":<epoch day>, "priority":n, "private":b, "uid":s}]}
 * "uid" came later and may be absent.
 *
 * The watch answers with the item [DONE_PATH]: the uids of the To Dos checked there, as the string
 * array [DONE_KEY]. The phone completes each one that is still open.
 */
data class WearAgenda(
    val today: Long,
    val days: Map<Long, List<AgendaEvent>>,
    val todos: List<AgendaTodo>,
    val generated: Long = 0,
) {
    /** The events of [day], in Day view order. */
    fun events(day: Long): List<AgendaEvent> = days[day].orEmpty()

    /** The To Dos due on [day] or before it. A To Do with no date is never due. */
    fun todosDue(day: Long): Int = todos.count { it.due in 0..day }

    /**
     * The appointments still to come at [now] (minutes after midnight) of [day], each with its
     * day: those of [day] that are untimed or have not ended, then all of the later days.
     */
    fun upcoming(day: Long, now: Int): List<Pair<Long, AgendaEvent>> =
        days.toSortedMap().filterKeys { it >= day }.flatMap { (d, list) ->
            list.filter { d > day || it.untimed || it.end > now }.map { d to it }
        }

    fun toJson(): String {
        val da = JSONArray()
        for ((day, list) in days.toSortedMap()) {
            val ea = JSONArray()
            for (e in list) {
                ea.put(JSONObject().put("title", e.title).put("start", e.start).put("end", e.end)
                    .put("untimed", e.untimed).put("private", e.isPrivate))
            }
            da.put(JSONObject().put("day", day).put("events", ea))
        }
        val ta = JSONArray()
        for (t in todos) {
            ta.put(JSONObject().put("title", t.title).put("due", t.due).put("priority", t.priority)
                .put("private", t.isPrivate).put("uid", t.uid))
        }
        return JSONObject().put("v", VERSION).put("generated", generated).put("today", today)
            .put("days", da).put("todos", ta).toString()
    }

    companion object {
        const val PATH = "/agenda"
        const val KEY = "json"
        const val VERSION = 1
        const val DONE_PATH = "/done"
        const val DONE_KEY = "uids"
        val EMPTY = WearAgenda(-1, emptyMap(), emptyList())

        /** Reads [toJson]. A bad payload, or one of another version, gives [EMPTY]. */
        fun parse(json: String?): WearAgenda {
            if (json.isNullOrEmpty()) return EMPTY
            return try {
                val o = JSONObject(json)
                if (o.optInt("v") != VERSION) return EMPTY
                val days = mutableMapOf<Long, List<AgendaEvent>>()
                val da = o.getJSONArray("days")
                for (i in 0 until da.length()) {
                    val d = da.getJSONObject(i)
                    val ea = d.getJSONArray("events")
                    days[d.getLong("day")] = (0 until ea.length()).map {
                        val e = ea.getJSONObject(it)
                        AgendaEvent(e.getString("title"), e.getInt("start"), e.getInt("end"),
                            e.getBoolean("untimed"), e.getBoolean("private"))
                    }
                }
                val ta = o.getJSONArray("todos")
                val todos = (0 until ta.length()).map {
                    val t = ta.getJSONObject(it)
                    AgendaTodo(t.getString("title"), t.getLong("due"), t.getInt("priority"), t.getBoolean("private"),
                        t.optString("uid"))
                }
                WearAgenda(o.getLong("today"), days, todos, o.getLong("generated"))
            } catch (_: Exception) {
                EMPTY
            }
        }
    }
}
