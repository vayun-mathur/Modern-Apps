package com.vayunmathur.appstore.sync

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
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
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

/** Await a Play services [Task] without taking a coroutines-play-services dependency. */
private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
}

/**
 * Phone side of the wear sync: capability discovery, catalog publish, APK
 * streaming and per-app state reconciliation.
 *
 * Every Wearable call is wrapped so that "no Play services / no watch / watch
 * without the wear store" is a normal state ([watchConnected] = false), never
 * a crash. The Data Layer only connects the two stores because they share the
 * package name `com.vayunmathur.appstore` and a signing certificate — dev
 * builds both use the debug key, release builds both use the same release key.
 */
class WearSyncManager(
    private val context: Context,
    private val scope: CoroutineScope,
) : CapabilityClient.OnCapabilityChangedListener,
    DataClient.OnDataChangedListener,
    MessageClient.OnMessageReceivedListener {

    /** Holds constants for the wear sync transport. */
    companion object {
        private const val TAG = "WearSyncManager"
        private const val KEY_JSON = "json"
        private const val KEY_SENT_AT = "sent_at"
    }

    private val _watchConnected = MutableStateFlow(false)

    /** True only when a reachable node advertises the wear store's capability. */
    val watchConnected: StateFlow<Boolean> = _watchConnected.asStateFlow()

    private val _states = MutableStateFlow<Map<String, PhoneWearState>>(emptyMap())

    /** Per-package sync state, keyed by package name. */
    val states: StateFlow<Map<String, PhoneWearState>> = _states.asStateFlow()

    private val _messages = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Per-package plain-language detail for the last failure/result, where any. */
    val messages: StateFlow<Map<String, String>> = _messages.asStateFlow()

    @Volatile
    private var watchNodeId: String? = null

    private var started = false

    /** Begin capability/data/message listening; no-op when already started or unavailable. */
    fun start() {
        if (started) return
        started = true
        runCatching {
            Wearable.getCapabilityClient(context).addListener(this, WearSyncProtocol.CAPABILITY)
            Wearable.getDataClient(context).addListener(this)
            Wearable.getMessageClient(context).addListener(this)
        }.onFailure { Log.status(TAG, "Wearable listeners unavailable: ${it.message}") }
        scope.launch { refreshCapability() }
    }

    override fun onCapabilityChanged(capabilityInfo: CapabilityInfo) {
        updateNodes(capabilityInfo.nodes.map { it.id })
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        for (event in dataEvents) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            val item = event.dataItem
            if (item.uri.path != WearSyncProtocol.WATCH_STATE_PATH) continue
            val json = runCatching {
                DataMapItem.fromDataItem(item).dataMap.getString(KEY_JSON)
            }.getOrNull() ?: continue
            val watchState = runCatching {
                WearSyncProtocol.json.decodeFromString<WatchState>(json)
            }.getOrNull() ?: continue
            reconcile(watchState)
        }
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path != WearSyncProtocol.INSTALL_RESULT_PATH) return
        val result = runCatching {
            WearSyncProtocol.json.decodeFromString<InstallResult>(
                messageEvent.data.decodeToString(),
            )
        }.getOrNull() ?: return
        applyEvent(
            result.packageName,
            PhoneWearEvent.ResultReceived(result),
            message = result.message.takeIf { it.isNotBlank() },
        )
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
            runCatching {
                val request = PutDataMapRequest.create(WearSyncProtocol.CATALOG_PATH).apply {
                    dataMap.putString(
                        KEY_JSON,
                        WearSyncProtocol.json.encodeToString(catalog),
                    )
                    dataMap.putLong(KEY_SENT_AT, System.currentTimeMillis())
                }.asPutDataRequest().setUrgent()
                Wearable.getDataClient(context).putDataItem(request).awaitTask()
            }.onFailure { Log.status(TAG, "catalog publish failed: ${it.message}") }
        }
    }

    /** Why [app] cannot be sent, or null when it can. Exact mirror of the catalog filter. */
    fun eligibility(app: UnifiedApp): String? =
        watchSendEligibility(app.apkUrl, app.splitFiles.size, app.sizeBytes)

    /**
     * Download [app]'s APK (once, into the wear staging dir), hash it, announce
     * the [ApkOffer], then stream the raw bytes over a channel. The watch does
     * the verifying; this side reports SENDING -> ON_WATCH/FAILED honestly.
     */
    fun sendToWatch(app: UnifiedApp) {
        val nodeId = watchNodeId
        if (nodeId == null) {
            applyEvent(
                app.packageName, PhoneWearEvent.WatchGone, "no watch with the Wear OS App Store is connected",
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
                Wearable.getMessageClient(context).sendMessage(
                    nodeId, WearSyncProtocol.OFFER_PATH,
                    WearSyncProtocol.json.encodeToString(offer).encodeToByteArray(),
                ).awaitTask()
                val channelClient = Wearable.getChannelClient(context)
                val channel = channelClient
                    .openChannel(nodeId, WearSyncProtocol.apkChannelPath(app.packageName))
                    .awaitTask()
                try {
                    channelClient.getOutputStream(channel).awaitTask().use { out ->
                        file.inputStream().buffered().use { it.copyTo(out) }
                    }
                } finally {
                    runCatching { channelClient.close(channel).awaitTask() }
                }
                applyEvent(app.packageName, PhoneWearEvent.SendFinished(true))
                requestWatchState(nodeId)
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

    /**
     * Ask the watch to surface its Install action for [packageName]. This only
     * asks: the person on the watch taps Install and confirms the system prompt.
     */
    fun requestInstall(packageName: String) {
        val nodeId = watchNodeId ?: return
        applyEvent(packageName, PhoneWearEvent.InstallRequested)
        scope.launch(Dispatchers.IO) {
            runCatching {
                Wearable.getMessageClient(context).sendMessage(
                    nodeId, WearSyncProtocol.REQUEST_INSTALL_PATH,
                    packageName.encodeToByteArray(),
                ).awaitTask()
            }.onFailure { Log.status(TAG, "request-install failed: ${it.message}") }
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
        // Packages the watch knows but this side has never tracked still reconcile.
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

    private suspend fun refreshCapability() {
        runCatching {
            val capability = Wearable.getCapabilityClient(context)
                .getCapability(WearSyncProtocol.CAPABILITY, CapabilityClient.FILTER_REACHABLE)
                .awaitTask()
            updateNodes(capability.nodes.map { it.id })
        }.onFailure {
            _watchConnected.value = false
            Log.status(TAG, "capability check unavailable: ${it.message}")
        }
    }

    private fun updateNodes(nodeIds: List<String>) {
        watchNodeId = nodeIds.firstOrNull()
        _watchConnected.value = nodeIds.isNotEmpty()
        if (nodeIds.isEmpty()) {
            _states.update { current ->
                current.mapValues { (_, state) ->
                    PhoneWearMachine.reduce(state, PhoneWearEvent.WatchGone)
                }
            }
        }
    }

    private fun requestWatchState(nodeId: String) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                Wearable.getMessageClient(context).sendMessage(
                    nodeId, WearSyncProtocol.REQUEST_STATE_PATH, byteArrayOf(),
                ).awaitTask()
            }
        }
    }

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

    /** Remember catalog versions so reconciliation can compare like with like. */
    fun noteCatalogVersions(apps: List<UnifiedApp>) {
        catalogVersions.value = apps.associate { it.packageName to it.versionCode }
    }
}
