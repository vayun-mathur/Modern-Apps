package com.vayunmathur.uniflix.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.IconArrowDropDown
import com.vayunmathur.library.ui.IconBack
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.IconFullscreen
import com.vayunmathur.library.ui.IconFullscreenExit
import com.vayunmathur.library.ui.IconLanguage
import com.vayunmathur.library.ui.IconPause
import com.vayunmathur.library.ui.IconPlay
import com.vayunmathur.library.ui.IconRotateLeft
import com.vayunmathur.library.ui.IconRotateRight
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.SelectableDropdownMenuItem
import com.vayunmathur.library.ui.Slider
import com.vayunmathur.library.ui.SliderDefaults
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.uniflix.domain.SubtitleTrack

internal const val SKIP_MS = 10_000L

private fun Modifier.chipModifier(): Modifier =
    background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))

internal fun formatWatchTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0L)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "$minutes:${seconds.toString().padStart(2, '0')}"
    }
}

private fun resolutionLabel(height: Int): String = when {
    height >= 2100 -> "4K"
    height >= 1000 -> "1080p"
    height >= 680 -> "720p"
    height >= 460 -> "480p"
    else -> "${height}p"
}

/**
 * The top overlay: back, title and the picker chips drawn over the video.
 *
 * Resolution constrains the HLS ABR ladder via track selection (Auto clears
 * the constraint); subtitles attach the provider's sidecar tracks and pick by
 * language; the server chip re-resolves against another healthy server.
 * Any open menu is reported via [onAnyMenuOpen] so auto-hide stands down.
 */
@Composable
internal fun WatchTopBar(
    title: String,
    serverLabel: String,
    servers: List<String>,
    onSelectServer: (String) -> Unit,
    videoHeights: List<Int>,
    selectedHeight: Int?,
    onSelectHeight: (Int?) -> Unit,
    subtitles: List<SubtitleTrack>,
    selectedSubtitle: SubtitleTrack?,
    onSelectSubtitle: (SubtitleTrack?) -> Unit,
    onBack: () -> Unit,
    onAnyMenuOpen: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var resolutionMenu by remember { mutableStateOf(false) }
    var subtitleMenu by remember { mutableStateOf(false) }
    var serverMenu by remember { mutableStateOf(false) }
    LaunchedEffect(resolutionMenu, subtitleMenu, serverMenu) {
        onAnyMenuOpen(resolutionMenu || subtitleMenu || serverMenu)
    }

    Row(
        modifier = modifier
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.chipModifier()) {
            IconBack(tint = Color.White)
        }
        Text(
            text = title,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )

        PickerChip(
            label = selectedHeight?.let(::resolutionLabel) ?: "Auto",
            onClick = { resolutionMenu = true },
        ) {
            DropdownMenu(expanded = resolutionMenu, onDismissRequest = { resolutionMenu = false }) {
                SelectableDropdownMenuItem(
                    selected = selectedHeight == null,
                    onClick = { onSelectHeight(null); resolutionMenu = false },
                    text = { Text("Auto") },
                    selectedLeadingIcon = { IconCheck() },
                )
                videoHeights.forEach { height ->
                    SelectableDropdownMenuItem(
                        selected = selectedHeight == height,
                        onClick = { onSelectHeight(height); resolutionMenu = false },
                        text = { Text(resolutionLabel(height)) },
                        selectedLeadingIcon = { IconCheck() },
                    )
                }
            }
        }

        PickerChip(
            label = selectedSubtitle?.displayName?.ifBlank { selectedSubtitle.language } ?: "CC",
            leadingIcon = { IconLanguage(tint = Color.White, modifier = Modifier.size(16.dp)) },
            onClick = { subtitleMenu = true },
        ) {
            DropdownMenu(expanded = subtitleMenu, onDismissRequest = { subtitleMenu = false }) {
                SelectableDropdownMenuItem(
                    selected = selectedSubtitle == null,
                    onClick = { onSelectSubtitle(null); subtitleMenu = false },
                    text = { Text("Off") },
                    selectedLeadingIcon = { IconCheck() },
                )
                subtitles.forEach { sub ->
                    SelectableDropdownMenuItem(
                        selected = selectedSubtitle?.url == sub.url,
                        onClick = { onSelectSubtitle(sub); subtitleMenu = false },
                        text = { Text(sub.displayName.ifBlank { sub.language }) },
                        selectedLeadingIcon = { IconCheck() },
                    )
                }
            }
        }

        if (servers.size > 1) {
            PickerChip(
                label = serverLabel,
                onClick = { serverMenu = true },
            ) {
                DropdownMenu(expanded = serverMenu, onDismissRequest = { serverMenu = false }) {
                    servers.forEach { server ->
                        SelectableDropdownMenuItem(
                            selected = server.equals(serverLabel, ignoreCase = true),
                            onClick = { onSelectServer(server); serverMenu = false },
                            text = { Text(server) },
                            selectedLeadingIcon = { IconCheck() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerChip(
    label: String,
    onClick: () -> Unit,
    leadingIcon: @Composable (() -> Unit)? = null,
    menu: @Composable () -> Unit,
) {
    Box {
        Surface(
            onClick = onClick,
            color = Color.Black.copy(alpha = 0.5f),
            shape = RoundedCornerShape(4.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                leadingIcon?.invoke()
                Text(text = label, color = Color.White, style = MaterialTheme.typography.labelMedium)
                IconArrowDropDown(tint = Color.White)
            }
        }
        menu()
    }
}

/** The bottom overlay: seeker with buffered track, transport and fullscreen. */
@Composable
internal fun WatchBottomBar(
    playerState: WatchPlayerState,
    isFullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val duration = playerState.durationMs
    Column(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatWatchTime(playerState.positionMs),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                text = formatWatchTime(duration),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Spacer(Modifier.height(2.dp))
        Box(contentAlignment = Alignment.CenterStart) {
            Slider(
                value = if (duration > 0) playerState.bufferedMs.toFloat() else 0f,
                onValueChange = { },
                valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
                colors = SliderDefaults.colors(
                    thumbColor = Color.Transparent,
                    activeTrackColor = Color.White.copy(alpha = 0.3f),
                    inactiveTrackColor = Color.Transparent,
                ),
            )
            Slider(
                value = if (duration > 0) playerState.positionMs.toFloat() else 0f,
                onValueChange = {
                    playerState.isDragging = true
                    playerState.positionMs = it.toLong()
                },
                onValueChangeFinished = {
                    playerState.seekTo(playerState.positionMs)
                    playerState.isDragging = false
                },
                valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = Color.White.copy(alpha = 0.2f),
                ),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { playerState.skipBy(-SKIP_MS) }) {
                IconRotateLeft(tint = Color.White, modifier = Modifier.size(28.dp))
            }
            IconButton(
                onClick = { playerState.togglePlay() },
                modifier = Modifier.padding(horizontal = 12.dp),
            ) {
                if (playerState.isPlaying) {
                    IconPause(tint = Color.White, modifier = Modifier.size(40.dp))
                } else {
                    IconPlay(tint = Color.White, modifier = Modifier.size(40.dp))
                }
            }
            IconButton(onClick = { playerState.skipBy(SKIP_MS) }) {
                IconRotateRight(tint = Color.White, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { onFullscreenChange(!isFullscreen) }) {
                if (isFullscreen) {
                    IconFullscreenExit(tint = Color.White)
                } else {
                    IconFullscreen(tint = Color.White)
                }
            }
        }
    }
}
