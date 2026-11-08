package com.vayunmathur.uniflix.platform

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.uniflix.data.TmdbApi
import com.vayunmathur.uniflix.data.UniflixRepository
import com.vayunmathur.uniflix.domain.MediaItem
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Home rows: trending + top-rated, served by the self-hosted TMDB proxy. */
data class HomeUiState(
    val loading: Boolean = false,
    val trendingMovies: List<MediaItem> = emptyList(),
    val trendingTv: List<MediaItem> = emptyList(),
    val topMovies: List<MediaItem> = emptyList(),
    val topTv: List<MediaItem> = emptyList(),
)

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val api = TmdbApi()
    val repository = UniflixRepository.get(app)

    var state by mutableStateOf(HomeUiState())
        private set

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            state = state.copy(loading = true)
            runCatching {
                val movies = api.trendingMovies()
                val tv = api.trendingTv()
                val topM = api.topRatedMovies()
                val topT = api.topRatedTv()
                state = state.copy(
                    loading = false,
                    trendingMovies = movies,
                    trendingTv = tv,
                    topMovies = topM,
                    topTv = topT,
                )
            }.onFailure {
                state = state.copy(loading = false)
                AppMessages.show("Catalog failed to load: ${it.message}")
            }
        }
    }

    /** Continue-watching row source, newest first. */
    val continueWatching get() = repository.continueWatching

    /** Watchlist row source, newest first. */
    val watchlist get() = repository.watchlist

    fun watchlistSnapshot() = combine(repository.watchlist, repository.continueWatching) { w, _ -> w }
}
