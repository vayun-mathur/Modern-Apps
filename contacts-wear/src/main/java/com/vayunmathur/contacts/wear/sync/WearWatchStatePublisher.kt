package com.vayunmathur.contacts.wear.sync

import android.content.Context
import com.vayunmathur.contacts.wear.data.WearSnapshot
import com.vayunmathur.contacts.wear.domain.WearContactsProtocol
import com.vayunmathur.contacts.wear.domain.WearHandoffRequest
import com.vayunmathur.contacts.wear.domain.WearWatchState
import com.vayunmathur.library.log.Log
import kotlinx.serialization.encodeToString

/** Watch -> phone transport over WearLink: received-state data item + messages. */
object WearWatchStatePublisher {

    private const val TAG = "WearWatchState"

    /** Publish how many contacts this watch holds, so the phone status is truthful. */
    suspend fun publish(context: Context, snapshot: WearSnapshot) {
        val state = WearWatchState(
            contactCount = snapshot.contacts.size,
            generation = snapshot.manifest?.generation ?: 0L,
            receivedAtMillis = System.currentTimeMillis(),
        )
        val sent = WearLinkBridge.get(context).putData(
            WearContactsProtocol.WATCH_STATE_PATH,
            WearContactsProtocol.json.encodeToString(state).encodeToByteArray(),
        )
        if (!sent) Log.status(TAG, "watch-state publish not delivered (no live session)")
    }

    /** Ask the phone to republish the snapshot (e.g. first launch with an empty book). */
    suspend fun requestSnapshot(context: Context) {
        sendToPhone(context, WearContactsProtocol.REQUEST_SNAPSHOT_PATH, byteArrayOf())
    }

    /** Ask the phone to start a call/message this watch cannot start itself. */
    suspend fun requestHandoff(context: Context, request: WearHandoffRequest) {
        sendToPhone(
            context,
            WearContactsProtocol.HANDOFF_PATH,
            WearContactsProtocol.json.encodeToString(request).encodeToByteArray(),
        )
    }

    private suspend fun sendToPhone(context: Context, path: String, payload: ByteArray) {
        val sent = WearLinkBridge.get(context).sendMessage(path, payload)
        if (!sent) Log.status(TAG, "message $path not delivered (no live session)")
    }
}
