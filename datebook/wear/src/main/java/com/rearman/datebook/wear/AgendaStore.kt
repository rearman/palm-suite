package com.rearman.datebook.wear

import android.content.Context
import android.net.Uri
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.rearman.datebook.AgendaTodo
import com.rearman.datebook.WearAgenda
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Keeps the last agenda from the phone (WearBridge, in the WearAgenda format) in SharedPreferences,
 * with the uids of the To Dos checked on the watch that the phone still lists as open.
 */
object AgendaStore {
    private const val PREFS = "agenda"
    private const val DONE = "done"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Saves an agenda from the phone. A checked To Do that it no longer lists is settled. */
    fun save(ctx: Context, json: String) {
        val listed = WearAgenda.parse(json).todos.map { it.uid }.toSet()
        prefs(ctx).edit().putString(WearAgenda.KEY, json).putStringSet(DONE, done(ctx) intersect listed).apply()
    }

    private fun saved(ctx: Context): String? = prefs(ctx).getString(WearAgenda.KEY, null)

    private fun done(ctx: Context): Set<String> = prefs(ctx).getStringSet(DONE, null).orEmpty().toSet()

    /**
     * The saved agenda. Before the first push reaches this app (a new install), it reads the item
     * that the Data Layer already holds.
     */
    suspend fun load(ctx: Context): WearAgenda {
        saved(ctx)?.let { return WearAgenda.parse(it) }
        val json = fetch(ctx) ?: return WearAgenda.EMPTY
        save(ctx, json)
        return WearAgenda.parse(json)
    }

    /** The To Dos of [agenda] less those checked on the watch, in the order of the phone. */
    fun openTodos(ctx: Context, agenda: WearAgenda): List<AgendaTodo> {
        val done = done(ctx)
        return agenda.todos.filter { it.uid.isEmpty() || it.uid !in done }
    }

    /**
     * Checks the To Do [uid]: it leaves the watch lists at once, and the phone gets every uid not
     * yet settled in the one item [WearAgenda.DONE_PATH], which the Data Layer delivers when the
     * phone is in reach.
     */
    suspend fun markDone(ctx: Context, uid: String) {
        if (uid.isEmpty()) return
        val done = done(ctx) + uid
        prefs(ctx).edit().putStringSet(DONE, done).apply()
        val req = PutDataMapRequest.create(WearAgenda.DONE_PATH).apply {
            dataMap.putStringArray(WearAgenda.DONE_KEY, done.toTypedArray())
            // The time makes each put a new item, so the phone hears of it.
            dataMap.putLong("t", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()
        suspendCancellableCoroutine<Unit> { cont ->
            Wearable.getDataClient(ctx.applicationContext).putDataItem(req)
                .addOnCompleteListener { cont.resume(Unit) }
        }
    }

    private suspend fun fetch(ctx: Context): String? = suspendCancellableCoroutine { cont ->
        val uri = Uri.Builder().scheme("wear").path(WearAgenda.PATH).build()
        Wearable.getDataClient(ctx).getDataItems(uri)
            .addOnSuccessListener { items ->
                val json = items.firstOrNull()?.let { DataMapItem.fromDataItem(it).dataMap.getString(WearAgenda.KEY) }
                items.release()
                cont.resume(json)
            }
            .addOnFailureListener { cont.resume(null) }
    }
}
