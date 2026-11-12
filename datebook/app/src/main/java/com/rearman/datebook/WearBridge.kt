package com.rearman.datebook

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Sends the agenda to the watch app (datebook/wear) as one Wear Data Layer item, in the format of
 * [WearAgenda] (sync-core). The payload holds today and the next 6 days; Alarms also pushes at each
 * midnight, so the watch never runs out of days. Private records follow the security option: Hide
 * leaves them out, Mask sends them as "Private" with no title. [WearListener] takes the To Dos that
 * the watch checked.
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
        // The open To Dos due by the last day, and those with no date (the To Do tile lists them).
        val open = store.todos.filter { !it.done && it.due <= last && !(hide && it.isPrivate) }
        val todos = store.sortTodos(open, prefs.sortMode).map {
            // A masked To Do goes with no uid, so the watch cannot complete it.
            val masked = mask && it.isPrivate
            AgendaTodo(if (masked) "Private" else it.title, it.due, it.priority, it.isPrivate, if (masked) "" else it.uid)
        }
        // The time makes each push a new item, so the watch hears of it.
        return WearAgenda(today, days, todos, System.currentTimeMillis())
    }
}

/**
 * Completes the To Dos that the watch checked: the Data Layer item [WearAgenda.DONE_PATH] holds
 * their uids. A uid that is unknown, or whose To Do is already completed, does nothing, so a
 * repeated item is harmless.
 */
class WearListener : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        val uids = mutableSetOf<String>()
        for (ev in events) {
            if (ev.type != DataEvent.TYPE_CHANGED || ev.dataItem.uri.path != WearAgenda.DONE_PATH) continue
            DataMapItem.fromDataItem(ev.dataItem).dataMap.getStringArray(WearAgenda.DONE_KEY)?.let { uids.addAll(it) }
        }
        if (uids.isEmpty()) return
        // Store work runs on the main thread. This worker thread waits, so the service lives until
        // the records are saved.
        val saved = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            try {
                complete(applicationContext, uids)
            } finally {
                saved.countDown()
            }
        }
        saved.await(10, TimeUnit.SECONDS)
    }

    private fun complete(ctx: Context, uids: Set<String>) {
        val store = Store.get(ctx)
        val prefs = Prefs(ctx)
        val open = store.todos.filter { !it.done && it.uid in uids }
        if (open.isEmpty()) return
        val today = LocalDate.now().toEpochDay()
        // With no activity in this process, Store.onWrite is not set: the sync job sends the change.
        val alone = store.onWrite == null
        for (t in open) store.complete(t, true, if (prefs.recordCompletion) today else -1)
        if (alone && prefs.syncOn) SyncJob.scheduleSoon(ctx)
        WearBridge.push(ctx)
        // An open screen shows the change (the hook of a sync that applied records).
        SyncEngine.onApplied?.invoke()
    }
}
