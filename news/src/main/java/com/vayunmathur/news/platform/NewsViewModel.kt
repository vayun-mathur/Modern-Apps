package com.vayunmathur.news.platform

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vayunmathur.news.data.CachedArticle
import com.vayunmathur.news.data.NewsPrefs
import com.vayunmathur.news.data.NewsRepository
import com.vayunmathur.news.domain.ExtractedArticle
import com.vayunmathur.news.domain.SourceCatalog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Feed filtering: "all", a source id, or nothing matches (empty feed). */
const val FILTER_ALL = "all"

/** UI state and actions for feed, reader, sources and saved screens. */
class NewsViewModel(
    application: Application,
    private val repository: NewsRepository,
    private val prefs: NewsPrefs,
) : AndroidViewModel(application) {

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _refreshFailed = MutableStateFlow(false)
    val refreshFailed: StateFlow<Boolean> = _refreshFailed.asStateFlow()

    private val _sourceFilter = MutableStateFlow(FILTER_ALL)
    val sourceFilter: StateFlow<String> = _sourceFilter.asStateFlow()

    val disabledSourceIds: StateFlow<Set<String>> = prefs.disabledSourceIds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val articles: StateFlow<List<CachedArticle>> = combine(
        repository.articles, _sourceFilter, disabledSourceIds,
    ) { all, filter, disabled ->
        all.filter { it.sourceId !in disabled && (filter == FILTER_ALL || it.sourceId == filter) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val bookmarked: StateFlow<List<CachedArticle>> = repository.bookmarked
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val readerTextSp: StateFlow<Int> = prefs.readerTextSp
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NewsPrefs.DEFAULT_TEXT_SP)
    val readerSerif: StateFlow<Boolean> = prefs.readerSerif
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _readerBody = MutableStateFlow<ExtractedArticle?>(null)
    val readerBody: StateFlow<ExtractedArticle?> = _readerBody.asStateFlow()
    private val _readerError = MutableStateFlow(false)
    val readerError: StateFlow<Boolean> = _readerError.asStateFlow()
    private val _readerBookmarked = MutableStateFlow(false)
    val readerBookmarked: StateFlow<Boolean> = _readerBookmarked.asStateFlow()
    private var readerUrl: String? = null

    init {
        refresh()
    }

    fun setSourceFilter(filter: String) {
        _sourceFilter.value = filter
    }

    fun refresh() {
        if (_isRefreshing.value) return
        viewModelScope.launch {
            _isRefreshing.value = true
            val enabled = SourceCatalog.sources.filter { it.id !in disabledSourceIds.value }
            val failed = runCatching { repository.refresh(enabled) }.getOrElse { listOf("all") }
            _refreshFailed.value = failed.isNotEmpty()
            _isRefreshing.value = false
        }
    }

    fun setSourceEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            prefs.setSourceEnabled(id, enabled)
            if (enabled) refresh()
        }
    }

    /** Loads (cached-first) the body for [url]; stale loads for other URLs are ignored. */
    fun openArticle(url: String) {
        readerUrl = url
        _readerBody.value = null
        _readerError.value = false
        viewModelScope.launch {
            _readerBookmarked.value = repository.isBookmarked(url)
            runCatching { repository.articleBody(url) }
                .onSuccess { if (readerUrl == url) _readerBody.value = it }
                .onFailure { if (readerUrl == url) _readerError.value = true }
        }
    }

    fun toggleReaderBookmark() {
        val url = readerUrl ?: return
        viewModelScope.launch {
            val next = !repository.isBookmarked(url)
            repository.setBookmarked(url, next)
            _readerBookmarked.value = next
        }
    }

    fun toggleBookmark(article: CachedArticle) {
        viewModelScope.launch { repository.setBookmarked(article.url, !article.bookmarked) }
    }

    fun setReaderTextSp(sp: Int) {
        viewModelScope.launch { prefs.setReaderTextSp(sp) }
    }

    fun setReaderSerif(serif: Boolean) {
        viewModelScope.launch { prefs.setReaderSerif(serif) }
    }
}

/** Factory wiring the repository and prefs owners into [NewsViewModel]. */
class NewsViewModelFactory(
    private val application: Application,
    private val repository: NewsRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        NewsViewModel(application, repository, NewsPrefs(application)) as T
}
