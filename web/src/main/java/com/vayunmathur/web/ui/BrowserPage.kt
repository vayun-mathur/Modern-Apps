package com.vayunmathur.web.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import android.net.Uri
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.vayunmathur.library.ui.IconForward
import com.vayunmathur.library.ui.IconMenu
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.web.MainActivity
import com.vayunmathur.web.Route
import com.vayunmathur.web.data.SearchResult
import com.vayunmathur.web.util.BrowserViewModel
import com.vayunmathur.web.util.NavEntry
import com.vayunmathur.web.util.SearchEngine
import org.mozilla.geckoview.GeckoView

@Composable
fun BrowserPage(
    viewModel: BrowserViewModel,
    backStack: NavBackStack<Route>
) {
    val entry = viewModel.currentEntry

    BackHandler(enabled = viewModel.canGoBack) {
        viewModel.goBack()
    }

    val context = LocalContext.current
    val displayUrl = when (entry) {
        is NavEntry.Search -> entry.query
        is NavEntry.NewTab -> ""
        is NavEntry.Blocked -> entry.url
        is NavEntry.WebPage -> viewModel.currentUrl
    }
    val isNewTab = entry is NavEntry.NewTab

    fun handleInput(input: String) {
        val trimmed = input.trim()
        val query = SearchEngine.extractSearchQuery(trimmed)
        when {
            query != null -> viewModel.search(query)
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> {
                if (SearchEngine.isSearchEngineUrl(trimmed)) viewModel.createTab()
                else viewModel.loadUrl(trimmed)
            }
            trimmed.contains(".") && !trimmed.contains(" ") -> {
                val url = "https://$trimmed"
                if (SearchEngine.isSearchEngineUrl(url)) viewModel.createTab()
                else viewModel.loadUrl(url)
            }
            else -> viewModel.search(trimmed)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        BrowserToolbar(
            currentUrl = displayUrl,
            isNewTab = isNewTab,
            tabCount = viewModel.tabs.size,
            isIncognito = viewModel.isIncognito,
            canGoForward = viewModel.canGoForward,
            onNavigate = ::handleInput,
            onForward = { viewModel.goForward() },
            onOpenTabs = { backStack.add(Route.Tabs) },
            onNewTab = { viewModel.createTab() },
            onNewWindow = { MainActivity.launchNewWindow(context) },
            onNewIncognitoWindow = { MainActivity.launchNewWindow(context, incognito = true) },
            onOpenHistory = { backStack.add(Route.History) }
        )

        AnimatedVisibility(visible = viewModel.isLoading && entry is NavEntry.WebPage) {
            LinearProgressIndicator(
                progress = { viewModel.progress },
                modifier = Modifier.fillMaxWidth()
            )
        }

        AnimatedVisibility(visible = entry is NavEntry.Search && entry.loading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        when (entry) {
            is NavEntry.Search -> SearchResultsContent(entry, viewModel)
            is NavEntry.Blocked -> BlockedContent(
                url = entry.url,
                onGoBack = { viewModel.goBack() },
                onNewTab = { viewModel.createTab() }
            )
            is NavEntry.NewTab -> NewTabContent(
                isIncognito = viewModel.isIncognito,
                onSearch = { viewModel.search(it) }
            )
            is NavEntry.WebPage -> {
                val activeSession = viewModel.getActiveSession()
                if (activeSession != null) {
                    AndroidView(
                        factory = { ctx -> GeckoView(ctx) },
                        update = { view ->
                            val session = viewModel.getActiveSession()
                            if (session != null && view.session != session) {
                                if (view.session != null) view.releaseSession()
                                view.setSession(session)
                            }
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchResultsContent(entry: NavEntry.Search, viewModel: BrowserViewModel) {
    when {
        entry.error != null -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(entry.error, color = MaterialTheme.colorScheme.error)
            }
        }
        entry.results.isEmpty() && !entry.loading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No results found")
            }
        }
        else -> {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(entry.results) { result ->
                    SearchResultItem(result) {
                        viewModel.loadUrl(result.url)
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultItem(result: SearchResult, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = result.title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = result.url,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (result.snippet.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = result.snippet,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun BlockedContent(url: String, onGoBack: () -> Unit, onNewTab: () -> Unit) {
    val host = Uri.parse(url).host ?: url

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                text = "Site blocked",
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = host,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "This site has been blocked by content filtering.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onNewTab) {
                Text("New tab")
            }
            OutlinedButton(onClick = onGoBack) {
                Text("Go back")
            }
        }
    }
}

@Composable
private fun NewTabContent(isIncognito: Boolean, onSearch: (String) -> Unit) {
    var searchText by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        OutlinedTextField(
            value = searchText,
            onValueChange = { searchText = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 32.dp),
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            placeholder = {
                Text(if (isIncognito) "Search privately with DuckDuckGo" else "Search with DuckDuckGo")
            },
            leadingIcon = { IconSearch() },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                if (searchText.isNotBlank()) {
                    onSearch(searchText)
                    focusManager.clearFocus()
                }
            })
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserToolbar(
    currentUrl: String,
    isNewTab: Boolean,
    tabCount: Int,
    isIncognito: Boolean,
    canGoForward: Boolean,
    onNavigate: (String) -> Unit,
    onForward: () -> Unit,
    onOpenTabs: () -> Unit,
    onNewTab: () -> Unit,
    onNewWindow: () -> Unit,
    onNewIncognitoWindow: () -> Unit,
    onOpenHistory: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    var urlFieldValue by remember(currentUrl) {
        mutableStateOf(TextFieldValue(currentUrl))
    }
    var showMenu by remember { mutableStateOf(false) }
    val containerColor = TopAppBarDefaults.topAppBarColors().containerColor

    Surface(
        color = if (isIncognito) MaterialTheme.colorScheme.surfaceContainerHigh
        else containerColor
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!isNewTab) {
                OutlinedTextField(
                    value = urlFieldValue,
                    onValueChange = { urlFieldValue = it },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp)
                        .onFocusChanged { state ->
                            if (state.isFocused) {
                                urlFieldValue = urlFieldValue.copy(
                                    selection = TextRange(0, urlFieldValue.text.length)
                                )
                            }
                        },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = Color.Transparent,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                    ),
                    placeholder = { Text("Search or enter URL") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = {
                        onNavigate(urlFieldValue.text)
                        focusManager.clearFocus()
                    }),
                    textStyle = MaterialTheme.typography.bodyMedium
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }

            IconButton(onClick = onOpenTabs) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = tabCount.toString(),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            Box {
                IconButton(onClick = { showMenu = true }) {
                    IconMenu()
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Forward") },
                        leadingIcon = { IconForward() },
                        enabled = canGoForward,
                        onClick = { showMenu = false; onForward() }
                    )
                    DropdownMenuItem(
                        text = { Text("New tab") },
                        onClick = { showMenu = false; onNewTab() }
                    )
                    DropdownMenuItem(
                        text = { Text("New window") },
                        onClick = { showMenu = false; onNewWindow() }
                    )
                    DropdownMenuItem(
                        text = { Text("New incognito window") },
                        onClick = { showMenu = false; onNewIncognitoWindow() }
                    )
                    DropdownMenuItem(
                        text = { Text("History") },
                        onClick = { showMenu = false; onOpenHistory() }
                    )
                }
            }
        }
    }
}
