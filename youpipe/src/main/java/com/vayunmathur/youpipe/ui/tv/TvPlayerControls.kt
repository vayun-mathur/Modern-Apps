package com.vayunmathur.youpipe.ui.tv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.tv.TvButton
import com.vayunmathur.library.tv.TvDialog
import com.vayunmathur.library.tv.TvIcon
import com.vayunmathur.library.tv.TvIconButton
import com.vayunmathur.library.tv.TvListRow
import com.vayunmathur.library.tv.TvSeekBar
import com.vayunmathur.library.ui.IconClosedCaption
import com.vayunmathur.library.ui.IconPause
import com.vayunmathur.library.ui.IconPlay
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.platform.CastPlayback
import com.vayunmathur.youpipe.platform.YouPipeViewModel

/** What the overlay's settings gear offers, with the current value of each. */
internal class TvPlayerSettings(
    val qualityLabel: String,
    val speed: Float,
    val hasChapters: Boolean,
    val hasLanguage: Boolean,
    val onQuality: () -> Unit,
    val onSpeed: () -> Unit,
    val onLanguage: () -> Unit,
    val onChapters: () -> Unit,
)

/**
 * Elapsed and total above a thin full-width bar, YouTube-style. Focusable: while it holds
 * focus the bar thickens and [TvDpadInput] turns LEFT/RIGHT into scrubbing.
 */
@Composable
internal fun TvPlayerSeekBar(ypvm: YouPipeViewModel, focusRequester: FocusRequester, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val videoState by ypvm.videoState.collectAsState()
    val categories by ypvm.sponsorBlockCategories.collectAsState()
    TvPlaybackClock { positionMs ->
        val transport by CastPlayback.transport.collectAsState()
        val duration = transport.durationMs
        val position = positionMs()
        val markers = remember(videoState.segments, videoState.sponsorSegments, categories, duration) {
            if (duration <= 0) {
                emptyList()
            } else {
                videoState.segments.map { it.time.toFloat() / duration } +
                    videoState.sponsorSegments.filter { it.category in categories }
                        .map { it.start.toFloat() / duration }
            }
        }
        Column(
            modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onFocusChanged {
                    focused = it.isFocused
                    TvDpadInput.seekFocused = it.isFocused
                }
                .focusable(),
        ) {
            Row(Modifier.fillMaxWidth()) {
                Text(formatTvElapsed(position), style = MaterialTheme.typography.titleSmall, color = Color.White)
                Box(Modifier.weight(1f))
                if (duration > 0) {
                    Text(formatTvElapsed(duration), style = MaterialTheme.typography.titleSmall, color = Color.White)
                }
            }
            TvSeekBar(
                progress = if (duration > 0) position.toFloat() / duration else 0f,
                buffered = if (duration > 0) transport.bufferedMs.toFloat() / duration else 0f,
                active = focused || TvDpadInput.scrubPreviewMs != null,
                markers = markers,
            )
        }
    }
}

/** The big round play/pause in the middle of the control row. */
@Composable
internal fun TvPlayPauseButton(modifier: Modifier = Modifier) {
    val transport by CastPlayback.transport.collectAsState()
    TvIconButton(
        onClick = {
            val player = CastPlayback.attachedPlayer ?: return@TvIconButton
            if (player.isPlaying) player.pause() else player.play()
        },
        size = 72.dp,
        icon = {
            Box(Modifier.size(32.dp)) {
                if (transport.playing) IconPause(Modifier.fillMaxSize()) else IconPlay(Modifier.fillMaxSize())
            }
        },
        modifier = modifier,
    )
}

/** The channel avatar as a round focusable button that opens the channel. */
@Composable
internal fun TvChannelAvatarButton(url: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(52.dp),
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(3.dp, MaterialTheme.colorScheme.inverseSurface), shape = CircleShape),
        ),
        colors = ClickableSurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (url.isNotEmpty()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

/** CC toggle-style button: tinted with the accent while captions are on. */
@Composable
internal fun TvCaptionsButton(on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TvIconButton(
        onClick = onClick,
        modifier = modifier,
        icon = {
            TvIcon {
                if (on) IconClosedCaption(tint = MaterialTheme.colorScheme.primary) else IconClosedCaption()
            }
        },
    )
}

/** The settings gear's menu: quality, speed, audio language, chapters - each with its value. */
@Composable
internal fun TvPlayerSettingsDialog(settings: TvPlayerSettings, onDismiss: () -> Unit) {
    fun pick(action: () -> Unit): () -> Unit = {
        onDismiss()
        action()
    }
    TvDialog(
        title = stringResource(UiR.string.settings),
        onDismissRequest = onDismiss,
        content = {
            Column {
                TvListRow(
                    headline = stringResource(R.string.label_quality),
                    supporting = settings.qualityLabel,
                    onClick = pick(settings.onQuality),
                )
                TvListRow(
                    headline = stringResource(R.string.label_speed),
                    supporting = formatTvSpeedLabel(settings.speed),
                    onClick = pick(settings.onSpeed),
                )
                if (settings.hasLanguage) {
                    TvListRow(headline = stringResource(R.string.language), onClick = pick(settings.onLanguage))
                }
                if (settings.hasChapters) {
                    TvListRow(
                        headline = stringResource(R.string.tv_section_chapters),
                        onClick = pick(settings.onChapters),
                    )
                }
            }
        },
        buttons = { TvButton(text = stringResource(UiR.string.close), onClick = onDismiss) },
    )
}

/**
 * Owns the one per-frame clock the player overlay needs and hands it down as a lambda, so only
 * the readers invalidate sixty times a second. Stops ticking while paused.
 */
@Composable
internal fun TvPlaybackClock(content: @Composable (positionMs: () -> Long) -> Unit) {
    val transport by CastPlayback.transport.collectAsState()
    var frameNowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(transport.positionMs, transport.playing) {
        frameNowMs = System.currentTimeMillis()
        if (!transport.playing) return@LaunchedEffect
        while (true) {
            withFrameMillis { frameNowMs = System.currentTimeMillis() }
        }
    }
    content { TvDpadInput.scrubPreviewMs ?: transport.positionMs }
}

/** `H:MM:SS` past an hour, `M:SS` below it. */
internal fun formatTvElapsed(positionMs: Long): String {
    val totalSeconds = (positionMs / 1_000).coerceAtLeast(0)
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}
