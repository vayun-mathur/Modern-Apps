package com.vayunmathur.youpipe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.image.ImageRequest
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconMoreVert
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.invisibleClickable
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.sharedText
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.Route
import com.vayunmathur.youpipe.platform.VideoRowState
import com.vayunmathur.youpipe.platform.YouPipeViewModel
import com.vayunmathur.youpipe.network.decodeHtml

/**
 * Resolve [videoInfo] into the display state [VideoRow] draws.
 *
 * Deliberately not a `@Composable`: the two counts it formats need a Context, not a
 * composition, and a screen that maps a whole list at once should not have to do it inside
 * a composable loop.
 */
fun videoRowState(
    context: android.content.Context,
    videoInfo: VideoInfo,
    showAuthor: Boolean,
    reason: String? = null,
    percentWatched: Float = 0f,
    deArrowTitle: String? = null,
    deArrowThumbnailURL: String? = null,
): VideoRowState = VideoRowState(
    videoID = videoInfo.videoID,
    title = (deArrowTitle ?: videoInfo.name).decodeHtml(),
    thumbnailURL = deArrowThumbnailURL ?: videoInfo.thumbnailURL,
    author = videoInfo.author.decodeHtml().takeIf { showAuthor },
    stats = if (videoInfo.isPaid || videoInfo.views < 0) {
        context.getString(
            R.string.video_stat_paid_format,
            uploadTimeAgo(context, videoInfo.uploadDate),
        )
    } else {
        context.getString(
            R.string.video_stat_format,
            countString(context, videoInfo.views),
            uploadTimeAgo(context, videoInfo.uploadDate),
        )
    },
    reason = reason,
    channelKey = videoInfo.author.lowercase(),
    percentWatched = percentWatched,
    deArrowThumbnail = deArrowThumbnailURL != null,
)

/**
 * One video row: thumbnail with a resume bar on the left, title and stats on the right.
 *
 * Stateless — every value it draws is already a string, which is what lets the store listing
 * previews render it. The click target arrives through [modifier] so the caller keeps the
 * choice between a rippled and a rippleless tap.
 *
 * Set [vertical] on expanded widths to stack the text under the thumbnail for grid cells
 * instead of beside it (see [FeedVideoRow]).
 */
@Composable
fun VideoRow(
    row: VideoRowState,
    modifier: Modifier = Modifier,
    trailingContent: @Composable (() -> Unit)? = null,
    overflowActions: List<Pair<String, () -> Unit>> = emptyList(),
    /**
     * Morphs this row's title into the title on the video screen. Passed in per call site rather
     * than derived from [row] here, because a row is drawn by eight of them: the home feed and the
     * search overlay are on screen together, and the tab pager has two feeds composed mid-swipe, so
     * keying every row would put the same video under one key twice with no single origin to travel
     * from. Null on any list that shares a composition with another keyed one.
     */
    titleSharedKey: Any? = null,
    vertical: Boolean = false,
) {
    val effectiveTrailing: (@Composable () -> Unit)? = when {
        overflowActions.isNotEmpty() -> {
            { VideoOverflowMenu(overflowActions) }
        }
        else -> trailingContent
    }

    if (vertical) {
        Column(modifier) {
            VideoThumbnail(
                row = row,
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp),
            )
            ListItem(modifier = Modifier, overlineContent = {

            }, supportingContent = {
                VideoSupportingText(row)
            }, trailingContent = effectiveTrailing?.let { { it() } }, colors = ListItemDefaults.colors(containerColor = Color.Transparent)) {
                Text(
                    row.title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = if (titleSharedKey == null) Modifier else Modifier.sharedText(titleSharedKey),
                )
            }
        }
        return
    }

    Row(modifier) {
        Box(Modifier.weight(1f)) {
            VideoThumbnail(
                row = row,
                modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
            )
        }
        Box(Modifier.weight(1.5f)) {
            ListItem(modifier = Modifier, overlineContent = {

            }, supportingContent = {
                VideoSupportingText(row)
            }, trailingContent = effectiveTrailing?.let { { it() } }, colors = ListItemDefaults.colors(containerColor = Color.Transparent)) {
                Text(
                    row.title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = if (titleSharedKey == null) Modifier else Modifier.sharedText(titleSharedKey),
                )
            }
        }
    }
}

/** The thumbnail block [VideoRow] draws, with the resume bar across its bottom edge. */
@Composable
private fun VideoThumbnail(row: VideoRowState, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(12.dp))) {
        // Block behind the thumbnail: what the row shows while the image is still in
        // flight, and all a preview can show at all — Layoutlib has no network.
        Box(
            Modifier.fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
        if (row.thumbnailURL.isNotEmpty()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(row.thumbnailURL)
                    .memoryCacheKey("video-thumb-${row.videoID}-${if (row.deArrowThumbnail) "da" else "yt"}")
                    .build(),
                contentDescription = null,
                Modifier.fillMaxWidth().aspectRatio(16f / 9f)
            )
        }
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))) {
            if(row.percentWatched > 0f)
                Surface(Modifier.weight(row.percentWatched).height(6.dp), color = Color.Red.copy(alpha = 0.6f)) {}
            if(row.percentWatched < 1f)
                Surface(Modifier.weight(1f - row.percentWatched).height(6.dp), color = Color.Black.copy(alpha = 0.8f)) {}
        }
    }
}

/** The author/stats/reason lines under [VideoRow]'s title. */
@Composable
private fun VideoSupportingText(row: VideoRowState) {
    Column {
        val author = row.author
        if(author != null) {
            Text(author, style = MaterialTheme.typography.bodySmall)
        }
        Text(row.stats, style = MaterialTheme.typography.bodySmall)
        val reason = row.reason
        if (reason != null) {
            Text(
                stringResource(R.string.recommendation_reason, reason),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun VideoOverflowMenu(actions: List<Pair<String, () -> Unit>>) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            IconMoreVert()
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            actions.forEach { (label, onClick) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        expanded = false
                        onClick()
                    },
                )
            }
        }
    }
}
