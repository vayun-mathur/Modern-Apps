package com.vayunmathur.uniflix.ui.tv

import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.library.tv.TvEmptyState
import com.vayunmathur.library.tv.TvLoading
import com.vayunmathur.library.tv.TvGrid
import com.vayunmathur.library.tv.TvInitialFocus
import com.vayunmathur.library.tv.tvFullWidth
import com.vayunmathur.library.tv.tvGridHeader
import com.vayunmathur.uniflix.R
import com.vayunmathur.uniflix.data.ContinueWatchingEntry
import com.vayunmathur.uniflix.data.UserListItem
import com.vayunmathur.uniflix.domain.MediaItem
import com.vayunmathur.uniflix.domain.MediaType
import com.vayunmathur.uniflix.platform.HomeViewModel
import com.vayunmathur.uniflix.platform.ListsViewModel
import kotlinx.coroutines.launch

/**
 * TV My List: one scrolling grid mirroring the phone list sections —
 * continue watching, watchlist, then each user list with its titles.
 * List creation stays on the phone; here lists are browse-and-open.
 */
@Composable
fun TvMyListTab(
    onOpenDetail: (MediaItem) -> Unit,
    onResume: (ContinueWatchingEntry) -> Unit,
) {
    val homeVm: HomeViewModel = viewModel()
    val listsVm: ListsViewModel = viewModel()
    val watchlist by homeVm.watchlist.collectAsState(initial = emptyList())
    val entries by homeVm.continueWatching.collectAsState(initial = emptyList())
    val lists by listsVm.lists.collectAsState(initial = emptyList())
    val hasLoadedLocal by homeVm.hasLoadedLocalLists.collectAsState(initial = false)
    val hasLoadedLists by listsVm.hasLoadedLists.collectAsState(initial = false)

    // Per-list items: one collector per list, restarted whenever the list set changes.
    val itemsByList = remember { mutableStateMapOf<Long, List<UserListItem>>() }
    LaunchedEffect(lists.map { it.id }) {
        itemsByList.keys.retainAll(lists.map { it.id }.toSet())
        lists.forEach { list ->
            launch { listsVm.items(list.id).collect { itemsByList[list.id] = it } }
        }
    }

    val firstFocus = remember { FocusRequester() }
    val firstKey = when {
        entries.isNotEmpty() -> "cw:${entries.first().key}"
        watchlist.isNotEmpty() -> "wl:${watchlist.first().key}"
        lists.isNotEmpty() -> "list:${lists.first().id}"
        else -> null
    }
    TvInitialFocus(firstFocus, key = firstKey)

    if ((!hasLoadedLocal || !hasLoadedLists) && entries.isEmpty() && watchlist.isEmpty() && lists.isEmpty()) {
        TvLoading()
        return
    }
    if (entries.isEmpty() && watchlist.isEmpty() && lists.isEmpty()) {
        TvEmptyState(
            title = stringResource(R.string.lists_empty),
            body = stringResource(R.string.empty_list_hint),
        )
        return
    }
    val continueTitle = stringResource(R.string.continue_watching)
    val watchlistTitle = stringResource(R.string.watchlist)
    TvGrid(columns = 6) {
        var headerShown = false
        if (entries.isNotEmpty()) {
            tvGridHeader(
                key = "hdr-continue",
                title = continueTitle,
                first = true,
            )
            headerShown = true
            items(
                entries.take(6),
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
        if (watchlist.isNotEmpty()) {
            tvGridHeader(
                key = "hdr-watchlist",
                title = watchlistTitle,
                first = !headerShown,
            )
            headerShown = true
            items(watchlist, key = { "wl:${it.key}" }) { row ->
                val item = row.toMediaItem()
                MediaPosterCard(
                    item = item,
                    onOpen = { onOpenDetail(item) },
                    modifier = Modifier.focusIf("wl:${row.key}", firstKey, firstFocus),
                )
            }
        }
        lists.forEach { list ->
            val listItems = itemsByList[list.id].orEmpty()
            if (listItems.isEmpty()) return@forEach
            tvGridHeader(key = "hdr-list-${list.id}", title = list.name, first = !headerShown)
            headerShown = true
            items(listItems, key = { "li:${it.id}" }) { row ->
                val item = row.toMediaItem()
                MediaPosterCard(
                    item = item,
                    onOpen = { onOpenDetail(item) },
                    modifier = Modifier.focusIf("li:${row.id}", firstKey, firstFocus),
                )
            }
        }
        tvFullWidth(key = "end-anchor") {}
    }
}

private fun UserListItem.toMediaItem(): MediaItem = MediaItem(
    tmdbId = tmdbId,
    mediaType = if (mediaType == "tv") MediaType.TV else MediaType.MOVIE,
    title = title,
    posterPath = posterPath,
)

