package com.vayunmathur.shop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.CommonSearchBar
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.rememberMessenger
import com.vayunmathur.shop.R
import com.vayunmathur.shop.data.UcpProduct
import com.vayunmathur.shop.domain.MoneyFormat
import com.vayunmathur.shop.platform.ShopViewModel

/**
 * Global Catalog search: query → product grid. Tapping a card opens the product detail
 * for the first variant; the detail resolves the exact variant.
 */
@Composable
fun SearchScreen(
    viewModel: ShopViewModel,
    onProductClick: (variantId: String, platformId: String, shopDomain: String) -> Unit,
) {
    val messenger = rememberMessenger()
    LaunchedEffect(viewModel.searchError) {
        viewModel.searchError?.let { error ->
            messenger.show(error)
            viewModel.dismissSearchError()
        }
    }
    AppScaffold(
        title = stringResource(R.string.app_name),
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        SearchContent(
            query = viewModel.query,
            onQueryChange = viewModel::updateQuery,
            searching = viewModel.searching,
            results = viewModel.results,
            onSearch = viewModel::search,
            onProductClick = onProductClick,
            modifier = Modifier.fillMaxSize().padding(padding),
            shopDomain = viewModel.searchShopDomain,
            onShopDomainChange = viewModel::updateShopDomain,
        )
    }
}

/**
 * The search page without ViewModel or scaffold, so previews and screenshot tests can
 * render it with fixture data. Search stays explicit (button, not per-keystroke): the
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
    shopDomain: String = "",
    onShopDomainChange: (String) -> Unit = {},
) {
    Column(modifier.fillMaxSize()) {
        com.vayunmathur.library.ui.OutlinedTextField(
            value = shopDomain,
            onValueChange = onShopDomainChange,
            label = { Text(stringResource(R.string.store_domain)) },
            supportingText = { Text(stringResource(R.string.store_domain_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CommonSearchBar(
                value = query,
                onValueChange = onQueryChange,
                placeholder = stringResource(R.string.search_products),
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = onSearch,
                enabled = !searching && query.isNotBlank(),
                modifier = Modifier.padding(end = 16.dp),
            ) {
                Text(stringResource(R.string.search))
            }
            Spacer(Modifier.width(8.dp))
        }
        ProductResults(
            results = results,
            searching = searching,
            onProductClick = onProductClick,
            modifier = Modifier.weight(1f),
        )
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
        items(results, key = { "${it.platformId}|${it.shopDomain}|${it.id}" }) { product ->
            ProductCard(product = product, onClick = {
                val variantId = product.variants.firstOrNull()?.id ?: return@ProductCard
                onProductClick(variantId, product.platformId, product.shopDomain)
            })
        }
    }
}

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
                    text = MoneyFormat.format(product.priceRange),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
