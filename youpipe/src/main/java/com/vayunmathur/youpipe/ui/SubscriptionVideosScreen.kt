package com.vayunmathur.youpipe.ui
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.ui.LinearProgressIndicator
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.adaptiveGridCells
import com.vayunmathur.library.ui.isExpandedWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.invisibleClickable
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.Route
import com.vayunmathur.youpipe.platform.SubscriptionFeedActions
import com.vayunmathur.youpipe.platform.SubscriptionFeedUiState
import com.vayunmathur.youpipe.platform.YouPipeViewModel

private const val SKELETON_ROW_COUNT = 8

@Composable
fun SubscriptionVideosScreen(
    state: SubscriptionFeedUiState,
    actions: SubscriptionFeedActions,
) {
// RAW SCAFFOLD EXCEPTION: the feed switches between a LazyColumn (compact) and a
// LazyVerticalGrid of adaptiveGridCells (expanded) around the same skeleton/loading
// content. No shared scaffold models a column/grid switch.
    Scaffold { paddingValues ->
        if (isExpandedWidth() && !state.isLoading && state.fetchProgress !in 0f..1f) {
            // Desktop: the feed fills the window with an adaptive grid instead of
            // stretching one column across it. Loading states stay a list below.
            LazyVerticalGrid(
                columns = adaptiveGridCells(320.dp),
                modifier = Modifier.padding(paddingValues),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.videos, key = { it.videoID }) { row ->
                    FeedVideoRow(
                        row = row,
                        openVideo = actions::openVideo,
                        titleSharedKey = "youpipe-video-title-${row.videoID}",
                    )
                }
            }
            return@Scaffold
        }
        LazyColumn(Modifier.padding(paddingValues)) {
            if (state.fetchProgress in 0f..1f) {
                item {
                    LinearProgressIndicator({ state.fetchProgress }, modifier = Modifier.fillMaxWidth().padding(16.dp))
                }
            }
            if (state.isLoading) {
                // Skeleton placeholders shown before the first DB emission arrives,
                // so the screen never looks frozen while the feed is loading.
                items(SKELETON_ROW_COUNT) {
                    VideoRowSkeleton()
                }
            } else {
                items(state.videos, key = { it.videoID }) { row ->
                    VideoRow(
                        row = row,
                        modifier = Modifier.invisibleClickable { actions.openVideo(row.videoID) },
                        titleSharedKey = "youpipe-video-title-${row.videoID}",
                    )
                }
            }
        }
    }
}
