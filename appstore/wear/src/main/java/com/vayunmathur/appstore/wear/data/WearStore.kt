package com.vayunmathur.appstore.wear.data

import android.content.Context
import com.vayunmathur.appstore.wear.domain.ApkOffer
import com.vayunmathur.appstore.wear.domain.WatchAppEntry
import com.vayunmathur.appstore.wear.domain.WatchAppMachine
import com.vayunmathur.appstore.wear.domain.WatchAppEvent
import com.vayunmathur.appstore.wear.domain.WatchAppPhase
import com.vayunmathur.appstore.wear.domain.WatchState
import com.vayunmathur.appstore.wear.domain.WearCatalog
import com.vayunmathur.appstore.wear.domain.WearCatalogApp
import com.vayunmathur.appstore.wear.domain.WearSyncProtocol
import com.vayunmathur.appstore.wear.sync.WearLinkBridge
import com.vayunmathur.library.log.Log
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.encodeToString

/** One row the watch UI draws: catalog facts plus where the local copy stands. */
data class WearRow(
    val app: WearCatalogApp,
    val phase: WatchAppPhase,
    /** True when the phone asked for install; cleared once the user acts. */
    val installRequested: Boolean = false,
)

/**
 * The watch's local truth: the last catalog the phone published, the verified
 * APKs on disk, and each app's phase.
 *
 * Everything survives the phone going away — the catalog and phases persist
 * as JSON in app-private storage and the APKs as files — so the watch app
 * works standalone with whatever it already has. Phases move only through
 * [WearAppMachine]; this class just persists and publishes the result.
 */
class WearStore private constructor(private val context: Context) {

    /** Holds singleton access. */
    companion object {
        private const val TAG = "WearStore"

        @Volatile
        private var instance: WearStore? = null

        /** The process-wide store (service and UI must agree on one). */
        fun get(context: Context): WearStore = instance ?: synchronized(this) {
            instance ?: WearStore(context.applicationContext).also { instance = it }
        }
    }

    private val apkDir = File(context.filesDir, "wear-apks").apply { mkdirs() }
    private val catalogFile = File(context.filesDir, "wear-catalog.json")
    private val phasesFile = File(context.filesDir, "wear-phases.json")

    private val _catalog = MutableStateFlow(loadCatalog())

    /** Last catalog snapshot from the phone; empty until the first sync. */
    val catalog: StateFlow<WearCatalog> = _catalog.asStateFlow()

    private val _phases = MutableStateFlow(loadPhases())

    /** Per-package install phase, persisted across process death. */
    val phases: StateFlow<Map<String, WatchAppPhase>> = _phases.asStateFlow()

    private val _requested = MutableStateFlow<Set<String>>(emptySet())

    /** Packages the phone asked the watch to surface Install for. */
    val requested: StateFlow<Set<String>> = _requested.asStateFlow()

    /** Offers announced but not yet fully streamed, by package. */
    private val pendingOffers = mutableMapOf<String, ApkOffer>()

    /** Rows for the UI: catalog order, plus on-disk apps the catalog forgot. */
    fun rows(): List<WearRow> {
        val known = _catalog.value.apps.map { app ->
            WearRow(
                app = app,
                phase = phaseOf(app.packageName),
                installRequested = app.packageName in _requested.value,
            )
        }
        val onDiskOnly = downloadedPackages()
            .filter { pkg -> known.none { it.app.packageName == pkg } }
            .map { pkg ->
                WearRow(
                    app = WearCatalogApp(packageName = pkg, name = pkg.substringAfterLast('.')),
                    phase = phaseOf(pkg),
                    installRequested = pkg in _requested.value,
                )
            }
        return known + onDiskOnly
    }

    /** Current phase of [packageName], reconciled with what PackageManager says. */
    fun phaseOf(packageName: String): WatchAppPhase {
        if (installedVersion(packageName) > 0) return WatchAppPhase.INSTALLED
        return _phases.value[packageName] ?: WatchAppPhase.LISTED
    }

    /** Version of [packageName] PackageManager reports installed, 0 when absent. */
    fun installedVersion(packageName: String): Long = runCatching {
        context.packageManager.getPackageInfo(packageName, 0).longVersionCode
    }.getOrDefault(0L)

    /** File a verified APK for [packageName] lives at once received. */
    fun apkFile(packageName: String): File = File(apkDir, "$packageName.apk")

    /** Packages with a (verified) APK currently on disk. */
    fun downloadedPackages(): List<String> =
        apkDir.listFiles { f -> f.isFile && f.name.endsWith(".apk") }
            ?.map { it.name.removeSuffix(".apk") }
            ?.sorted()
            ?: emptyList()

    /** Replace the catalog snapshot (WearLink data item from the phone). */
    fun setCatalog(catalog: WearCatalog) {
        _catalog.value = catalog
        runCatching { catalogFile.writeText(WearSyncProtocol.json.encodeToString(catalog)) }
            .onFailure { Log.status(TAG, "persist catalog failed: ${it.message}") }
    }

    /** Remember [offer] until its channel finishes (or fails) arriving. */
    fun noteOffer(offer: ApkOffer) {
        synchronized(pendingOffers) { pendingOffers[offer.packageName] = offer }
    }

    /** The offer a channel for [packageName] must verify against, if any. */
    fun offerFor(packageName: String): ApkOffer? =
        synchronized(pendingOffers) { pendingOffers[packageName] }

    /** Forget [packageName]'s offer once its stream has been dealt with. */
    fun clearOffer(packageName: String) {
        synchronized(pendingOffers) { pendingOffers.remove(packageName) }
    }

    /** Feed [event] for [packageName] through the state machine and persist. */
    fun applyEvent(packageName: String, event: WatchAppEvent) {
        _phases.update { current ->
            val old = current[packageName] ?: WatchAppPhase.LISTED
            val next = WatchAppMachine.reduce(old, event)
            if (next == old) current else (current + (packageName to next)).also { persistPhases(it) }
        }
        if (event is WatchAppEvent.InstallTapped) {
            _requested.update { it - packageName }
        }
    }

    /** The phone asked for install: surface it, never perform it. */
    fun noteInstallRequested(packageName: String) {
        _requested.update { it + packageName }
    }

    /** This watch's truth, for the phone's per-app sync reconciliation. */
    fun watchState(): WatchState {
        val packages = (_catalog.value.apps.map { it.packageName } + downloadedPackages()).distinct()
        return WatchState(
            packages.map { pkg ->
                WatchAppEntry(
                    packageName = pkg,
                    downloadedVersionCode = downloadedVersionOf(pkg),
                    installedVersionCode = installedVersion(pkg),
                    installPending = phaseOf(pkg) == WatchAppPhase.READY_TO_INSTALL ||
                        pkg in _requested.value,
                )
            },
        )
    }

    /**
     * Publish [watchState] to the phone ([WearSyncProtocol.WATCH_STATE_PATH])
     * over WearLink; a no-op delivery-wise when no session is live.
     */
    fun publishWatchState() {
        WearLinkBridge.publishWatchState(context)
    }

    private fun downloadedVersionOf(packageName: String): Long =
        _catalog.value.apps.firstOrNull { it.packageName == packageName }
            ?.versionCode
            ?.takeIf { apkFile(packageName).exists() }
            ?: if (apkFile(packageName).exists()) 1L else 0L

    private fun loadCatalog(): WearCatalog = runCatching {
        WearSyncProtocol.json.decodeFromString<WearCatalog>(catalogFile.readText())
    }.getOrDefault(WearCatalog())

    // Persisted as phase *names*: kotlinx JSON cannot use enum map keys.
    private fun loadPhases(): Map<String, WatchAppPhase> = runCatching {
        WearSyncProtocol.json.decodeFromString<Map<String, String>>(phasesFile.readText())
            .mapNotNull { (pkg, name) ->
                runCatching { WatchAppPhase.valueOf(name) }
                    .getOrNull()?.let { pkg to it }
            }
            .toMap()
    }.getOrDefault(emptyMap())

    private fun persistPhases(phases: Map<String, WatchAppPhase>) {
        runCatching {
            phasesFile.writeText(
                WearSyncProtocol.json.encodeToString(phases.mapValues { it.value.name }),
            )
        }.onFailure { Log.status(TAG, "persist phases failed: ${it.message}") }
    }
}
