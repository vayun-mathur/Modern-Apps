package com.vayunmathur.appstore.sync

import android.content.Context
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.domain.ApkHash
import com.vayunmathur.appstore.domain.ApkOffer
import com.vayunmathur.appstore.domain.InstallResult
import com.vayunmathur.appstore.domain.PhoneWearEvent
import com.vayunmathur.appstore.domain.PhoneWearMachine
import com.vayunmathur.appstore.domain.PhoneWearState
import com.vayunmathur.appstore.domain.WatchState
import com.vayunmathur.appstore.domain.WearCatalog
import com.vayunmathur.appstore.domain.WearCatalogApp
import com.vayunmathur.appstore.domain.WearSyncProtocol
import com.vayunmathur.appstore.domain.watchSendEligibility
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.wearlink.FileStreamSource
import com.vayunmathur.library.wearlink.WearLinkListener
import com.vayunmathur.library.wearlink.WearLinkNode
import com.vayunmathur.library.wearlink.WearLinkState
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

/**
 * Phone side of the wear sync over WearLink (RFCOMM, :library:wearlink):
 * presence is a completed cert-bound handshake, catalog/watch-state are
 * data items, offers/results are messages and APK bytes are a stream.
 *
 * "No Bluetooth permission / Bluetooth off / no watch / watch without the
 * wear store" is a normal state ([watchConnected] = false), never a crash.
 * Delivery is app-lifecycle-bound: unlike the old Play-services listener,
 * nothing wakes this process when the watch speaks first.
 */
class WearSyncManager(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "WearSyncManager"
    }

    private val listener = object : WearLinkListener {
        override fun onMessage(path: String, body: ByteArray) {
            if (path != WearSyncProtocol.INSTALL_RESULT_PATH) return
            val result = runCatching {
                WearSyncProtocol.json.decodeFromString<InstallResult>(body.decodeToString())
            }.getOrNull() ?: return
            applyEvent(
                result.packageName,
                PhoneWearEvent.ResultReceived(result),
                message = result.message.takeIf { it.isNotBlank() },
            )
        }

        override fun onData(path: String, body: ByteArray) {
            if (path != WearSyncProtocol.WATCH_STATE_PATH) return
            val watchState = runCatching {
                WearSyncProtocol.json.decodeFromString<WatchState>(body.decodeToString())
            }.getOrNull() ?: return
            reconcile(watchState)
        }
    }

    private val node = WearLinkNode(context, WearSyncProtocol.CAPABILITY, listener)

    private val _watchConnected = MutableStateFlow(false)

    /** True only when a WearLink handshake with the wear store completed. */
    val watchConnected: StateFlow<Boolean> = _watchConnected.asStateFlow()

    private val _states = MutableStateFlow<Map<String, PhoneWearState>>(emptyMap())
    val states: StateFlow<Map<String, PhoneWearState>> = _states.asStateFlow()

    private val _messages = MutableStateFlow<Map<String, String>>(emptyMap())
    val messages: StateFlow<Map<String, String>> = _messages.asStateFlow()

    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            node.state.collect { state ->
                val connected = state is WearLinkState.Connected
                _watchConnected.value = connected
                if (!connected) {
                    _states.update { current ->
                        current.mapValues { (_, s) ->
                            PhoneWearMachine.reduce(s, PhoneWearEvent.WatchGone)
                        }
                    }
                }
            }
        }
        node.start()
    }

    /** Publish the watch-eligible catalog snapshot so the watch can list it standalone. */
    fun syncCatalog(apps: List<UnifiedApp>) {
        noteCatalogVersions(apps)
        val catalog = WearCatalog(
            apps.filter { watchSendEligibility(it.apkUrl, it.splitFiles.size, it.sizeBytes) == null }
                .map {
                    WearCatalogApp(
                        packageName = it.packageName,
                        name = it.name,
                        versionName = it.versionName,
                        versionCode = it.versionCode,
                        sizeBytes = it.sizeBytes,
                    )
                },
        )
        scope.launch(Dispatchers.IO) {
            val sent = node.putData(
                WearSyncProtocol.CATALOG_PATH,
                WearSyncProtocol.json.encodeToString(catalog).encodeToByteArray(),
            )
            if (!sent) Log.status(TAG, "catalog publish not delivered (no live WearLink session)")
        }
    }

    fun eligibility(app: UnifiedApp): String? =
        watchSendEligibility(app.apkUrl, app.splitFiles.size, app.sizeBytes)

    /**
     * Download [app]'s APK (once, into the wear staging dir), hash it, announce
     * the [ApkOffer], then stream the raw bytes. The watch does the verifying;
     * this side reports SENDING -> ON_WATCH/FAILED honestly.
     */
    fun sendToWatch(app: UnifiedApp) {
        if (!node.isConnected) {
            applyEvent(
                app.packageName, PhoneWearEvent.WatchGone,
                "no watch with the Wear OS App Store is connected",
            )
            return
        }
        eligibility(app)?.let {
            _messages.update { m -> m + (app.packageName to it) }
            return
        }
        applyEvent(app.packageName, PhoneWearEvent.SendStarted)
        scope.launch(Dispatchers.IO) {
            try {
                val file = stageApk(app)
                val offer = ApkOffer(
                    packageName = app.packageName,
                    name = app.name,
                    versionCode = app.versionCode,
                    sizeBytes = file.length(),
                    sha256 = ApkHash.sha256Hex(file),
                )
                val offered = node.sendMessage(
                    WearSyncProtocol.OFFER_PATH,
                    WearSyncProtocol.json.encodeToString(offer).encodeToByteArray(),
                )
                if (!offered) throw IOException("watch is not connected")
                val streamed = node.sendStream(
                    WearSyncProtocol.apkChannelPath(app.packageName),
                    FileStreamSource(file, offer.sha256),
                )
                if (!streamed) throw IOException("APK transfer failed")
                applyEvent(app.packageName, PhoneWearEvent.SendFinished(true))
                node.sendMessage(WearSyncProtocol.REQUEST_STATE_PATH, byteArrayOf())
            } catch (expected: Exception) {
                Log.error(TAG, "send ${app.packageName} failed", expected)
                applyEvent(
                    app.packageName,
                    PhoneWearEvent.SendFinished(false),
                    expected.message ?: "could not send to the watch",
                )
            }
        }
    }

    /** Ask the watch to surface its Install action. The wearer still taps + confirms. */
    fun requestInstall(packageName: String) {
        if (!node.isConnected) return
        applyEvent(packageName, PhoneWearEvent.InstallRequested)
        scope.launch(Dispatchers.IO) {
            node.sendMessage(
                WearSyncProtocol.REQUEST_INSTALL_PATH, packageName.encodeToByteArray(),
            )
        }
    }

    private suspend fun stageApk(app: UnifiedApp): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "wear").apply { mkdirs() }
        val target = File(dir, "${app.packageName}.apk")
        val fresh = target.exists() && target.length() > 0 &&
            (app.sizeBytes <= 0 || target.length() == app.sizeBytes) &&
            (app.apkSha256 == null || ApkHash.sha256Hex(target) == app.apkSha256.lowercase())
        if (!fresh) download(app.apkUrl!!, target)
        if (app.apkSha256 != null &&
            !ApkHash.sha256Hex(target).equals(app.apkSha256, ignoreCase = true)
        ) {
            target.delete()
            throw IOException("downloaded APK does not match the catalog hash")
        }
        target
    }

    private fun download(url: String, target: File) {
        val connection = URL(url).openConnection()
        val sslSocketFactory = NetworkClient.defaultSslSocketFactory
        if (sslSocketFactory != null && connection is HttpsURLConnection) {
            connection.sslSocketFactory = sslSocketFactory
        }
        val conn = (connection as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 120_000
            instanceFollowRedirects = true
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("the server returned HTTP $code")
            conn.inputStream.use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun reconcile(watchState: WatchState) {
        val byPackage = watchState.apps.associateBy { it.packageName }
        _states.update { current ->
            current.mapValues { (pkg, state) ->
                PhoneWearMachine.reduce(
                    state,
                    PhoneWearEvent.Reconciled(byPackage[pkg], catalogVersionOf(pkg)),
                )
            }
        }
        _states.update { current ->
            var next = current
            for (entry in watchState.apps) {
                if (entry.packageName !in next) {
                    next = next + (entry.packageName to PhoneWearMachine.reduce(
                        PhoneWearState.NOT_DOWNLOADED,
                        PhoneWearEvent.Reconciled(entry, entry.downloadedVersionCode),
                    ))
                }
            }
            next
        }
    }

    private val catalogVersions = MutableStateFlow<Map<String, Long>>(emptyMap())

    private fun catalogVersionOf(packageName: String): Long =
        catalogVersions.value[packageName] ?: 0L

    private fun applyEvent(
        packageName: String,
        event: PhoneWearEvent,
        message: String? = null,
    ) {
        _states.update { current ->
            val old = current[packageName] ?: PhoneWearState.NOT_DOWNLOADED
            current + (packageName to PhoneWearMachine.reduce(old, event))
        }
        if (message != null) {
            _messages.update { it + (packageName to message) }
        }
    }

    fun noteCatalogVersions(apps: List<UnifiedApp>) {
        catalogVersions.value = apps.associate { it.packageName to it.versionCode }
    }
}
