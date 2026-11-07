package com.vayunmathur.music.platform
import com.vayunmathur.music.R
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import com.vayunmathur.library.log.Log
import android.util.Size
import androidx.collection.LruCache
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import com.vayunmathur.library.ui.IconAlbum
import com.vayunmathur.library.ui.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.image.ImageRequest
import com.vayunmathur.library.image.Size as CoilSize
import com.vayunmathur.music.data.Album
import com.vayunmathur.music.data.Artist
import com.vayunmathur.music.data.Music
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale
import androidx.compose.ui.res.stringResource

// Process-scope LRU for album thumbnails. Bounded so we don't retain decoded
// bitmaps for the entire library; 64 entries covers a typical visible list.
private val albumArtCache = LruCache<Uri, Bitmap>(THUMBNAIL_CACHE_SIZE)

private const val THUMBNAIL_CACHE_SIZE = 64
private const val THUMBNAIL_EDGE = 300
private const val PLACEHOLDER_FILL_FRACTION = 0.55f
private const val COLLAGE_TILE_COUNT = 4
private const val MILLIS_PER_SECOND = 1000L
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3600L
private const val DISC_TRACK_FACTOR = 1000
private const val MIN_IMAGE_HEADER_BYTES = 12
private const val RIFF_NAME_OFFSET = 0
private const val RIFF_NAME_LENGTH = 4
private const val RIFF_TYPE_OFFSET = 8
/** MediaStore's literal for a missing artist tag; normalised to "" in [getArtists]. */
private const val UNKNOWN_ARTIST_SENTINEL = "<unknown>"

fun getThumbnail(context: Context, uri: Uri): Bitmap? {
    return try {
        context.contentResolver.loadThumbnail(
            uri,
            Size(THUMBNAIL_EDGE, THUMBNAIL_EDGE),
            null
        )
    } catch (_: IllegalArgumentException) {
        null // Fallback to a placeholder
    } catch (_: SecurityException) {
        null // Fallback to a placeholder
    } catch (_: UnsupportedOperationException) {
        null // Fallback to a placeholder
    } catch (_: IOException) {
        null // No album art found (FileNotFoundException) — fall back to a placeholder
    }
}

fun albumArtistPairs(music: List<Music>, artists: List<Artist>, albums: List<Album>): List<Pair<Album, Artist>> {
    val albumMap = albums.associateBy { it.id }
    val artistMap = artists.associateBy { it.id }
    var unmatched = 0
    val pairs = music.mapNotNull { song ->
        val album = albumMap[song.albumId]
        val artist = artistMap[song.artistId]
        // Counted, not logged per song. A library whose Albums table is out of step with its Media
        // table produces one of these per track, and a few thousand Log.w calls on the refresh path
        // cost considerably more than the pairing itself.
        if (album == null || artist == null) {
            unmatched++
            null
        } else album to artist
    }.distinct()
    if (unmatched > 0) {
        Log.status("MusicUtil", "$unmatched of ${music.size} songs had no matching album or artist")
    }
    return pairs
}

/**
 * The whole song library, straight from the MediaStore cursor.
 *
 * Deliberately no [MediaMetadataRetriever] here: opening each file to read a tag costs tens of
 * milliseconds, and this runs on every refresh. Fields MediaStore leaves blank - usually `year`,
 * occasionally `duration` - are backfilled separately and cached, see
 * `MusicRepository.backfillTags`.
 */
suspend fun getSongs(context: Context): List<Music> = withContext(Dispatchers.IO) {
    val songs = mutableListOf<Music>()
    val projection = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ARTIST_ID,
        MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.ALBUM_ID,
        MediaStore.Audio.Media.DURATION,
        MediaStore.Audio.Media.TRACK,
        MediaStore.Audio.Media.YEAR,
    )
    try {
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            // Sorted here rather than in the list screen. SQLite does it once per refresh over the
            // whole table; the client-side comparator ran again on every recomposition that changed
            // the data, and NOCASE is closer to what a reader expects than Kotlin's case-sensitive
            // String.compareTo, which files everything lowercase after everything uppercase.
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
        )?.use { cursor -> songs.addAll(readSongs(cursor)) }
    } catch (e: SecurityException) {
        Log.error("MusicUtil", "Error querying songs", e)
    } catch (e: IllegalArgumentException) {
        Log.error("MusicUtil", "Error querying songs", e)
    } catch (e: IllegalStateException) {
        Log.error("MusicUtil", "Error querying songs", e)
    }
    return@withContext songs
}

private class SongColumns(cursor: android.database.Cursor) {
    val id = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
    val title = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
    val artist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
    val album = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
    val artistId = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST_ID)
    val albumId = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
    val duration = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
    val track = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
    val year = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
}

private fun readSongs(cursor: android.database.Cursor): List<Music> {
    val columns = SongColumns(cursor)
    val songs = ArrayList<Music>()
    while (cursor.moveToNext()) songs.add(readSong(cursor, columns))
    return songs
}

private fun readSong(cursor: android.database.Cursor, columns: SongColumns): Music {
    val id = cursor.getLong(columns.id)
    val uri = ContentUris.withAppendedId(
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id,
    ).toString()
    val (discNumber, trackNumber) = splitDiscTrack(cursor.getInt(columns.track))
    return Music(
        id = id,
        // MediaStore returns null for untagged files; Music's fields are
        // non-null and consumers sort/search on them directly, so an
        // untagged row crashed the scan or a later search. getArtists
        // already normalises the same case; do it here too.
        title = cursor.getString(columns.title) ?: "",
        artist = cursor.getString(columns.artist) ?: "",
        artistId = cursor.getLong(columns.artistId),
        album = cursor.getString(columns.album) ?: "",
        albumId = cursor.getLong(columns.albumId),
        uri = uri,
        duration = cursor.getLong(columns.duration),
        trackNumber = trackNumber,
        year = cursor.getInt(columns.year).takeIf { it > 0 } ?: 0,
        discNumber = discNumber,
    )
}

private fun splitDiscTrack(rawTrack: Int): Pair<Int, Int> {
    // MediaStore encodes multi-disc numbers as disc*1000 + track, so a value of
    // 2005 is disc 2, track 5. Split them apart instead of discarding the disc.
    if (rawTrack < DISC_TRACK_FACTOR) return 1 to rawTrack
    return (rawTrack / DISC_TRACK_FACTOR) to (rawTrack % DISC_TRACK_FACTOR)
}

suspend fun getAlbums(context: Context): List<Album> = withContext(Dispatchers.IO) {
    val musicList = mutableListOf<Album>()
    val projection = arrayOf(
        MediaStore.Audio.Albums._ID,
        MediaStore.Audio.Albums.ALBUM,
        MediaStore.Audio.Albums.ARTIST,
        MediaStore.Audio.Albums.ARTIST_ID,
    )

    // Filter to only get music files
    val sortOrder = "${MediaStore.Audio.Albums.ALBUM} COLLATE NOCASE ASC"

    try {
        context.contentResolver.query(
            MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Albums._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Albums.ALBUM)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val title = cursor.getString(titleColumn) ?: ""
                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
                    id
                ).toString()
                musicList.add(Album(id, title, contentUri))
            }
        }
    } catch (e: SecurityException) {
        Log.error("MusicUtil", "Error querying albums", e)
    } catch (e: IllegalArgumentException) {
        Log.error("MusicUtil", "Error querying albums", e)
    } catch (e: IllegalStateException) {
        Log.error("MusicUtil", "Error querying albums", e)
    }
    return@withContext musicList
}

suspend fun getArtists(context: Context): List<Artist> = withContext(Dispatchers.IO) {
    val artistList = mutableListOf<Artist>()
    val projection = arrayOf(
        MediaStore.Audio.Artists._ID,
        MediaStore.Audio.Artists.ARTIST,
    )

    // Filter to only get music files
    val sortOrder = "${MediaStore.Audio.Artists.ARTIST} COLLATE NOCASE ASC"

    try {
        context.contentResolver.query(
            MediaStore.Audio.Artists.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Artists._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Artists.ARTIST)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                // MediaStore hands back null, "" or the literal "<unknown>" when the files carry
                // no artist tag (the "Tyler, The Creator" report was a mistagged file, reporter's
                // own words). Normalise all three to "" here so `Artist.name` is never null at
                // runtime despite its non-null type; the screens render `unknown_artist` for it.
                val rawTitle = cursor.getString(titleColumn)
                val title = if (rawTitle.isNullOrBlank() || rawTitle == UNKNOWN_ARTIST_SENTINEL) "" else rawTitle
                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Audio.Artists.EXTERNAL_CONTENT_URI,
                    id
                ).toString()
                artistList.add(Artist(id, title, contentUri))
            }
        }
    } catch (e: SecurityException) {
        Log.error("MusicUtil", "Error querying artists", e)
    } catch (e: IllegalArgumentException) {
        Log.error("MusicUtil", "Error querying artists", e)
    } catch (e: IllegalStateException) {
        Log.error("MusicUtil", "Error querying artists", e)
    }
    return@withContext artistList
}

/**
 * Stand-in for artwork that isn't there — either still decoding, or genuinely absent
 * (plenty of files carry no embedded cover). A blank gap reads as a layout bug; a tinted
 * tile with a record glyph reads as "no cover".
 */
@Composable
private fun AlbumArtPlaceholder(modifier: Modifier) {
    Box(
        modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        IconAlbum(
            modifier = Modifier.fillMaxSize(PLACEHOLDER_FILL_FRACTION),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun AlbumArt(artUri: Uri?, modifier: Modifier) {
    // Previews — including the ones the store listing images are rendered from — have no
    // MediaStore behind them, so skip the load entirely rather than churning through a
    // guaranteed-failing thumbnail read.
    if (artUri == null || LocalInspectionMode.current) {
        AlbumArtPlaceholder(modifier)
        return
    }
    val context = LocalContext.current
    // contentResolver.loadThumbnail handles MediaStore.Audio.Albums URIs (which
    // resolve to the underlying album art) where Coil's default fetcher
    // doesn't. Cache the decoded bitmap in a process-scope LRU keyed by URI so
    // the same album doesn't re-decode each time it scrolls in/out.
    var bitmap: Bitmap? by remember(artUri) { mutableStateOf(albumArtCache.get(artUri)) }
    LaunchedEffect(artUri) {
        if (bitmap == null) {
            val loaded = withContext(Dispatchers.IO) { getThumbnail(context, artUri) }
            if (loaded != null) albumArtCache.put(artUri, loaded)
            bitmap = loaded
        }
    }
    val loaded = bitmap
    if (loaded == null) {
        AlbumArtPlaceholder(modifier)
        return
    }
    AsyncImage(
        model = loaded,
        contentDescription = stringResource(R.string.content_desc_album_art),
        modifier = modifier
    )
}
@Composable
fun AlbumArt(artUris: List<Uri>, modifier: Modifier) {
    if (artUris.isEmpty() || LocalInspectionMode.current) {
        AlbumArtPlaceholder(modifier)
        return
    }
    val context = LocalContext.current
    var bitmap: Bitmap? by remember { mutableStateOf(null) }

    // Re-run whenever the list of URIs changes
    LaunchedEffect(artUris) {
        withContext(Dispatchers.IO) {
            bitmap = if (artUris.size > 1) {
                createCollageBitmap(context, artUris.take(COLLAGE_TILE_COUNT))
            } else {
                // Fallback for single image
                artUris.firstOrNull()?.let { getThumbnail(context, it) }
            }
        }
    }

    val loaded = bitmap
    if (loaded == null) {
        AlbumArtPlaceholder(modifier)
        return
    }
    AsyncImage(
        model = loaded,
        contentDescription = stringResource(R.string.content_desc_album_art_grid),
        modifier = modifier
    )
}

/**
 * Creates a 2x2 grid bitmap from a list of Uris
 */
fun createCollageBitmap(context: Context, uris: List<Uri>): Bitmap {
    val size = 512 // Define a standard size for the output square
    val halfSize = size / 2
    val result = createBitmap(size, size)
    val canvas = Canvas(result)

    uris.forEachIndexed { index, uri ->
        val thumb = getThumbnail(context, uri) ?: return@forEachIndexed

        // Calculate grid position
        val left = (index % 2) * halfSize
        val top = (index / 2) * halfSize

        val rect = Rect(left, top, left + halfSize, top + halfSize)
        canvas.drawBitmap(thumb, null, rect, null)
    }

    return result
}

private inline fun <T> withAudioMetadata(
    context: Context,
    uri: Uri,
    default: T,
    extract: (MediaMetadataRetriever) -> T
): T = try {
    MediaMetadataRetriever().use { retriever ->
        retriever.setDataSource(context, uri)
        extract(retriever)
    }
} catch (e: IllegalArgumentException) {
    Log.status("MusicUtil", "Could not read audio metadata", e)
    default
} catch (e: SecurityException) {
    Log.status("MusicUtil", "Could not read audio metadata", e)
    default
} catch (e: IllegalStateException) {
    Log.status("MusicUtil", "Could not read audio metadata", e)
    default
}

fun getRealAudioDuration(context: Context, uri: Uri): Long =
    withAudioMetadata(context, uri, 0L) {
        it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
    }

fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / MILLIS_PER_SECOND
    val minutes = (totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    val hours = totalSeconds / SECONDS_PER_HOUR

    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

fun getAudioYear(context: Context, uri: Uri): Int =
    withAudioMetadata(context, uri, 0) {
        // Some containers (e.g. Opus/Vorbis) expose the release date only via DATE, not
        // YEAR, so fall back to it and pull the first four-digit year out of either.
        val raw = it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
            ?: it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
        raw?.let { value -> Regex("\\d{4}").find(value)?.value?.toIntOrNull() } ?: 0
    }

/** Cover art bytes, with the type a server would have to state for them. */
class ArtworkBytes(val bytes: ByteArray, val contentType: String)

/**
 * The largest edge a cover is sent at.
 *
 * Bounding this on the phone is what keeps the receiver simple - it decodes whatever arrives with no
 * pre-pass - and stops a 6 MB scan of an LP sleeve competing with the initial audio buffer over
 * Wi-Fi. Generous for a television: 1280px fills the cover half of a 4K panel.
 */
private const val ARTWORK_MAX_EDGE = 1280

private const val ARTWORK_JPEG_QUALITY = 88

/**
 * A track's cover art as bytes, bounded in size, or null when it has none.
 *
 * The embedded picture first, because it is the original at full resolution and is common even on
 * MP3s, where nothing else about the file's tags is readable. [fallbackArtUri] is the album's
 * `MediaStore` entry, whose 300x300 thumbnail is small for a television panel but beats a
 * placeholder.
 *
 * **Re-compressed only when it has to be.** Bounds are decoded first, and a picture already inside
 * [ARTWORK_MAX_EDGE] is passed through untouched - so the common case costs one decode of a header
 * rather than a decode, a scale and a JPEG encode. The type is sniffed from the magic bytes rather
 * than assumed, because whoever serves these has to state it.
 */
fun artworkBytes(context: Context, uri: Uri, fallbackArtUri: Uri?): ArtworkBytes? {
    val embedded = withAudioMetadata(context, uri, null) { it.embeddedPicture }
    if (embedded != null) return boundedArtwork(embedded)
    val thumbnail = fallbackArtUri?.let { getThumbnail(context, it) } ?: return null
    return ArtworkBytes(compressToJpeg(thumbnail), "image/jpeg")
}

private fun boundedArtwork(source: ByteArray): ArtworkBytes? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    if (longest <= 0) return null
    val sniffed = sniffImageType(source)
    if (longest <= ARTWORK_MAX_EDGE && sniffed != null) return ArtworkBytes(source, sniffed)

    // `inSampleSize` only halves, so this lands at or below the bound and the scale below finishes
    // the job exactly - decoding at full size first is what a large cover must not be allowed to do.
    var sample = 1
    while (longest / (sample * 2) >= ARTWORK_MAX_EDGE) sample *= 2
    val decoded = BitmapFactory.decodeByteArray(
        source,
        0,
        source.size,
        BitmapFactory.Options().apply { inSampleSize = sample },
    ) ?: return null
    val scaled = scaleWithin(decoded, ARTWORK_MAX_EDGE)
    val bytes = compressToJpeg(scaled)
    if (scaled !== decoded) scaled.recycle()
    decoded.recycle()
    return ArtworkBytes(bytes, "image/jpeg")
}

private fun scaleWithin(bitmap: Bitmap, maxEdge: Int): Bitmap {
    val longest = maxOf(bitmap.width, bitmap.height)
    if (longest <= maxEdge) return bitmap
    val scale = maxEdge.toDouble() / longest
    return bitmap.scale(
        (bitmap.width * scale).toInt().coerceAtLeast(1),
        (bitmap.height * scale).toInt().coerceAtLeast(1),
    )
}

private fun compressToJpeg(bitmap: Bitmap): ByteArray {
    val out = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, ARTWORK_JPEG_QUALITY, out)
    return out.toByteArray()
}

/**
 * The image type from its first bytes, or null for one nothing here can name.
 *
 * Null is what sends the picture through the re-compression path even when its size did not need it:
 * a type that cannot be stated honestly is worse than a JPEG, because a receiver is told what to
 * decode before it looks.
 */
private fun sniffImageType(bytes: ByteArray): String? = when {
    bytes.size < MIN_IMAGE_HEADER_BYTES -> null
    bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
    bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "image/png"
    String(bytes, RIFF_NAME_OFFSET, RIFF_NAME_LENGTH, Charsets.ISO_8859_1) == "RIFF" &&
        String(bytes, RIFF_TYPE_OFFSET, RIFF_NAME_LENGTH, Charsets.ISO_8859_1) == "WEBP" -> "image/webp"
    else -> null
}
