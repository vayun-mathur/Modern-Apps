package com.vayunmathur.youpipe.util

import android.app.Application
import android.net.Uri
import com.vayunmathur.library.log.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.youpipe.DEFAULT_PAGE_HOME
import com.vayunmathur.youpipe.DEFAULT_PAGE_KEY
import com.vayunmathur.youpipe.data.CachedRelatedVideo
import com.vayunmathur.youpipe.data.ChannelPreference
import com.vayunmathur.youpipe.data.DownloadedVideo
import com.vayunmathur.youpipe.data.HistoryVideo
import com.vayunmathur.youpipe.data.KeywordPreference
import com.vayunmathur.youpipe.data.Playlist
import com.vayunmathur.youpipe.data.PlaylistItem
import com.vayunmathur.youpipe.data.RecommendationPreferences
import com.vayunmathur.youpipe.data.Subscription
import com.vayunmathur.youpipe.data.SubscriptionCategory
import com.vayunmathur.youpipe.data.SubscriptionRepository
import com.vayunmathur.youpipe.data.SubscriptionVideo
import com.vayunmathur.youpipe.ui.AudioStream
import com.vayunmathur.youpipe.ui.ChannelInfo
import com.vayunmathur.youpipe.ui.Comment
import com.vayunmathur.youpipe.ui.ItemInfo
import com.vayunmathur.youpipe.ui.VideoChapter
import com.vayunmathur.youpipe.ui.SubtitleTrack
import com.vayunmathur.youpipe.ui.VideoData
import com.vayunmathur.youpipe.ui.VideoInfo
import com.vayunmathur.youpipe.ui.VideoStream
import com.vayunmathur.youpipe.ui.fromHTML
import com.vayunmathur.youpipe.ui.getVideoCodecName
import com.vayunmathur.youpipe.ui.getAudioCodecName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.stream.Description
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.SubtitlesStream
import org.schabi.newpipe.extractor.localization.Localization
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.time.toKotlinInstant

/**
 * Single ViewModel for the YouPipe app.
 *
 * Owns:
 *  - Search query state, suggestions, and result list (via NewPipe Extractor).
 *  - Per-video data load (streams, comments, related, segments, sponsor segments)
 *    triggered by [loadVideo].
 *  - Per-channel data load triggered by [loadChannel].
 *  - YouTube/NewPipe/Youpipe import/export pipelines and their progress state.
 *  - WorkManager subscription-fetch progress mirror.
 *  - One-time hourly subscription-fetch task setup.
 *  - All Room CRUD through directly-injected DAOs.
 */
class YouPipeViewModel(
    application: Application,
    internal val repository: SubscriptionRepository,
) : AndroidViewModel(application) {

    /** Subscription persistence (delegates to the repository's store). */
    val subscriptionStore get() = repository.subscriptionStore

    /** Category persistence (delegates to the repository's store). */
    val categoryStore get() = repository.categoryStore

    /** Playlist persistence (delegates to the repository's store). */
    val playlistStore get() = repository.playlistStore

    /** History/download persistence (delegates to the repository's store). */
    val historyStore get() = repository.historyStore

    /** Recommendation persistence (delegates to the repository's store). */
    val recommendationStore get() = repository.recommendationStore

    // ===================== Data StateFlows =====================

    val subscriptions: StateFlow<List<Subscription>> = repository.subscriptions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val subscriptionCategories: StateFlow<List<SubscriptionCategory>> = repository.subscriptionCategories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _hasLoadedSubscriptionVideos = MutableStateFlow(false)

    /**
     * Flips true once the subscription-video DAO emits at least once, so the UI can
     * distinguish "still loading" from "loaded and empty".
     */
    val hasLoadedSubscriptionVideos: StateFlow<Boolean> = _hasLoadedSubscriptionVideos.asStateFlow()

    val subscriptionVideos: StateFlow<List<SubscriptionVideo>> = repository.subscriptionVideos
        .onEach { _hasLoadedSubscriptionVideos.value = true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val historyVideos: StateFlow<List<HistoryVideo>> = repository.historyVideos
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val downloadedVideos: StateFlow<List<DownloadedVideo>> = repository.downloadedVideos
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** All playlists sorted by their persisted [Playlist.position]. */
    val playlists: StateFlow<List<Playlist>> = repository.playlists
        .map { list -> list.sortedBy { it.position } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Every playlist membership row, for computing membership checks and per-playlist counts. */
    val allPlaylistItems: StateFlow<List<PlaylistItem>> = repository.playlistItems
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ===================== Derived, ready-to-render state =====================

    /** History sorted most-recent-first, as shown by the History screen. */
    val historyVideosByRecency: StateFlow<List<HistoryVideo>> = repository.historyVideos
        .map { list -> list.sortedByDescending { it.timestamp } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Downloaded videos sorted most-recent-first, as shown by the Downloads screen. */
    val downloadedVideosByRecency: StateFlow<List<DownloadedVideo>> = repository.downloadedVideos
        .map { list -> list.sortedByDescending { it.timestamp } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Distinct subscription-category names, as listed by the Subscriptions screen. */
    val categoryNames: StateFlow<List<String>> = repository.subscriptionCategories
        .map { cats -> cats.map { it.category }.distinct() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The videos to display on the subscription-videos screen for [category]
     * (all subscriptions when null), already filtered, mapped to [VideoInfo],
     * and sorted newest-first. Subscriptions referenced by a dangling category
     * row are skipped rather than crashing.
     */
    fun subscriptionVideosFor(category: String?): Flow<List<VideoInfo>> =
        combine(subscriptionVideos, subscriptions, subscriptionCategories) { videos, subs, cats ->
            val visible = if (category == null) {
                videos
            } else {
                val subIds = cats.filter { it.category == category }
                    .mapNotNull { pair -> subs.firstOrNull { it.id == pair.subscriptionID }?.id }
                    .toSet()
                videos.filter { it.channelID in subIds }
            }
            visible
                .map { VideoInfo(it.name, it.id, it.duration, it.views, it.uploadDate, it.thumbnailURL, it.author) }
                .sortedByDescending { it.uploadDate }
        }.flowOn(Dispatchers.Default)

    /**
     * The subscriptions currently assigned to [category]. A category row that
     * references a missing subscription is skipped rather than crashing.
     */
    fun subscriptionsInCategory(category: String): Flow<List<Subscription>> =
        combine(subscriptionCategories, subscriptions) { cats, subs ->
            cats.filter { it.category == category }
                .mapNotNull { pair -> subs.firstOrNull { it.id == pair.subscriptionID } }
        }

    // ===================== By-id flows =====================

    fun historyById(id: Long): Flow<HistoryVideo?> = repository.historyStore.historyById(id)
    fun downloadedById(id: Long): Flow<DownloadedVideo?> = repository.historyStore.downloadedById(id)

    fun playlistById(id: Long): Flow<Playlist?> = repository.playlistStore.playlistById(id)

    /** The items of [playlistId], sorted by their persisted [PlaylistItem.position]. */
    fun playlistItemsFor(playlistId: Long): Flow<List<PlaylistItem>> =
        repository.playlistStore.playlistItemsFor(playlistId).map { list -> list.sortedBy { it.position } }

    // ===================== Mutations =====================

    fun upsertSubscription(item: Subscription) {
        viewModelScope.launch(Dispatchers.IO) { repository.subscriptionStore.upsertSubscription(item) }
    }

    fun deleteSubscription(item: Subscription) {
        viewModelScope.launch(Dispatchers.IO) { repository.subscriptionStore.deleteSubscription(item) }
    }

    fun upsertHistoryVideo(item: HistoryVideo) {
        viewModelScope.launch(Dispatchers.IO) { repository.historyStore.upsertHistoryVideo(item) }
    }

    fun deleteHistoryVideos(ids: List<Long>) {
        viewModelScope.launch(Dispatchers.IO) { repository.historyStore.deleteHistoryVideosByIds(ids) }
    }

    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) { repository.historyStore.clearAllHistory() }
    }

    fun deleteDownloadedVideo(item: DownloadedVideo) {
        viewModelScope.launch(Dispatchers.IO) { repository.historyStore.deleteDownloadedVideo(item) }
    }

    // ===================== Playlists =====================
    // Playlist mutations live on [YouPipePlaylistOps] (TooManyFunctions cap);
    // behavior identical, same module. Call sites use `playlistOps.*` directly.

    /** Playlist mutations (split out: TooManyFunctions cap). Same module; behavior identical. */
    internal val playlistOps by lazy { YouPipePlaylistOps(this) }

    suspend fun replaceCategory(originalCategoryName: String?, categoryName: String, ids: List<Long>) {
        withContext(Dispatchers.IO) {
            repository.categoryStore.replaceCategory(originalCategoryName, categoryName, ids)
        }
    }

    internal val recommendationsMutable = MutableStateFlow<List<RankedVideo>>(emptyList())
    val recommendations: StateFlow<List<RankedVideo>> = recommendationsMutable.asStateFlow()

    internal val recommendationsLoadingMutable = MutableStateFlow(false)
    val recommendationsLoading: StateFlow<Boolean> = recommendationsLoadingMutable.asStateFlow()

    /** User-facing recommendation controls, defaulting to today's balanced behavior. */
    val recommendationPreferences: StateFlow<RecommendationPreferences> =
        repository.recommendationPreferencesFlow
            .map { it ?: RecommendationPreferences() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecommendationPreferences())

    val channelPreferences: StateFlow<List<ChannelPreference>> = repository.channelPreferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val keywordPreferences: StateFlow<List<KeywordPreference>> = repository.keywordPreferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The inferred interest profile, for the editable interest-management screen. */
    val interestProfile: StateFlow<InterestProfile> =
        combine(repository.historyVideos, recommendationPreferences) { history, prefs ->
            buildInterestProfile(history, Clock.System.now(), RecommendationWeights.fromPreferences(prefs))
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InterestProfile(emptyMap(), emptyMap()))

    // In-VM TTL cache for Trending so repeated Search-screen visits don't refetch.
    internal var trendingCache: Pair<Instant, List<VideoInfo>>? = null

    // Recommendation engine lives in YouPipeRecommendations.kt as extensions.

    // ===================== Search =====================

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions: StateFlow<List<String>> = _suggestions.asStateFlow()

    private val _searchResults = MutableStateFlow<List<ItemInfo>>(emptyList())
    val searchResults: StateFlow<List<ItemInfo>> = _searchResults.asStateFlow()

    private var suggestionJob: Job? = null
    private var searchJob: Job? = null

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        suggestionJob?.cancel()
        suggestionJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                _suggestions.value = if (query.isNotBlank()) {
                    ServiceList.YouTube.getSuggestionExtractor()
                        .suggestionList(query)
                        .map { it.decodeHtml() }
                } else {
                    emptyList()
                }
            } catch (e: org.schabi.newpipe.extractor.exceptions.ExtractionException) {
                Log.error(TAG, "Suggestion error", e)
            } catch (e: java.io.IOException) {
                Log.error(TAG, "Suggestion error", e)
            }
        }
    }

    fun performSearch() {
        val q = _searchQuery.value
        searchJob?.cancel()
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val ex = ServiceList.YouTube.getSearchExtractor(q)
                ex.fetchPage()
                val results = ex.getInitialPage().getItems().mapNotNull { item ->
                    when (item) {
                        is StreamInfoItem -> item.toVideoInfo()
                        is ChannelInfoItem -> ChannelInfo(
                            item.name.decodeHtml(),
                            channelURLtoID(item.url),
                            item.getSubscriberCount(),
                            0,
                            item.thumbnails.first().url,
                        )
                        else -> null
                    }
                }
                _searchResults.value = results
                fetchDeArrowForVideos(results.filterIsInstance<VideoInfo>().map { it.videoID })
                _suggestions.value = emptyList()
            } catch (e: org.schabi.newpipe.extractor.exceptions.ExtractionException) {
                Log.error(TAG, "Search error", e)
            } catch (e: java.io.IOException) {
                Log.error(TAG, "Search error", e)
            }
        }
    }

    // ===================== Channel =====================

    /** Immutable value describing channel state. */
    data class ChannelState(
        val info: ChannelInfo? = null,
        val videos: List<VideoInfo> = emptyList(),
    )

    private val _channelState = MutableStateFlow(ChannelState())
    val channelState: StateFlow<ChannelState> = _channelState.asStateFlow()
    private var channelJob: Job? = null

    fun loadChannel(channelID: String) {
        channelJob?.cancel()
        _channelState.value = ChannelState()
        channelJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val info = getChannelInfo(channelID)
                _channelState.update { it.copy(info = info) }
                val preferences = repository.recommendationStore.getRecommendationPreferences()
                val hidePaid = (preferences ?: RecommendationPreferences()).hidePaid
                val channelVideos = mutableListOf<VideoInfo>()
                getChannelVideos(info.channelID).forEach { video ->
                    if (hidePaid && video.isPaid) return@forEach
                    channelVideos.add(video)
                    _channelState.update { it.copy(videos = it.videos + video) }
                }
                fetchDeArrowForVideos(channelVideos.map { it.videoID })
            } catch (e: org.schabi.newpipe.extractor.exceptions.ExtractionException) {
                Log.error(TAG, "Channel load error", e)
            } catch (e: java.io.IOException) {
                Log.error(TAG, "Channel load error", e)
            }
        }
    }

    // ===================== Video =====================

    /** Immutable value describing video state. */
    data class VideoState(
        val data: VideoData? = null,
        val videoStreams: List<VideoStream> = emptyList(),
        val audioStreams: List<AudioStream> = emptyList(),
        val segments: List<VideoChapter> = emptyList(),
        val subtitles: List<SubtitleTrack> = emptyList(),
        val comments: List<Comment> = emptyList(),
        val relatedVideos: List<VideoInfo> = emptyList(),
        val sponsorSegments: List<SponsorSegment> = emptyList(),
        val error: Boolean = false,
    )

    internal val videoStateMutable = MutableStateFlow(VideoState())
    val videoState: StateFlow<VideoState> = videoStateMutable.asStateFlow()
    internal var videoJob: Job? = null
    internal var sponsorJob: Job? = null

    // Video loading lives in YouPipeVideoLoading.kt as extensions.

    // ===================== Subscription fetch progress (WorkManager) =====================

    /**
     * Mirrors the currently-running "subscription_fetch_immediate" WorkInfo's
     * progress as a float in [0f, 1f], or -1f if no fetch is running.
     */
    val fetchProgress: StateFlow<Float> = WorkManager.getInstance(application)
        .getWorkInfosForUniqueWorkFlow("subscription_fetch_immediate")
        .map { infos ->
            infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
                ?.progress?.getFloat("progress", -1f) ?: -1f
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), -1f)

    // ===================== DeArrow cache =====================

    /** Immutable value describing de arrow data; carries title, thumbnailUrl. */
    data class DeArrowData(val title: String?, val thumbnailUrl: String?)

    private val _deArrowCache = MutableStateFlow<Map<Long, DeArrowData>>(emptyMap())
    val deArrowCache: StateFlow<Map<Long, DeArrowData>> = _deArrowCache.asStateFlow()

    private val deArrowSemaphore = Semaphore(5)

    fun fetchDeArrowForVideos(videoIds: List<Long>) {
        if (!_deArrowEnabled.value) return
        val idsToFetch = videoIds.filter { it !in _deArrowCache.value }
        if (idsToFetch.isEmpty()) return
        idsToFetch.forEach { id ->
            viewModelScope.launch(Dispatchers.IO) {
                deArrowSemaphore.withPermit {
                    val branding = getDeArrowBranding(id)
                    val data = DeArrowData(
                        branding?.trustedTitle(),
                        branding?.trustedThumbnailUrl(id)
                    )
                    _deArrowCache.update { it + (id to data) }
                }
            }
        }
    }

    // ===================== Settings: imports/exports =====================

    internal val isImportingMutable = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = isImportingMutable.asStateFlow()

    internal val importProgressMutable = MutableStateFlow(0f)
    val importProgress: StateFlow<Float> = importProgressMutable.asStateFlow()

    val sponsorBlockEnabled: StateFlow<Boolean> = DataStoreUtils
        .getInstance(application)
        .stringSetFlow("sponsorblock_categories")
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _deArrowEnabled: StateFlow<Boolean> = DataStoreUtils
        .getInstance(application)
        .booleanFlow("dearrow_enabled")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val deArrowEnabled: StateFlow<Boolean> = _deArrowEnabled

    /** Preferred language for YouTube-sourced content (titles, comments, etc.). Empty = system default. */
    val youtubeLanguage: StateFlow<String> = DataStoreUtils
        .getInstance(application)
        .stringFlow("youtube_language")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    fun setYouTubeLanguage(code: String) {
        viewModelScope.launch {
            DataStoreUtils.getInstance(getApplication()).setString("youtube_language", code)
            NewPipe.setupLocalization(youtubeLocalization(code))
        }
    }

    /** Which screen the app opens to at launch. See [DEFAULT_PAGE_KEY]; default Home. */
    val defaultPage: StateFlow<String> = DataStoreUtils
        .getInstance(application)
        .stringFlow(DEFAULT_PAGE_KEY)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DEFAULT_PAGE_HOME)

    fun setDefaultPage(key: String) {
        viewModelScope.launch {
            DataStoreUtils.getInstance(getApplication()).setString(DEFAULT_PAGE_KEY, key)
        }
    }

    private val _sponsorBlockCategories: StateFlow<Set<String>> = DataStoreUtils
        .getInstance(application)
        .stringSetFlow("sponsorblock_categories")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DEFAULT_SPONSOR_CATEGORIES)
    val sponsorBlockCategories: StateFlow<Set<String>> = _sponsorBlockCategories

    private val _keepPlayerControlsVisible: StateFlow<Boolean> = DataStoreUtils
        .getInstance(application)
        .booleanFlow("keep_player_controls_visible")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val keepPlayerControlsVisible: StateFlow<Boolean> = _keepPlayerControlsVisible

    private val _notificationStyleProgressBar: StateFlow<Boolean> = DataStoreUtils
        .getInstance(application)
        .booleanFlow("notification_style_progress_bar")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val notificationStyleProgressBar: StateFlow<Boolean> = _notificationStyleProgressBar

    /**
     * The tempo multiplier, kept across videos.
     *
     * Durable because it stopped being a per-video whim the moment a television could show it: a speed
     * that reset on every navigation would have the TV's overlay disagree with the phone for reasons
     * neither screen explains. Stored as a double because that is what `DataStoreUtils` has; the player
     * works in floats.
     */
    private val _playbackSpeed: StateFlow<Float> = DataStoreUtils
        .getInstance(application)
        .doubleFlow("playback_speed")
        .map { it.toFloat() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 1f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed

    fun setPlaybackSpeed(speed: Float) {
        viewModelScope.launch {
            DataStoreUtils.getInstance(getApplication()).setDouble("playback_speed", speed.toDouble())
        }
    }

    fun setKeepPlayerControlsVisible(keepVisible: Boolean) {
        viewModelScope.launch {
            DataStoreUtils.getInstance(getApplication()).setBoolean("keep_player_controls_visible", keepVisible)
        }
    }

    fun setNotificationStyleProgressBar(enabled: Boolean) {
        viewModelScope.launch {
            DataStoreUtils.getInstance(getApplication()).setBoolean("notification_style_progress_bar", enabled)
        }
    }

    fun setDeArrowEnabled(enabled: Boolean) {
        viewModelScope.launch {
            DataStoreUtils.getInstance(getApplication()).setBoolean("dearrow_enabled", enabled)
        }
    }

    fun setSponsorBlockCategories(categories: Set<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            val ds = DataStoreUtils.getInstance(getApplication())
            // Clear and re-add all categories
            for (cat in ALL_SPONSOR_CATEGORIES) {
                ds.removeStringFromSet("sponsorblock_categories", cat)
            }
            for (cat in categories) {
                ds.addStringToSet("sponsorblock_categories", cat)
            }
        }
    }

    fun toggleSponsorBlockCategory(category: String) {
        val current = _sponsorBlockCategories.value
        val updated = if (category in current) current - category else current + category
        setSponsorBlockCategories(updated)
    }

    // Import/export pipelines live in YouPipeImportExport.kt as extensions.

    // ===================== Hourly fetch task =====================

    init {
        setupHourlyTask(application)
        viewModelScope.launch {
            DataStoreUtils.getInstance(application).stringFlow("youtube_language").collect { code ->
                NewPipe.setupLocalization(youtubeLocalization(code))
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            val cutoff = Clock.System.now() - 30.days
            repository.recommendationStore.deleteCachedRelatedOlderThan(cutoff)
            repository.recommendationStore.deleteRecommendationImpressionsOlderThan(cutoff)
        }
        // Seed the mandatory "Watch later" playlist once (covers fresh installs and upgrades).
        viewModelScope.launch(Dispatchers.IO) {
            if (repository.playlistStore.getAllPlaylists().none { it.mandatory }) {
                repository.playlistStore.upsertPlaylist(
                    Playlist(name = "Watch later", position = 0.0, mandatory = true),
                )
            }
        }
    }

    /** Shared constants and helpers for YouPipeViewModel. */
    companion object {
        private const val TAG = "YouPipeViewModel"

        internal val TRENDING_TTL = 5.minutes

        val ALL_SPONSOR_CATEGORIES = setOf(
            "sponsor", "selfpromo", "interaction", "intro", "outro",
            "preview", "music_offtopic", "filler"
        )
        val DEFAULT_SPONSOR_CATEGORIES = ALL_SPONSOR_CATEGORIES

        val SPONSOR_CATEGORY_LABELS = mapOf(
            "sponsor" to "Block Sponsors",
            "selfpromo" to "Block Self Promotion",
            "interaction" to "Block Interaction Reminders",
            "intro" to "Block Intros",
            "outro" to "Block Outros",
            "preview" to "Block Previews",
            "music_offtopic" to "Block Non-Music",
            "filler" to "Block Filler",
        )

        /** Selectable YouTube content languages: (localization code, endonym display name). */
        val YOUTUBE_LANGUAGES: List<Pair<String, String>> = listOf(
            "en" to "English",
            "es" to "Español",
            "fr" to "Français",
            "de" to "Deutsch",
            "it" to "Italiano",
            "pt" to "Português",
            "ru" to "Русский",
            "ja" to "日本語",
            "ko" to "한국어",
            "zh-CN" to "中文 (简体)",
            "zh-TW" to "中文 (繁體)",
            "ar" to "العربية",
            "hi" to "हिन्दी",
            "id" to "Indonesia",
            "nl" to "Nederlands",
            "pl" to "Polski",
            "tr" to "Türkçe",
            "vi" to "Tiếng Việt",
            "th" to "ไทย",
            "sv" to "Svenska",
            "uk" to "Українська",
            "fil" to "Filipino",
        )
    }
}

/** Stream-mapping helpers live in YouPipeVideoLoading.kt. */

/** Builds a NewPipe [Localization] for the given language [code]; blank falls back to the device locale. */
fun youtubeLocalization(code: String): Localization =
    if (code.isBlank()) {
        Localization.fromLocale(Locale.getDefault())
    } else {
        Localization.fromLocalizationCode(code)
            ?: Localization.fromLocale(Locale.forLanguageTag(code))
    }

/** Factory for constructing [YouPipeViewModel] with the repository. */
class YouPipeViewModelFactory(
    private val application: Application,
    private val repository: SubscriptionRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(YouPipeViewModel::class.java)) {
            "Unexpected ViewModel class: $modelClass"
        }
        return YouPipeViewModel(
            application,
            repository,
        ) as T
    }
}

/** Returns the resolved videoID if the current search query is a watch URL, else null. */
fun YouPipeViewModel.resolveWatchUrl(): Long? {
    val q = searchQuery.value
    return if (q.contains("/watch?v=")) videoURLtoID(q) else null
}
