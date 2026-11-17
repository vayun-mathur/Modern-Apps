package com.vayunmathur.youpipe.ui.tv

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.image.ImageRequest
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.tv.TvButton
import com.vayunmathur.library.tv.TvDialog
import com.vayunmathur.library.tv.TvGrid
import com.vayunmathur.library.tv.TvInitialFocus
import com.vayunmathur.library.tv.TvListRow
import com.vayunmathur.library.tv.TvMediaCard
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.ui.VideoInfo
import com.vayunmathur.youpipe.ui.videoRowState
import com.vayunmathur.youpipe.platform.VideoRowState
import com.vayunmathur.youpipe.platform.YouPipeViewModel
import com.vayunmathur.youpipe.platform.blockChannel
import com.vayunmathur.youpipe.platform.boostChannel
import com.vayunmathur.youpipe.platform.pinChannel
import com.vayunmathur.youpipe.platform.removeInterest

/**
 * Shared TV presentation helpers for YouPipe: the one video-card mapping and the 4-column
 * browse grid. Every TV screen builds its rows through [rememberTvVideoRows] so DeArrow,
 * history progress and the recommendation reason map identically everywhere.
 */
internal data class TvVideoRow(val row: VideoRowState, val info: VideoInfo)

/**
 * Map videos to display rows with the phone's [videoRowState] mapping (DeArrow, history
 * progress, stats, reason) — the shared binder every TV screen reads.
 *
 * Duration is seconds here; [historyProgressMsById] is milliseconds, so progress converts
 * before dividing (the old TV code divided ms by seconds and pinned every bar full).
 */
@Composable
internal fun rememberTvVideoRows(
    videos: List<VideoInfo>,
    ypvm: YouPipeViewModel,
    historyProgressMsById: Map<Long, Long>,
    showAuthor: Boolean = true,
    reasonsById: Map<Long, String> = emptyMap(),
): List<TvVideoRow> {
    val context = LocalContext.current
    val deArrowEnabled by ypvm.deArrowEnabled.collectAsState()
    val deArrowCache by ypvm.deArrowCache.collectAsState()
    return remember(videos, deArrowEnabled, deArrowCache, historyProgressMsById, showAuthor, reasonsById) {
        videos.map { video ->
            val deArrow = if (deArrowEnabled) deArrowCache[video.videoID] else null
            val watchedMs = historyProgressMsById[video.videoID] ?: 0L
            val row = videoRowState(
                context = context,
                videoInfo = video,
                showAuthor = showAuthor,
                reason = reasonsById[video.videoID],
                percentWatched = if (video.duration > 0) {
                    (watchedMs / 1_000.0 / video.duration).toFloat().coerceIn(0f, 1f)
                } else {
                    0f
                },
                deArrowTitle = deArrow?.title,
                deArrowThumbnailURL = deArrow?.thumbnailUrl,
            )
            TvVideoRow(row, video)
        }
    }
}

/** Duration badge text: `M:SS` / `H:MM:SS`, or null for live/unknown lengths. */
internal fun tvDurationBadge(durationSec: Long): String? =
    if (durationSec > 0) DateUtils.formatElapsedTime(durationSec) else null

/** One-line subtitle under a video card: "channel · views · age" plus the Why reason. */
internal fun tvVideoSubtitle(item: TvVideoRow): String? {
    val base = listOfNotNull(item.row.author, item.row.stats.takeIf { it.isNotBlank() })
        .joinToString("  ·  ").takeIf { it.isNotBlank() }
    val reason = item.row.reason?.takeIf { it.isNotBlank() }
    return when {
        base != null && reason != null -> "$base  ·  Why: $reason"
        base != null -> base
        reason != null -> "Why: $reason"
        else -> null
    }
}

/**
 * The one TV video card: 16:9 artwork with duration badge + resume strip, title/subtitle
 * below the art. Fills its grid cell. Training actions never live here — they belong to
 * the options dialog.
 */
@Composable
internal fun TvVideoCard(
    item: TvVideoRow,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    onFocus: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    TvMediaCard(
        onClick = onOpen,
        onLongClick = onLongClick,
        onFocus = onFocus,
        modifier = modifier,
        title = item.row.title,
        subtitle = tvVideoSubtitle(item),
        progress = item.row.percentWatched.takeIf { it > 0f },
        badge = tvDurationBadge(item.info.duration),
        image = {
            if (item.row.thumbnailURL.isNotEmpty()) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(item.row.thumbnailURL)
                        .memoryCacheKey(
                            "video-thumb-${item.row.videoID}-" +
                                if (item.row.deArrowThumbnail) "da" else "yt",
                        )
                        .build(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        },
    )
}

/**
 * A 4-column grid of [TvVideoCard]s with initial focus on the first card. Grids are only
 * for browse/library pages; home builds its own [TvGrid] with section headers.
 */
@Composable
internal fun TvVideoGrid(
    backStack: NavBackStack<TvRoute>,
    rows: List<TvVideoRow>,
    modifier: Modifier = Modifier,
    columns: Int = TV_GRID_COLUMNS,
) {
    val firstFocus = remember { FocusRequester() }
    TvInitialFocus(firstFocus)
    TvGrid(columns = columns, modifier = modifier) {
        tvVideoItems(backStack, rows, firstFocus)
    }
}

/** Grid items for [TvVideoGrid]; extracted so empty-state callers share the same mapping. */
internal fun LazyGridScope.tvVideoItems(
    backStack: NavBackStack<TvRoute>,
    rows: List<TvVideoRow>,
    firstFocus: FocusRequester,
) {
    items(rows.size, key = { rows[it].row.videoID }) { index ->
        TvVideoCard(
            item = rows[index],
            onOpen = { backStack.playVideo(rows[index].row.videoID) },
            modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
        )
    }
}

/**
 * A [TvVideoCard] that doubles as a selection toggle: CENTER opens outside selection mode
 * and toggles inside it; long-press always toggles. Selected cards get the accent badge.
 */
@Composable
internal fun TvSelectableCard(
    item: TvVideoRow,
    selected: Boolean,
    inSelectionMode: Boolean,
    onOpen: () -> Unit,
    onToggleSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    TvMediaCard(
        onClick = { if (inSelectionMode) onToggleSelect() else onOpen() },
        onLongClick = onToggleSelect,
        modifier = modifier,
        title = item.row.title,
        subtitle = tvVideoSubtitle(item),
        progress = item.row.percentWatched.takeIf { it > 0f },
        badge = if (selected) {
            "✓"
        } else {
            tvDurationBadge(item.info.duration)
        },
        image = {
            if (item.row.thumbnailURL.isNotEmpty()) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(item.row.thumbnailURL)
                        .memoryCacheKey(
                            "video-thumb-${item.row.videoID}-" +
                                if (item.row.deArrowThumbnail) "da" else "yt",
                        )
                        .build(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        },
    )
}

/**
 * The recommendation-training dialog: not-interested / more-like / pin / block for one
 * video's channel. Opened via long-press AND a visible "More" button on the video page —
 * never buttons stacked inside cards.
 */
@Composable
internal fun TvVideoOptionsDialog(
    item: TvVideoRow,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    ypvm: YouPipeViewModel,
) {
    TvDialog(
        title = item.row.title,
        onDismissRequest = onDismiss,
        body = tvVideoSubtitle(item),
        buttons = {
            TvButton(
                text = stringResource(com.vayunmathur.library.ui.R.string.play),
                onClick = {
                    onDismiss()
                    onOpen()
                },
                primary = true,
            )
            TvButton(
                text = stringResource(com.vayunmathur.library.ui.R.string.close),
                onClick = onDismiss,
            )
        },
        content = {
            Column {
                TvListRow(
                    headline = stringResource(R.string.action_not_interested),
                    supporting = item.row.reason?.let {
                        stringResource(R.string.recommendation_reason, it)
                    },
                    onClick = {
                        ypvm.removeInterest(channelKey = item.row.channelKey)
                        onDismiss()
                    },
                )
                TvListRow(
                    headline = stringResource(R.string.action_more_like_this),
                    onClick = {
                        ypvm.boostChannel(item.row.channelKey)
                        onDismiss()
                    },
                )
                TvListRow(
                    headline = stringResource(R.string.action_pin_channel),
                    onClick = {
                        ypvm.pinChannel(item.row.channelKey)
                        onDismiss()
                    },
                )
                TvListRow(
                    headline = stringResource(R.string.action_block_channel),
                    onClick = {
                        ypvm.blockChannel(item.row.channelKey)
                        onDismiss()
                    },
                )
            }
        },
    )
}

/**
 * Constrain settings content to a readable max width instead of stretching across the TV.
 * Screens put their header + rows inside [content].
 */
@Composable
internal fun TvSettingsBody(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 900.dp).fillMaxWidth()) {
            content()
        }
    }
}

internal const val TV_GRID_COLUMNS = 4
