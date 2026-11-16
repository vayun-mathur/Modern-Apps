package com.vayunmathur.uniflix.ui.tv

import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.library.tv.TvButton
import com.vayunmathur.library.tv.TvEmptyState
import com.vayunmathur.library.tv.TvGrid
import com.vayunmathur.library.tv.TvInitialFocus
import com.vayunmathur.library.tv.TvLoading
import com.vayunmathur.library.tv.tvFullWidth
import com.vayunmathur.library.tv.tvGridHeader
import com.vayunmathur.uniflix.R
import com.vayunmathur.uniflix.data.ContinueWatchingEntry
import com.vayunmathur.uniflix.domain.MediaItem
import com.vayunmathur.uniflix.platform.HomeViewModel

private const val HOME_COLUMNS = 6

/** Home sections at most this many items each (i.e. two poster rows). */
private const val HOME_SECTION_MAX = HOME_COLUMNS * 2

/** Continue-watching cards at most this many (one landscape row). */
private const val HOME_CONTINUE_MAX = 3

/**
 * TV home: one scrolling grid mirroring the phone sections — Continue watching
 * first (landscape cards spanning 2 cells each), then two rows of posters per
 * catalog section with a "See all" action that opens the tab or a grid page.
 */
@Composable
fun TvHomeTab(
    onOpenDetail: (MediaItem) -> Unit,
    onResume: (ContinueWatchingEntry) -> Unit,
    onOpenGrid: (kind: String) -> Unit,
    onOpenTab: (tab: TvRoute) -> Unit,
) {
    val vm: HomeViewModel = viewModel()
    val state = vm.state
    val entries by vm.continueWatching.collectAsState(initial = emptyList())

    val seeAll = stringResource(R.string.see_all)
    val continueTitle = stringResource(R.string.continue_watching)
    val trendingMoviesTitle = stringResource(R.string.trending_movies)
    val trendingSeriesTitle = stringResource(R.string.trending_series)
    val topMoviesTitle = stringResource(R.string.top_rated_movies)
    val topSeriesTitle = stringResource(R.string.top_rated_series)
    val firstFocus = remember { FocusRequester() }
    val firstKey = when {
        entries.isNotEmpty() -> "cw:${entries.first().key}"
        state.trendingMovies.isNotEmpty() -> mediaKey(state.trendingMovies.first())
        state.trendingTv.isNotEmpty() -> mediaKey(state.trendingTv.first())
        state.topMovies.isNotEmpty() -> mediaKey(state.topMovies.first())
        state.topTv.isNotEmpty() -> mediaKey(state.topTv.first())
        else -> null
    }
    TvInitialFocus(firstFocus, key = firstKey)

    val empty = entries.isEmpty() && state.trendingMovies.isEmpty() && state.trendingTv.isEmpty() &&
        state.topMovies.isEmpty() && state.topTv.isEmpty()
    if (state.loading && empty) {
        TvLoading()
        return
    }
    if (empty) {
        TvEmptyState(title = stringResource(R.string.no_results))
        return
    }
    TvGrid(columns = HOME_COLUMNS) {
        if (entries.isNotEmpty()) {
            tvGridHeader(
                key = "hdr-continue",
                title = continueTitle,
                first = true,
                action = {
                    TvButton(text = seeAll, onClick = { onOpenGrid(TV_GRID_CONTINUE) })
                },
            )
            items(
                entries.take(HOME_CONTINUE_MAX),
                key = { "cw:${it.key}" },
                span = { GridItemSpan(2) },
            ) { entry ->
                ContinueEntryCard(
                    entry = entry,
                    onResume = { onResume(entry) },
                    modifier = Modifier.focusIf("cw:${entry.key}", firstKey, firstFocus),
                )
            }
        }
        posterSection(
            key = "trending-movies",
            items = state.trendingMovies,
            title = trendingMoviesTitle,
            first = entries.isEmpty(),
            firstKey = firstKey,
            firstFocus = firstFocus,
            seeAll = seeAll,
            onSeeAll = { onOpenTab(TvRoute.Movies) },
            onOpenDetail = onOpenDetail,
        )
        posterSection(
            key = "trending-tv",
            items = state.trendingTv,
            title = trendingSeriesTitle,
            first = false,
            firstKey = firstKey,
            firstFocus = firstFocus,
            seeAll = seeAll,
            onSeeAll = { onOpenTab(TvRoute.Shows) },
            onOpenDetail = onOpenDetail,
        )
        posterSection(
            key = "top-movies",
            items = state.topMovies,
            title = topMoviesTitle,
            first = false,
            firstKey = firstKey,
            firstFocus = firstFocus,
            seeAll = seeAll,
            onSeeAll = { onOpenGrid(TV_GRID_TOP_MOVIES) },
            onOpenDetail = onOpenDetail,
        )
        posterSection(
            key = "top-tv",
            items = state.topTv,
            title = topSeriesTitle,
            first = false,
            firstKey = firstKey,
            firstFocus = firstFocus,
            seeAll = seeAll,
            onSeeAll = { onOpenGrid(TV_GRID_TOP_TV) },
            onOpenDetail = onOpenDetail,
        )
        // Phantom anchor: a second-frame double-layout pass can replay remembered
        // offsets below the last item and strand focus there; a trailing span
        // keeps the grid's line count honest.
        tvFullWidth(key = "end-anchor") {}
    }
}

private fun LazyGridScope.posterSection(
    key: String,
    items: List<MediaItem>,
    title: String,
    first: Boolean,
    firstKey: String?,
    firstFocus: FocusRequester,
    seeAll: String,
    onSeeAll: () -> Unit,
    onOpenDetail: (MediaItem) -> Unit,
) {
    if (items.isEmpty()) return
    tvGridHeader(
        key = "hdr-$key",
        title = title,
        first = first,
        action = { TvButton(text = seeAll, onClick = onSeeAll) },
    )
    items(items.take(HOME_SECTION_MAX), key = { "$key:${mediaKey(it)}" }) { item ->
        val itemKey = mediaKey(item)
        MediaPosterCard(
            item = item,
            onOpen = { onOpenDetail(item) },
            modifier = Modifier.focusIf(itemKey, firstKey, firstFocus),
        )
    }
}

