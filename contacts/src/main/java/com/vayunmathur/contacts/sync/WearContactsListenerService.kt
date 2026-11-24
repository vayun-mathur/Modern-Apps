package com.vayunmathur.contacts.sync

import android.content.Intent
import android.net.Uri
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.vayunmathur.contacts.domain.WearContactsProtocol
import com.vayunmathur.contacts.domain.WearHandoffRequest
import com.vayunmathur.library.log.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Phone side of the watch handoff, alive even when the app UI is not: the watch
 * asks for a fresh snapshot, or asks the phone to start a call/message it cannot
 * start itself. Handoffs open the phone's dialler/messaging composer — the
 * person on the phone still taps call/send; nothing is placed silently.
 */
class WearContactsListenerService : WearableListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(messageEvent: MessageEvent) {
        when (messageEvent.path) {
            WearContactsProtocol.REQUEST_SNAPSHOT_PATH -> republish()
            WearContactsProtocol.HANDOFF_PATH -> handleHandoff(messageEvent.data)
        }
    }

    private fun republish() {
        scope.launch {
            runCatching {
                WearContactsSync(applicationContext, scope)
                    .publishWearContacts(loadWearContacts(applicationContext))
            }.onFailure { Log.status(TAG, "republish failed: ${it.message}") }
        }
    }

    private fun handleHandoff(data: ByteArray) {
        val request = runCatching {
            WearContactsProtocol.json.decodeFromString<WearHandoffRequest>(
                data.decodeToString(),
            )
        }.getOrNull() ?: return
        val intent = when (request.kind) {
            WearHandoffRequest.KIND_CALL ->
                Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", request.value, null))
            WearHandoffRequest.KIND_MESSAGE ->
                Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", request.value, null))
            else -> return
        }
        runCatching {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.status(TAG, "handoff failed: ${it.message}") }
    }

    companion object {
        private const val TAG = "WearContactsListener"
    }
}
