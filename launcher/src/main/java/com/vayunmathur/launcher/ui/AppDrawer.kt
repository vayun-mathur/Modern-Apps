package com.vayunmathur.launcher.ui

import android.content.ContentUris
import android.content.Intent
import android.provider.CalendarContract
import android.provider.ContactsContract
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.launcher.AppInfo
import com.vayunmathur.launcher.search.GroupedResults
import com.vayunmathur.launcher.search.SearchResult

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDrawer(
    apps: List<AppInfo>,
    query: String,
    searchResults: GroupedResults,
    isSearchActive: Boolean,
    onQueryChange: (String) -> Unit,
    onSearchActiveChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onAppClick: (AppInfo) -> Unit,
    focusSearch: Boolean = false
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(focusSearch) {
        if (focusSearch) {
            onSearchActiveChange(true)
            try { focusRequester.requestFocus() } catch (_: Exception) {}
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        SearchBar(
            inputField = {
                SearchBarDefaults.InputField(
                    query = query,
                    onQueryChange = onQueryChange,
                    onSearch = {},
                    expanded = isSearchActive,
                    onExpandedChange = onSearchActiveChange,
                    placeholder = { Text("Search apps, contacts, events…") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (isSearchActive) {
                            IconButton(onClick = { onSearchActiveChange(false) }) {
                                Icon(Icons.Default.Close, contentDescription = "Close")
                            }
                        }
                    },
                    modifier = if (focusSearch) Modifier.focusRequester(focusRequester) else Modifier
                )
            },
            expanded = isSearchActive,
            onExpandedChange = onSearchActiveChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            SearchResultsContent(searchResults, context)
        }

        if (!isSearchActive) {
            val grouped = remember(apps) {
                apps.groupBy { it.name.first().uppercaseChar() }
                    .toSortedMap()
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                apps.forEach { app ->
                    item(key = app.packageName) {
                        AppIcon(
                            name = app.name,
                            icon = app.icon,
                            onClick = { onAppClick(app) },
                            labelColor = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultsContent(results: GroupedResults, context: android.content.Context) {
    androidx.compose.foundation.lazy.LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        if (results.apps.isNotEmpty()) {
            item {
                Text(
                    "Apps",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            items(results.apps.size) { index ->
                val app = results.apps[index]
                ListItem(
                    headlineContent = { Text(app.name) },
                    supportingContent = { Text(app.packageName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.clickable {
                        context.packageManager.getLaunchIntentForPackage(app.packageName)?.let {
                            context.startActivity(it)
                        }
                    }
                )
            }
        }
        if (results.contacts.isNotEmpty()) {
            item {
                if (results.apps.isNotEmpty()) HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text(
                    "Contacts",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            items(results.contacts.size) { index ->
                val contact = results.contacts[index]
                ListItem(
                    headlineContent = { Text(contact.name) },
                    supportingContent = {
                        if (contact.phones.isNotBlank()) Text(contact.phones, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    leadingContent = { Icon(Icons.Default.Person, contentDescription = null) },
                    modifier = Modifier.clickable {
                        val uri = ContactsContract.Contacts.getLookupUri(
                            contact.contactId.toLongOrNull() ?: 0L,
                            contact.lookupKey
                        )
                        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                    }
                )
            }
        }
        if (results.events.isNotEmpty()) {
            item {
                if (results.apps.isNotEmpty() || results.contacts.isNotEmpty()) HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text(
                    "Events",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            items(results.events.size) { index ->
                val event = results.events[index]
                ListItem(
                    headlineContent = { Text(event.title) },
                    supportingContent = {
                        val subtitle = listOfNotNull(
                            event.date.takeIf { it.isNotBlank() },
                            event.location.takeIf { it.isNotBlank() }
                        ).joinToString(" · ")
                        if (subtitle.isNotBlank()) Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    leadingContent = { Icon(Icons.Default.DateRange, contentDescription = null) },
                    modifier = Modifier.clickable {
                        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.eventId)
                        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                    }
                )
            }
        }
        if (results.apps.isEmpty() && results.contacts.isEmpty() && results.events.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("No results", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
