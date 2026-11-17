package com.vayunmathur.appstore.platform
import com.vayunmathur.appstore.R

import androidx.lifecycle.viewModelScope
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.play.PlayAuthState
import com.vayunmathur.appstore.data.priority
import com.vayunmathur.appstore.data.searchCandidate
import com.vayunmathur.appstore.domain.SearchRanking
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ---- Search ----
// Moved from AppStoreViewModel.kt (FileLength split); behavior identical.

internal fun AppStoreViewModel.setSearchImpl(query: String) {
    queryFlow.value = query
    searchJob?.cancel()
    if (query.isBlank()) {
        searchResultsFlow.value = emptyList()
        hasSearchedFlow.value = false
        isSearchingFlow.value = false
        return
    }
    searchJob = viewModelScope.launch {
        delay(AppStoreViewModel.SEARCH_DEBOUNCE_MS)
        isSearchingFlow.value = true
        searchPlayErrorFlow.value = ""
        // The persisted choice, not the flow's optimistic default: a search fired on a
        // cold start must not query a source the user switched off (#845).
        val enabled = settings.readEnabledSources()

        // Local first and published immediately: the F-Droid catalogue is on disk, so
        // there is no reason to make the user wait on Play before seeing anything.
        // Filtered to enabled sources: the Room cache can still hold a disabled
        // source's rows before the startup purge lands.
        val local = catalog.searchLocal(query).filter { it.source in enabled }
        searchResultsFlow.value = rank(local, query)

        // Accrescent search is client-side over the listings already cached from the home
        // carousel (its API has no search RPC), so it adds no network round-trip here.
        val accrescentResults =
            if (AppSource.ACCRESCENT in enabled) accrescent.search(query) else emptyList()
        // Play may refuse (429 Too Many Requests, issue #797): withApi records the refusal
        // on authState after cycling the account once, and the local half stays on screen.
        val remote = if (AppSource.PLAYSTORE in enabled) play.search(query) else emptyList()
        val playError = (play.authState.value as? PlayAuthState.Error)
            ?.let { context.getString(R.string.play_unavailable, it.message) }
            .orEmpty()
        searchPlayErrorFlow.value = playError
        searchResultsFlow.value = rank(merge(local, remote, accrescentResults), query)
        isSearchingFlow.value = false
        hasSearchedFlow.value = true
    }
}

/**
 * Combine catalogue, Play and Accrescent hits, one row per package.
 *
 * Where several sources offer a package, [AppSource.PRIORITY] decides which row survives.
 * Sorting is stable, so each source's own relevance ordering is preserved within its
 * rank, and [rank] re-sorts the result anyway.
 */
internal fun AppStoreViewModel.merge(vararg lists: List<UnifiedApp>): List<UnifiedApp> =
    lists.asSequence()
        .flatten()
        .sortedBy { it.source.priority }
        .distinctBy { it.packageName }
        .toList()

/** See [SearchRanking]: exact and prefix hits first, and nothing that answers no word. */
internal fun AppStoreViewModel.rank(apps: List<UnifiedApp>, query: String): List<UnifiedApp> =
    SearchRanking.rank(apps, query) { it.searchCandidate() }
