package com.vayunmathur.shop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.CommonSearchBar
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.shop.data.MerchantListing
import com.vayunmathur.shop.data.message
import com.vayunmathur.shop.network.ShopBackend
import com.vayunmathur.shop.network.ShopBackendException
import com.vayunmathur.shop.network.ShopHttpBackend
import com.vayunmathur.shop.platform.AppInit
import kotlinx.coroutines.launch

@Composable
fun MerchantDirectoryScreen(onMerchantSelected: (String) -> Unit) {
    val backend: ShopBackend = ShopHttpBackend
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var rows by remember { mutableStateOf(emptyList<MerchantListing>()) }
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AppScaffold(title = "Merchants", scrollBehavior = appBarScrollBehavior()) { padding ->
        MerchantDirectoryContent(
            rows = rows,
            query = query,
            onQueryChange = { query = it },
            loaded = loaded,
            loading = loading,
            checking = null,
            error = error,
            onLoad = {
                if (!loading) scope.launch {
                    loading = true
                    error = null
                    try {
                        AppInit.awaitReady()
                        backend.listMerchants(refresh = loaded).fold(
                            onSuccess = { listings -> rows = listings; loaded = true },
                            onFailure = { failure -> error = (failure as? ShopBackendException)?.error?.message() ?: "Couldn't load the merchant directory." },
                        )
                    } finally { loading = false }
                }
            },
            // Compatibility is checked server-side on first search; the
            // directory entry is only a discovery lead.
            onUse = { row -> onMerchantSelected(row.domain) },
            onOpenSite = { openContinueUrl(context, it.siteUrl) },
            modifier = Modifier.fillMaxSize().padding(padding),
        )
    }
}

@Composable
internal fun MerchantDirectoryContent(
    rows: List<MerchantListing>,
    query: String,
    onQueryChange: (String) -> Unit,
    loaded: Boolean,
    loading: Boolean,
    checking: String?,
    error: String?,
    onLoad: () -> Unit,
    onUse: (MerchantListing) -> Unit,
    onOpenSite: (MerchantListing) -> Unit,
    modifier: Modifier = Modifier,
) {
    val matches = rows.filter {
        query.isBlank() || it.name.contains(query, true) || it.domain.contains(query, true)
    }.take(100)
    Column(modifier.fillMaxSize()) {
        Text(
            "Directory entries are discovery leads, not verified access or payment permission. Catalog compatibility is checked when you select a merchant.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (loaded) "${rows.size} merchants cached" else "Load the public UCPList directory", modifier = Modifier.weight(1f))
            Button(onClick = onLoad, enabled = !loading && checking == null) {
                Text(if (loaded) "Refresh" else "Load")
            }
        }
        CommonSearchBar(value = query, onValueChange = onQueryChange, placeholder = "Filter merchants locally")
        if (loading) CircularProgressIndicator(modifier = Modifier.padding(16.dp))
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        if (loaded && matches.isEmpty()) Text("No matching merchants.", modifier = Modifier.padding(16.dp))
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(matches, key = { "${it.id}|${it.domain}" }) { row ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(row.name, style = MaterialTheme.typography.titleMedium)
                        Text(row.domain, style = MaterialTheme.typography.bodyMedium)
                        Text("Directory status: ${row.directoryStatus.ifBlank { "unknown" }}. Not independently verified.", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = { onUse(row) }, enabled = !loading && checking == null) {
                                Text(if (checking == row.id) "Checking..." else "Use merchant")
                            }
                            TextButton(onClick = { onOpenSite(row) }) { Text("Open site") }
                        }
                    }
                }
            }
        }
        if (rows.size > 100 && query.isBlank()) {
            Text("Showing the first 100. Filter to find another merchant.", modifier = Modifier.padding(16.dp))
        }
    }
}
