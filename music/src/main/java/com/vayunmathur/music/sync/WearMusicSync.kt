package com.vayunmathur.music.sync

import android.content.Context
import com.vayunmathur.library.wearlink.WearLinkListener
import com.vayunmathur.library.wearlink.WearLinkNode
import com.vayunmathur.music.data.MusicRepository
import com.vayunmathur.music.domain.WearAlbum
import com.vayunmathur.music.domain.WearArtist
import com.vayunmathur.music.domain.WearMusicOp
import com.vayunmathur.music.domain.WearMusicProtocol
import com.vayunmathur.music.domain.WearOpKind
import com.vayunmathur.music.domain.WearPageId
import com.vayunmathur.music.domain.WearPageKey
import com.vayunmathur.music.domain.WearPageRequest
import com.vayunmathur.music.domain.WearPageSnapshot
import com.vayunmathur.music.domain.WearPlaylist
import com.vayunmathur.music.domain.WearPlaybackState
import com.vayunmathur.music.domain.WearSearchRequest
import com.vayunmathur.music.domain.WearTrack
import com.vayunmathur.music.domain.capWearTrack
import com.vayunmathur.music.domain.paginateWearPage
import com.vayunmathur.music.platform.PlaybackManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString

/**
 * Phone side of the Music WearLink companion: serves bounded page snapshots
 * from the phone library, streams now-playing state, and executes watch ops
 * in the phone playback service. The catalog and all audio stay here.
 */
object WearMusicSync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var node: WearLinkNode? = null
    private var generation = 0L

    /** Start phone-side WearLink (call from the app's main activity once). */
    fun start(context: Context) {
        val appContext = context.applicationContext
        if (node != null) return
        synchronized(this) {
            if (node != null) return
            val listener = object : WearLinkListener {
                override fun onMessage(path: String, body: ByteArray) {
                    val text = body.decodeToString()
                    when (path) {
                        WearMusicProtocol.PAGE_REQUEST_PATH -> runCatching {
                            WearMusicProtocol.json.decodeFromString<WearPageRequest>(text)
                        }.onSuccess { scope.launch { publishPage(appContext, it) } }
                        WearMusicProtocol.SEARCH_REQUEST_PATH -> runCatching {
                            WearMusicProtocol.json.decodeFromString<WearSearchRequest>(text)
                        }.onSuccess { req ->
                            scope.launch {
                                publishPage(
                                    appContext,
                                    WearPageRequest(WearPageId(WearPageKey.SEARCH, query = req.query), req.cursor),
                                )
                            }
                        }
                        WearMusicProtocol.OP_PATH -> runCatching {
                            WearMusicProtocol.json.decodeFromString<WearMusicOp>(text)
                        }.onSuccess { op -> scope.launch { executeOp(appContext, op) } }
                    }
                }
            }
            node = WearLinkNode(appContext, WearMusicProtocol.CAPABILITY, listener).also { it.start() }
            observePlayback(appContext)
        }
    }

    private fun observePlayback(context: Context) {
        val playback = PlaybackManager.getInstance(context)
        scope.launch {
            combine(
                playback.isPlaying, playback.currentMediaItem, playback.shuffleMode, playback.repeatMode,
            ) { playing, item, shuffle, repeat ->
                WearPlaybackState(
                    title = item?.mediaMetadata?.title?.toString().orEmpty(),
                    artist = item?.mediaMetadata?.artist?.toString().orEmpty(),
                    album = item?.mediaMetadata?.albumTitle?.toString().orEmpty(),
                    isPlaying = playing,
                    positionMs = playback.currentPosition.value,
                    durationMs = playback.duration.value,
                    shuffle = shuffle,
                    repeatMode = repeat,
                )
            }.collect { state ->
                node?.putData(
                    WearMusicProtocol.PLAYBACK_PATH,
                    WearMusicProtocol.json.encodeToString(state).encodeToByteArray(),
                )
            }
        }
    }

    private suspend fun publishPage(context: Context, request: WearPageRequest) {
        val snapshot = buildPage(context, request)
        node?.putData(
            WearMusicProtocol.PAGE_PATH,
            WearMusicProtocol.json.encodeToString(snapshot).encodeToByteArray(),
        )
    }

    /** Builds one bounded page from the phone library; ordering matches the phone tabs. */
    suspend fun buildPage(context: Context, request: WearPageRequest): WearPageSnapshot {
        val repo = MusicRepository.get(context)
        val allTracks = repo.getAllMusic()
        val gen = ++generation
        fun tracksPage(tracks: List<com.vayunmathur.music.data.Music>): WearPageSnapshot {
            val (slice, next) = paginateWearPage(tracks.map { it.toWear() }, request.cursor)
            return WearPageSnapshot(
                page = request.page, generation = gen, tracks = slice.map(::capWearTrack),
                nextCursor = next, totalCount = tracks.size,
            )
        }
        return when (request.page.key) {
            WearPageKey.HOME, WearPageKey.TRACKS -> tracksPage(allTracks)
            WearPageKey.ALBUMS -> {
                val (slice, next) = paginateWearPage(
                    repo.getAllAlbums().map { WearAlbum(it.id, it.name) }, request.cursor,
                )
                WearPageSnapshot(request.page, gen, albums = slice, nextCursor = next, totalCount = repo.getAllAlbums().size)
            }
            WearPageKey.ARTISTS -> {
                val (slice, next) = paginateWearPage(
                    repo.getAllArtists().map { WearArtist(it.id, it.name) }, request.cursor,
                )
                WearPageSnapshot(request.page, gen, artists = slice, nextCursor = next, totalCount = repo.getAllArtists().size)
            }
            WearPageKey.PLAYLISTS -> {
                val (slice, next) = paginateWearPage(
                    repo.getAllPlaylists().map { WearPlaylist(it.id, it.name) }, request.cursor,
                )
                WearPageSnapshot(request.page, gen, playlists = slice, nextCursor = next, totalCount = repo.getAllPlaylists().size)
            }
            WearPageKey.ALBUM_DETAIL -> tracksPage(allTracks.filter { it.albumId == request.page.entityId })
            WearPageKey.ARTIST_DETAIL -> tracksPage(allTracks.filter { it.artistId == request.page.entityId })
            WearPageKey.PLAYLIST_DETAIL -> {
                val ids = repo.getFromRight(request.page.entityId, com.vayunmathur.music.data.TYPE_MUSIC_PLAYLIST)
                val byId = allTracks.associateBy { it.id }
                tracksPage(ids.mapNotNull { byId[it] })
            }
            WearPageKey.SEARCH -> {
                val q = request.page.query.lowercase()
                tracksPage(
                    allTracks.filter {
                        q.isEmpty() || it.title.lowercase().contains(q) ||
                            it.artist.lowercase().contains(q) || it.album.lowercase().contains(q)
                    },
                )
            }
            WearPageKey.QUEUE, WearPageKey.NOW_PLAYING, WearPageKey.SETTINGS ->
                WearPageSnapshot(request.page, gen, totalCount = 0)
        }
    }

    /** Executes one watch op in the phone playback service / library. */
    suspend fun executeOp(context: Context, op: WearMusicOp) {
        val playback = PlaybackManager.getInstance(context)
        val repo = MusicRepository.get(context)
        val allTracks = repo.getAllMusic()
        val byId = allTracks.associateBy { it.id }
        when (op.kind) {
            WearOpKind.PLAY_TRACKS -> playback.playSong(
                op.trackIds.mapNotNull { byId[it] }, op.startIndex.coerceAtLeast(0),
            )
            WearOpKind.PLAY_SHUFFLED -> playback.playShuffled(op.trackIds.mapNotNull { byId[it] })
            WearOpKind.TOGGLE_PLAY_PAUSE -> playback.togglePlayPause()
            WearOpKind.SEEK -> playback.seekTo(op.positionMs)
            WearOpKind.SKIP_NEXT -> playback.skipNext()
            WearOpKind.SKIP_PREVIOUS -> playback.skipPrevious()
            WearOpKind.TOGGLE_SHUFFLE -> playback.toggleShuffle()
            WearOpKind.CYCLE_REPEAT -> playback.toggleRepeat()
            WearOpKind.PLAY_ALBUM -> {
                val tracks = allTracks.filter { it.albumId == op.entityId }
                if (tracks.isNotEmpty()) playback.playSong(tracks, 0)
            }
            WearOpKind.PLAY_ARTIST -> {
                val tracks = allTracks.filter { it.artistId == op.entityId }
                if (tracks.isNotEmpty()) playback.playSong(tracks, 0)
            }
            WearOpKind.PLAY_PLAYLIST -> {
                val ids = repo.getFromRight(op.entityId, com.vayunmathur.music.data.TYPE_MUSIC_PLAYLIST)
                val tracks = ids.mapNotNull { byId[it] }
                if (tracks.isNotEmpty()) playback.playSong(tracks, 0)
            }
            WearOpKind.ADD_TO_QUEUE -> Unit // queue append runs through playSong on phone; no silent watch-side claim
            WearOpKind.ADD_TO_PLAYLIST -> op.trackIds.forEach {
                repo.upsertMatching(com.vayunmathur.library.util.ManyManyMatching(it, op.entityId, com.vayunmathur.music.data.TYPE_MUSIC_PLAYLIST))
            }
            WearOpKind.CREATE_PLAYLIST -> if (op.text.isNotBlank()) repo.upsertPlaylist(com.vayunmathur.music.data.Playlist(name = op.text))
        }
    }

    private fun com.vayunmathur.music.data.Music.toWear(): WearTrack =
        WearTrack(id = id, title = title, artist = artist, album = album, durationMs = duration)
}
