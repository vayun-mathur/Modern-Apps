package com.vayunmathur.shop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.InputChip
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.SearchBarInputField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.rememberMessenger
import com.vayunmathur.shop.R
import com.vayunmathur.shop.data.UcpProduct
import com.vayunmathur.shop.domain.MoneyFormat
import com.vayunmathur.shop.platform.LinkDefaults
import com.vayunmathur.shop.platform.ShopViewModel

/**
 * Full search: the query is matched to stores server-side, then searched across them.
 * Tapping a card opens the product detail for the first variant; the detail resolves the exact variant.
 */
@Composable
fun SearchScreen(
    viewModel: ShopViewModel,
    onProductClick: (variantId: String, platformId: String, shopDomain: String) -> Unit,
) {
    val context = LocalContext.current
    val messenger = rememberMessenger()
    LaunchedEffect(viewModel.searchError) {
        viewModel.searchError?.let { error ->
            messenger.show(error)
            viewModel.dismissSearchError()
        }
    }
    var showLinkPrompt by remember { mutableStateOf(LinkDefaults.shouldPrompt(context)) }
    SearchContent(
        query = viewModel.query,
        onQueryChange = viewModel::updateQuery,
        searching = viewModel.searching,
        results = viewModel.results,
        onSearch = viewModel::search,
        onProductClick = onProductClick,
        scopeName = viewModel.scopeName,
        onClearScope = viewModel::clearScope,
        focusRequest = viewModel.focusSearchRequest,
        linkPrompt = if (!showLinkPrompt) null else LinkPrompt(
            onOpenSettings = { LinkDefaults.openSettings(context); showLinkPrompt = false },
            onDismiss = { LinkDefaults.dismiss(context); showLinkPrompt = false },
        ),
    )
}

internal data class LinkPrompt(val onOpenSettings: () -> Unit, val onDismiss: () -> Unit)

/**
 * The search page without ViewModel, so previews and screenshot tests can render it with
 * fixture data. Search stays explicit (IME action or search button, not per-keystroke): the
 * anonymous tier shares one IP pool and every keystroke would burn shared quota.
 */
@Composable
internal fun SearchContent(
    query: String,
    onQueryChange: (String) -> Unit,
    searching: Boolean,
    results: List<UcpProduct>,
    onSearch: () -> Unit,
    onProductClick: (variantId: String, platformId: String, shopDomain: String) -> Unit,
    modifier: Modifier = Modifier,
    scopeName: String? = null,
    onClearScope: () -> Unit = {},
    focusRequest: Int = 0,
    linkPrompt: LinkPrompt? = null,
) {
    AppScaffold(
        modifier = modifier,
        topBar = { SearchTopBar(query, onQueryChange, onSearch, enabled = !searching, focusRequest = focusRequest) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            linkPrompt?.let { LinkPromptCard(it) }
            scopeName?.let { name ->
                InputChip(
                    selected = true,
                    onClick = onClearScope,
                    label = { Text("Results from $name") },
                    trailingIcon = { IconClose() },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            ProductResults(
                results = results,
                searching = searching,
                onProductClick = onProductClick,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SearchTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    enabled: Boolean,
    focusRequest: Int,
) {
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(focusRequest) { if (focusRequest > 0) focusRequester.requestFocus() }
    val submit = {
        if (enabled && query.isNotBlank()) {
            focusManager.clearFocus()
            onSearch()
        }
    }
    SearchBarInputField(
        query = query,
        onQueryChange = onQueryChange,
        onSearch = { submit() },
        expanded = false,
        onExpandedChange = {},
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .focusRequester(focusRequester),
        placeholder = { Text(stringResource(R.string.search_products)) },
        leadingIcon = { IconButton(onClick = submit) { IconSearch() } },
        trailingIcon = if (query.isEmpty()) null else {
            { IconButton(onClick = { onQueryChange("") }) { IconClose() } }
        },
    )
}

@Composable
private fun LinkPromptCard(prompt: LinkPrompt) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Open store links in Shop", style = MaterialTheme.typography.titleSmall)
            Text(
                "In the next screen, tap \"Add link\" and select all to open store links here.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = prompt.onDismiss) { Text("Not now") }
                TextButton(onClick = prompt.onOpenSettings) { Text("Open settings") }
            }
        }
    }
}

@Composable
private fun ProductResults(
    results: List<UcpProduct>,
    searching: Boolean,
    onProductClick: (variantId: String, platformId: String, shopDomain: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (searching) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    if (results.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(R.string.no_products_found),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.check_connection_retry),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 160.dp),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier,
    ) {
        items(results.distinctBy { it.gridKey }, key = { it.gridKey }) { product ->
            ProductCard(product = product, onClick = {
                val variant = product.variants.firstOrNull() ?: return@ProductCard
                onProductClick(variant.id, product.platformId, variant.shopDomain.ifBlank { product.shopDomain })
            })
        }
    }
}

private val UcpProduct.gridKey: String get() = "$platformId|$shopDomain|$id"

@Composable
private fun ProductCard(product: UcpProduct, onClick: () -> Unit) {
    Card(onClick = onClick) {
        Column {
            val image = product.images.firstOrNull()?.url
            if (!image.isNullOrEmpty()) {
                AsyncImage(
                    model = image,
                    contentDescription = product.title,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                    contentScale = ContentScale.Crop,
                )
            }
            Column(Modifier.padding(12.dp)) {
                Text(
                    text = product.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = MoneyFormat.formatRange(product.priceRange, product.priceRangeMax),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                product.storeName.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
