package com.vayunmathur.appstore.wear.sync

import android.content.Context
import android.content.Intent
import com.vayunmathur.appstore.wear.data.WearStore
import com.vayunmathur.appstore.wear.domain.ApkOffer
import com.vayunmathur.appstore.wear.domain.InstallResult
import com.vayunmathur.appstore.wear.domain.WatchAppEvent
import com.vayunmathur.appstore.wear.domain.WearCatalog
import com.vayunmathur.appstore.wear.domain.WearSyncProtocol
import com.vayunmathur.appstore.wear.ui.WearMainActivity
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.wearlink.StreamSink
import com.vayunmathur.library.wearlink.StreamHeader
import com.vayunmathur.library.wearlink.WearLinkListener
import com.vayunmathur.library.wearlink.WearLinkNode
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString

/**
 * Watch side of WearLink: catalog data items, APK offers, APK streams and
 * install requests from the phone. Replaces the old Play-services listener
 * service — delivery is now bound to this process being alive (the activity
 * starts the node; there is no system wakeup for a dead process).
 *
 * APK bytes only land in app-private storage after the stream verifies
 * against its [ApkOffer] (size, then SHA-256, enforced by the WearLink
 * stream layer against the header the phone sent from the same file).
 */
object WearLinkBridge {
    private const val TAG = "WearLinkBridge"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var node: WearLinkNode? = null

    /** The live node, created (and started) on first use. */
    fun get(context: Context): WearLinkNode {
        val appContext = context.applicationContext
        return node ?: synchronized(this) {
            node ?: create(appContext).also { node = it }
        }
    }

    private fun create(context: Context): WearLinkNode {
        val listener = object : WearLinkListener {
            override fun onData(path: String, body: ByteArray) {
                if (path != WearSyncProtocol.CATALOG_PATH) return
                runCatching {
                    WearSyncProtocol.json.decodeFromString<WearCatalog>(body.decodeToString())
                }.onSuccess { WearStore.get(context).setCatalog(it) }
                    .onFailure { Log.status(TAG, "bad catalog push: ${it.message}") }
            }

            override fun onMessage(path: String, body: ByteArray) {
                val store = WearStore.get(context)
                when (path) {
                    WearSyncProtocol.OFFER_PATH -> runCatching {
                        WearSyncProtocol.json.decodeFromString<ApkOffer>(body.decodeToString())
                    }.onSuccess { store.noteOffer(it) }
                        .onFailure { Log.status(TAG, "bad apk offer: ${it.message}") }

                    WearSyncProtocol.REQUEST_STATE_PATH -> publishWatchState(context)

                    WearSyncProtocol.REQUEST_INSTALL_PATH -> {
                        store.noteInstallRequested(body.decodeToString())
                        publishWatchState(context)
                        runCatching {
                            context.startActivity(
                                Intent(context, WearMainActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }
                }
            }

            override fun onStreamOpen(path: String, header: StreamHeader): StreamSink? {
                val packageName = WearSyncProtocol.packageFromChannelPath(path) ?: return null
                val store = WearStore.get(context)
                val offer = store.offerFor(packageName) ?: run {
                    Log.error(TAG, "stream for $packageName without an offer; refusing")
                    reportFailure(context, packageName, 0L, "no download offer from the phone")
                    return null
                }
                if (header.totalSize != offer.sizeBytes || !header.sha256.equals(offer.sha256, ignoreCase = true)) {
                    reportFailure(
                        context, packageName, offer.versionCode,
                        "stream header does not match the phone's offer",
                    )
                    return null
                }
                store.applyEvent(packageName, WatchAppEvent.ReceiveStarted)
                return ApkStreamSink(context, store, packageName, offer)
            }
        }
        return WearLinkNode(context, WearSyncProtocol.CAPABILITY, listener).also { it.start() }
    }

    /** Publish this watch's state data item through the live node, if any. */
    fun publishWatchState(context: Context) {
        val store = WearStore.get(context)
        scope.launch {
            val sent = get(context).putData(
                WearSyncProtocol.WATCH_STATE_PATH,
                WearSyncProtocol.json.encodeToString(store.watchState()).encodeToByteArray(),
            )
            if (!sent) Log.status(TAG, "watch state not delivered (no live session)")
        }
    }

    /** Send an install result message to the phone, if a session is live. */
    fun reportResult(context: Context, result: InstallResult) {
        scope.launch {
            get(context).sendMessage(
                WearSyncProtocol.INSTALL_RESULT_PATH,
                WearSyncProtocol.json.encodeToString(result).encodeToByteArray(),
            )
        }
    }

    private fun reportFailure(
        context: Context,
        packageName: String,
        versionCode: Long,
        message: String,
    ) {
        reportResult(context, InstallResult(packageName, versionCode, false, message))
    }

    /**
     * Writes a stream into `<pkg>.partial`, resuming a retained partial
     * (its bytes are re-hashed by the WearLink receiver), and on a verified
     * close renames it into place. Unverified bytes are deleted, never
     * handed to PackageInstaller.
     */
    private class ApkStreamSink(
        private val context: Context,
        private val store: WearStore,
        private val packageName: String,
        private val offer: ApkOffer,
    ) : StreamSink {
        private val target: File = store.apkFile(packageName)
        private val partial: File = File(target.parentFile, "$packageName.partial")
        private var output: OutputStream? = null

        override val resumeOffset: Long =
            if (partial.exists() && partial.length() in 1 until offer.sizeBytes) {
                partial.length()
            } else {
                0L
            }

        override fun prefixStream(): InputStream? =
            if (resumeOffset > 0) FileInputStream(partial) else null

        override fun write(bytes: ByteArray, offset: Long) {
            val out = output ?: FileOutputStream(partial, resumeOffset > 0).also { output = it }
            out.write(bytes)
        }

        override fun close(verified: Boolean) {
            runCatching { output?.close() }
            output = null
            if (verified) {
                if (partial.renameTo(target)) {
                    store.applyEvent(packageName, WatchAppEvent.ReceiveFinished(verified = true))
                } else {
                    store.applyEvent(packageName, WatchAppEvent.ReceiveFinished(verified = false))
                }
            } else {
                partial.delete()
                store.applyEvent(packageName, WatchAppEvent.ReceiveFinished(verified = false))
                WearLinkBridge.reportFailure(
                    context, packageName, offer.versionCode,
                    "download did not match the size/hash the phone announced",
                )
            }
            store.clearOffer(packageName)
            WearLinkBridge.publishWatchState(context)
        }
    }
}
