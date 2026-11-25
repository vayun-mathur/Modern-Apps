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

/** Round watch, 454x454px class (227dp at 320dpi) — Pixel Watch / Galaxy Watch size. */
private const val ROUND = "spec:width=227dp,height=227dp,dpi=320"

/**
 * Store listing images for `:music:wear`, rendered from Compose previews instead of
 * from an instrumented test on a device.
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
    fun Preview1HomeRound() {
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
    fun Preview2NowPlayingRound() {
        RoundPreview {
            WearNowPlayingContent(playback = fixturePlayback, onOp = {}, onRoute = {})
        }
    }
}

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
