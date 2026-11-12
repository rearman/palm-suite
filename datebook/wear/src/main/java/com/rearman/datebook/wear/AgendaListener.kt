package com.rearman.datebook.wear

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import com.rearman.datebook.WearAgenda

/** Saves each agenda that the phone sends (WearBridge in datebook/app) and updates the tiles. */
class AgendaListener : WearableListenerService() {
    // A worker thread of the service; the tile update is a request, which returns at once.
    override fun onDataChanged(events: DataEventBuffer) {
        var got = false
        for (ev in events) {
            if (ev.type != DataEvent.TYPE_CHANGED || ev.dataItem.uri.path != WearAgenda.PATH) continue
            val json = DataMapItem.fromDataItem(ev.dataItem).dataMap.getString(WearAgenda.KEY) ?: continue
            AgendaStore.save(this, json)
            got = true
        }
        if (got) Tiles.updateAll(this)
    }
}
