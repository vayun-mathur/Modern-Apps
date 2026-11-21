package com.vayunmathur.web.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.DesktopMaxWidthContainer
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.friendlyError
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Tab
import com.vayunmathur.library.ui.TabRow
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.rememberMessenger
import com.vayunmathur.web.R
import com.vayunmathur.web.data.WebSearchKind
import com.vayunmathur.web.data.WebSearchRepository
import com.vayunmathur.web.data.WebSearchResponse
import com.vayunmathur.web.platform.ContentFilters
import com.vayunmathur.web.platform.SearchEngine
import kotlinx.coroutines.launch

/**
 * Native search results page for the self-hosted engine ([SearchEngine.SELF_HOSTED]).
 *
 * Calls the JSON API and renders results with on-device Compose UI — no WebView,
 * no engine HTML. Shown by [BrowserContent] when the active tab holds a
 * `search://` pseudo-URL (see [nativeSearchUrl]).
 *
 * Query logging decision: the query itself is NOT written to history — only
 * result taps the user opens are recorded (via [onOpenResult]). A search box
 * is not a visit, and logging every keystroke-confirmed query would pollute
 * history with non-pages.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchResultsPage(
    query: String,
    onOpenResult: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val searchFailedText = stringResource(R.string.search_failed)
    val messenger = rememberMessenger()
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableIntStateOf(0) }
    // Explicit tab->kind order (never WebSearchKind.entries[selectedTab]: a
    // future enum reorder must not silently mismatch tabs).
    val kind = when (selectedTab) {
        1 -> WebSearchKind.IMAGES
        2 -> WebSearchKind.NEWS
        else -> WebSearchKind.TEXT
    }
    var response by remember(query) { mutableStateOf<WebSearchResponse?>(null) }
    var loading by remember(query) { mutableStateOf(true) }
    var failure by remember(query) { mutableStateOf<String?>(null) }
    var page by remember(query) { mutableIntStateOf(1) }

    fun load(requestedPage: Int) {
        // Guard against the tab-switch race: capture the kind at request time
        // and drop completions that arrive after the user moved on, so a slow
        // TEXT reply cannot overwrite the NEWS response (or vice versa).
        val requestKind = kind
        loading = true
        failure = null
        scope.launch {
            runCatching {
                WebSearchRepository.search(
                    query = query,
                    kind = requestKind,
                    safeSearch = ContentFilters.filterSearchResults(context),
                    page = requestedPage,
                )
            }.onSuccess {
                if (requestKind != kind) return@onSuccess
                response = if (requestedPage > 1 && response?.query == it.query) {
                    appendPage(response, it)
                } else {
                    it
                }
                page = requestedPage
                loading = false
            }.onFailure { e ->
                if (requestKind != kind) return@onFailure
                failure = context.friendlyError(e)
                loading = false
                messenger.show(searchFailedText)
            }
        }
    }

    LaunchedEffect(query, selectedTab) {
        // SearchImageResults owns its own paginated fetch (the grid needs
        // append semantics the shared response does not have). Clear the shared
        // slot so switching tabs never flashes the previous kind's results.
        if (kind == WebSearchKind.IMAGES) {
            loading = false
            failure = null
            response = null
        } else {
            response = null
            load(1)
        }
    }

    AppScaffold(
        title = query,
        modifier = modifier,
    ) { paddingValues ->
        DesktopMaxWidthContainer(modifier = Modifier.padding(paddingValues)) {
            Column(Modifier.fillMaxSize()) {
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text(stringResource(R.string.search_tab_text)) },
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text(stringResource(R.string.search_tab_images)) },
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = { Text(stringResource(R.string.search_tab_news)) },
                    )
                }
                Box(Modifier.fillMaxSize().weight(1f)) {
                    when {
                        loading && response == null -> Box(
                            Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) { CircularProgressIndicator() }

                        failure != null && response == null -> SearchErrorState(
                            message = failure.orEmpty(),
                            onRetry = { load(1) },
                        )

                        else -> {
                            val resp = response
                            // Degraded server reply: lists are empty and an
                            // upstream error is set.
                            if (resp?.error != null && resp.text.isNullOrEmpty() &&
                                resp.images.isNullOrEmpty() && resp.news.isNullOrEmpty()
                            ) {
                                SearchErrorState(
                                    message = resp.error.orEmpty(),
                                    onRetry = { load(1) },
                                )
                            } else when (kind) {
                                WebSearchKind.TEXT -> SearchTextResults(
                                    items = resp?.text.orEmpty(),
                                    loadingMore = loading,
                                    onOpenResult = onOpenResult,
                                    onLoadMore = { load(page + 1) },
                                )
                                WebSearchKind.IMAGES -> SearchImageResults(
                                    query = query,
                                    onOpenResult = onOpenResult,
                                )
                                WebSearchKind.NEWS -> SearchNewsResults(
                                    items = resp?.news.orEmpty(),
                                    onOpenResult = onOpenResult,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchErrorState(
    message: String,
    onRetry: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        EmptyState(title = stringResource(R.string.search_failed))
        if (message.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
    }
}

/** Appends page 2+ results onto the accumulated [previous] response. */
private fun appendPage(
    previous: WebSearchResponse?,
    next: WebSearchResponse,
): WebSearchResponse {
    if (previous == null) return next
    return previous.copy(
        text = (previous.text.orEmpty() + next.text.orEmpty()).ifEmpty { null },
        news = (previous.news.orEmpty() + next.news.orEmpty()).ifEmpty { null },
        // Images paginate inside SearchImageResults, which owns its own accumulation.
        error = next.error,
    )
}
