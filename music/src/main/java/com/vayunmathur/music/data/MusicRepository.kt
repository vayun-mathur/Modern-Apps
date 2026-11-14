package com.vayunmathur.music.data

import android.content.Context
import com.vayunmathur.library.room.RoomRepository
import com.vayunmathur.library.util.ManyManyMatching
import com.vayunmathur.music.platform.albumArtistPairs
import com.vayunmathur.music.platform.getAlbums
import com.vayunmathur.music.platform.getArtists
import com.vayunmathur.music.platform.getAudioYear
import com.vayunmathur.music.platform.getRealAudioDuration
import com.vayunmathur.music.platform.getSongs
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Single source of truth for the music library.
 *
 * Songs, albums and artists come **straight from MediaStore** and live in memory, the way
 * `contacts` reads `ContactsContract`. MediaStore already is the source of truth for them, so
 * mirroring it into a database only bought a cold start with an empty screen: the list could not
 * appear until a worker had finished importing, and after that not until the encrypted database had
 * been opened. Three cursor queries are fast enough to do on every refresh.
 *
 * Room still holds the two things MediaStore cannot give us:
 *  - **playlists** and their song matchings, which are the user's own data. Note that
 *    `MediaStore.Audio.Playlists` is deprecated and deliberately not used.
 *  - a **cache of `duration` and `year`** for files whose MediaStore rows leave them blank.
 *    Recovering those means opening each file with a `MediaMetadataRetriever`, so it is done once,
 *    in the background, and the result is kept. Only those two columns are ever read back; the rest
 *    of the `Music` row is written because the table already has the columns, not because anything
 *    depends on them.
 *
 * This is also the seam for everything else in the app: [com.vayunmathur.music.platform.PlaybackManager],
 * `MusicLibraryTree` (Android Auto) and `CastQueue` subscribe to these flows directly and have no
 * ViewModel, so the in-memory state has to live here rather than in one.
 */
class MusicRepository private constructor(context: Context) :
    RoomRepository<MusicDatabase>(context, MusicDatabase::class) {

    private val musicDao get() = db.musicDao()
    private val playlistDao get() = db.playlistDao()
    private val matchingDao get() = db.matchingDao()

    private val warmScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Open the database now rather than when something first reads from it.
        //
        // Songs, albums and artists never touch Room, so the tabs that show them are up straight
        // away - but playlists live here, and the first read pays a SQLCipher key derivation. Left
        // to itself that bill landed on whoever opened the playlists tab. Starting it here overlaps
        // it with the MediaStore scan the caller is about to run.
        warmScope.launch { runCatching { playlistDao.getAll() } }
    }

    // ------------------------------------------------------------------
    // MediaStore-backed library (in memory)
    // ------------------------------------------------------------------

    private val _music = MutableStateFlow<List<Music>>(emptyList())
    private val _albums = MutableStateFlow<List<Album>>(emptyList())
    private val _artists = MutableStateFlow<List<Artist>>(emptyList())

    /** Album-to-artist pairs, recomputed per refresh rather than per emission. */
    private val _albumArtistMatchings = MutableStateFlow<List<ManyManyMatching>>(emptyList())

    private val _loaded = MutableStateFlow(false)

    /** False until the first MediaStore read completes, so a list can tell empty from not-yet. */
    val loaded: Flow<Boolean> get() = _loaded.onStart { ensureLoaded() }

    /**
     * Every entry point gets a populated library, not just the one that thought to ask.
     *
     * The tabs screen refreshes explicitly once the audio permission is granted, but the assistant
     * intents, Android Auto's browse tree and the cast queue all reach the repository without it -
     * sometimes in a process where no Activity ever ran. When the library was a database they were
     * served by whatever the last import wrote; now the first read has to fetch it.
     */
    val music: Flow<List<Music>> get() = _music.onStart { ensureLoaded() }
    val albums: Flow<List<Album>> get() = _albums.onStart { ensureLoaded() }
    val artists: Flow<List<Artist>> get() = _artists.onStart { ensureLoaded() }

    private val refreshLock = Mutex()

    @Volatile private var loadedOnce = false

    /** [refresh]es exactly once per process, for callers that only need the library to be there. */
    private suspend fun ensureLoaded() {
        if (!loadedOnce) refresh()
    }

    /**
     * Re-reads the library from MediaStore and publishes it. Three cursor queries, no database.
     *
     * Deliberately does not touch Room. The tag cache lives in the encrypted database, and opening
     * that costs a SQLCipher key derivation - reading the cache here would put roughly a second
     * back in front of the first frame, which is the whole thing this design exists to avoid.
     * [backfillTags] applies the cache afterwards, by which point the list is already on screen.
     *
     * Serialised, because the UI kickoff and the content-observer worker can both land at once and
     * there is no value in two identical scans racing to publish the same lists.
     *
     * Silently yields an empty library when the audio permission has not been granted, which is the
     * ordinary state before the user accepts the prompt; the caller refreshes again afterwards.
     */
    suspend fun refresh() = refreshLock.withLock {
        val songs = getSongs(appContext)
        val albums = getAlbums(appContext)
        val artists = getArtists(appContext)

        _albums.value = albums
        _artists.value = artists
        _music.value = songs
        _albumArtistMatchings.value = albumArtistPairs(songs, artists, albums)
            .map { (album, artist) -> ManyManyMatching(album.id, artist.id, TYPE_ALBUM_ARTIST) }

        loadedOnce = true
        _loaded.value = true
    }

    /**
     * Applies the cached tag data, then reads the files for whatever is still missing.
     *
     * Two sources, cheapest first: the cache covers everything seen on a previous run, and only
     * genuinely new files need a `MediaMetadataRetriever`, which has to open and parse each one.
     *
     * Runs after [refresh] has already published the library, so all of it - including the first
     * touch of the encrypted database - happens behind a list that is already on screen. Songs whose
     * tags carry neither field are re-read on each pass, which is the price of not persisting a
     * "we looked and found nothing" marker.
     */
    suspend fun backfillTags() {
        val cached = musicDao.getAll().associateBy { it.id }
        applyTagCache(cached)
        pruneStaleTags(cached)
        backfillFromFiles()
    }

    private fun applyTagCache(cached: Map<Long, Music>) {
        if (cached.isEmpty()) return
        _music.value = _music.value.map { song -> cachedSong(song, cached[song.id]) }
    }

    private fun cachedSong(song: Music, hit: Music?): Music {
        if (hit == null) return song
        if (song.duration != 0L && song.year != 0) return song
        return song.copy(
            duration = if (song.duration == 0L) hit.duration else song.duration,
            year = if (song.year == 0) hit.year else song.year,
        )
    }

    private suspend fun pruneStaleTags(cached: Map<Long, Music>) {
        // Entries for songs that no longer exist would otherwise accumulate forever.
        val live = _music.value.mapTo(mutableSetOf()) { it.id }
        val stale = cached.keys - live
        if (stale.isNotEmpty()) stale.chunked(STALE_DELETE_BATCH).forEach { musicDao.deleteByIds(it) }
    }

    private suspend fun backfillFromFiles() {
        val pending = _music.value.filter { it.duration == 0L || it.year == 0 }
        if (pending.isEmpty()) return
        val resolved = pending.mapNotNull { resolveTags(it) }
        if (resolved.isEmpty()) return
        resolved.chunked(TAG_CACHE_UPSERT_BATCH).forEach { musicDao.upsertAll(it) }

        val byId = resolved.associateBy { it.id }
        _music.value = _music.value.map { byId[it.id] ?: it }
    }

    private fun resolveTags(song: Music): Music? {
        val uri = song.uri.toUri()
        val duration =
            if (song.duration == 0L) getRealAudioDuration(appContext, uri) else song.duration
        val year = if (song.year == 0) getAudioYear(appContext, uri) else song.year
        return song.copy(duration = duration, year = year).takeIf { it != song }
    }

    // ------------------------------------------------------------------
    // Room-backed: playlists and matchings
    // ------------------------------------------------------------------

    val playlists: Flow<List<Playlist>> get() = playlistDao.getAllFlow()

    /**
     * Album-to-artist pairs derived in memory, plus the playlist matchings from Room.
     *
     * Both kinds share one table and one flow historically, and consumers filter by `type`, so they
     * are merged here rather than splitting the read API. Only `TYPE_MUSIC_PLAYLIST` rows are taken
     * from Room - `MIGRATION_4_5` clears the persisted `TYPE_ALBUM_ARTIST` rows, and this filter
     * stops any that survive from double-counting against the derived ones.
     *
     * The Room side is seeded with an empty list because `combine` waits for *every* source before
     * it emits anything. Without the seed the derived album-to-artist pairs - which are pure
     * MediaStore data and ready immediately - were held back until the encrypted database had
     * opened, so the artists tab sat blank behind a key derivation it had no need of.
     */
    val matchings: Flow<List<ManyManyMatching>>
        get() = combine(
            _albumArtistMatchings.onStart { ensureLoaded() },
            matchingDao.flow().onStart { emit(emptyList()) },
        ) { derived, stored ->
            derived + stored.filter { it.type == TYPE_MUSIC_PLAYLIST }
        }

    // Music (tag cache only - the library itself is [music])
    suspend fun getAllMusic(): List<Music> {
        ensureLoaded()
        return _music.value
    }

    // Playlist
    suspend fun getAllPlaylists(): List<Playlist> = playlistDao.getAll()
    suspend fun upsertPlaylist(value: Playlist): Long = playlistDao.upsert(value)
    suspend fun deletePlaylistById(id: Long) = playlistDao.deleteById(id)

    // Album / Artist reads, served from the in-memory library
    suspend fun getAllAlbums(): List<Album> {
        ensureLoaded()
        return _albums.value
    }

    suspend fun getAllArtists(): List<Artist> {
        ensureLoaded()
        return _artists.value
    }

    // Matching
    suspend fun getFromRight(rightId: Long, type: Int): List<Long> =
        matchingDao.getFromRight(rightId, type)
    suspend fun getFromLeft(leftId: Long, type: Int): List<Long> =
        matchingDao.getFromLeft(leftId, type)
    suspend fun upsertMatching(value: ManyManyMatching): Long = matchingDao.upsert(value)
    suspend fun upsertMatchings(values: List<ManyManyMatching>) = matchingDao.upsert(values)
    suspend fun deleteMatch(left: Long, right: Long, type: Int) =
        matchingDao.deleteMatch(left, right, type)
    suspend fun deleteFromRight(rightId: Long, type: Int) =
        matchingDao.deleteFromRight(rightId, type)
    suspend fun deleteFromLeft(leftId: Long, type: Int) =
        matchingDao.deleteFromLeft(leftId, type)
    suspend fun deleteByType(type: Int) = matchingDao.deleteByType(type)
    suspend fun clearMatchings() = matchingDao.clear()

    /** Shared constants and helpers for MusicRepository. */
    companion object {
        private const val STALE_DELETE_BATCH = 900
        private const val TAG_CACHE_UPSERT_BATCH = 200
        @Volatile private var instance: MusicRepository? = null
        fun get(context: Context): MusicRepository =
            instance ?: synchronized(this) {
                instance ?: MusicRepository(context).also { instance = it }
            }
    }
}
