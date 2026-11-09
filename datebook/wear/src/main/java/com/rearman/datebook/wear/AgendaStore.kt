package com.rearman.datebook.wear

import android.content.Context
import android.net.Uri
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.rearman.datebook.WearAgenda
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Keeps the last agenda from the phone (WearBridge, in the WearAgenda format) in SharedPreferences. */
object AgendaStore {
    private const val PREFS = "agenda"

    fun save(ctx: Context, json: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(WearAgenda.KEY, json).apply()
    }

    private fun saved(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(WearAgenda.KEY, null)

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
