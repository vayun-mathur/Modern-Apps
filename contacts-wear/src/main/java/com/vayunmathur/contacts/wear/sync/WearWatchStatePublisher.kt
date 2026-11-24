package com.vayunmathur.contacts.wear.sync

import android.content.Context
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.vayunmathur.contacts.wear.data.WearSnapshot
import com.vayunmathur.contacts.wear.domain.WearContactsProtocol
import com.vayunmathur.contacts.wear.domain.WearHandoffRequest
import com.vayunmathur.contacts.wear.domain.WearWatchState
import com.vayunmathur.library.log.Log
import com.google.android.gms.tasks.Task
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.encodeToString

/** Await a Play services [Task] without a coroutines-play-services dependency. */
private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
}

/** Watch -> phone transport: received-state DataItem + ephemeral messages. */
object WearWatchStatePublisher {

    private const val TAG = "WearWatchState"
    private const val KEY_JSON = "json"
    private const val KEY_SENT_AT = "sent_at"

    /** Publish how many contacts this watch holds, so the phone status is truthful. */
    suspend fun publish(context: Context, snapshot: WearSnapshot) {
        runCatching {
            val state = WearWatchState(
                contactCount = snapshot.contacts.size,
                generation = snapshot.manifest?.generation ?: 0L,
                receivedAtMillis = System.currentTimeMillis(),
            )
            val request = PutDataMapRequest.create(WearContactsProtocol.WATCH_STATE_PATH)
                .apply {
                    dataMap.putString(KEY_JSON, WearContactsProtocol.json.encodeToString(state))
                    dataMap.putLong(KEY_SENT_AT, state.receivedAtMillis)
                }
                .asPutDataRequest().setUrgent()
            Wearable.getDataClient(context).putDataItem(request).awaitTask()
        }.onFailure { Log.status(TAG, "watch-state publish failed: ${it.message}") }
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
        runCatching {
            val nodes = Wearable.getNodeClient(context).connectedNodes.awaitTask()
            val messages = Wearable.getMessageClient(context)
            for (node in nodes) messages.sendMessage(node.id, path, payload).awaitTask()
        }.onFailure { Log.status(TAG, "message $path failed: ${it.message}") }
    }
}
