package com.vayunmathur.music.service

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaConstants
import com.vayunmathur.library.util.ManyManyMatching
import com.vayunmathur.music.data.Album
import com.vayunmathur.music.data.Artist
import com.vayunmathur.music.data.Music
import com.vayunmathur.music.data.MusicRepository
import com.vayunmathur.music.data.Playlist
import com.vayunmathur.music.data.TYPE_MUSIC_PLAYLIST
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Read-only, in-memory projection of the music library used to serve the
 * Android Auto (media) browse tree from [PlaybackService].
 *
 * The Media3 [androidx.media3.session.MediaLibraryService] callbacks must return
 * results synchronously (as [com.google.common.util.concurrent.ListenableFuture]s
 * we resolve immediately), so we keep a hot snapshot of the five Room flows here
 * and rebuild [MediaItem]s from it on demand. Nothing here touches the phone
 * playback path; it only exposes the existing library to the car.
 */
class MusicLibraryTree(context: Context) {

    private val repo = MusicRepository.get(context.applicationContext)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Volatile private var songs: List<Music> = emptyList()
    @Volatile private var albums: List<Album> = emptyList()
    @Volatile private var artists: List<Artist> = emptyList()
    @Volatile private var playlists: List<Playlist> = emptyList()
    @Volatile private var matchings: List<ManyManyMatching> = emptyList()

    /** Album id -> art uri, for fast artwork lookup when building song items. */
    @Volatile private var albumArt: Map<Long, String> = emptyMap()

    /** Most-recently-played song ids (front = newest), capped. */
    private val recent = ArrayDeque<Long>()

    /** Invoked (on a background thread) whenever the snapshot changes. */
    var onLibraryChanged: (() -> Unit)? = null

    init {
        scope.launch {
            combine(
                repo.music,
                repo.albums,
                repo.artists,
                repo.playlists,
                repo.matchings,
            ) { m, al, ar, pl, mt -> Snapshot(m, al, ar, pl, mt) }
                // A dead store (e.g. SQLCipher native missing on a host
                // JVM) must leave the tree empty, not escape as an
                // uncaught exception on Dispatchers.Default that then
                // poisons unrelated coroutines/tests sharing the process.
                .catch { }
                .collect { snap ->
                    songs = snap.songs
                    albums = snap.albums
                    artists = snap.artists
                    playlists = snap.playlists
                    matchings = snap.matchings
                    albumArt = snap.albums.associate { it.id to it.uri }
                    onLibraryChanged?.invoke()
                }
        }
    }

    fun release() = scope.cancel()

    // ── Browse ids ─────────────────────────────────────────────────────────

    /** Shared constants and helpers for MusicLibraryTree. */
    companion object {
        const val ROOT = "root"
        const val TAB_PLAYLISTS = "tab_playlists"
        const val TAB_ALBUMS = "tab_albums"
        const val TAB_ARTISTS = "tab_artists"
        const val TAB_SONGS = "tab_songs"
        const val TAB_RECENT = "tab_recent"

        private const val PREFIX_PLAYLIST = "playlist_"
        private const val PREFIX_ALBUM = "album_"
        private const val PREFIX_ARTIST = "artist_"

        private const val RECENT_LIMIT = 50

        private val TAB_IDS = listOf(TAB_PLAYLISTS, TAB_ALBUMS, TAB_ARTISTS, TAB_SONGS, TAB_RECENT)
    }

    /** Parent ids whose child lists AA should be told to refresh on data change. */
    fun refreshableParents(): List<String> = listOf(ROOT) + TAB_IDS

    fun childCount(parentId: String): Int = children(parentId).size

    // ── Tree ───────────────────────────────────────────────────────────────

    fun rootItem(): MediaItem =
        browsable(ROOT, "Music", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)

    fun children(parentId: String): List<MediaItem> {
        tabChildren(parentId)?.let { return it }
        return nodeChildren(parentId)
    }

    private fun tabChildren(parentId: String): List<MediaItem>? = when (parentId) {
        ROOT -> rootTabs()
        TAB_PLAYLISTS -> playlists.sortedBy { it.name.lowercase() }.map { playlistNode(it) }
        TAB_ALBUMS -> albums.sortedBy { it.name.lowercase() }.map { albumNode(it) }
        TAB_ARTISTS -> artists.sortedBy { it.name.lowercase() }.map { artistNode(it) }
        TAB_SONGS -> songs.sortedBy { it.title.lowercase() }.map { songItem(it) }
        TAB_RECENT -> recentSongs().map { songItem(it) }
        else -> null
    }

    private fun rootTabs(): List<MediaItem> = listOf(
        tabNode(TAB_PLAYLISTS, "Playlists", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
        tabNode(TAB_ALBUMS, "Albums", MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS),
        tabNode(TAB_ARTISTS, "Artists", MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS),
        tabNode(TAB_SONGS, "Songs", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
        tabNode(TAB_RECENT, "Recently played", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
    )

    private fun nodeChildren(parentId: String): List<MediaItem> = when {
        parentId.startsWith(PREFIX_PLAYLIST) -> playlistSongs(parentId)
        parentId.startsWith(PREFIX_ALBUM) -> albumSongs(parentId)
        parentId.startsWith(PREFIX_ARTIST) -> artistSongs(parentId)
        else -> emptyList()
    }

    private fun playlistSongs(parentId: String): List<MediaItem> =
        parentId.removePrefix(PREFIX_PLAYLIST).toLongOrNull()
            ?.let { songsInPlaylist(it) }.orEmpty().map { songItem(it) }

    private fun albumSongs(parentId: String): List<MediaItem> =
        parentId.removePrefix(PREFIX_ALBUM).toLongOrNull()
            ?.let { id -> songsSortedForAlbum(songs.filter { it.albumId == id }) }
            .orEmpty().map { songItem(it) }

    private fun artistSongs(parentId: String): List<MediaItem> =
        parentId.removePrefix(PREFIX_ARTIST).toLongOrNull()
            ?.let { id -> songs.filter { it.artistId == id }.sortedBy { it.title.lowercase() } }
            .orEmpty().map { songItem(it) }

    /** Resolve any browse mediaId (tab, node, or song) to a display [MediaItem]. */
    fun item(mediaId: String): MediaItem? {
        tabItem(mediaId)?.let { return it }
        return nodeItem(mediaId)
    }

    private fun tabItem(mediaId: String): MediaItem? = when (mediaId) {
        ROOT -> rootItem()
        TAB_PLAYLISTS -> tabNode(TAB_PLAYLISTS, "Playlists", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS)
        TAB_ALBUMS -> tabNode(TAB_ALBUMS, "Albums", MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS)
        TAB_ARTISTS -> tabNode(TAB_ARTISTS, "Artists", MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS)
        TAB_SONGS -> tabNode(TAB_SONGS, "Songs", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
        TAB_RECENT -> tabNode(TAB_RECENT, "Recently played", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
        else -> null
    }

    private fun nodeItem(mediaId: String): MediaItem? = when {
        mediaId.startsWith(PREFIX_PLAYLIST) -> playlistItem(mediaId)
        mediaId.startsWith(PREFIX_ALBUM) -> albumItem(mediaId)
        mediaId.startsWith(PREFIX_ARTIST) -> artistItem(mediaId)
        else -> songById(mediaId.toLongOrNull())?.let { songItem(it) }
    }

    private fun playlistItem(mediaId: String): MediaItem? =
        mediaId.removePrefix(PREFIX_PLAYLIST).toLongOrNull()
            ?.let { id -> playlists.firstOrNull { it.id == id } }?.let { playlistNode(it) }

    private fun albumItem(mediaId: String): MediaItem? =
        mediaId.removePrefix(PREFIX_ALBUM).toLongOrNull()
            ?.let { id -> albums.firstOrNull { it.id == id } }?.let { albumNode(it) }

    private fun artistItem(mediaId: String): MediaItem? =
        mediaId.removePrefix(PREFIX_ARTIST).toLongOrNull()
            ?.let { id -> artists.firstOrNull { it.id == id } }?.let { artistNode(it) }

    // ── Playback resolution ─────────────────────────────────────────────────

    /**
     * Turn a browse [MediaItem] (whose local uri was stripped over IPC) into a
     * fully playable item, reusing the same media id + uri scheme as the phone
     * player so now-playing metadata keeps working everywhere.
     */
    fun resolveForPlayback(mediaId: String): MediaItem? =
        songById(mediaId.toLongOrNull())?.let { playableItem(it) }

    /** Voice "play X": best-effort mapping of a free-text query to a playable queue. */
    fun searchPlayableSongs(query: String): List<MediaItem> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()

        val byTitle = songs.filter { it.title.contains(q, ignoreCase = true) }
        if (byTitle.isNotEmpty()) return byTitle.sortedBy { it.title.lowercase() }.map { playableItem(it) }

        albums.firstOrNull { it.name.equals(q, true) || it.name.contains(q, true) }?.let { al ->
            val inAlbum = songsSortedForAlbum(songs.filter { it.albumId == al.id })
            if (inAlbum.isNotEmpty()) return inAlbum.map { playableItem(it) }
        }
        artists.firstOrNull { it.name.equals(q, true) || it.name.contains(q, true) }?.let { ar ->
            val byArtist = songs.filter { it.artistId == ar.id }.sortedBy { it.title.lowercase() }
            if (byArtist.isNotEmpty()) return byArtist.map { playableItem(it) }
        }
        playlists.firstOrNull { it.name.equals(q, true) || it.name.contains(q, true) }?.let { pl ->
            val inPlaylist = songsInPlaylist(pl.id)
            if (inPlaylist.isNotEmpty()) return inPlaylist.map { playableItem(it) }
        }
        return byTitle.map { playableItem(it) }
    }

    /** Mixed results for the car's search UI (songs playable, groups browsable). */
    fun searchResults(query: String): List<MediaItem> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val songHits = songs.filter {
            it.title.contains(q, true) || it.artist.contains(q, true) || it.album.contains(q, true)
        }.sortedBy { it.title.lowercase() }.map { songItem(it).withGroupTitle("Songs") }
        val albumHits = albums.filter { it.name.contains(q, true) }
            .map { albumNode(it).withGroupTitle("Albums") }
        val artistHits = artists.filter { it.name.contains(q, true) }
            .map { artistNode(it).withGroupTitle("Artists") }
        val playlistHits = playlists.filter { it.name.contains(q, true) }
            .map { playlistNode(it).withGroupTitle("Playlists") }
        return songHits + albumHits + artistHits + playlistHits
    }

    /** Record played song ids so the "Recently played" node reflects car playback. */
    fun markPlayed(mediaIds: List<String>) {
        val ids = mediaIds.mapNotNull { it.toLongOrNull() }
        if (ids.isEmpty()) return
        synchronized(recent) {
            ids.forEach { id ->
                recent.remove(id)
                recent.addFirst(id)
            }
            while (recent.size > RECENT_LIMIT) recent.removeLast()
        }
        onLibraryChanged?.invoke()
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private fun songById(id: Long?): Music? = id?.let { i -> songs.firstOrNull { it.id == i } }

    private fun recentSongs(): List<Music> = synchronized(recent) { recent.toList() }
        .mapNotNull { id -> songs.firstOrNull { it.id == id } }

    private fun songsInPlaylist(playlistId: Long): List<Music> {
        val ids = matchings
            .filter { it.type == TYPE_MUSIC_PLAYLIST && it.rightID == playlistId }
            .map { it.leftID }
            .toSet()
        return songs.filter { it.id in ids }
    }

    private fun songsSortedForAlbum(items: List<Music>): List<Music> =
        items.sortedWith(compareBy({ it.discNumber }, { it.trackNumber }, { it.title.lowercase() }))

    private fun playlistNode(p: Playlist): MediaItem =
        browsable("$PREFIX_PLAYLIST${p.id}", p.name, MediaMetadata.MEDIA_TYPE_PLAYLIST)

    private fun albumNode(a: Album): MediaItem =
        browsable(
            "$PREFIX_ALBUM${a.id}", a.name, MediaMetadata.MEDIA_TYPE_ALBUM,
            artwork = a.uri.takeIf { it.isNotBlank() }?.toUri(),
            // Albums carry cover art, so present them as grid tiles (Auxio parity).
            browsableStyle = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
        )

    private fun artistNode(a: Artist): MediaItem =
        browsable("$PREFIX_ARTIST${a.id}", a.name, MediaMetadata.MEDIA_TYPE_ARTIST)

    private fun artworkFor(song: Music): Uri {
        val art = albumArt[song.albumId]?.takeIf { it.isNotBlank() }
        return (art ?: song.uri).toUri()
    }

    /** A top-level tab node, styled as a category so AA renders the browse chrome. */
    private fun tabNode(mediaId: String, title: String, mediaType: Int): MediaItem =
        browsable(
            mediaId, title, mediaType,
            singleStyle = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_CATEGORY_LIST_ITEM,
        )

    private fun browsable(
        mediaId: String,
        title: String,
        mediaType: Int,
        artwork: Uri? = null,
        browsableStyle: Int? = null,
        singleStyle: Int? = null,
    ): MediaItem {
        val meta = MediaMetadata.Builder()
            .setTitle(title)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(mediaType)
            .apply { artwork?.let { setArtworkUri(it) } }
            .setExtras(contentStyleExtras(browsable = browsableStyle, single = singleStyle))
            .build()
        return MediaItem.Builder().setMediaId(mediaId).setMediaMetadata(meta).build()
    }

    /**
     * Content-style hints that tell the Android Auto media browser how to lay an
     * item out: grid vs list tiles, category chrome, and section group titles.
     * Mirrors Auxio's MediaItemTranslation. Empty bundle = host default.
     */
    private fun contentStyleExtras(
        browsable: Int? = null,
        playable: Int? = null,
        single: Int? = null,
    ): Bundle = Bundle().apply {
        browsable?.let { putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, it) }
        playable?.let { putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, it) }
        single?.let { putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM, it) }
    }

    /** Returns a copy of this browse item tagged with a section group title. */
    private fun MediaItem.withGroupTitle(title: String): MediaItem {
        val extras = Bundle(mediaMetadata.extras ?: Bundle())
        extras.putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, title)
        return buildUpon()
            .setMediaMetadata(mediaMetadata.buildUpon().setExtras(extras).build())
            .build()
    }

    /** Browse leaf for a song: playable flag set, uri intentionally omitted (added on playback). */
    private fun songItem(song: Music): MediaItem {
        val meta = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist)
            .setAlbumTitle(song.album)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setArtworkUri(artworkFor(song))
            .setExtras(contentStyleExtras(playable = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM))
            .build()
        return MediaItem.Builder().setMediaId(song.id.toString()).setMediaMetadata(meta).build()
    }

    /** Playable item with a concrete uri, mirroring the phone player's item shape. */
    private fun playableItem(song: Music): MediaItem {
        val meta = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist)
            .setAlbumTitle(song.album)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setArtworkUri(artworkFor(song))
            .build()
        return MediaItem.Builder()
            .setMediaId(song.id.toString())
            .setUri(song.uri.toUri())
            .setMediaMetadata(meta)
            .build()
    }

    private data class Snapshot(
        val songs: List<Music>,
        val albums: List<Album>,
        val artists: List<Artist>,
        val playlists: List<Playlist>,
        val matchings: List<ManyManyMatching>,
    )
}
