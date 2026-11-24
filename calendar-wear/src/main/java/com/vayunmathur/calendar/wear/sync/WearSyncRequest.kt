package com.vayunmathur.calendar.wear.sync

import android.content.Context
import com.google.android.gms.wearable.Wearable
import com.vayunmathur.calendar.wear.domain.WearCalendarProtocol
import com.vayunmathur.library.log.Log
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import com.google.android.gms.tasks.Task

/** Await a Play services [Task] without taking a coroutines-play-services dependency. */
private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
}

/**
 * Asks every connected phone node for a fresh snapshot. Returns false when no
 * phone is reachable — the watch keeps rendering its last synced copy, and
 * the UI says exactly that instead of pretending a sync happened.
 */
suspend fun requestWearSync(context: Context): Boolean = runCatching {
    val nodes = Wearable.getNodeClient(context).connectedNodes.awaitTask()
    if (nodes.isEmpty()) return false
    var sent = false
    for (node in nodes) {
        runCatching {
            Wearable.getMessageClient(context)
                .sendMessage(node.id, WearCalendarProtocol.REQUEST_SYNC_PATH, ByteArray(0))
                .awaitTask()
            sent = true
        }.onFailure { Log.status("WearSyncRequest", "request to ${node.id} failed: ${it.message}") }
    }
    sent
}.getOrDefault(false)
