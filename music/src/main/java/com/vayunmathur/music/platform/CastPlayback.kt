package com.vayunmathur.music.platform

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.vayunmathur.library.log.Log
import androidx.core.net.toUri
import com.vayunmathur.library.media.OpusTranscoder
import com.vayunmathur.music.data.Music
import com.vayunmathur.sdk.cast.CastClient
import com.vayunmathur.sdk.cast.CastException
import com.vayunmathur.sdk.cast.CastResource
import com.vayunmathur.sdk.cast.PlaybackCommand
import com.vayunmathur.sdk.cast.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "CastPlayback"

/**
 * Plays this library on a television, by serving it rather than streaming pixels.
 *
 * Casting music was not possible before: the SDK's only session handed back a `Surface` to draw
 * into, and a music player has nothing to draw. A content session has no surface at all - Cast runs
 * an HTTPS proxy, asks this app for a descriptor per track, and the TV decodes the original bytes.
 * So the phone starts no encoder, and the one thing it does encode is Opus, once per file.
 *
 * **The TV is told to play a track before it has finished being encoded, deliberately.** Waiting for
 * the whole transcode first left the television holding an idle control channel for over a minute,
 * and it tore the session down before a note played. Now the resource is offered with an unknown
 * length, `PLAY_MEDIA` goes out at once, and the encoder - which runs several times faster than real
 * time - stays comfortably ahead of the TV's reader.
 *
 * The price is one thing a user can notice: **a track being encoded for the first time cannot be
 * seeked**, because a stream with no known length has nothing to answer a byte range with. It is
 * cached as it goes, so every later play of it is instant and seekable both. [growing] is which
 * resource that currently applies to, and `CastingPlayer` takes the seek commands away while it does.
 *
 * **This object does not decide anything about the queue.** It opens the session, answers for bytes,
 * and carries the television's own playback back through [tv] - `CastQueue` and `CastingPlayer` are
 * what turn that into one player on the phone.
 */
object CastPlayback {

    /** Contract for state. */
    sealed interface State {

        /** Singleton holding idle state and operations. */
        data object Idle : State

        /** The picker has been dismissed and the session is being negotiated. */
        data object Connecting : State

        /** Immutable value describing casting; carries receiverName. */
        data class Casting(val receiverName: String) : State
    }

    /**
     * The television's own playback, and when it was true.
     *
     * **The wall clock is the missing half of the message.** Snapshots land twice a second and a seek
     * bar redraws sixty times a second, so a position plotted where it was reported would visibly step;
     * what makes it smooth is knowing when the number was true. The extrapolation itself is
     * `CastingPlayer`'s, because media3 already has a `PositionSupplier` for exactly this.
     */
    data class TvPlayback(val state: PlaybackState, val receivedAtMs: Long)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _tv = MutableStateFlow<TvPlayback?>(null)

    /**
     * What the television's player is doing, or null before it has said anything.
     *
     * The only account of playback there is while casting: the phone's own player is paused and muted,
     * so every surface that shows a position - the now-playing screen, the notification, the
     * lockscreen, a car - is ultimately reading this.
     */
    val tv: StateFlow<TvPlayback?> = _tv.asStateFlow()

    private val _growing = MutableStateFlow<String?>(null)

    /**
     * The resource currently being encoded as the television reads it, or null.
     *
     * Its own value rather than a flag on [State.Casting], because *which* resource matters: a
     * background transcode of a track the user has already skipped past says nothing about whether the
     * track now playing can be seeked. A flow rather than a field so the scrubber comes back the moment
     * the encoder finishes, without waiting for a track change.
     */
    val growing: StateFlow<String?> = _growing.asStateFlow()

    /**
     * Owns the background transcodes, and is why they are not launched in the caller's scope.
     *
     * The one caller is a composable's `rememberCoroutineScope`, which is cancelled the moment the
     * now-playing screen leaves composition - so a transcode launched there would be abandoned
     * mid-track by a user simply navigating away, leaving the TV reading a file nothing is writing.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var client: CastClient? = null

    /**
     * The running transcode, so a track change can cancel it.
     *
     * Volatile because it is written from the transcode's own completion on `Dispatchers.IO` and read
     * by [play] on the main thread. Which resource it is producing is [growing].
     */
    @Volatile
    private var transcodeJob: Job? = null

    /**
     * The running metadata job, so a track change can cancel it.
     *
     * **Cancelled rather than left to lose a race.** Reading a cover out of a file and re-compressing
     * it takes long enough that a job for track A can finish after track B has started playing, and
     * its snapshot would then describe the wrong audio. The television refuses a snapshot that does
     * not name what it is playing as well - see `NowPlaying.resourceId` - because either measure alone
     * leaves a window: cancellation is not instantaneous, and a gate cannot stop wasted work.
     */
    @Volatile
    private var metadataJob: Job? = null

    /**
     * What the TV has been told about, by resource id.
     *
     * Also the authorisation list: a request for anything this app did not offer is answered with
     * nothing, which the TV sees as a `404`. Cast is trusted, but a bug on either side should not be
     * able to turn the proxy into a reader of the whole music library.
     */
    private val offered = ConcurrentHashMap<String, Prepared>()

    /** A track that is ready to be served, and where its bytes are. */
    private class Prepared(
        val source: Source,
        val contentType: String,
    )

    private sealed interface Source {
        /** Already 48 kHz Opus, so the file itself is served with no processing at all. */
        class Original(val uri: Uri) : Source

        /** Converted once and cached, keyed by track id. */
        class Cached(val file: File) : Source

        /**
         * Being converted right now, into a file the TV is already reading.
         *
         * Served with an unknown length, which is what lets it be offered before it exists. Becomes
         * a [Cached] the moment the transcode finishes, so the next play of it is seekable.
         */
        class Growing(val file: File) : Source
    }

    fun support(context: Context): CastClient.Support = CastClient(context).support()

    val isCasting: Boolean get() = _state.value is State.Casting

    /**
     * Open an audio-only session on the TV the user picked.
     *
     * Requires a TV already connected, which the picker Activity is for. Returns false with the state
     * back at [State.Idle] if Cast refused - most usefully when the television has no Opus decoder,
     * which it now says rather than playing silence.
     */
    suspend fun open(context: Context): Boolean {
        close()
        _state.value = State.Connecting
        val newClient = CastClient(context)
        return try {
            val session = newClient.openContentSession(
                resources = { resourceId -> openResource(context, resourceId) },
                // No picture. This is the case the Surface contract could not express at all.
                video = false,
            )
            newClient.onEnded = {
                Log.status(TAG, "the TV ended the session")
                clear()
            }
            // The television holds the player, so this is where every position, every play/pause and
            // every end-of-track on the phone ultimately comes from.
            newClient.onPlaybackState = { state ->
                _tv.value = TvPlayback(state, System.currentTimeMillis())
            }
            client = newClient
            _state.value = State.Casting(session.receiverName)
            Log.status(TAG, "casting to '${session.receiverName}'")
            true
        } catch (e: CastException) {
            Log.status(TAG, "could not start casting", e)
            newClient.close()
            _state.value = State.Idle
            false
        }
    }

    /**
     * Put [song] on the TV, encoding it in the background if it is not already Opus.
     *
     * Returns as soon as the TV has been told to play, which for a track needing conversion is
     * before any of it has been encoded. [growing] is what says the encoder is still running behind
     * the playback.
     *
     * [startPositionMs] is what makes starting a cast keep its place rather than restart the track. A
     * resource still being written cannot honour it - there is nothing to seek against - so a track
     * cast for the first time begins at the beginning.
     */
    suspend fun play(context: Context, song: Music, startPositionMs: Long = 0) {
        val active = client ?: return
        val id = song.id.toString()
        // A transcode for a track the TV has moved on from is a minute of CPU nobody is waiting
        // for. Skipped when the id matches, so replaying the current track does not kill its own.
        cancelTranscodeExcept(id)

        // Above the three branches below, because the first of them returns early: a track replayed
        // within one session takes the `offered` fast path, and metadata launched inside it would
        // never run at all.
        startMetadata(context, active, song, id)

        val known = offered[id]
        if (known != null) {
            active.play(id, known.contentType, song.duration, startPositionMs)
            return
        }

        val uri = song.uri.toUri()
        val ready = withContext(Dispatchers.IO) { alreadyPlayable(context, uri, song) }
        if (ready != null) {
            offered[id] = ready
            active.play(id, ready.contentType, song.duration, startPositionMs)
            return
        }

        // Everything else is encoded as the TV plays it. The empty cache file has to exist and the
        // resource has to be registered *before* PLAY_MEDIA: the TV asks for bytes before any have
        // been written, and a resource this app has not offered - or a file that is not there yet -
        // is answered with a 404 rather than a wait.
        val cached = cacheFile(context, song.id)
        val source = withContext(Dispatchers.IO) { readSource(context, uri, cached) }
        if (source == null) {
            Log.status(TAG, "'${song.title}' could not be read for casting")
            return
        }
        offered[id] = Prepared(Source.Growing(cached), CONTENT_TYPE)
        // No start position: a growing stream has no length, so the TV would begin at the start
        // whatever it was told, and asking for a position it cannot honour reads as a bug.
        active.play(id, CONTENT_TYPE, song.duration)
        startTranscode(active, song, id, cached, source)
    }

    /**
     * Ask the television to play, pause, seek or change speed.
     *
     * The one way a press on the phone reaches the sound, wherever it was pressed. Silent with no
     * session, like everything else here: `CastingPlayer` cannot know precisely when a session ended.
     */
    fun send(command: PlaybackCommand) {
        client?.sendCommand(command)
    }

    /**
     * Encodes into the file the TV is already reading, and says so when it stops.
     *
     * The completion is not optional on either path. A reader that has caught up with the encoder is
     * parked waiting for more bytes, and the only things that release it are a real length or a
     * failure - so a transcode that ended without reporting either would hold an HTTP connection
     * open until the proxy's own bound expired.
     */
    private fun startTranscode(
        active: CastClient,
        song: Music,
        id: String,
        cached: File,
        source: ByteArray,
    ) {
        _growing.value = id
        transcodeJob = scope.launch {
            val length = runCatching {
                FileOutputStream(cached).use { sink ->
                    OpusTranscoder.transcodeTo(source, sink, isStopped = { !isActive })
                }
            }.getOrNull()

            // NonCancellable because this is what a cancelled transcode owes its reader: the
            // failure has to travel even though the work was abandoned on purpose.
            withContext(NonCancellable) {
                if (length != null) {
                    // Now a real file with a real length, so the next play of it can be seeked.
                    offered[id] = Prepared(Source.Cached(cached), CONTENT_TYPE)
                    Log.status(TAG, "encoded '${song.title}' to $length bytes of Opus")
                } else {
                    // A part-written file has no end-of-stream page, so it must not be mistaken for
                    // a cached track on the next cast.
                    offered.remove(id)
                    runCatching { cached.delete() }
                    Log.status(TAG, "could not encode '${song.title}' for casting")
                }
                active.resourceComplete(id, length ?: PRODUCER_FAILED)
                // Compared rather than cleared outright: a later track's transcode may already have
                // claimed this, and clearing it would say the newer one had finished.
                _growing.compareAndSet(id, null)
            }
        }
    }

    private fun cancelTranscodeExcept(id: String) {
        val current = _growing.value
        if (current == null || current == id) return
        transcodeJob?.cancel()
    }

    // ------------------------------------------------------------------
    // What the television shows while it plays
    // ------------------------------------------------------------------

    /**
     * Tell the TV what this track is, and then what it looks like.
     *
     * **Two sends, because the two halves are ready at different times.** The text is already in the
     * `Music` row the caller handed over, so it goes out at once and the television has a real
     * now-playing screen from the first moment. The cover has to be read out of the file and
     * re-compressed, and the lyrics out of its tag region, so those follow in a second snapshot -
     * which is exactly why this is not fields on `PLAY_MEDIA`, where changing them would mean
     * restarting the track.
     *
     * The artwork resource is registered in [offered] and its bytes are on disk **before** its id is
     * named, and that ordering is not stylistic: the TV fetches the id the moment it arrives, and an
     * unoffered one is a `404` it does not retry.
     */
    private fun startMetadata(context: Context, active: CastClient, song: Music, id: String) {
        metadataJob?.cancel()
        active.setNowPlaying(
            resourceId = id,
            title = song.title,
            author = song.artist,
            album = song.album,
        )
        metadataJob = scope.launch {
            val lyrics = classifyLyrics(EmbeddedLyrics.read(context, song.uri.toUri()))
            val timed = (lyrics as? Lyrics.Timed)?.lines.orEmpty()
            val artworkId = prepareArtwork(context, song)
            active.setNowPlaying(
                resourceId = id,
                title = song.title,
                author = song.artist,
                album = song.album,
                artworkResourceId = artworkId.orEmpty(),
                lyricTimesMs = LongArray(timed.size) { timed[it].timestamp },
                lyricTexts = Array(timed.size) { timed[it].text },
                plainLyrics = (lyrics as? Lyrics.Plain)?.text.orEmpty(),
            )
        }
    }

    /**
     * The resource id the cover can be fetched from, or null when the track has none.
     *
     * Cached on disk beside the converted audio, so a track cast twice - in this session or a later
     * one - is read, scaled and re-compressed only once. An ordinary [Source.Cached]: a real file with
     * a real length, so it needs no `resourceComplete` handshake and no new kind of source.
     *
     * **The file's extension carries the content type**, which is what makes the disk hit usable at
     * all: `offered` is emptied when a session ends, so a later cast finds only the file - and the
     * proxy has to state a type for it. Sniffing the bytes back would cost about as much as producing
     * them again.
     */
    private suspend fun prepareArtwork(context: Context, song: Music): String? =
        withContext(Dispatchers.IO) {
            val id = artworkResourceId(song.id)
            if (offered[id]?.let { hasBytes(it) } == true) return@withContext id

            // A file left by an earlier session, which `offered` knows nothing about.
            for ((extension, contentType) in ARTWORK_TYPES) {
                val existing = artworkFile(context, song.id, extension)
                if (existing.length() > 0) {
                    offered[id] = Prepared(Source.Cached(existing), contentType)
                    return@withContext id
                }
            }

            val artwork = runCatching {
                artworkBytes(context, song.uri.toUri(), albumArtUri(song.albumId))
            }.getOrNull() ?: return@withContext null
            val extension = ARTWORK_TYPES.entries.firstOrNull { it.value == artwork.contentType }?.key
                ?: return@withContext null
            val file = artworkFile(context, song.id, extension)
            val written = runCatching {
                file.parentFile?.mkdirs()
                file.writeBytes(artwork.bytes)
                true
            }.getOrDefault(false)
            if (!written) return@withContext null
            offered[id] = Prepared(Source.Cached(file), artwork.contentType)
            id
        }

    /** Whether an offered resource still has a file with something in it behind it. */
    private fun hasBytes(prepared: Prepared): Boolean =
        (prepared.source as? Source.Cached)?.file?.length()?.let { it > 0 } == true

    /**
     * The album's `MediaStore` entry, which is where the fallback thumbnail comes from.
     *
     * Built rather than looked up: this is the same URI `getAlbums` composes for `Album.uri`, so
     * reaching into the repository for it would be a database read to reconstruct an id we already
     * have.
     */
    private fun albumArtUri(albumId: Long): Uri? = runCatching {
        ContentUris.withAppendedId(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, albumId)
    }.getOrNull()

    /**
     * Prefixed, so an artwork id can never collide with a track id.
     *
     * Both live in one [offered] map and both are addressed on one proxy, and a track whose numeric id
     * happened to match another's would otherwise serve a JPEG as audio.
     */
    private fun artworkResourceId(songId: Long): String = "$ARTWORK_PREFIX$songId"

    private fun artworkFile(context: Context, songId: Long, extension: String): File =
        File(File(context.cacheDir, ARTWORK_CACHE_DIR), "$songId.$extension")

    fun close() {
        client?.close()
        clear()
    }

    private fun clear() {
        // Cancelled before the state is published: the transcode's own completion clears
        // `growing`, and letting it run on would leave it writing into a session that has gone.
        transcodeJob?.cancel()
        transcodeJob = null
        metadataJob?.cancel()
        metadataJob = null
        _growing.value = null
        client = null
        offered.clear()
        _tv.value = null
        _state.value = State.Idle
    }

    // ------------------------------------------------------------------
    // Getting a track into a shape the TV can play
    // ------------------------------------------------------------------

    /**
     * Whichever of the two no-work cases applies, or null when the track has to be encoded.
     *
     * An Opus file is served as it is, and a track converted on an earlier cast is served from the
     * cache - both with a real length, so both are seekable from the first moment.
     */
    private fun alreadyPlayable(context: Context, uri: Uri, song: Music): Prepared? {
        if (isOpus(context, uri)) {
            Log.status(TAG, "'${song.title}' is already Opus; serving it unchanged")
            return Prepared(Source.Original(uri), CONTENT_TYPE)
        }

        val cached = cacheFile(context, song.id)
        if (cached.length() > 0) {
            Log.status(TAG, "'${song.title}' was converted on an earlier cast; reusing it")
            return Prepared(Source.Cached(cached), CONTENT_TYPE)
        }
        return null
    }

    /**
     * Reads the track's bytes and creates the empty file its Opus will be written into.
     *
     * The file is created here rather than by the transcode, because it has to exist before the TV
     * is told to play: the first fetch arrives within moments, and opening a descriptor to a file
     * that is not there yet is a `404` the player treats as a failed load.
     */
    private fun readSource(context: Context, uri: Uri, cached: File): ByteArray? = runCatching {
        val source = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return null
        cached.parentFile?.mkdirs()
        // Truncating, so a file left behind by a transcode that died is not read as a head start.
        FileOutputStream(cached).close()
        source
    }.getOrNull()

    /**
     * Answers Cast's request for a track's bytes.
     *
     * Called on Cast's own background thread, and quick in every branch: even a track still being
     * encoded is answered immediately, with a descriptor to the file being written and a negative
     * length that tells Cast to wait at the end of it rather than report one. The descriptor is
     * always a real file, so a completed resource is seekable.
     */
    private fun openResource(context: Context, resourceId: String): CastResource? {
        val prepared = offered[resourceId] ?: run {
            Log.status(TAG, "asked for '$resourceId', which was never offered")
            return null
        }
        return runCatching {
            when (val source = prepared.source) {
                is Source.Original -> {
                    val descriptor = context.contentResolver.openFileDescriptor(source.uri, "r")
                        ?: return null
                    CastResource(descriptor, descriptor.statSize, prepared.contentType)
                }
                is Source.Cached -> {
                    // Checked rather than assumed: these files outlive the session that wrote them,
                    // and one the system has reclaimed under storage pressure should be a clean 404
                    // rather than an exception on Cast's own thread.
                    if (!source.file.isFile) {
                        Log.status(TAG, "'$resourceId' was cached and is no longer on disk")
                        offered.remove(resourceId)
                        return null
                    }
                    CastResource(
                        ParcelFileDescriptor.open(source.file, ParcelFileDescriptor.MODE_READ_ONLY),
                        source.file.length(),
                        prepared.contentType,
                    )
                }
                is Source.Growing -> CastResource(
                    ParcelFileDescriptor.open(source.file, ParcelFileDescriptor.MODE_READ_ONLY),
                    // Not `file.length()`: the length now is a snapshot of a file still growing,
                    // and stating it would cut the track off wherever the encoder happened to be.
                    UNKNOWN_LENGTH,
                    prepared.contentType,
                )
            }
        }.onFailure { Log.status(TAG, "could not open '$resourceId'", it) }.getOrNull()
    }

    /**
     * Whether the file is already Ogg/Opus, from its first bytes rather than its name.
     *
     * `MediaStore` has no column for this and `getType` reports `audio/ogg` for Vorbis too, so the
     * container is read: an Ogg page header, then `OpusHead` inside the first page. Getting this
     * wrong in the permissive direction would serve a Vorbis file as Opus and the TV would refuse
     * it, so the check errs towards converting.
     */
    private fun isOpus(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val head = ByteArray(SNIFF_BYTES)
            val read = input.read(head)
            if (read < MIN_OPUS_HEADER_BYTES) return@use false
            val magic = String(head, 0, 4, Charsets.ISO_8859_1)
            magic == "OggS" && String(head, 0, read, Charsets.ISO_8859_1).contains("OpusHead")
        } == true
    }.getOrDefault(false)

    private fun cacheFile(context: Context, songId: Long): File =
        File(File(context.cacheDir, CACHE_DIR), "$songId.opus")

    /**
     * Clears converted tracks and cached cover art.
     *
     * The cache is what makes a second cast of the same track free, so it is kept across sessions -
     * but it is a cache, and 256 kbps Opus of a whole library would not be small.
     *
     * **Both directories, or the one it forgot leaks for ever**: nothing else ever deletes them, and
     * a user who clears the cache means all of it.
     */
    fun clearCache(context: Context) {
        runCatching { File(context.cacheDir, CACHE_DIR).deleteRecursively() }
        runCatching { File(context.cacheDir, ARTWORK_CACHE_DIR).deleteRecursively() }
    }

    /**
     * Enough to cover an Ogg page header and the `OpusHead` packet that follows it.
     */
    private const val SNIFF_BYTES = 64

    /** Minimum bytes to check for the Ogg magic plus OpusHead marker. */
    private const val MIN_OPUS_HEADER_BYTES = 36

    private const val CACHE_DIR = "cast-opus"

    /** Its own directory rather than a suffix in [CACHE_DIR], so either can be cleared alone. */
    private const val ARTWORK_CACHE_DIR = "cast-art"

    private const val ARTWORK_PREFIX = "art-"

    /**
     * The image types a cover is stored and served as, by the extension it is stored under.
     *
     * The extension is how the type survives a session ending, and the map is also what decides
     * whether a picture can be passed through untouched: a type not named here is re-compressed to
     * JPEG by `artworkBytes`, so there is never a file on disk this cannot state a type for.
     */
    private val ARTWORK_TYPES = mapOf(
        "jpg" to "image/jpeg",
        "png" to "image/png",
        "webp" to "image/webp",
    )

    /**
     * The length reported for a track still being encoded: negative means "still being written,
     * final size unknown", which is what makes Cast wait at the end of the file instead of
     * reporting it.
     */
    private const val UNKNOWN_LENGTH = -1L

    /** Sent as the completed length when the transcode failed, which releases readers with an error. */
    private const val PRODUCER_FAILED = -1L

    /**
     * `audio/ogg` rather than `audio/opus`: the container is Ogg, and that is what decides which
     * extractor the TV's player reaches for.
     */
    private const val CONTENT_TYPE = "audio/ogg"
}
