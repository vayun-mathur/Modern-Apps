package com.vayunmathur.web.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.friendlyError
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Text
import com.vayunmathur.web.R
import com.vayunmathur.web.data.WebSearchImageItem
import com.vayunmathur.web.data.WebSearchKind
import com.vayunmathur.web.data.WebSearchRepository
import com.vayunmathur.web.data.WebSearchResponse
import com.vayunmathur.web.platform.ContentFilters
import kotlinx.coroutines.launch

/**
 * Image-result grid for [SearchResultsPage].
 *
 * Owns its fetch (rather than sharing the page's response) because the grid
 * needs append-on-"more" semantics while text/news replace per tab switch.
 */
@Composable
fun SearchImageResults(
    query: String,
    onOpenResult: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember(query) { mutableStateOf<List<WebSearchImageItem>>(emptyList()) }
    var loading by remember(query) { mutableStateOf(true) }
    var failure by remember(query) { mutableStateOf<String?>(null) }
    var degraded by remember(query) { mutableStateOf<WebSearchResponse?>(null) }
    var imgPage by remember(query) { mutableIntStateOf(1) }

    fun load(requestedPage: Int) {
        loading = true
        failure = null
        scope.launch {
            runCatching {
                WebSearchRepository.search(
                    query = query,
                    kind = WebSearchKind.IMAGES,
                    safeSearch = ContentFilters.filterSearchResults(context),
                    page = requestedPage,
                )
            }.onSuccess {
                val fetched = it.images.orEmpty()
                if (requestedPage == 1) {
                    items = fetched
                    // Degraded server reply: empty list + upstream error set.
                    degraded = if (fetched.isEmpty() && it.error != null) it else null
                } else {
                    items = items + fetched
                }
                imgPage = requestedPage
                loading = false
            }.onFailure { e ->
                failure = context.friendlyError(e)
                loading = false
            }
        }
    }

    LaunchedEffect(query) { load(1) }

    val errorState = degraded ?: failure?.let {
        WebSearchResponse(kind = "images", query = query, error = it)
    }
    when {
        loading && items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Loading…")
        }
        errorState != null && items.isEmpty() -> SearchImageErrorState(
            message = errorState.error.orEmpty(),
            onRetry = { load(1) },
        )
        items.isEmpty() -> EmptyState(
            title = stringResource(R.string.search_no_results),
            modifier = Modifier.fillMaxSize(),
        )
        else -> LazyVerticalGrid(
            columns = GridCells.Adaptive(120.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items, key = { it.imageUrl + it.title }) { item ->
                AsyncImage(
                    model = item.thumbnail.ifBlank { item.imageUrl },
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .aspectRatio(1f)
                        .clip(MaterialTheme.shapes.medium)
                        .clickable { onOpenResult(item.sourceUrl.ifBlank { item.imageUrl }) },
                )
            }
            if (!loading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                        OutlinedButton(onClick = { load(imgPage + 1) }) {
                            Text(stringResource(R.string.search_more_results))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchImageErrorState(
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
