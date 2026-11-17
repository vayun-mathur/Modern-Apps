package com.vayunmathur.youpipe.ui
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.adaptiveGridCells
import com.vayunmathur.library.ui.invisibleClickable
import com.vayunmathur.library.ui.isExpandedWidth
import com.vayunmathur.youpipe.platform.VideoRowState


/**
 * One video in a feed grid: a thumbnail stacked over its title and stats, opening [openVideo].
 *
 * The compact feed keeps calling [VideoRow] directly so its shared-element title key stays
 * single-origin (see the `titleSharedKey` note there); the grid is only ever composed on
 * expanded widths, so keying its own rows cannot collide with the phone list.
 */
@Composable
fun FeedVideoRow(
    row: VideoRowState,
    openVideo: (Long) -> Unit,
    modifier: Modifier = Modifier,
    titleSharedKey: Any? = null,
    overflowActions: List<Pair<String, () -> Unit>> = emptyList(),
) {
    VideoRow(
        row = row,
        modifier = modifier.invisibleClickable { openVideo(row.videoID) },
        overflowActions = overflowActions,
        titleSharedKey = titleSharedKey,
        vertical = true,
    )
}
