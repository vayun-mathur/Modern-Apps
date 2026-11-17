package com.vayunmathur.youpipe.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.SecondaryTabRow
import com.vayunmathur.library.ui.Tab
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.VerticalDivider
import com.vayunmathur.library.ui.isExpandedWidth
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.platform.VideoDetailActions
import com.vayunmathur.youpipe.platform.VideoDetailUiState
import kotlinx.coroutines.launch

/**
 * The video screen with the player supplied as a slot.
 *
 * Stateless, so the store listing preview can render it — which is also why the player is a
 * slot rather than a call: Layoutlib cannot draw an ExoPlayer surface, so a preview simply
 * leaves it out.
 *
 * On expanded widths (large tablets, ChromeOS, desktop) the tabs become a side-by-side
 * two-pane: player, details and description scroll in the left pane while comments and
 * related fill the right pane.
 */
// RAW SCAFFOLD EXCEPTION: the screen pairs a player slot with a 3-tab pager
// (comments/related/description) that becomes a side-by-side two-pane on expanded
// widths. No shared scaffold models a player slot plus this tabbed detail content.
@Composable
fun VideoDetailScreen(
    state: VideoDetailUiState,
    actions: VideoDetailActions,
    fullscreen: Boolean = false,
    /** Which of comments / related / description opens first. A seam for the previews. */
    initialTab: Int = 0,
    /** Morphs the title out of the feed row the user tapped. Null when nothing morphs into it. */
    titleSharedKey: Any? = null,
    player: @Composable () -> Unit = {},
) {
    Scaffold { paddingValues ->
        val modifier = if(fullscreen) Modifier.padding(top = paddingValues.calculateTopPadding(), bottom = paddingValues.calculateBottomPadding()) else Modifier.padding(paddingValues)
        Column(modifier) {
            if (state.loaded) {
                if (!fullscreen && isExpandedWidth()) {
                    // Desktop: player, details and description scroll in the left pane;
                    // comments and related tabs fill the right pane.
                    Row(Modifier.fillMaxSize()) {
                        Column(
                            Modifier.weight(0.62f).fillMaxHeight()
                                .verticalScroll(rememberScrollState())
                        ) {
                            player()
                            VideoDetails(state, actions, titleSharedKey)
                            LinkifiedText(
                                text = state.description,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                        VerticalDivider()
                        Column(Modifier.weight(0.38f).fillMaxHeight()) {
                            DetailTabs(state, actions, initialTab.coerceIn(0, 1), withDescription = false)
                        }
                    }
                } else {
                    player()
                    VideoDetails(state, actions, titleSharedKey)

                    if(!fullscreen) {
                        DetailTabs(state, actions, initialTab, withDescription = true)
                    }
                }
            }
        }
    }
}

/**
 * The comments/related(/description) pager under — or, on expanded widths, beside — the
 * player. Split out so the compact tabbed column and the expanded side pane share one
 * implementation; on expanded widths the description already scrolls under the player,
 * so [withDescription] drops its tab.
 */
@Composable
internal fun DetailTabs(
    state: VideoDetailUiState,
    actions: VideoDetailActions,
    initialTab: Int,
    withDescription: Boolean,
) {
    val tabLabels = if (withDescription) {
        listOf(R.string.label_comments, R.string.label_related_videos, R.string.label_description)
    } else {
        listOf(R.string.label_comments, R.string.label_related_videos)
    }
    val pagerState = rememberPagerState(
        initialPage = initialTab.coerceIn(0, tabLabels.size - 1),
        pageCount = { tabLabels.size },
    )
    val coroutineScope = rememberCoroutineScope()

    Column {
        SecondaryTabRow(selectedTabIndex = pagerState.currentPage) {
            tabLabels.forEachIndexed { index, labelRes ->
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = { coroutineScope.launch { pagerState.animateScrollToPage(index) } }
                ) {
                    Text(stringResource(labelRes), modifier = Modifier.padding(16.dp))
                }
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.Top // Ensures content starts at top
        ) { page ->
            when (page) {
                0 -> CommentsSection(state.comments)
                1 -> RelatedVideosSection(state.relatedVideos, actions::openVideo)
                else -> DescriptionSection(state.description)
            }
        }
    }
}
