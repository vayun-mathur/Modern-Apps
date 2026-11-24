package com.vayunmathur.appstore.wear.service

import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.vayunmathur.appstore.wear.data.WearStore
import com.vayunmathur.appstore.wear.domain.ApkHash
import com.vayunmathur.appstore.wear.domain.ApkOffer
import com.vayunmathur.appstore.wear.domain.InstallResult
import com.vayunmathur.appstore.wear.domain.WatchAppEvent
import com.vayunmathur.appstore.wear.domain.WearCatalog
import com.vayunmathur.appstore.wear.domain.WearSyncProtocol
import com.vayunmathur.appstore.wear.ui.WearMainActivity
import com.vayunmathur.library.log.Log
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.encodeToString

/** Await a Play services [Task] without taking a coroutines-play-services dependency. */
private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
}

/**
 * Watch side of the Data Layer: catalog pushes, APK offers, APK channels and
 * install requests from the phone.
 *
 * APK bytes only ever land in app-private storage after the stream has been
 * checked against its [ApkOffer] (size, then SHA-256). A mismatch deletes the
 * file, marks the app REJECTED and tells the phone — unverified bytes are
 * never handed to PackageInstaller.
 */
class WearDataListenerService : WearableListenerService() {

    /** Holds constants for the listener service. */
    companion object {
        private const val TAG = "WearDataListener"
        private const val KEY_JSON = "json"
        private const val OFFER_WAIT_MS = 5_000L
    }

    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        val store = WearStore.get(this)
        for (event in dataEvents) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            if (event.dataItem.uri.path != WearSyncProtocol.CATALOG_PATH) continue
            val json = runCatching {
                DataMapItem.fromDataItem(event.dataItem).dataMap.getString(KEY_JSON)
            }.getOrNull() ?: continue
            runCatching { WearSyncProtocol.json.decodeFromString<WearCatalog>(json) }
                .onSuccess { store.setCatalog(it) }
                .onFailure { Log.status(TAG, "bad catalog push: ${it.message}") }
        }
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        val store = WearStore.get(this)
        when (messageEvent.path) {
            WearSyncProtocol.OFFER_PATH -> runCatching {
                WearSyncProtocol.json.decodeFromString<ApkOffer>(
                    messageEvent.data.decodeToString(),
                )
            }.onSuccess { store.noteOffer(it) }
                .onFailure { Log.status(TAG, "bad apk offer: ${it.message}") }

            WearSyncProtocol.REQUEST_STATE_PATH -> store.publishWatchState()

            WearSyncProtocol.REQUEST_INSTALL_PATH -> {
                // Asking is all the phone can do: surface the app so the wearer
                // can tap Install and confirm the system prompt themselves.
                store.noteInstallRequested(messageEvent.data.decodeToString())
                store.publishWatchState()
                runCatching {
                    startActivity(
                        android.content.Intent(this, WearMainActivity::class.java)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        }
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        val packageName = WearSyncProtocol.packageFromChannelPath(channel.path) ?: run {
            runCatching { Wearable.getChannelClient(this@WearDataListenerService).close(channel) }
            return
        }
        val store = WearStore.get(this)
        scope.launch { receiveApk(channel, packageName, store) }
    }

    private suspend fun receiveApk(
        channel: ChannelClient.Channel,
        packageName: String,
        store: WearStore,
    ) {
        // The offer message is sent before the channel opens, but the two
        // travel independently: give it a moment to land before refusing.
        var offer = store.offerFor(packageName)
        var waited = 0L
        while (offer == null && waited < OFFER_WAIT_MS) {
            delay(200)
            waited += 200
            offer = store.offerFor(packageName)
        }
        val target = store.apkFile(packageName)
        val partial = File(target.parentFile, "$packageName.partial")
        try {
            if (offer == null) {
                Log.error(TAG, "channel for $packageName arrived without an offer; refusing")
                reportFailure(packageName, 0L, "no download offer from the phone")
                return
            }
            store.applyEvent(packageName, WatchAppEvent.ReceiveStarted)
            val input = Wearable.getChannelClient(this).getInputStream(channel).awaitTask()
            input.use { stream ->
                partial.outputStream().use { stream.copyTo(it) }
            }
            if (ApkHash.verify(partial, offer)) {
                partial.renameTo(target)
                store.applyEvent(packageName, WatchAppEvent.ReceiveFinished(verified = true))
            } else {
                // Truncated or tampered stream: the bytes die here, unsigned
                // claims and all, and the phone hears about it.
                partial.delete()
                store.applyEvent(packageName, WatchAppEvent.ReceiveFinished(verified = false))
                reportFailure(
                    packageName, offer.versionCode,
                    "download did not match the size/hash the phone announced",
                )
            }
        } catch (expected: Exception) {
            Log.error(TAG, "receive $packageName failed", expected)
            runCatching { partial.delete() }
            store.applyEvent(packageName, WatchAppEvent.ReceiveFinished(verified = false))
            reportFailure(packageName, offer?.versionCode ?: 0L, expected.message ?: "transfer failed")
        } finally {
            store.clearOffer(packageName)
            runCatching { Wearable.getChannelClient(this).close(channel) }
            store.publishWatchState()
        }
    }

    private fun reportFailure(packageName: String, versionCode: Long, message: String) {
        val result = InstallResult(packageName, versionCode, installed = false, message)
        runCatching {
            Wearable.getNodeClient(this).connectedNodes.addOnSuccessListener { nodes ->
                for (node in nodes) {
                    Wearable.getMessageClient(this).sendMessage(
                        node.id,
                        WearSyncProtocol.INSTALL_RESULT_PATH,
                        WearSyncProtocol.json.encodeToString(result).encodeToByteArray(),
                    )
                }
            }
        }
    }
}
