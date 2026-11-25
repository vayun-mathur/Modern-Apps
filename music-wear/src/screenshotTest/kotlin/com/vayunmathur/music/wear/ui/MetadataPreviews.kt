package com.vayunmathur.music.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.wear.compose.material.MaterialTheme
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.music.wear.domain.WearPageId
import com.vayunmathur.music.wear.domain.WearPageKey
import com.vayunmathur.music.wear.domain.WearPageSnapshot
import com.vayunmathur.music.wear.domain.WearPlaybackState
import com.vayunmathur.music.wear.domain.WearTrack
import com.vayunmathur.music.wear.domain.WearAlbum
import com.vayunmathur.music.wear.domain.WearArtist
import com.vayunmathur.music.wear.domain.WearPlaylist

/** Round watch, 454x454px class (227dp at 320dpi) — Pixel Watch / Galaxy Watch size. */
private const val ROUND = "spec:width=227dp,height=227dp,dpi=320"

/**
 * Store listing images for `:music:wear`, rendered from Compose previews instead of
 * from an instrumented test on a device. Every top-level route is captured at least
 * once: home, now-playing, albums, artists, playlists, track-list (album/artist/
 * playlist detail share this screen), queue, add-to-playlist, search, settings.
 *
 * `./gradlew :music:wear:metadata` renders these and copies the PNGs into
 * `metadata_data/photos/music-wear/wear/` — the wear module's own bucket, not the
 * phone Music app's folder — where the release build picks them up.
 *
 * Rules (same as every other module's MetadataPreviews): order comes from the
 * function names, each preview needs `@PreviewTest` plus `group = "wear"`, previews
 * are class members, and everything is a literal fixture (a fixed page snapshot and
 * playback state — no store, no phone). Layoutlib renders a rectangular canvas, so
 * round previews clip to a circle: what is visible is what a round watch shows.
 */
class MetadataPreviews {

    @PreviewTest
    @Preview(name = "1-home-round", device = ROUND, group = "wear")
    @Composable
    fun Preview01HomeRound() {
        RoundPreview {
            WearHomeContent(
                page = WearPageId(WearPageKey.HOME),
                snapshot = WearPageSnapshot(
                    page = WearPageId(WearPageKey.HOME),
                    tracks = listOf(
                        WearTrack(id = 1, title = "Midnight Run", artist = "The Night Trains", durationMs = 214_000L),
                        WearTrack(id = 2, title = "Harbour Lights", artist = "Ana Sol", durationMs = 187_000L),
                        WearTrack(id = 3, title = "Static Bloom", artist = "Field Kit", durationMs = 243_000L),
                    ),
                    totalCount = 3,
                ),
                playback = fixturePlayback,
                onEnqueueOp = {},
                onRoute = {},
            )
        }
    }

    @PreviewTest
    @Preview(name = "2-now-playing-round", device = ROUND, group = "wear")
    @Composable
    fun Preview02NowPlayingRound() {
        RoundPreview {
            WearNowPlayingContent(playback = fixturePlayback, onOp = {}, onRoute = {})
        }
    }

    @PreviewTest
    @Preview(name = "3-albums-round", device = ROUND, group = "wear")
    @Composable
    fun Preview03AlbumsRound() {
        RoundPreview {
            WearAlbumListContent(
                snapshot = WearPageSnapshot(page = WearPageId(WearPageKey.ALBUMS), albums = listOf(
                    WearAlbum(id = 1, name = "Northbound", artistName = "The Night Trains", trackCount = 9),
                    WearAlbum(id = 2, name = "Harbour", artistName = "Ana Sol", trackCount = 7),
                )),
                onRoute = {},
            )
        }
    }

    @PreviewTest
    @Preview(name = "4-artists-round", device = ROUND, group = "wear")
    @Composable
    fun Preview04ArtistsRound() {
        RoundPreview {
            WearArtistListContent(
                snapshot = WearPageSnapshot(page = WearPageId(WearPageKey.ARTISTS), artists = listOf(
                    WearArtist(id = 1, name = "The Night Trains", albumCount = 3),
                    WearArtist(id = 2, name = "Ana Sol", albumCount = 2),
                )),
                onRoute = {},
            )
        }
    }

    @PreviewTest
    @Preview(name = "5-playlists-round", device = ROUND, group = "wear")
    @Composable
    fun Preview05PlaylistsRound() {
        RoundPreview {
            WearPlaylistListContent(
                snapshot = WearPageSnapshot(page = WearPageId(WearPageKey.PLAYLISTS), playlists = listOf(
                    WearPlaylist(id = 1, name = "Evening run", trackCount = 12),
                    WearPlaylist(id = 2, name = "Focus", trackCount = 8),
                )),
                onRoute = {},
            )
        }
    }

    @PreviewTest
    @Preview(name = "6-track-list-round", device = ROUND, group = "wear")
    @Composable
    fun Preview06TrackListRound() {
        RoundPreview {
            WearTrackListContent(
                page = WearPageId(WearPageKey.ALBUM_DETAIL, 1),
                title = "Northbound",
                snapshot = WearPageSnapshot(page = WearPageId(WearPageKey.ALBUM_DETAIL, 1), tracks = fixtureTracks, totalCount = 3),
                playback = fixturePlayback,
                onEnqueueOp = {},
                onRoute = {},
                onBack = {},
            )
        }
    }

    @PreviewTest
    @Preview(name = "7-queue-round", device = ROUND, group = "wear")
    @Composable
    fun Preview07QueueRound() {
        RoundPreview {
            WearQueueContent(playback = fixturePlayback.copy(queue = fixtureTracks, queueIndex = 0), onOp = {}, onRoute = {})
        }
    }

    @PreviewTest
    @Preview(name = "8-add-to-playlist-round", device = ROUND, group = "wear")
    @Composable
    fun Preview08AddToPlaylistRound() {
        RoundPreview {
            WearAddToPlaylistContent(
                snapshot = WearPageSnapshot(page = WearPageId(WearPageKey.PLAYLISTS), playlists = listOf(
                    WearPlaylist(id = 1, name = "Evening run", trackCount = 12),
                    WearPlaylist(id = 2, name = "Focus", trackCount = 8),
                )),
                onAdd = {},
                onRoute = {},
            )
        }
    }

    @PreviewTest
    @Preview(name = "9-search-round", device = ROUND, group = "wear")
    @Composable
    fun Preview09SearchRound() {
        RoundPreview {
            WearSearchContent(
                initialQuery = "midnight",
                snapshots = mapOf(WearPageId(WearPageKey.SEARCH, query = "midnight") to WearPageSnapshot(page = WearPageId(WearPageKey.SEARCH, query = "midnight"), tracks = fixtureTracks, totalCount = 3)),
                playback = fixturePlayback,
                onSearch = {},
                onEnqueueOp = {},
                onRoute = {},
            )
        }
    }

    @PreviewTest
    @Preview(name = "10-settings-round", device = ROUND, group = "wear")
    @Composable
    fun Preview10SettingsRound() {
        RoundPreview { WearSettingsContent(cachedPages = 4, pendingCount = 0, onRoute = {}) }
    }
}

private val fixtureTracks = listOf(
    WearTrack(id = 1, title = "Midnight Run", artist = "The Night Trains", durationMs = 214_000L),
    WearTrack(id = 2, title = "Harbour Lights", artist = "Ana Sol", durationMs = 187_000L),
    WearTrack(id = 3, title = "Static Bloom", artist = "Field Kit", durationMs = 243_000L),
)

private val fixturePlayback = WearPlaybackState(
    trackId = 1,
    title = "Midnight Run",
    artist = "The Night Trains",
    album = "Northbound",
    isPlaying = true,
    positionMs = 62_000L,
    durationMs = 214_000L,
    shuffle = false,
    repeatMode = 0,
    queue = emptyList(),
    queueIndex = 0,
    sourceName = "Library",
)

/** Round-watch framing: wear screens are round-safe by design; clip so the render is too. */
@Composable
private fun RoundPreview(content: @Composable () -> Unit) {
    MaterialTheme {
        Box(
            Modifier.fillMaxSize().clip(CircleShape).background(MaterialTheme.colors.background),
        ) { content() }
    }
}
