package com.vayunmathur.launcher.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Phone <-> watch sync protocol for the Wear OS Launcher companion.
 *
 * SINGLE SOURCE OF TRUTH, DELIBERATELY DUPLICATED: Gradle cannot let one
 * application module depend on another, so the wear module
 * (`:launcher:wear`, applicationId `com.vayunmathur.launcher`) carries a copy
 * of this file under `com.vayunmathur.launcher.wear.domain`. Change both
 * copies together; each side's unit tests pin the same golden JSON so a
 * one-sided edit fails loudly on the side that was not updated.
 *
 * What syncs, and what deliberately does not:
 * - Phone -> watch: only the phone hotseat (the pinned/favorites row), as
 *   package + component + label in rank order. The full workspace grid,
 *   folders and widgets do not sync: a round watch has no paged grid to put
 *   them on, and inventing a mapping would misrepresent the phone layout.
 * - Watch -> phone: only a watch-state receipt and a request-sync message.
 *   Recents are watch-local launch history; sending them to the phone would
 *   create a second, competing launch-count truth next to the phone's own.
 *
 * Transport (WearLink, :library:wearlink - RFCOMM, no Play services):
 * presence is a completed handshake for [CAPABILITY] (the applicationId);
 * delivery needs a live session and each side persists what it receives.
 */
object WearLauncherProtocol {
    /** WearLink appId the phone requires in the peer handshake. */
    const val CAPABILITY = "launcher_wear_companion"

    /** Data-item path (phone -> watch): the [WearFavoritesSnapshot] JSON. */
    const val FAVORITES_PATH = "/launcher/favorites"

    /** Data-item path (watch -> phone): the [WearLauncherWatchState] JSON. */
    const val WATCH_STATE_PATH = "/launcher/watch-state"

    /** Message path (watch -> phone): ask the phone to publish fresh favorites. */
    const val REQUEST_SYNC_PATH = "/launcher/request-sync"

    /** Most pinned apps sent; the phone hotseat is capped well below this. */
    const val MAX_FAVORITES = 10

    /** Largest snapshot JSON in UTF-8 bytes, aligned with the other wear apps. */
    const val MAX_JSON_BYTES = 90_000

    /** Label cap applied when a snapshot is built. */
    const val MAX_LABEL_CHARS = 60

    /** Wire JSON: unknown keys ignored so a newer side never breaks an older one. */
    val json: Json = Json { ignoreUnknownKeys = true }
}

/** One pinned (hotseat) app in rank order. */
@Serializable
data class WearFavorite(
    val packageName: String,
    /** Flattened component class, empty when the phone row had none. */
    val className: String = "",
    val label: String = "",
    val rank: Int = 0,
)

/** The phone's bounded favorites snapshot for the watch ([WearLauncherProtocol.FAVORITES_PATH]). */
@Serializable
data class WearFavoritesSnapshot(
    val generatedAtMillis: Long = 0L,
    val favorites: List<WearFavorite> = emptyList(),
    /** True when the count/size caps dropped pinned apps. */
    val truncated: Boolean = false,
)

/** What the watch last applied, published at [WearLauncherProtocol.WATCH_STATE_PATH]. */
@Serializable
data class WearLauncherWatchState(
    val appliedGeneratedAtMillis: Long = 0L,
    val favoriteCount: Int = 0,
    val installedCount: Int = 0,
)

/**
 * Builds a bounded favorites snapshot from [candidates]: drops blank
 * packages, de-duplicates by package (lowest rank wins), sorts by rank then
 * label, caps labels, then enforces [WearLauncherProtocol.MAX_FAVORITES] and
 * [WearLauncherProtocol.MAX_JSON_BYTES] dropping lowest-ranked first.
 */
fun buildWearFavorites(
    candidates: List<WearFavorite>,
    generatedAtMillis: Long,
): WearFavoritesSnapshot {
    val cleaned = candidates
        .filter { it.packageName.isNotBlank() }
        .groupBy { it.packageName }
        .values.map { group -> group.minWith(compareBy({ it.rank }, { it.label })) }
        .sortedWith(compareBy({ it.rank }, { it.label.lowercase() }, { it.packageName }))
        .mapIndexed { index, favorite ->
            favorite.copy(label = favorite.label.take(WearLauncherProtocol.MAX_LABEL_CHARS), rank = index)
        }
    var kept = cleaned.take(WearLauncherProtocol.MAX_FAVORITES)
    var truncated = kept.size < cleaned.size
    var snapshot = WearFavoritesSnapshot(generatedAtMillis, kept, truncated)
    while (kept.isNotEmpty() &&
        WearLauncherProtocol.json.encodeToString(snapshot).encodeToByteArray().size >
        WearLauncherProtocol.MAX_JSON_BYTES
    ) {
        kept = kept.dropLast(1)
        truncated = true
        snapshot = WearFavoritesSnapshot(generatedAtMillis, kept, truncated)
    }
    return snapshot
}

/** True when a watch companion completed a WearLink handshake. */
fun wearLauncherCompanionPresent(peerCount: Int): Boolean = peerCount > 0
