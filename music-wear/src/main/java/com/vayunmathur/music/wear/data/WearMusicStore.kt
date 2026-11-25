package com.vayunmathur.music.wear.data

import android.content.Context
import com.vayunmathur.library.log.Log
import com.vayunmathur.music.wear.domain.WearMusicOp
import com.vayunmathur.music.wear.domain.WearMusicProtocol
import com.vayunmathur.music.wear.domain.WearPageId
import com.vayunmathur.music.wear.domain.WearPageSnapshot
import com.vayunmathur.music.wear.domain.WearPlaybackState
import com.vayunmathur.music.wear.domain.WearWatchState
import com.vayunmathur.music.wear.sync.WearLinkBridge
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString

/**
 * The watch's local truth: visited page snapshots + latest playback state,
 * persisted as JSON in app-private storage so the UI renders offline
 * (phone absent, process cold-started) from pages already visited. The
 * whole catalog is never stored — only pages the user opened.
 */
class WearMusicStore private constructor(private val context: Context) {

    companion object {
        private const val TAG = "WearMusicStore"

        @Volatile
        private var instance: WearMusicStore? = null

        /** The process-wide store (bridge and UI must agree on one). */
        fun get(context: Context): WearMusicStore = instance ?: synchronized(this) {
            instance ?: WearMusicStore(context.applicationContext).also { instance = it }
        }
    }

    private val pagesFile = File(context.filesDir, "wear-music-pages.json")
    private val playbackFile = File(context.filesDir, "wear-music-playback.json")
    private val opsFile = File(context.filesDir, "wear-music-pending-ops.json")

    private val _pages = MutableStateFlow(loadPages())

    /** Visited pages keyed by [WearPageId]; first-page snapshots replace, later pages append-merge. */
    val pages: StateFlow<Map<WearPageId, WearPageSnapshot>> = _pages.asStateFlow()

    private val _playback = MutableStateFlow(loadPlayback())

    /** Latest phone-reported playback state; the watch renders, never owns, playback. */
    val playback: StateFlow<WearPlaybackState> = _playback.asStateFlow()

    private val _pendingOps = MutableStateFlow(loadOps())

    /** Ops sent (or queued offline); cleared only when phone state reflects them. */
    val pendingOps: StateFlow<List<WearMusicOp>> = _pendingOps.asStateFlow()

    /** Merge one page snapshot: a fresh first page replaces its page; a cursor page extends it. */
    fun setPage(snapshot: WearPageSnapshot) {
        val current = _pages.value.toMutableMap()
        val existing = current[snapshot.page]
        current[snapshot.page] = if (existing == null || snapshot.page == existing.page && snapshot.nextCursor.isNotEmpty() && existing.nextCursor.isEmpty()) {
            snapshot
        } else if (existing != null && snapshot.generation >= existing.generation) {
            existing.copy(
                tracks = existing.tracks + snapshot.tracks,
                albums = existing.albums + snapshot.albums,
                artists = existing.artists + snapshot.artists,
                playlists = existing.playlists + snapshot.playlists,
                nextCursor = snapshot.nextCursor,
                generation = snapshot.generation,
            )
        } else {
            snapshot
        }
        _pages.value = current
        persist(pagesFile, WearMusicProtocol.json.encodeToString(current.values.toList()))
        publishWatchState()
    }

    /** Replace playback state (phone data item) and drop ops it reflects (any state newer than queue time). */
    fun setPlayback(state: WearPlaybackState) {
        _playback.value = state
        persist(playbackFile, WearMusicProtocol.json.encodeToString(state))
    }

    /** Queue an op for the phone; persisted so an offline tap is not lost, resent on reconnect. */
    fun enqueueOp(op: WearMusicOp) {
        _pendingOps.value = _pendingOps.value + op
        persist(opsFile, WearMusicProtocol.json.encodeToString(_pendingOps.value))
        WearLinkBridge.sendOp(context, op)
        publishWatchState()
    }

    /** Publish what this watch holds, so the phone can show last-sync truth. */
    fun publishWatchState() {
        WearLinkBridge.publishWatchState(
            context,
            WearWatchState(
                appliedGeneration = _pages.value.values.maxOfOrNull { it.generation } ?: 0L,
                cachedPages = _pages.value.size,
                pendingOps = _pendingOps.value.size,
            ),
        )
    }

    private fun persist(file: File, text: String) {
        runCatching { file.writeText(text) }.onFailure { Log.status(TAG, "persist failed: ${it.message}") }
    }

    private fun loadPages(): Map<WearPageId, WearPageSnapshot> = runCatching {
        WearMusicProtocol.json.decodeFromString<List<WearPageSnapshot>>(pagesFile.readText())
            .associateBy { it.page }
    }.getOrDefault(emptyMap())

    private fun loadPlayback(): WearPlaybackState = runCatching {
        WearMusicProtocol.json.decodeFromString<WearPlaybackState>(playbackFile.readText())
    }.getOrDefault(WearPlaybackState())

    private fun loadOps(): List<WearMusicOp> = runCatching {
        WearMusicProtocol.json.decodeFromString<List<WearMusicOp>>(opsFile.readText())
    }.getOrDefault(emptyList())
}
