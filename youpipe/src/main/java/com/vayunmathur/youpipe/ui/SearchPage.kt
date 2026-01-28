package com.vayunmathur.youpipe.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import com.vayunmathur.youpipe.BottomBar
import com.vayunmathur.youpipe.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import kotlin.time.toKotlinInstant

@Composable
fun SearchPage(backStack: NavBackStack<Route>) {
    // State Management
    var searchQuery by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var searchResults by remember { mutableStateOf<List<VideoInfo>>(emptyList()) }

    val scope = rememberCoroutineScope()

    fun search() {
        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val extractor = ServiceList.YouTube.getSearchExtractor(searchQuery)
                    extractor.fetchPage()
                    withContext(Dispatchers.Main) {
                        searchResults =
                            extractor.initialPage.items.filterIsInstance<StreamInfoItem>().map {
                                VideoInfo(
                                    it.name,
                                    it.url,
                                    it.viewCount,
                                    it.uploadDate!!.instant.toKotlinInstant(),
                                    it.thumbnails.first().url,
                                    it.uploaderName
                                )
                            }
                        suggestions = emptyList() // Hide suggestions when searching
                        println(searchResults)
                    }
                } catch (e: Exception) {
                    // Handle search error
                }
            }
        }
    }

    Scaffold(bottomBar = { BottomBar(backStack, Route.SearchPage) }) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues).padding(16.dp)) {
            TextField(
                value = searchQuery,
                onValueChange = { newValue ->
                    searchQuery = newValue
                    // Continuous updates for suggestions
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            try {
                                if (newValue.isNotBlank()) {
                                    suggestions = ServiceList.YouTube
                                        .suggestionExtractor
                                        .suggestionList(newValue)
                                } else {
                                    suggestions = emptyList()
                                }
                            } catch (e: Exception) {
                                // Handle extraction error
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Search") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search() }),
                singleLine = true
            )

            // Result / Suggestion List
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (searchResults.isNotEmpty()) {
                    // Show actual Search Results
                    items(searchResults) { item ->
                        VideoItem(backStack, item, true)
                    }
                } else {
                    // Show Suggestions
                    items(suggestions) { suggestion ->
                        Text(
                            text = suggestion,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    searchQuery = suggestion
                                    search()
                                }
                                .padding(12.dp)
                        )
                    }
                }
            }
        }
    }
}