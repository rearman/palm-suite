package com.rearman.datebook

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import java.time.LocalDate

/**
 * Sends the agenda to the watch app (datebook/wear) as one Wear Data Layer item, in the format of
 * [WearAgenda] (sync-core). The payload holds today and the next 6 days; Alarms also pushes at each
 * midnight, so the watch never runs out of days. Private records follow the security option: Hide
 * leaves them out, Mask sends them as "Private" with no title.
 */
object WearBridge {
    private const val DAYS = 7
    private const val DELAY_MS = 2_000L

    private val main = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null

    /** Main thread, with the app open. Pushes 2 s from now; a later call moves the push. */
    fun pushSoon(ctx: Context) {
        val app = ctx.applicationContext
        pending?.let { main.removeCallbacks(it) }
        pending = Runnable { pending = null; push(app) }.also { main.postDelayed(it, DELAY_MS) }
    }

    /**
     * Main thread. Pushes now: a receiver or job calls this one, since its process may stop before
     * a delayed push. Without Play services (or a watch) the put fails and nothing happens.
     */
    fun push(ctx: Context) {
        val json = agenda(Store.get(ctx), Prefs(ctx)).toJson()
        try {
            val req = PutDataMapRequest.create(WearAgenda.PATH).apply { dataMap.putString(WearAgenda.KEY, json) }
                .asPutDataRequest().setUrgent()
            Wearable.getDataClient(ctx.applicationContext).putDataItem(req)
        } catch (_: Exception) {
        }
    }

    private fun agenda(store: Store, prefs: Prefs): WearAgenda {
        val today = LocalDate.now().toEpochDay()
        val hide = prefs.security == 2
        val mask = prefs.security == 1
        val days = (today until today + DAYS).associateWith { d ->
            store.dayEvents(d).map { it.event }.filter { !(hide && it.isPrivate) }.map {
                AgendaEvent(if (mask && it.isPrivate) "Private" else it.title, it.start, it.end, it.untimed, it.isPrivate)
            }
        }
        val last = today + DAYS - 1
        val open = store.todos.filter { !it.done && it.due in 0..last && !(hide && it.isPrivate) }
        val todos = store.sortTodos(open, prefs.sortMode).map {
            AgendaTodo(if (mask && it.isPrivate) "Private" else it.title, it.due, it.priority, it.isPrivate)
        }
        // The time makes each push a new item, so the watch hears of it.
        return WearAgenda(today, days, todos, System.currentTimeMillis())
    }
}
