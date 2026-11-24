package com.vayunmathur.calendar.wear.service

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import com.vayunmathur.calendar.wear.data.WearCalendarStore
import com.vayunmathur.calendar.wear.domain.WearCalendarProtocol
import com.vayunmathur.calendar.wear.domain.WearCalendarSnapshot
import com.vayunmathur.library.log.Log

/** Receives the phone's snapshot DataItem, including while the UI is not running. */
class WearCalendarListenerService : WearableListenerService() {

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        for (event in dataEvents) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            val item = event.dataItem
            if (item.uri.path != WearCalendarProtocol.SNAPSHOT_PATH) continue
            val raw = runCatching {
                DataMapItem.fromDataItem(item).dataMap.getString(KEY_JSON)
            }.getOrNull() ?: continue
            val snapshot = runCatching {
                WearCalendarProtocol.json.decodeFromString<WearCalendarSnapshot>(raw)
            }.getOrNull()
            if (snapshot == null) {
                Log.status(TAG, "ignoring malformed snapshot")
                continue
            }
            WearCalendarStore.get(this).setSnapshot(snapshot)
        }
    }

    private companion object {
        const val TAG = "WearCalendarListener"
        const val KEY_JSON = "json"
    }
}
