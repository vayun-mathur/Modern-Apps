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
import com.vayunmathur.news.domain.LocalNews
import com.vayunmathur.news.domain.Story
import com.vayunmathur.news.domain.StoryGrouping
import com.vayunmathur.news.domain.orderedBySearchHits
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Feed = stories. Grouping ([StoryGrouping]) is purely mechanical —
 * exact server `storyId` key only, no client-side grouping — computed
 * in-memory over the Room article cache per emission (the two server
 * story columns are the only v2 addition). A null category filter is
 * "All"; a story matches a category when ANY member does.
 * [FeedRanking] only *orders* stories (Top folds cross-source
 * breadth — counted over actual server membership — into recency;
 * Latest is by latest update). Search hits surface their whole story.
 */

/** UI state and actions for feed, reader and saved screens. */
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

    /** Manually picked Local news location; null = unset (all locals equal). */
    val localLocation: StateFlow<String?> = prefs.localLocation
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** All stories over the full cache (unfiltered), grouped once per emission. */
    val allStories: StateFlow<List<Story>> = repository.articles
        .map { all -> StoryGrouping.group(all.map { it.toFeedArticle() }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Extra sources per feed-row URL, from actual server story membership only. */
    val clusterCounts: StateFlow<Map<String, Int>> = allStories
        .map { stories -> stories.associate { it.representative.url to (it.sourceCount - 1).coerceAtLeast(0) } }
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

    /** Search hits expanded to their whole stories, relevance order kept. */
    val searchStories: StateFlow<List<Story>> = combine(
        allStories, searchResults,
    ) { stories, hits ->
        stories.orderedBySearchHits(hits.map { it.url })
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

    /** Feed stories: mechanical grouping, category = any-member, ranked (order only). */
    val stories: StateFlow<List<Story>> = combine(
        allStories, _categoryFilter, feedSort, localLocation,
    ) { grouped, category, sort, location ->
        val filtered = grouped.filter { it.matchesCategory(category) }
        val ranked = FeedRanking.rankGroupedStories(filtered, sort, System.currentTimeMillis())
        if (category == NewsCategory.LOCAL && !location.isNullOrBlank()) {
            val (matching, rest) = ranked.partition { story ->
                story.articles.any { LocalNews.matches(SourceCatalog.regionOf(it.sourceId), location) }
            }
            matching + rest
        } else {
            ranked
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Cached lookup by article URL, for story cards/pages. */
    val cachedByUrl: StateFlow<Map<String, CachedArticle>> = repository.articles
        .map { all -> all.associateBy { it.url } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Representative articles in feed order (kept for existing callers/tests). */
    val articles: StateFlow<List<CachedArticle>> = combine(
        stories, cachedByUrl,
    ) { feedStories, byUrl ->
        feedStories.mapNotNull { byUrl[it.representative.url] }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Story by id from the full corpus, or null (story page lookup). */
    fun storyById(storyId: String): Story? = allStories.value.firstOrNull { it.id == storyId }

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

    /** Persists the manual Local news location; null/blank clears it. */
    fun setLocalLocation(location: String?) {
        viewModelScope.launch { prefs.setLocalLocation(location) }
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
