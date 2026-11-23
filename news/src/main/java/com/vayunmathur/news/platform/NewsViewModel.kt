package com.vayunmathur.news.platform

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vayunmathur.news.data.CachedArticle
import com.vayunmathur.news.data.NewsPrefs
import com.vayunmathur.news.data.NewsRepository
import com.vayunmathur.news.data.toFeedArticle
import com.vayunmathur.news.domain.ExtractedArticle
import com.vayunmathur.news.domain.FeedRanking
import com.vayunmathur.news.domain.FeedSort
import com.vayunmathur.news.domain.NewsCategory
import com.vayunmathur.news.domain.SourceCatalog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Feed filtering: every source is always in the corpus (no per-source
 * toggle). A null category filter is "All"; a non-null [NewsCategory]
 * keeps only articles whose source carries that catalog tag (see
 * [SourceCatalog.matchesCategory]). The persisted [FeedSort] then
 * orders the filtered corpus via [FeedRanking] (Top stories by
 * default); search keeps its own relevance ranking.
 */

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

    private val _categoryFilter = MutableStateFlow<NewsCategory?>(null)
    val categoryFilter: StateFlow<NewsCategory?> = _categoryFilter.asStateFlow()

    val feedSort: StateFlow<FeedSort> = prefs.feedSort
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FeedSort.TOP)

    /** Extra sources per feed-row URL, from story clustering (see [FeedRanking]). */
    private val _clusterCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val clusterCounts: StateFlow<Map<String, Int>> = _clusterCounts.asStateFlow()

    /** Cached-article count per source id, for the read-only Sources directory. */
    val sourceArticleCounts: StateFlow<Map<String, Int>> = repository.articles
        .map { all -> all.groupingBy { it.sourceId }.eachCount() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()
    private val _searchResults = MutableStateFlow<List<CachedArticle>>(emptyList())

    /**
     * Search composes with the category filter: the repository search
     * runs over the whole local corpus, and its results are then kept to
     * the selected category exactly like the feed. Search order is its
     * relevance ranking regardless of the feed sort.
     * So searching searches *within* the selected category, and changing
     * the category while a query is active re-filters the visible
     * results without a new search.
     */
    val searchResults: StateFlow<List<CachedArticle>> = combine(
        _searchResults, _categoryFilter,
    ) { results, category ->
        results.filter { SourceCatalog.matchesCategory(it.sourceId, category) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private var searchJob: kotlinx.coroutines.Job? = null

    /** Debounced on-device search (local corpus only); blank query clears results. */
    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        searchJob?.cancel()
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            return
        }
        searchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(SEARCH_DEBOUNCE_MS)
            val results = runCatching { repository.search(query) }.getOrDefault(emptyList())
            if (_searchQuery.value == query) {
                _searchResults.value = results
            }
        }
    }

    val articles: StateFlow<List<CachedArticle>> = combine(
        repository.articles, _categoryFilter, feedSort,
    ) { all, category, sort ->
        val filtered = all.filter { SourceCatalog.matchesCategory(it.sourceId, category) }
        val ranked = FeedRanking.rank(
            filtered.map { it.toFeedArticle() }, sort, System.currentTimeMillis(),
        )
        _clusterCounts.value = ranked.associate { it.article.url to it.alsoFromSources }
        val byUrl = filtered.associateBy { it.url }
        ranked.mapNotNull { byUrl[it.article.url] }
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
        viewModelScope.launch { prefs.clearLegacySourcePrefs() }
        refresh()
    }

    /** Single-select category filter; null is "All". */
    fun setCategoryFilter(category: NewsCategory?) {
        _categoryFilter.value = category
    }

    /** Persists the feed order (Top stories / Latest). Search is unaffected. */
    fun setFeedSort(sort: FeedSort) {
        viewModelScope.launch { prefs.setFeedSort(sort) }
    }

    fun refresh() {
        if (_isRefreshing.value) return
        viewModelScope.launch {
            _isRefreshing.value = true
            val failed = runCatching { repository.refresh(SourceCatalog.sources) }
                .getOrElse { listOf("all") }
            _refreshFailed.value = failed.isNotEmpty()
            _isRefreshing.value = false
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

    companion object {
        const val SEARCH_DEBOUNCE_MS = 250L
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
