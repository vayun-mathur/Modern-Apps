package com.vayunmathur.music.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Phone <-> watch protocol for the Wear OS Music companion.
 *
 * SINGLE SOURCE OF TRUTH, DELIBERATELY DUPLICATED: Gradle cannot let one
 * application module depend on another, so the wear module (`:music:wear`,
 * applicationId `com.vayunmathur.music`) carries a copy under
 * `com.vayunmathur.music.wear.domain`. Change both copies together; each
 * side's `WearMusicProtocolTest` pins the same golden JSON.
 *
 * Design (owner direction): the phone owns the catalog and ALL audio
 * playback. The watch never stores music files and never warehouses the
 * whole catalog. Instead the phone serves bounded page snapshots keyed by
 * [WearPageKey] with cursor pagination; the watch caches visited pages for
 * offline browsing. Now-playing is a small [WearPlaybackState] streamed as
 * a data item. Every playback action is a [WearMusicOp] sent to the phone,
 * which executes it in its playback service; the watch UI only reflects the
 * resulting [WearPlaybackState]. No audio plays on the watch itself.
 */
object WearMusicProtocol {
    /** WearLink appId the phone requires in the peer handshake. */
    const val CAPABILITY = "music_wear_companion"

    /** Data-item path (phone -> watch): one [WearPageSnapshot] JSON. */
    const val PAGE_PATH = "/music/page"

    /** Data-item path (phone -> watch): the [WearPlaybackState] JSON. */
    const val PLAYBACK_PATH = "/music/playback"

    /** Data-item path (watch -> phone): the [WearWatchState] JSON. */
    const val WATCH_STATE_PATH = "/music/watch-state"

    /** Message path (watch -> phone): one [WearPageRequest] JSON. */
    const val PAGE_REQUEST_PATH = "/music/page-request"

    /** Message path (watch -> phone): one [WearMusicOp] JSON. */
    const val OP_PATH = "/music/op"

    /** Message path (watch -> phone): one [WearSearchRequest] JSON. */
    const val SEARCH_REQUEST_PATH = "/music/search-request"

    /** Tracks/artists/albums per page; keeps one frame small on the watch. */
    const val PAGE_SIZE = 30

    /** Largest page/playback JSON in UTF-8 bytes. */
    const val MAX_JSON_BYTES = 45_000

    /** Largest playback queue page carried in state (full queue stays on phone). */
    const val MAX_QUEUE_ITEMS = 50

    /** Field caps. */
    const val MAX_TITLE_CHARS = 120
    const val MAX_NAME_CHARS = 120

    /** Wire JSON: unknown keys ignored so a newer side never breaks an older one. */
    val json: Json = Json { ignoreUnknownKeys = true }
}

/** Every phone page/route, mirrored 1:1 as a watch page key. */
@Serializable
enum class WearPageKey {
    HOME, ALBUMS, ARTISTS, PLAYLISTS, TRACKS,
    ALBUM_DETAIL, ARTIST_DETAIL, PLAYLIST_DETAIL,
    SEARCH, QUEUE, NOW_PLAYING, SETTINGS,
}

/** Identifies one bounded page: route + entity id + search query. */
@Serializable
data class WearPageId(
    val key: WearPageKey,
    val entityId: Long = 0L,
    val query: String = "",
)

/** Id-carrying page request (watch -> phone). [cursor] is the opaque next-page cursor, "" = first page. */
@Serializable
data class WearPageRequest(val page: WearPageId, val cursor: String = "")

/** Search request (watch -> phone): dictation/typed text lives in [WearPageRequest.page.query]. */
@Serializable
data class WearSearchRequest(val query: String, val cursor: String = "")

/** One track row, trimmed to watch size. IDs are phone library IDs. */
@Serializable
data class WearTrack(
    val id: Long,
    val title: String,
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0L,
    val isPlaying: Boolean = false,
)

/** One album row. */
@Serializable
data class WearAlbum(val id: Long, val name: String, val artistName: String = "", val trackCount: Int = 0)

/** One artist row. */
@Serializable
data class WearArtist(val id: Long, val name: String, val albumCount: Int = 0)

/** One playlist row. */
@Serializable
data class WearPlaylist(val id: Long, val name: String, val trackCount: Int = 0)

/** One bounded page snapshot (phone -> watch). [nextCursor] empty = last page. */
@Serializable
data class WearPageSnapshot(
    val page: WearPageId,
    val generation: Long = 0L,
    val tracks: List<WearTrack> = emptyList(),
    val albums: List<WearAlbum> = emptyList(),
    val artists: List<WearArtist> = emptyList(),
    val playlists: List<WearPlaylist> = emptyList(),
    val nextCursor: String = "",
    val totalCount: Int = 0,
    val truncated: Boolean = false,
)

/** Now-playing state streamed phone -> watch; the phone service is the only player. */
@Serializable
data class WearPlaybackState(
    val trackId: Long? = null,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffle: Boolean = false,
    /** Mirrors media3 Player repeat constants: 0=off, 1=one, 2=all. */
    val repeatMode: Int = 0,
    val queue: List<WearTrack> = emptyList(),
    val queueIndex: Int = -1,
    val sourceName: String = "",
    val lyricsSnippet: String = "",
)

/** What the watch last applied, published at [WearMusicProtocol.WATCH_STATE_PATH]. */
@Serializable
data class WearWatchState(
    val appliedGeneration: Long = 0L,
    val cachedPages: Int = 0,
    val pendingOps: Int = 0,
)

/** Kinds of watch-originated ops; all execute on the phone only. */
@Serializable
enum class WearOpKind {
    PLAY_TRACKS, PLAY_SHUFFLED, TOGGLE_PLAY_PAUSE, SEEK, SKIP_NEXT, SKIP_PREVIOUS,
    TOGGLE_SHUFFLE, CYCLE_REPEAT, PLAY_ALBUM, PLAY_ARTIST, PLAY_PLAYLIST,
    ADD_TO_QUEUE, ADD_TO_PLAYLIST, CREATE_PLAYLIST,
}

/**
 * One watch-originated op ([WearMusicProtocol.OP_PATH]). The phone executes
 * it against its playback service / library; the watch never claims success
 * until the next [WearPlaybackState]/page reflects it.
 */
@Serializable
data class WearMusicOp(
    val kind: WearOpKind,
    val trackIds: List<Long> = emptyList(),
    val startIndex: Int = 0,
    val entityId: Long = 0L,
    val positionMs: Long = 0L,
    val text: String = "",
    val sourceName: String = "",
)

/** Pure playback state machine: applies an op's *result* (as reported by the phone) to prior state. */
fun reducePlayback(state: WearPlaybackState, op: WearMusicOp, reported: WearPlaybackState): WearPlaybackState = reported

/** Next repeat mode in the phone's cycle: off -> all -> one -> off. */
fun nextWearRepeatMode(mode: Int): Int = when (mode) { 0 -> 2; 2 -> 1; else -> 0 }

/**
 * Builds one bounded page from a full ordered list using an offset cursor.
 * Cursor is the decimal offset of the next item; malformed/overflow cursors
 * restart at the first page rather than crashing the watch.
 */
fun <T> paginateWearPage(items: List<T>, cursor: String, pageSize: Int = WearMusicProtocol.PAGE_SIZE): Pair<List<T>, String> {
    val offset = cursor.toIntOrNull()?.coerceIn(0, items.size) ?: 0
    val slice = items.drop(offset).take(pageSize)
    val next = if (offset + slice.size < items.size) (offset + slice.size).toString() else ""
    return slice to next
}

/** Caps one track's fields to watch size. */
fun capWearTrack(track: WearTrack): WearTrack = track.copy(
    title = track.title.take(WearMusicProtocol.MAX_TITLE_CHARS),
    artist = track.artist.take(WearMusicProtocol.MAX_NAME_CHARS),
    album = track.album.take(WearMusicProtocol.MAX_NAME_CHARS),
)
