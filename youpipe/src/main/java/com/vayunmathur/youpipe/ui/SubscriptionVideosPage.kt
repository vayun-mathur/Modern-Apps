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

/**
 * Binder for the subscription feed: newest uploads across every channel the user follows,
 * or across one category of them.
 */
@Composable
fun SubscriptionVideosPage(
    backStack: NavBackStack<Route>,
    youPipeViewModel: YouPipeViewModel,
    category: String?,
) {
    val videos by remember(category) { youPipeViewModel.subscriptionVideosFor(category) }
        .collectAsState(initial = emptyList())
    val fetchProgress by youPipeViewModel.fetchProgress.collectAsState()
    val hasLoaded by youPipeViewModel.hasLoadedSubscriptionVideos.collectAsState()

    val history by youPipeViewModel.historyVideos.collectAsState()
    val deArrowEnabled by youPipeViewModel.deArrowEnabled.collectAsState()
    val deArrowCache by youPipeViewModel.deArrowCache.collectAsState()
    val progressById = remember(history) { history.associate { it.id to it.progress } }
    val context = LocalContext.current

    // Map the raw feed to render-ready rows only when an input actually changes,
    // rather than on every recomposition.
    val rows = remember(videos, deArrowEnabled, deArrowCache, progressById) {
        videos.map { video ->
            val deArrow = if (deArrowEnabled) deArrowCache[video.videoID] else null
            val watched = progressById[video.videoID] ?: 0L
            videoRowState(
                context = context,
                videoInfo = video,
                showAuthor = true,
                percentWatched = if (video.duration > 0) (watched.toDouble() / video.duration).toFloat() else 0f,
                deArrowTitle = deArrow?.title,
                deArrowThumbnailURL = deArrow?.thumbnailUrl,
            )
        }
    }

    SubscriptionVideosScreen(
        state = SubscriptionFeedUiState(
            videos = rows,
            fetchProgress = fetchProgress,
            isLoading = !hasLoaded,
        ),
        actions = object : SubscriptionFeedActions {
            override fun openVideo(videoID: Long) {
                backStack.add(Route.VideoPage(videoID))
            }
        },
    )
}

/**
 * Stateless subscription feed. Taps on the list itself go through [actions].
 */

