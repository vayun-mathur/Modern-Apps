package com.vayunmathur.uniflix.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.friendlyErrorMessage
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.FadeVisibility
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconPause
import com.vayunmathur.library.ui.IconPlay
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.uniflix.domain.MediaType
import com.vayunmathur.uniflix.domain.SubtitleTrack
import com.vayunmathur.uniflix.platform.DetailUiState
import kotlinx.coroutines.delay

private const val CONTROLS_AUTO_HIDE_MS = 3000L
private const val CUES_BOTTOM_LIFT_DP = 132

/**
 * Immersive fullscreen player: the video fills the window edge to edge and
 * every control floats above it, auto-hiding while playback runs.
 *
 * Tap toggles the chrome, double-tap seeks ∓10s, and the system bars stay
 * hidden (transient swipe) for the life of this destination. The fullscreen
 * button toggles the landscape lock; leaving the route restores orientation,
 * bars and the keep-screen-on flag.
 */
@OptIn(UnstableApi::class)
@Composable
fun WatchScreen(
    state: DetailUiState,
    season: Int,
    episode: Int,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onOpenAddons: () -> Unit,
    onSelectServer: (String) -> Unit,
    onSavePosition: (positionMs: Long, durationMs: Long) -> Unit,
) {
    val context = LocalContext.current
    val source = state.source

    var controlsVisible by remember { mutableStateOf(true) }
    var menusOpen by remember { mutableStateOf(false) }
    var selectedSubtitle by remember(source?.streamUrl) { mutableStateOf<SubtitleTrack?>(null) }
    var selectedHeight by remember(source?.streamUrl) { mutableStateOf<Int?>(null) }
    var retryTicket by remember { mutableIntStateOf(0) }
    var carriedPosition by remember { mutableStateOf<Long?>(null) }
    var landscapeLocked by remember { mutableStateOf(true) }

    LaunchedEffect(state.detail?.tmdbId, season, episode) {
        carriedPosition = null
    }

    val playerState = rememberWatchPlayerState(
        source = source,
        startPositionMs = carriedPosition ?: state.startPositionMs,
        selectedSubtitle = selectedSubtitle,
        maxHeight = selectedHeight,
        ticket = retryTicket,
        onRelease = onSavePosition,
    )
    val player = playerState.player

    val configuration = LocalConfiguration.current
    val isPhone = configuration.smallestScreenWidthDp < 600
    if (landscapeLocked && isPhone) {
        LockScreenOrientation(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE)
    }

    DisposableEffect(Unit) {
        val activity = context.findActivity()
        val window = activity.window
        val bars = WindowCompat.getInsetsController(window, window.decorView)
        bars.hide(WindowInsetsCompat.Type.systemBars())
        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            bars.show(WindowInsetsCompat.Type.systemBars())
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    LaunchedEffect(playerState.isPlaying) {
        if (!playerState.isPlaying) controlsVisible = true
    }
    LaunchedEffect(controlsVisible, playerState.isPlaying, menusOpen, playerState.isDragging, player) {
        if (!controlsVisible || !playerState.isPlaying || menusOpen || playerState.isDragging || player == null) {
            return@LaunchedEffect
        }
        delay(CONTROLS_AUTO_HIDE_MS)
        controlsVisible = false
    }

    val loading = state.resolving || (source == null && state.detail == null && state.resolveError == null)
    val noAddons = source == null && !state.resolving && state.providerMissing
    val loadFailed = source == null && !state.resolving && !state.providerMissing &&
        (state.detail != null || state.resolveError != null)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(controlsVisible, menusOpen, player) {
                detectTapGestures(
                    onTap = {
                        if (!menusOpen) controlsVisible = !controlsVisible
                    },
                    onDoubleTap = { offset ->
                        if (player == null) return@detectTapGestures
                        controlsVisible = true
                        playerState.skipBy(if (offset.x > size.width / 2) SKIP_MS else -SKIP_MS)
                    },
                )
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
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(
                        bottom = CUES_BOTTOM_LIFT_DP.dp,
                        start = 16.dp,
                        end = 16.dp,
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                playerState.cues.forEach { cue ->
                    cue.text?.let { text ->
                        Text(
                            text = text.toString(),
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }

        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(48.dp).align(Alignment.Center),
                color = Color.White,
            )
        }

        if (noAddons) {
            WatchNoAddonsPanel(
                onOpenAddons = onOpenAddons,
                onBack = onBack,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        if (loadFailed) {
            WatchErrorPanel(
                message = state.resolveError?.let { friendlyErrorMessage(it) } ?: "No stream available for this title.",
                onRetry = onRetry,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        playerState.playerError?.let { error ->
            WatchErrorPanel(
                message = friendlyErrorMessage(error),
                onRetry = {
                    carriedPosition = playerState.positionMs
                    retryTicket++
                },
                modifier = Modifier.align(Alignment.Center),
            )
        }

        FadeVisibility(visible = controlsVisible) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f))) {
                val title = state.detail?.title ?: "Watch"
                WatchTopBar(
                    title = title,
                    serverLabel = source?.title ?: state.selectedServer ?: "Server",
                    servers = state.servers,
                    onSelectServer = {
                        carriedPosition = playerState.positionMs
                        onSelectServer(it)
                    },
                    videoHeights = playerState.videoHeights,
                    selectedHeight = selectedHeight,
                    onSelectHeight = { selectedHeight = it },
                    subtitles = source?.subtitles ?: emptyList(),
                    selectedSubtitle = selectedSubtitle,
                    onSelectSubtitle = { selectedSubtitle = it },
                    onBack = onBack,
                    onAnyMenuOpen = { menusOpen = it },
                    modifier = Modifier.align(Alignment.TopCenter),
                )

                if (player != null && !playerState.isBuffering) {
                    IconButton(
                        onClick = { playerState.togglePlay() },
                        modifier = Modifier.size(72.dp).align(Alignment.Center),
                    ) {
                        if (playerState.isPlaying) {
                            IconPause(tint = Color.White, modifier = Modifier.size(56.dp))
                        } else {
                            IconPlay(tint = Color.White, modifier = Modifier.size(56.dp))
                        }
                    }
                }
                if (playerState.isBuffering && player != null) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(56.dp).align(Alignment.Center),
                        color = Color.White,
                    )
                }

                if (player != null) {
                    WatchBottomBar(
                        playerState = playerState,
                        isFullscreen = landscapeLocked,
                        onFullscreenChange = { landscapeLocked = it },
                        modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
                    )
                }
            }
        }
    }
}

@Composable
private fun WatchErrorPanel(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = message,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = onRetry) { Text("Retry") }
    }
}

/** Empty state when no addons are installed: links to the manager instead of failing. */
@Composable
private fun WatchNoAddonsPanel(
    onOpenAddons: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "No stream addons installed. Add one to watch this title.",
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = onOpenAddons) { Text("Open addon manager") }
        Button(onClick = onBack) { Text("Back") }
    }
}

@Composable
private fun LockScreenOrientation(orientation: Int) {
    val context = LocalContext.current
    DisposableEffect(orientation) {
        val activity = context.findActivity()
        val original = activity.requestedOrientation
        activity.requestedOrientation = orientation
        onDispose { activity.requestedOrientation = original }
    }
}

private fun Context.findActivity(): ComponentActivity {
    var context = this
    while (context is ContextWrapper) {
        if (context is ComponentActivity) return context
        context = context.baseContext
    }
    error("WatchScreen must be hosted in an Activity")
}
