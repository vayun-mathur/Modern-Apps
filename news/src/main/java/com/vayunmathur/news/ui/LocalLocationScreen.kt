package com.vayunmathur.news.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ScaffoldSearch
import com.vayunmathur.library.ui.Text
import com.vayunmathur.news.R
import com.vayunmathur.news.domain.LocalNews
import com.vayunmathur.news.domain.SourceCatalog
import com.vayunmathur.news.platform.NewsViewModel

/**
 * Local news location picker: a manual preference, never device
 * location. The searchable list is the country -> region/city hierarchy
 * derived from the catalog's LOCAL sources ([SourceCatalog.localRegions],
 * grouped by [LocalNews.countryOf]); typing a place name that is not in
 * the list offers it verbatim as a free-text location. Picking (or
 * clearing) persists via NewsPrefs and returns to the previous screen.
 */
@Composable
fun LocalLocationScreen(viewModel: NewsViewModel, onBack: () -> Unit) {
    val current by viewModel.localLocation.collectAsState()
    var query by remember { mutableStateOf("") }
    val trimmed = query.trim()
    val filtered = remember(trimmed) {
        if (trimmed.isEmpty()) {
            SourceCatalog.localRegions
        } else {
            SourceCatalog.localRegions.filter {
                it.contains(trimmed, ignoreCase = true)
            }
        }
    }
    val grouped = remember(filtered) {
        filtered.groupBy { LocalNews.countryOf(it) ?: "" }.toSortedMap()
    }
    val exactOffered = trimmed.isNotEmpty() && filtered.any { it.equals(trimmed, ignoreCase = true) }
    AppScaffold(
        title = stringResource(R.string.local_location_title),
        onNavigateBack = onBack,
        search = ScaffoldSearch(
            query = query,
            onQueryChange = { query = it },
            placeholder = stringResource(R.string.local_location_search_placeholder),
        ),
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            item {
                Text(
                    stringResource(R.string.local_location_note),
                    modifier = Modifier.padding(16.dp),
                )
            }
            if (current != null) {
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.local_location_current, current!!)) },
                        supportingContent = { Text(stringResource(R.string.local_location_clear)) },
                        modifier = Modifier.clickable {
                            viewModel.setLocalLocation(null)
                            onBack()
                        },
                    )
                }
            }
            if (trimmed.isNotEmpty() && !exactOffered) {
                item {
                    ListItem(
                        headlineContent = {
                            Text(stringResource(R.string.local_location_use_typed, trimmed))
                        },
                        modifier = Modifier.clickable {
                            viewModel.setLocalLocation(trimmed)
                            onBack()
                        },
                    )
                }
            }
            grouped.forEach { (country, regions) ->
                if (country.isNotEmpty()) {
                    item(key = "country-$country") {
                        Text(country, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                }
                items(regions, key = { it }) { region ->
                    ListItem(
                        headlineContent = { Text(region) },
                        modifier = Modifier.clickable {
                            viewModel.setLocalLocation(region)
                            onBack()
                        },
                    )
                }
            }
            if (filtered.isEmpty() && trimmed.isEmpty()) {
                item { Text(stringResource(R.string.local_location_empty), modifier = Modifier.padding(16.dp)) }
            }
        }
    }
}
