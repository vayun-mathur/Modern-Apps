package com.vayunmathur.uniflix.platform

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.uniflix.data.TmdbApi
import com.vayunmathur.uniflix.domain.MediaItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Multi-search state with a short debounce. */
data class SearchUiState(
    val query: String = "",
    val loading: Boolean = false,
    val results: List<MediaItem> = emptyList(),
)

class SearchViewModel(app: Application) : AndroidViewModel(app) {
    private val api = TmdbApi()
    private var searchJob: Job? = null

    var state by mutableStateOf(SearchUiState())
        private set

    fun onQueryChange(value: String) {
        state = state.copy(query = value)
        searchJob?.cancel()
        if (value.isBlank()) {
            state = state.copy(results = emptyList(), loading = false)
            return
        }
        searchJob = viewModelScope.launch {
            delay(400)
            state = state.copy(loading = true)
            runCatching {
                state = state.copy(loading = false, results = api.search(value))
            }.onFailure {
                state = state.copy(loading = false)
                AppMessages.show("Search failed: ${it.message}")
            }
        }
    }
}
