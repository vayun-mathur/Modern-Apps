package com.vayunmathur.uniflix.ui.tv

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.vayunmathur.library.tv.TvButton
import com.vayunmathur.library.tv.TvEmptyState
import com.vayunmathur.library.tv.TvLoading
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.uniflix.R
import com.vayunmathur.uniflix.domain.SubtitleTrack
import com.vayunmathur.uniflix.platform.DetailViewModel
import com.vayunmathur.uniflix.ui.SKIP_MS
import com.vayunmathur.uniflix.ui.rememberWatchPlayerState
import kotlinx.coroutines.delay

private const val OVERLAY_AUTO_HIDE_MS = 3000L

/** Which picker dialog sits over the player, if any. */
private enum class WatchPicker { Server, Subtitles, Quality }

/**
 * TV watch: the phone's ExoPlayer state/logic ([rememberWatchPlayerState]) with a
 * D-pad overlay (see TvWatchOverlay).
 *
 * CENTER toggles overlay/play-pause, LEFT/RIGHT seek ±10s while the overlay is
 * hidden, BACK hides the overlay before exiting. Server/subtitle/quality pickers
 * are dialogs — no bottom sheets on a TV.
 */
@Composable
fun TvWatchPage(
    backStack: NavBackStack<TvRoute>,
    mediaType: String,
    tmdbId: Int,
    season: Int,
    episode: Int,
) {
    val type = mediaTypeOf(mediaType)
    val vm: DetailViewModel = viewModel()
    var startPositionMs by remember(tmdbId, season, episode) { mutableLongStateOf(0L) }
    LaunchedEffect(tmdbId, season, episode) {
        vm.load(type, tmdbId)
        vm.loadServers()
        startPositionMs = vm.startPosition(type, tmdbId, season, episode)
        vm.resolve(type, tmdbId, season, episode)
    }
    val state = vm.state
    val source = state.source

    var overlayVisible by remember { mutableStateOf(true) }
    var picker by remember { mutableStateOf<WatchPicker?>(null) }
    var selectedSubtitle by remember(source?.streamUrl) { mutableStateOf<SubtitleTrack?>(null) }
    var selectedHeight by remember(source?.streamUrl) { mutableStateOf<Int?>(null) }
    var retryTicket by remember { mutableStateOf(0) }
    var carriedPosition by remember { mutableStateOf<Long?>(null) }

    val playerState = rememberWatchPlayerState(
        source = source,
        startPositionMs = carriedPosition ?: startPositionMs,
        selectedSubtitle = selectedSubtitle,
        maxHeight = selectedHeight,
        ticket = retryTicket,
        onRelease = { positionMs, durationMs ->
            val detail = vm.state.detail ?: return@rememberWatchPlayerState
            vm.savePosition(
                type, tmdbId, detail.title, detail.posterPath,
                season, episode, positionMs, durationMs,
            )
        },
    )
    val player = playerState.player

    LaunchedEffect(overlayVisible, playerState.isPlaying) {
        if (overlayVisible && playerState.isPlaying && picker == null) {
            delay(OVERLAY_AUTO_HIDE_MS)
            overlayVisible = false
        }
    }
    BackHandler(enabled = picker != null) { picker = null }
    BackHandler(enabled = picker == null && overlayVisible && player != null) { overlayVisible = false }

    val playFocus = remember { FocusRequester() }

    val loading = state.resolving || (source == null && state.detail == null && state.resolveError == null)
    val noAddons = source == null && !state.resolving && state.providerMissing
    val loadFailed = source == null && !state.resolving && !state.providerMissing &&
        (state.detail != null || state.resolveError != null)

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                when (event.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        if (player == null) return@onPreviewKeyEvent false
                        if (picker != null) return@onPreviewKeyEvent false
                        if (!overlayVisible) overlayVisible = true else playerState.togglePlay()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        // Only when the overlay is hidden: CENTER reveals it. While the
                        // overlay is up, CENTER must reach the focused overlay button —
                        // consuming it here would make play/pause unreachable.
                        if (player == null || picker != null || overlayVisible) {
                            false
                        } else {
                            overlayVisible = true
                            true
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (picker == null && !overlayVisible && player != null) {
                            overlayVisible = true
                            playerState.skipBy(-SKIP_MS)
                            true
                        } else {
                            false
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (picker == null && !overlayVisible && player != null) {
                            overlayVisible = true
                            playerState.skipBy(SKIP_MS)
                            true
                        } else {
                            false
                        }
                    }
                    KeyEvent.KEYCODE_MEDIA_PLAY -> {
                        player?.play(); true
                    }
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                        player?.pause(); true
                    }
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                        if (player != null) playerState.skipBy(SKIP_MS)
                        player != null
                    }
                    KeyEvent.KEYCODE_MEDIA_REWIND -> {
                        if (player != null) playerState.skipBy(-SKIP_MS)
                        player != null
                    }
                    else -> false
                }
            },
    ) {
        player?.let {
            PlayerSurface(
                player = it,
                modifier = Modifier.fillMaxSize(),
                surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
            )
        }
        if (playerState.cues.isNotEmpty()) {
            Column(
                Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = 132.dp, start = 48.dp, end = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                playerState.cues.forEach { cue ->
                    cue.text?.let { text ->
                        Text(
                            text = text.toString(),
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.background(Color.Black.copy(alpha = 0.6f))
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
        if (loading) TvLoading()
        if (noAddons) {
            TvEmptyState(
                title = stringResource(R.string.no_addons_title),
                body = stringResource(R.string.no_addons_body),
                action = {
                    TvButton(
                        text = stringResource(R.string.open_addon_manager),
                        onClick = {
                            backStack.pop()
                            backStack.setLast(TvRoute.Addons)
                        },
                        primary = true,
                    )
                },
            )
        }
        if (loadFailed) {
            TvEmptyState(
                title = state.resolveError ?: stringResource(R.string.no_results),
                isError = true,
                action = {
                    TvButton(
                        text = stringResource(UiR.string.retry),
                        onClick = { vm.resolve(type, tmdbId, season, episode) },
                        primary = true,
                    )
                },
            )
        }
        playerState.playerError?.let { error ->
            TvEmptyState(
                title = error,
                isError = true,
                action = {
                    TvButton(
                        text = stringResource(UiR.string.retry),
                        onClick = {
                            carriedPosition = playerState.positionMs
                            retryTicket++
                        },
                        primary = true,
                    )
                },
            )
        }
        if (overlayVisible && picker == null && player != null && !loading && !noAddons && !loadFailed) {
            TvWatchOverlay(
                title = state.detail?.title ?: "",
                serverLabel = source?.title ?: state.selectedServer ?: stringResource(R.string.server),
                showServerPicker = state.servers.size > 1,
                onOpenServerPicker = { picker = WatchPicker.Server },
                playerState = playerState,
                subtitleLabel = run {
                    val sub = selectedSubtitle
                    sub?.displayName?.ifBlank { sub.language }
                } ?: stringResource(R.string.subtitles_cc),
                showSubtitlePicker = (source?.subtitles ?: emptyList()).isNotEmpty(),
                onOpenSubtitlePicker = { picker = WatchPicker.Subtitles },
                qualityLabel = selectedHeight?.let(::resolutionLabel) ?: stringResource(R.string.auto),
                showQualityPicker = playerState.videoHeights.isNotEmpty(),
                onOpenQualityPicker = { picker = WatchPicker.Quality },
                playFocus = playFocus,
                onBack = { backStack.pop() },
            )
        }
        when (picker) {
            WatchPicker.Server -> TvServerPickerDialog(
                servers = state.servers,
                selectedTitle = source?.title ?: state.selectedServer,
                onDismiss = { picker = null },
                onSelect = { server ->
                    carriedPosition = playerState.positionMs
                    vm.selectServer(type, tmdbId, season, episode, server)
                    picker = null
                },
            )
            WatchPicker.Subtitles -> TvSubtitlePickerDialog(
                subtitles = source?.subtitles ?: emptyList(),
                selected = selectedSubtitle,
                onDismiss = { picker = null },
                onSelect = { selectedSubtitle = it; picker = null },
            )
            WatchPicker.Quality -> TvQualityPickerDialog(
                heights = playerState.videoHeights,
                selectedHeight = selectedHeight,
                onDismiss = { picker = null },
                onSelect = { selectedHeight = it; picker = null },
            )
            null -> Unit
        }
    }
}
