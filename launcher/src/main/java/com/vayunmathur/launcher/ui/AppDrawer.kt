package com.vayunmathur.launcher.ui

import android.content.ContentUris
import android.content.Intent
import android.provider.CalendarContract
import android.provider.ContactsContract
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.launcher.AppInfo
import com.vayunmathur.launcher.search.GroupedResults
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
    onAppLongClick: (AppInfo) -> Unit = {},
    focusSearch: Boolean = false
) {
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.PartiallyExpanded, SheetValue.Expanded))
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    val isFullyExpanded = sheetState.currentValue == SheetValue.Expanded

    LaunchedEffect(focusSearch) {
        if (focusSearch) {
            sheetState.expand()
            onSearchActiveChange(true)
            try { focusRequester.requestFocus() } catch (_: Exception) {}
        }
    }

    val grouped = remember(apps) {
        apps.groupBy { it.name.first().uppercaseChar() }.toSortedMap()
    }

    // Build flat list for LazyColumn: headers + rows of 4
    data class DrawerRow(val letter: Char? = null, val apps: List<AppInfo> = emptyList())
    val flatRows = remember(grouped) {
        val rows = mutableListOf<DrawerRow>()
        grouped.forEach { (letter, items) ->
            rows.add(DrawerRow(letter = letter))
            items.chunked(4).forEach { chunk ->
                rows.add(DrawerRow(apps = chunk))
            }
        }
        rows
    }

    val letterToFlatIndex = remember(flatRows) {
        val map = mutableMapOf<Char, Int>()
        flatRows.forEachIndexed { index, row ->
            if (row.letter != null && row.letter !in map) {
                map[row.letter] = index
            }
        }
        map
    }

    val listState = rememberLazyListState()
    val allLetters = remember(grouped) { grouped.keys.toList() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (isFullyExpanded) {
                SearchBar(
                    inputField = {
                        SearchBarDefaults.InputField(
                            query = query,
                            onQueryChange = onQueryChange,
                            onSearch = {},
                            expanded = isSearchActive,
                            onExpandedChange = onSearchActiveChange,
                            placeholder = { Text("Search apps, contacts, events…", fontSize = 16.sp) },
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
            }

            if (!isSearchActive) {
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(start = 16.dp, end = 40.dp, top = 8.dp, bottom = 32.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        flatRows.forEachIndexed { index, row ->
                            if (row.letter != null) {
                                stickyHeader(key = "header_${row.letter}") {
                                    Text(
                                        text = row.letter.toString(),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                        .fillMaxWidth()
                                            .background(MaterialTheme.colorScheme.surfaceContainer)
                                            .padding(horizontal = 16.dp, vertical = 8.dp)
                                    )
                                }
                            } else {
                                item(key = "row_$index") {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceEvenly
                                    ) {
                                        row.apps.forEach { app ->
                                            var showMenu by remember { mutableStateOf(false) }
                                            Box(modifier = Modifier.weight(1f)) {
                                                AppIcon(
                                                    name = app.name,
                                                    icon = app.icon,
                                                    onClick = { onAppClick(app) },
                                                    onLongClick = { showMenu = true },
                                                    labelColor = MaterialTheme.colorScheme.onSurface,
                                                    modifier = Modifier.padding(vertical = 12.dp)
                                                )
                                                DropdownMenu(
                                                    expanded = showMenu,
                                                    onDismissRequest = { showMenu = false }
                                                ) {
                                                    DropdownMenuItem(
                                                        text = { Text("Add to Home") },
                                                        onClick = {
                                                            showMenu = false
                                                            onAppLongClick(app)
                                                        }
                                                    )
                                                    DropdownMenuItem(
                                                        text = { Text("App Info") },
                                                        onClick = {
                                                            showMenu = false
                                                            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                                                data = android.net.Uri.parse("package:${app.packageName}")
                                                            }
                                                            context.startActivity(intent)
                                                        }
                                                    )
                                                    DropdownMenuItem(
                                                        text = { Text("Uninstall") },
                                                        onClick = {
                                                            showMenu = false
                                                            val intent = Intent(Intent.ACTION_DELETE).apply {
                                                                data = android.net.Uri.parse("package:${app.packageName}")
                                                            }
                                                            context.startActivity(intent)
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                        // Fill remaining space if row has < 4 items
                                        repeat(4 - row.apps.size) {
                                            Box(modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Fast-scroll sidebar
                    Column(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .width(28.dp)
                            .padding(vertical = 8.dp)
                            .pointerInput(allLetters) {
                                detectVerticalDragGestures { change, _ ->
                                    val y = change.position.y
                                    val totalHeight = size.height.toFloat()
                                    val index = ((y / totalHeight) * allLetters.size)
                                        .toInt()
                                        .coerceIn(0, allLetters.lastIndex)
                                    val letter = allLetters[index]
                                    letterToFlatIndex[letter]?.let { flatIndex ->
                                        scope.launch { listState.scrollToItem(flatIndex) }
                                    }
                                }
                            },
                        verticalArrangement = Arrangement.SpaceEvenly,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        allLetters.forEach { letter ->
                            Text(
                                text = letter.toString(),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .clickable {
                                        letterToFlatIndex[letter]?.let { flatIndex ->
                                            scope.launch { listState.scrollToItem(flatIndex) }
                                        }
                                    }
                                    .padding(vertical = 1.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultsContent(results: GroupedResults, context: android.content.Context) {
    LazyColumn(
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
                    content = { Text(app.name) },
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
                    content = { Text(contact.name) },
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
                    content = { Text(event.title) },
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
