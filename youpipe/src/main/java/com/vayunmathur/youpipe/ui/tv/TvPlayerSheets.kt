package com.vayunmathur.youpipe.ui.tv

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.image.ImageRequest
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.tv.TvButton
import com.vayunmathur.library.tv.TvInitialFocus
import com.vayunmathur.library.tv.TvListRow
import com.vayunmathur.library.tv.TvScreenHeader
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.platform.CastPlayback
import com.vayunmathur.youpipe.ui.AudioStream
import com.vayunmathur.youpipe.ui.SubtitleTrack
import com.vayunmathur.youpipe.ui.VideoChapter
import com.vayunmathur.youpipe.ui.VideoStream

/**
 * D-pad option sheets for the TV player: chapters, quality, language, captions, speed.
 * One focusable [TvListRow] per choice with a check on the current — replacing the phone
 * dropdowns and the old raw tv-material sheets. Each sheet takes initial focus on its
 * close button; BACK dismisses via [TvDpadInput].
 */

/** D-pad chapter list: focus a chapter, CENTER/ENTER seeks. Replaces the phone bottom sheet. */
@Composable
internal fun TvChapterSheet(
    segments: List<VideoChapter>,
    onSeekTo: (Long) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val transport by CastPlayback.transport.collectAsState()
    TvOptionSheet(title = stringResource(R.string.chapter), onDismiss = onDismiss, modifier = modifier) {
        items(segments, key = { it.time }) { chapter ->
            val index = segments.indexOf(chapter)
            val isCurrent = transport.positionMs >= chapter.time &&
                (segments.getOrNull(index + 1)?.time?.let { transport.positionMs < it } ?: true)
            TvListRow(
                headline = chapter.title,
                supporting = DateUtils.formatElapsedTime(chapter.time.toLong() / MS_PER_SECOND),
                leading = if (!chapter.previewURL.isNullOrEmpty()) {
                    { TvChapterPreview(url = chapter.previewURL!!, time = chapter.time) }
                } else {
                    null
                },
                trailing = if (isCurrent) {
                    { IconCheck() }
                } else {
                    null
                },
                selected = isCurrent,
                onClick = { onSeekTo(chapter.time.toLong()); onDismiss() },
            )
        }
    }
}

@Composable
private fun TvChapterPreview(url: String, time: Int) {
    val context = LocalContext.current
    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(url)
            .memoryCacheKey("chapter-$time")
            .build(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
    )
}

/** D-pad quality list: one focusable row per stream. Replaces the phone dropdown. */
@Composable
internal fun TvQualitySheet(
    streams: List<VideoStream>,
    current: VideoStream,
    onSelect: (VideoStream) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TvOptionSheet(title = stringResource(R.string.label_quality), onDismiss = onDismiss, modifier = modifier) {
        items(streams, key = { it.quality + it.height }) { stream ->
            TvListRow(
                headline = stream.quality,
                trailing = if (stream == current) {
                    { IconCheck() }
                } else {
                    null
                },
                selected = stream == current,
                onClick = { onSelect(stream) },
            )
        }
    }
}

/** D-pad caption list: off + one row per track. Replaces the phone dropdown. */
@Composable
internal fun TvCaptionSheet(
    tracks: List<SubtitleTrack>,
    selected: SubtitleTrack?,
    onSelect: (SubtitleTrack?) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TvOptionSheet(title = stringResource(R.string.label_captions), onDismiss = onDismiss, modifier = modifier) {
        item {
            TvListRow(
                headline = stringResource(R.string.label_captions_off),
                trailing = if (selected == null) {
                    { IconCheck() }
                } else {
                    null
                },
                selected = selected == null,
                onClick = { onSelect(null) },
            )
        }
        items(tracks, key = { it.languageTag + it.url }) { track ->
            TvListRow(
                headline = track.displayName,
                trailing = if (track == selected) {
                    { IconCheck() }
                } else {
                    null
                },
                selected = track == selected,
                onClick = { onSelect(track) },
            )
        }
    }
}

/** D-pad speed list: the same range the phone offers, nothing the phone cannot do. */
@Composable
internal fun TvSpeedSheet(
    current: Float,
    onSelect: (Float) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TvOptionSheet(title = stringResource(R.string.label_speed), onDismiss = onDismiss, modifier = modifier) {
        items(TV_SPEEDS, key = { it }) { speed ->
            TvListRow(
                headline = formatTvSpeedLabel(speed),
                trailing = if (speed == current) {
                    { IconCheck() }
                } else {
                    null
                },
                selected = speed == current,
                onClick = { onSelect(speed) },
            )
        }
    }
}

/**
 * D-pad audio-language list: one focusable row per language, display name with code
 * fallback. Replaces the phone dropdown in `ui/VideoPlayerTopControls.kt`.
 *
 * Entries mirror the phone's `languageEntries` (code to displayName-or-code), and the
 * caller's best-opus-per-language rule is untouched — this sheet only picks the code.
 */
@Composable
internal fun TvLanguageSheet(
    entries: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TvOptionSheet(title = stringResource(R.string.language), onDismiss = onDismiss, modifier = modifier) {
        items(entries, key = { it.first }) { (code, display) ->
            TvListRow(
                headline = display,
                trailing = if (code == selected) {
                    { IconCheck() }
                } else {
                    null
                },
                selected = code == selected,
                onClick = { onSelect(code) },
            )
        }
    }
}

/** Shared sheet frame: header + close, focus starts on close, BACK dismisses. */
@Composable
internal fun TvOptionSheet(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    TvInitialFocus(focusRequester)
    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.9f)),
    ) {
        Column(Modifier.fillMaxSize()) {
            TvScreenHeader(
                title = title,
                actions = {
                    TvButton(
                        text = stringResource(UiR.string.close),
                        onClick = onDismiss,
                    )
                },
            )
            LazyColumn(
                state = rememberLazyListState(),
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 48.dp,
                    end = 48.dp,
                    bottom = 44.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content,
            )
        }
    }
}

internal val TV_SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

internal fun formatTvSpeedLabel(speed: Float): String =
    if (speed % 1f == 0f) "${speed.toInt()}x" else "${speed}x"

private const val MS_PER_SECOND = 1000L
