package com.vayunmathur.youpipe.ui.tv

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.tv.TvEmptyState
import com.vayunmathur.library.tv.TvLoading
import com.vayunmathur.library.tv.TvScreenHeader
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.platform.YouPipeViewModel

/**
 * TV subscription feed: newest uploads across every channel (or one category) as a 4-column
 * grid of [TvVideoCard]s.
 *
 * Same binder mapping as the phone `SubscriptionVideosPage` (feed + history progress +
 * DeArrow → rows). The loading contract matches too: a spinner before the first DB emission.
 */
@Composable
fun TvSubscriptionFeedPage(
    backStack: NavBackStack<TvRoute>,
    ypvm: YouPipeViewModel,
    category: String?,
) {
    val videos by remember(category) { ypvm.subscriptionVideosFor(category) }
        .collectAsState(initial = emptyList())
    val hasLoaded by ypvm.hasLoadedSubscriptionVideos.collectAsState()
    val history by ypvm.historyVideos.collectAsState()
    val historyById = remember(history) { history.associate { it.id to it.progress } }
    val rows = rememberTvVideoRows(videos, ypvm, historyById)

    Column(Modifier.fillMaxSize()) {
        TvScreenHeader(title = category ?: stringResource(R.string.label_all_subscriptions))
        when {
            !hasLoaded && rows.isEmpty() -> TvLoading()
            rows.isEmpty() -> TvEmptyState(title = stringResource(R.string.empty_recommendations))
            else -> TvVideoGrid(
                backStack = backStack,
                rows = rows,
                modifier = Modifier.fillMaxSize().weight(1f),
            )
        }
    }
}
