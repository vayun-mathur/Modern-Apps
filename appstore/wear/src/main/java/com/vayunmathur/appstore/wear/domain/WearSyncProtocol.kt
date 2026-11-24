package com.vayunmathur.appstore.wear.domain

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Phone <-> watch sync protocol for the Wear OS App Store.
 *
 * SINGLE SOURCE OF TRUTH, DELIBERATELY DUPLICATED: Gradle cannot let one
 * application module depend on another, so the wear module
 * (`:appstore:wear`, applicationId `com.vayunmathur.appstore`) carries a
 * byte-identical copy of this file under `com.vayunmathur.appstore.wear.domain`.
 * Change both copies together; each side's unit tests pin the same golden JSON
 * so a one-sided edit fails loudly on the side that was not updated.
 *
 * Transport (WearLink, :library:wearlink — RFCOMM, no Play services):
 * - Presence is a completed handshake for [CAPABILITY] (the appId).
 * - Data items carry the small state documents: [CATALOG_PATH] phone ->
 *   watch and [WATCH_STATE_PATH] watch -> phone. Delivery needs a live
 *   session; each side persists what it receives and republishes on
 *   reconnect, reconciling the two sides when they meet again.
 * - Messages carry ephemeral commands/results ([OFFER_PATH],
 *   [INSTALL_RESULT_PATH], [REQUEST_STATE_PATH]).
 * - A stream ([apkChannelPath]) carries the APK itself, verified against
 *   the preceding offer's size+SHA-256 before PackageInstaller sees it.
 *
 * Install honesty: nothing here can install silently. The watch verifies the
 * stream against [ApkOffer.sha256], then a person on the watch taps Install
 * and confirms the system PackageInstaller prompt. States say exactly that —
 * there is no state that claims an install the OS has not confirmed.
 */
object WearSyncProtocol {
    /** WearLink appId the phone requires in the peer handshake. */
    const val CAPABILITY = "appstore_wear_store"

    /** Data-item path (phone -> watch): the [WearCatalog] JSON snapshot. */
    const val CATALOG_PATH = "/appstore/catalog"

    /** Data-item path (watch -> phone): the [WatchState] JSON snapshot. */
    const val WATCH_STATE_PATH = "/appstore/watch-state"

    /** Message path (phone -> watch): an [ApkOffer] precedes every stream. */
    const val OFFER_PATH = "/appstore/apk-offer"

    /** Message path (watch -> phone): an [InstallResult] after the OS answers. */
    const val INSTALL_RESULT_PATH = "/appstore/install-result"

    /** Message path (phone -> watch): ask the watch to republish [WATCH_STATE_PATH]. */
    const val REQUEST_STATE_PATH = "/appstore/request-state"

    /**
     * Message path (phone -> watch): ask the watch to surface its Install
     * action for the package named in the payload. This only *asks* — a person
     * on the watch still taps Install and confirms the system prompt.
     */
    const val REQUEST_INSTALL_PATH = "/appstore/request-install"

    /** Stream path prefix (phone -> watch); the package name is the last segment. */
    const val APK_CHANNEL_PREFIX = "/appstore/apk/"

    /** Largest single APK this slice will stream (a watch has little storage to spare). */
    const val MAX_APK_BYTES = 200L * 1024 * 1024

    /** Stream path streaming [packageName]'s APK. */
    fun apkChannelPath(packageName: String): String = APK_CHANNEL_PREFIX + packageName

    /** The package a stream path streams, or null when [path] is not an APK stream. */
    fun packageFromChannelPath(path: String): String? =
        path.takeIf { it.startsWith(APK_CHANNEL_PREFIX) }
            ?.removePrefix(APK_CHANNEL_PREFIX)
            ?.takeIf { it.isNotBlank() && '/' !in it }

    /** Wire JSON: unknown keys ignored so a newer side never breaks an older one. */
    val json: Json = Json { ignoreUnknownKeys = true }
}

/** One catalog row the phone offers the watch. Mirrors the phone catalog, trimmed to watch size. */
@Serializable
data class WearCatalogApp(
    val packageName: String,
    val name: String,
    val versionName: String? = null,
    val versionCode: Long = 0L,
    val sizeBytes: Long = 0L,
)

/** The phone's catalog snapshot for the watch ([WearSyncProtocol.CATALOG_PATH]). */
@Serializable
data class WearCatalog(val apps: List<WearCatalogApp> = emptyList())

/**
 * Metadata announced by message immediately before an APK stream opens.
 * The watch refuses bytes that do not match [sizeBytes]/[sha256] — the channel
 * itself carries no metadata, so this offer is the integrity contract.
 */
@Serializable
data class ApkOffer(
    val packageName: String,
    val name: String,
    val versionCode: Long,
    val sizeBytes: Long,
    /** Lowercase hex SHA-256 of the exact bytes about to be streamed. */
    val sha256: String,
)

/** Per-app truth as the watch knows it, published in [WatchState]. */
@Serializable
data class WatchAppEntry(
    val packageName: String,
    /** The APK for this version is fully received and hash-verified on the watch. */
    val downloadedVersionCode: Long = 0L,
    /** The version the watch's PackageManager reports installed, 0 when absent. */
    val installedVersionCode: Long = 0L,
    /** True while a verified APK sits waiting for the user to tap Install. */
    val installPending: Boolean = false,
)

/** The watch's downloaded/installed snapshot ([WearSyncProtocol.WATCH_STATE_PATH]). */
@Serializable
data class WatchState(val apps: List<WatchAppEntry> = emptyList())

/** The OS install verdict, sent watch -> phone once PackageInstaller answers. */
@Serializable
data class InstallResult(
    val packageName: String,
    val versionCode: Long,
    val installed: Boolean,
    /** Plain-language failure from the OS/status, empty on success. */
    val message: String = "",
)

/** Per-app sync state on the phone, per package. */
enum class PhoneWearState {
    /** No watch, or the watch has never been offered this app. */
    NOT_DOWNLOADED,
    /** The APK channel is open and bytes are moving. */
    SENDING,
    /** Verified APK is on the watch; nobody has asked to install it yet. */
    ON_WATCH,
    /** Install was requested; the watch still owes a user tap + OS confirmation. */
    INSTALL_REQUESTED,
    /** The watch's PackageManager confirms this (or a newer) version installed. */
    INSTALLED,
    /** The last send/verify/install step failed; see the message kept beside the state. */
    FAILED,
}

/** Events that move the phone-side per-app state. */
sealed interface PhoneWearEvent {
    data object SendStarted : PhoneWearEvent
    data class SendFinished(val success: Boolean) : PhoneWearEvent
    data object InstallRequested : PhoneWearEvent
    data class ResultReceived(val result: InstallResult) : PhoneWearEvent
    data class Reconciled(val entry: WatchAppEntry?, val catalogVersionCode: Long) : PhoneWearEvent
    data object WatchGone : PhoneWearEvent
}

/** Pure phone-side state machine; the Android transport only feeds it events. */
object PhoneWearMachine {
    fun reduce(state: PhoneWearState, event: PhoneWearEvent): PhoneWearState = when (event) {
        PhoneWearEvent.SendStarted -> PhoneWearState.SENDING
        is PhoneWearEvent.SendFinished ->
            if (event.success) PhoneWearState.ON_WATCH else PhoneWearState.FAILED
        PhoneWearEvent.InstallRequested ->
            if (state == PhoneWearState.ON_WATCH) PhoneWearState.INSTALL_REQUESTED else state
        is PhoneWearEvent.ResultReceived ->
            if (event.result.installed) PhoneWearState.INSTALLED else PhoneWearState.FAILED
        is PhoneWearEvent.Reconciled -> reconcile(state, event.entry, event.catalogVersionCode)
        PhoneWearEvent.WatchGone ->
            if (state == PhoneWearState.SENDING) PhoneWearState.FAILED else state
    }

    /**
     * Fold the watch's published truth into the local state. A live transfer
     * ([PhoneWearState.SENDING]) is never overwritten by a stale snapshot; an
     * install the watch confirms wins over everything else.
     */
    private fun reconcile(
        state: PhoneWearState,
        entry: WatchAppEntry?,
        catalogVersionCode: Long,
    ): PhoneWearState {
        if (state == PhoneWearState.SENDING) return state
        if (entry == null) {
            return if (state == PhoneWearState.INSTALLED) PhoneWearState.NOT_DOWNLOADED else state
        }
        return when {
            entry.installedVersionCode >= catalogVersionCode && catalogVersionCode > 0 ->
                PhoneWearState.INSTALLED
            entry.installPending -> PhoneWearState.INSTALL_REQUESTED
            entry.downloadedVersionCode >= catalogVersionCode && catalogVersionCode > 0 ->
                PhoneWearState.ON_WATCH
            entry.downloadedVersionCode > 0 -> PhoneWearState.ON_WATCH
            else -> state
        }
    }
}

/** Per-app state on the watch, per package. */
enum class WatchAppPhase {
    /** Offered in the catalog; no bytes yet. */
    LISTED,
    /** Channel open, bytes arriving. */
    RECEIVING,
    /** Stream arrived but failed the size/hash contract; bytes deleted. */
    REJECTED,
    /** Verified APK on disk, waiting for the user to tap Install. */
    READY_TO_INSTALL,
    /** PackageInstaller session committed; waiting for the OS verdict. */
    INSTALLING,
    /** PackageManager confirms the package installed. */
    INSTALLED,
    /** Install was attempted and the OS (or user) said no. */
    INSTALL_FAILED,
}

/** Events that move the watch-side per-app state. */
sealed interface WatchAppEvent {
    data object ReceiveStarted : WatchAppEvent
    data class ReceiveFinished(val verified: Boolean) : WatchAppEvent
    data object InstallTapped : WatchAppEvent
    data class InstallFinished(val installed: Boolean) : WatchAppEvent
    data object BecameInstalled : WatchAppEvent
}

/** Pure watch-side state machine; services/installers only feed it events. */
object WatchAppMachine {
    fun reduce(phase: WatchAppPhase, event: WatchAppEvent): WatchAppPhase = when (event) {
        WatchAppEvent.ReceiveStarted -> WatchAppPhase.RECEIVING
        is WatchAppEvent.ReceiveFinished ->
            if (event.verified) WatchAppPhase.READY_TO_INSTALL else WatchAppPhase.REJECTED
        WatchAppEvent.InstallTapped ->
            if (phase == WatchAppPhase.READY_TO_INSTALL ||
                phase == WatchAppPhase.INSTALL_FAILED
            ) {
                WatchAppPhase.INSTALLING
            } else {
                phase
            }
        is WatchAppEvent.InstallFinished ->
            if (event.installed) WatchAppPhase.INSTALLED else WatchAppPhase.INSTALL_FAILED
        WatchAppEvent.BecameInstalled -> WatchAppPhase.INSTALLED
    }
}

/**
 * Whether the phone can offer this catalog row to the watch at all.
 *
 * The catalog cannot prove a phone UI will be usable on a watch, and this does
 * not pretend otherwise: the gate is purely technical — one streamable APK
 * (split sets need a multi-file session protocol this slice does not carry)
 * with known bytes to hash. A truly incompatible APK is refused by the watch's
 * PackageManager at install time and that verdict is reported, not hidden.
 */
fun watchSendEligibility(
    apkUrl: String?,
    splitCount: Int,
    sizeBytes: Long,
): String? = when {
    apkUrl.isNullOrBlank() -> "this source published no downloadable APK"
    splitCount > 0 -> "split APK sets cannot be sent to the watch yet"
    sizeBytes > WearSyncProtocol.MAX_APK_BYTES -> "too large for the watch"
    else -> null
}

/** Streaming SHA-256 helpers: the offer/watch verification contract. */
object ApkHash {
    private const val BUFFER_SIZE = 64 * 1024

    /** Lowercase hex SHA-256 of [input]'s contents (consumed, not closed). */
    fun sha256Hex(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Lowercase hex SHA-256 of [file]. */
    fun sha256Hex(file: File): String = file.inputStream().buffered().use { sha256Hex(it) }

    /**
     * True when [file] is exactly [offer]'s bytes. A mismatch (or a truncated
     * stream, which the size check catches first) means the bytes are refused:
     * callers delete the file and never hand it to PackageInstaller.
     */
    fun verify(file: File, offer: ApkOffer): Boolean =
        file.length() == offer.sizeBytes && sha256Hex(file) == offer.sha256.lowercase()
}
