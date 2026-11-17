package com.vayunmathur.youpipe.ui
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.IconThumbDown
import com.vayunmathur.library.ui.IconThumbUp
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.adaptiveGridCells
import com.vayunmathur.library.ui.invisibleClickable
import com.vayunmathur.library.ui.isExpandedWidth
import com.vayunmathur.youpipe.platform.VideoRowState


@Composable
fun RelatedVideosSection(relatedVideos: List<VideoRowState>, onOpenVideo: (Long) -> Unit) {
    if (isExpandedWidth()) {
        // The side-by-side pane is narrow, but on a very wide window it still fits two
        // stacked cards across — so this also adapts rather than fixing one column.
        LazyVerticalGrid(
            columns = adaptiveGridCells(280.dp),
            contentPadding = PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(relatedVideos, { it.videoID }) { row ->
                VideoRow(
                    row = row,
                    modifier = Modifier.invisibleClickable { onOpenVideo(row.videoID) },
                    vertical = true,
                )
            }
        }
        return
    }
    LazyColumn(contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(relatedVideos, { it.videoID }) { row ->
            VideoRow(row, Modifier.invisibleClickable { onOpenVideo(row.videoID) })
        }
    }
}
