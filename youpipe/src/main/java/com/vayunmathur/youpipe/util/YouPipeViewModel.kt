package com.vayunmathur.youpipe.util

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.core.text.HtmlCompat
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.youpipe.data.CachedRelatedVideo
import com.vayunmathur.youpipe.data.CachedRelatedVideoDao
import com.vayunmathur.youpipe.data.DownloadedVideo
import com.vayunmathur.youpipe.data.DownloadedVideoDao
import com.vayunmathur.youpipe.data.HistoryVideo
import com.vayunmathur.youpipe.data.HistoryVideoDao
import com.vayunmathur.youpipe.data.Subscription
import com.vayunmathur.youpipe.data.SubscriptionCategory
import com.vayunmathur.youpipe.data.SubscriptionCategoryDao
import com.vayunmathur.youpipe.data.SubscriptionDao
import com.vayunmathur.youpipe.data.SubscriptionVideo
import com.vayunmathur.youpipe.data.SubscriptionVideoDao
import com.vayunmathur.youpipe.ui.AudioStream
import com.vayunmathur.youpipe.ui.ChannelInfo
import com.vayunmathur.youpipe.ui.Comment
import com.vayunmathur.youpipe.ui.ItemInfo
import com.vayunmathur.youpipe.ui.VideoChapter
import com.vayunmathur.youpipe.ui.VideoData
import com.vayunmathur.youpipe.ui.VideoInfo
import com.vayunmathur.youpipe.ui.VideoStream
import com.vayunmathur.youpipe.ui.fromHTML
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.stream.Description
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.zip.ZipInputStream
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
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
    private val subscriptionDao: SubscriptionDao,
    private val subscriptionCategoryDao: SubscriptionCategoryDao,
    private val subscriptionVideoDao: SubscriptionVideoDao,
    private val historyVideoDao: HistoryVideoDao,
    private val downloadedVideoDao: DownloadedVideoDao,
    private val cachedRelatedVideoDao: CachedRelatedVideoDao,
) : AndroidViewModel(application) {

    // ===================== Data StateFlows =====================

    val subscriptions: StateFlow<List<Subscription>> = subscriptionDao.getAllFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val subscriptionCategories: StateFlow<List<SubscriptionCategory>> = subscriptionCategoryDao.getAllFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val subscriptionVideos: StateFlow<List<SubscriptionVideo>> = subscriptionVideoDao.getAllFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val historyVideos: StateFlow<List<HistoryVideo>> = historyVideoDao.getAllFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val downloadedVideos: StateFlow<List<DownloadedVideo>> = downloadedVideoDao.getAllFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ===================== By-id flows =====================

    fun historyById(id: Long): Flow<HistoryVideo?> = historyVideoDao.getByIdFlow(id)
    fun downloadedById(id: Long): Flow<DownloadedVideo?> = downloadedVideoDao.getByIdFlow(id)

    // ===================== Mutations =====================

    fun upsertSubscription(item: Subscription) {
        viewModelScope.launch(Dispatchers.IO) { subscriptionDao.upsert(item) }
    }

    fun deleteSubscription(item: Subscription) {
        viewModelScope.launch(Dispatchers.IO) { subscriptionDao.delete(item) }
    }

    fun upsertHistoryVideo(item: HistoryVideo) {
        viewModelScope.launch(Dispatchers.IO) { historyVideoDao.upsert(item) }
    }

    fun deleteHistoryVideos(ids: List<Long>) {
        viewModelScope.launch(Dispatchers.IO) { historyVideoDao.deleteByIds(ids) }
    }

    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) { historyVideoDao.clearAll() }
    }

    fun deleteDownloadedVideo(item: DownloadedVideo) {
        viewModelScope.launch(Dispatchers.IO) { downloadedVideoDao.delete(item) }
    }

    suspend fun replaceCategory(originalCategoryName: String?, categoryName: String, ids: List<Long>) {
        withContext(Dispatchers.IO) {
            subscriptionCategoryDao.replaceCategory(originalCategoryName, categoryName, ids)
        }
    }

    // ===================== Recommendations =====================

    private val _recommendations = MutableStateFlow<List<VideoInfo>>(emptyList())
    val recommendations: StateFlow<List<VideoInfo>> = _recommendations.asStateFlow()

    private val _recommendationsLoading = MutableStateFlow(false)
    val recommendationsLoading: StateFlow<Boolean> = _recommendationsLoading.asStateFlow()

    fun loadRecommendations() {
        viewModelScope.launch(Dispatchers.IO) {
            _recommendationsLoading.value = true
            try {
                val cached = cachedRelatedVideoDao.getAllFlow().stateIn(viewModelScope).value
                val history = historyVideos.value
                val subs = subscriptions.value
                val subNames = subs.map { it.name.lowercase() }.toSet()

                val historyMap = history.associateBy { it.id }
                val authorFreq = mutableMapOf<String, Int>()
                history.forEach { h ->
                    val key = h.videoItem.author.lowercase()
                    authorFreq[key] = (authorFreq[key] ?: 0) + 1
                }

                val sourceTimestamps = history.associate { it.id to it.timestamp }
                val now = Clock.System.now()

                data class ScoredVideo(val video: VideoInfo, val score: Double)

                val scored = mutableMapOf<Long, ScoredVideo>()

                for (item in cached) {
                    val v = item.videoItem
                    if (v.author.lowercase() in subNames) continue
                    val h = historyMap[v.videoID]
                    if (h != null && v.duration > 0 && h.progress.toDouble() / (v.duration * 1000) >= 0.9) continue

                    val wc = minOf((authorFreq[v.author.lowercase()] ?: 0) * 2, 10).toDouble()
                    val sourceTs = sourceTimestamps[item.sourceVideoID]
                    val wr = if (sourceTs != null) {
                        val hoursAgo = (now - sourceTs).inWholeHours.toDouble()
                        maxOf(5.0 - hoursAgo / 24.0, 0.0)
                    } else 0.0
                    val uploadAgeDays = (now - v.uploadDate).inWholeDays.toDouble()
                    val d = kotlin.math.exp(-0.03 * uploadAgeDays)
                    val score = (wc + wr) * d

                    val existing = scored[v.videoID]
                    if (existing == null || score > existing.score) {
                        scored[v.videoID] = ScoredVideo(v, score)
                    }
                }

                val sorted = scored.values.sortedByDescending { it.score }

                val diverse = mutableListOf<VideoInfo>()
                var consecutiveAuthor = ""
                var consecutiveCount = 0
                for (sv in sorted) {
                    val author = sv.video.author.lowercase()
                    if (author == consecutiveAuthor) {
                        consecutiveCount++
                        if (consecutiveCount > 3) continue
                    } else {
                        consecutiveAuthor = author
                        consecutiveCount = 1
                    }
                    diverse.add(sv.video)
                }

                _recommendations.value = diverse
            } catch (e: Exception) {
                Log.e(TAG, "Recommendation error", e)
            }
            _recommendationsLoading.value = false
        }
    }

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
                    ServiceList.YouTube.suggestionExtractor
                        .suggestionList(query)
                        .map { HtmlCompat.fromHtml(it, HtmlCompat.FROM_HTML_MODE_LEGACY).toString() }
                } else {
                    emptyList()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Suggestion error", e)
            }
        }
    }

    /** Returns the resolved videoID if the query is a watch URL, else null. */
    fun resolveWatchUrl(): Long? {
        val q = _searchQuery.value
        return if (q.contains("/watch?v=")) videoURLtoID(q) else null
    }

    fun performSearch() {
        val q = _searchQuery.value
        searchJob?.cancel()
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val ex = ServiceList.YouTube.getSearchExtractor(q)
                ex.fetchPage()
                val results = ex.initialPage.items.mapNotNull { item ->
                    when (item) {
                        is StreamInfoItem -> {
                            val date = item.uploadDate ?: return@mapNotNull null
                            VideoInfo(
                                HtmlCompat.fromHtml(item.name, HtmlCompat.FROM_HTML_MODE_LEGACY).toString(),
                                videoURLtoID(item.url),
                                item.duration,
                                item.viewCount,
                                date.instant.toKotlinInstant(),
                                item.thumbnails.first().url,
                                HtmlCompat.fromHtml(item.uploaderName, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
                            )
                        }
                        is ChannelInfoItem -> ChannelInfo(
                            HtmlCompat.fromHtml(item.name, HtmlCompat.FROM_HTML_MODE_LEGACY).toString(),
                            channelURLtoID(item.url),
                            item.subscriberCount,
                            0,
                            item.thumbnails.first().url,
                        )
                        else -> null
                    }
                }
                _searchResults.value = results
                _suggestions.value = emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "Search error", e)
            }
        }
    }

    // ===================== Channel =====================

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
                getChannelVideos(info.channelID).forEach { video ->
                    _channelState.update { it.copy(videos = it.videos + video) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Channel load error", e)
            }
        }
    }

    // ===================== Video =====================

    data class VideoState(
        val data: VideoData? = null,
        val videoStreams: List<VideoStream> = emptyList(),
        val audioStreams: List<AudioStream> = emptyList(),
        val segments: List<VideoChapter> = emptyList(),
        val comments: List<Comment> = emptyList(),
        val relatedVideos: List<VideoInfo> = emptyList(),
        val sponsorSegments: List<SponsorSegment> = emptyList(),
        val deArrowTitle: String? = null,
        val deArrowThumbnail: String? = null,
        val error: Boolean = false,
    )

    private val _videoState = MutableStateFlow(VideoState())
    val videoState: StateFlow<VideoState> = _videoState.asStateFlow()
    private var videoJob: Job? = null
    private var sponsorJob: Job? = null
    private var deArrowJob: Job? = null

    fun loadVideo(videoID: Long, downloadedVideo: DownloadedVideo?) {
        videoJob?.cancel()
        sponsorJob?.cancel()
        deArrowJob?.cancel()
        _videoState.value = VideoState()

        // Sponsor segments load in parallel.
        sponsorJob = viewModelScope.launch(Dispatchers.IO) {
            val segs = getSponsorSegments(videoID)
            _videoState.update { it.copy(sponsorSegments = segs) }
        }

        deArrowJob = viewModelScope.launch(Dispatchers.IO) {
            if (_deArrowEnabled.value) {
                val branding = getDeArrowBranding(videoID)
                if (branding != null) {
                    _videoState.update {
                        it.copy(
                            deArrowTitle = branding.trustedTitle(),
                            deArrowThumbnail = branding.trustedThumbnailUrl(videoID)
                        )
                    }
                }
            }
        }

        videoJob = viewModelScope.launch {
            val url = videoIDtoURL(videoID)
            val youtubeService = ServiceList.YouTube
            try {
                withContext(Dispatchers.IO) {
                    val ex = youtubeService.getStreamExtractor(url)
                    ex.fetchPage()

                    val videoStreams: List<VideoStream>
                    val audioStreams: List<AudioStream>
                    val segments: List<VideoChapter>

                    if (downloadedVideo == null) {
                        segments = ex.streamSegments.map {
                            VideoChapter(it.startTimeSeconds * 1000, it.title, it.previewUrl)
                        }
                        val rawVideoOnly = ex.videoOnlyStreams
                        val rawAudio = ex.audioStreams

                        if (rawVideoOnly.isNotEmpty() && rawAudio.isNotEmpty()) {
                            videoStreams = rawVideoOnly.map { stream ->
                                val codecStr = stream.codec ?: ""
                                val codec = when {
                                    codecStr.contains("av01", ignoreCase = true) -> "av1"
                                    codecStr.contains("vp9", ignoreCase = true) || codecStr.contains("vp09", ignoreCase = true) -> "vp9"
                                    codecStr.contains("avc", ignoreCase = true) || codecStr.contains("h264", ignoreCase = true) -> "avc"
                                    else -> codecStr
                                }
                                VideoStream(
                                    stream.content,
                                    stream.width,
                                    stream.height,
                                    stream.bitrate,
                                    stream.fps,
                                    "${stream.height}p",
                                    codec,
                                    stream.itagItem?.contentLength ?: 0L
                                )
                            }.sortedWith(
                                compareByDescending<VideoStream> { it.height }
                                    .thenByDescending {
                                        when (it.codec) {
                                            "av1" -> 3
                                            "vp9" -> 2
                                            "avc" -> 1
                                            else -> 0
                                        }
                                    }
                            )
                            audioStreams = rawAudio.map { stream ->
                                val codecStr = stream.codec ?: ""
                                val codec = when {
                                    codecStr.contains("opus", ignoreCase = true) -> "opus"
                                    codecStr.contains("mp4a", ignoreCase = true) || codecStr.contains("aac", ignoreCase = true) -> "aac"
                                    else -> codecStr
                                }
                                AudioStream(
                                    stream.content,
                                    stream.bitrate,
                                    stream.audioLocale?.language ?: "Default",
                                    codec,
                                    stream.itagItem?.contentLength ?: 0L
                                )
                            }.sortedWith(
                                compareByDescending<AudioStream> { it.bitrate }
                                    .thenByDescending {
                                        when (it.codec) {
                                            "opus" -> 2
                                            "aac" -> 1
                                            else -> 0
                                        }
                                    }
                            )
                        } else {
                            videoStreams = ex.videoStreams.map { stream ->
                                val codecStr = stream.codec ?: ""
                                val codec = when {
                                    codecStr.contains("av01", ignoreCase = true) -> "av1"
                                    codecStr.contains("vp9", ignoreCase = true) || codecStr.contains("vp09", ignoreCase = true) -> "vp9"
                                    codecStr.contains("avc", ignoreCase = true) || codecStr.contains("h264", ignoreCase = true) -> "avc"
                                    else -> codecStr
                                }
                                VideoStream(
                                    stream.content,
                                    stream.width,
                                    stream.height,
                                    stream.bitrate,
                                    stream.fps,
                                    "${stream.height}p",
                                    codec,
                                    stream.itagItem?.contentLength ?: 0L
                                )
                            }.sortedWith(compareByDescending { it.height })
                            audioStreams = emptyList()
                        }
                    } else {
                        videoStreams = listOf(VideoStream(downloadedVideo.filePath, 1920, 1080, 0, 30, "Downloaded", "avc", 0L))
                        audioStreams = if (downloadedVideo.audioPath != null)
                            listOf(AudioStream(downloadedVideo.audioPath, 0, "Default", "aac", 0L))
                        else emptyList()
                        segments = emptyList()
                    }

                    val data = VideoData(
                        HtmlCompat.fromHtml(ex.name, HtmlCompat.FROM_HTML_MODE_LEGACY).toString(),
                        ex.viewCount,
                        ex.length,
                        ex.uploadDate!!.instant.toKotlinInstant(),
                        ex.thumbnails.first().url,
                        HtmlCompat.fromHtml(ex.uploaderName, HtmlCompat.FROM_HTML_MODE_LEGACY).toString(),
                        channelURLtoID(ex.uploaderUrl),
                        ex.uploaderAvatars.first().url,
                        ex.description.content.fromHTML()
                    )
                    val related = ex.relatedItems?.items?.filterIsInstance<StreamInfoItem>()?.mapNotNull {
                        val date = it.uploadDate ?: return@mapNotNull null
                        VideoInfo(
                            HtmlCompat.fromHtml(it.name, HtmlCompat.FROM_HTML_MODE_LEGACY).toString(),
                            videoURLtoID(it.url),
                            it.duration,
                            it.viewCount,
                            date.instant.toKotlinInstant(),
                            it.thumbnails.firstOrNull()?.url ?: "",
                            HtmlCompat.fromHtml(it.uploaderName, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
                        )
                    } ?: emptyList()

                    _videoState.update {
                        it.copy(
                            data = data,
                            videoStreams = videoStreams,
                            audioStreams = audioStreams,
                            segments = segments,
                            relatedVideos = related,
                        )
                    }

                    if (related.isNotEmpty()) {
                        cachedRelatedVideoDao.upsertAll(related.map {
                            CachedRelatedVideo(
                                sourceVideoID = videoID,
                                videoItem = it,
                                cachedAt = Clock.System.now()
                            )
                        })
                    }
                }
                withContext(Dispatchers.IO) {
                    val cex = youtubeService.getCommentsExtractor(url)
                    cex.fetchPage()
                    val comments = cex.initialPage.items.map { c ->
                        val content = if (c.commentText.type == Description.HTML) {
                            c.commentText.content.fromHTML()
                        } else {
                            HtmlCompat.fromHtml(c.commentText.content, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
                        }
                        Comment(content, HtmlCompat.fromHtml(c.uploaderName, HtmlCompat.FROM_HTML_MODE_LEGACY).toString(), c.likeCount, 0)
                    }
                    _videoState.update { it.copy(comments = comments) }
                }
            } catch (e: Exception) {
                if (downloadedVideo != null) {
                    val video = downloadedVideo
                    val data = VideoData(
                        video.videoItem.name,
                        video.videoItem.views,
                        video.videoItem.duration,
                        video.videoItem.uploadDate,
                        video.videoItem.thumbnailURL,
                        video.videoItem.author,
                        "",
                        "",
                        ""
                    )
                    _videoState.update {
                        it.copy(
                            data = data,
                            videoStreams = listOf(VideoStream(video.filePath, 1920, 1080, 0, 30, "Downloaded", "avc", 0L)),
                            audioStreams = if (video.audioPath != null)
                                listOf(AudioStream(video.audioPath, 0, "Default", "aac", 0L))
                            else emptyList(),
                        )
                    }
                } else {
                    _videoState.update { it.copy(error = true) }
                    Log.e(TAG, "Video load error", e)
                }
            }
        }
    }

    /**
     * When a download completes while the user is on the video page, swap the
     * playable streams over to the on-disk copy (mirrors the original
     * `LaunchedEffect(downloadedVideo)` in VideoPage).
     */
    fun applyDownloadedStreams(downloadedVideo: DownloadedVideo) {
        _videoState.update {
            it.copy(
                videoStreams = listOf(VideoStream(downloadedVideo.filePath, 1920, 1080, 0, 30, "Downloaded", "avc", 0L)),
                audioStreams = if (downloadedVideo.audioPath != null)
                    listOf(AudioStream(downloadedVideo.audioPath, 0, "Default", "aac", 0L))
                else emptyList(),
                segments = emptyList(),
            )
        }
    }

    fun clearVideoError() {
        _videoState.update { it.copy(error = false) }
    }

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

    // ===================== Settings: imports/exports =====================

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    private val _importProgress = MutableStateFlow(0f)
    val importProgress: StateFlow<Float> = _importProgress.asStateFlow()

    private val _sponsorBlockEnabled: StateFlow<Boolean> = DataStoreUtils
        .getInstance(application)
        .booleanFlow("sponsorblock_enabled")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val sponsorBlockEnabled: StateFlow<Boolean> = _sponsorBlockEnabled

    private val _deArrowEnabled: StateFlow<Boolean> = DataStoreUtils
        .getInstance(application)
        .booleanFlow("dearrow_enabled")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val deArrowEnabled: StateFlow<Boolean> = _deArrowEnabled

    private val _sponsorBlockCategories: StateFlow<Set<String>> = DataStoreUtils
        .getInstance(application)
        .stringSetFlow("sponsorblock_categories")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DEFAULT_SPONSOR_CATEGORIES)
    val sponsorBlockCategories: StateFlow<Set<String>> = _sponsorBlockCategories

    fun setDeArrowEnabled(enabled: Boolean) {
        viewModelScope.launch {
            DataStoreUtils.getInstance(getApplication()).setBoolean("dearrow_enabled", enabled)
        }
    }

    fun setSponsorBlockEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val ds = DataStoreUtils.getInstance(getApplication())
            ds.setBoolean("sponsorblock_enabled", enabled)
            if (enabled && _sponsorBlockCategories.value.isEmpty()) {
                setSponsorBlockCategories(DEFAULT_SPONSOR_CATEGORIES)
            }
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

    fun importYouTubeTakeout(uri: Uri) {
        val ctx = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            _isImporting.value = true
            _importProgress.value = 0f
            try {
                val zipInputStream = ZipInputStream(ctx.contentResolver.openInputStream(uri))
                var entry = zipInputStream.nextEntry
                val subs = mutableListOf<Subscription>()
                val history = mutableListOf<HistoryVideo>()

                while (entry != null) {
                    when {
                        entry.name.endsWith("subscriptions/subscriptions.csv") -> {
                            val content = zipInputStream.readBytes().decodeToString()
                            val lines = content.lines().drop(1)
                            val total = lines.size
                            lines.forEachIndexed { index, line ->
                                if (line.isNotBlank()) {
                                    val parts = line.split(",")
                                    if (parts.size >= 2) {
                                        val url = parts[1]
                                        try {
                                            val channelInfo = getChannelInfoFromURL(url)
                                            subs.add(channelInfo.toSubscription())
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Error fetching channel info for $url", e)
                                        }
                                    }
                                }
                                _importProgress.value = (index + 1).toFloat() / total
                            }
                        }
                        entry.name.endsWith("history/watch-history.json") -> {
                            val jsonString = zipInputStream.readBytes().decodeToString()
                            val jsonArray = Json.parseToJsonElement(jsonString).jsonArray
                            jsonArray.forEach { element ->
                                try {
                                    val title = element.jsonObject["title"]?.jsonPrimitive?.content?.removePrefix("Watched ") ?: ""
                                    val url = element.jsonObject["titleUrl"]?.jsonPrimitive?.content ?: ""
                                    val time = element.jsonObject["time"]?.jsonPrimitive?.content?.let { Instant.parse(it) } ?: Clock.System.now()
                                    val author = element.jsonObject["subtitles"]?.jsonArray?.firstOrNull()?.jsonObject?.get("name")?.jsonPrimitive?.content ?: ""

                                    if (url.contains("watch?v=")) {
                                        val videoID = videoURLtoID(url)
                                        history.add(
                                            HistoryVideo(
                                                id = videoID,
                                                progress = 0,
                                                videoItem = VideoInfo(
                                                    HtmlCompat.fromHtml(title, HtmlCompat.FROM_HTML_MODE_LEGACY).toString(),
                                                    videoID,
                                                    0,
                                                    0,
                                                    time,
                                                    "",
                                                    HtmlCompat.fromHtml(author, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
                                                ),
                                                timestamp = time
                                            )
                                        )
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error parsing history item", e)
                                }
                            }
                        }
                        entry.name.endsWith("history/watch-history.html") -> {
                            val html = zipInputStream.readBytes().decodeToString()
                            val regex = Regex(
                                "<div class=\"content-cell mdl-cell mdl-cell--6-col mdl-typography--body-1\">Watched&nbsp;<a href=\"(.*?)\">(.*?)</a><br><a href=\"(.*?)\">(.*?)</a><br>(.*?)</div>",
                                RegexOption.DOT_MATCHES_ALL
                            )
                            val matches = regex.findAll(html)
                            matches.forEach { match ->
                                try {
                                    val url = match.groupValues[1]
                                    val title = match.groupValues[2]
                                    val author = match.groupValues[4]

                                    if (url.contains("watch?v=")) {
                                        val videoID = videoURLtoID(url)
                                        history.add(
                                            HistoryVideo(
                                                id = videoID,
                                                progress = 0,
                                                videoItem = VideoInfo(
                                                    HtmlCompat.fromHtml(title, HtmlCompat.FROM_HTML_MODE_LEGACY).toString(),
                                                    videoID,
                                                    0,
                                                    0,
                                                    Clock.System.now(),
                                                    "",
                                                    HtmlCompat.fromHtml(author, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
                                                ),
                                                timestamp = Clock.System.now()
                                            )
                                        )
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error parsing HTML history item", e)
                                }
                            }
                        }
                    }
                    entry = zipInputStream.nextEntry
                }

                if (subs.isNotEmpty()) subscriptionDao.upsertAll(subs)
                if (history.isNotEmpty()) historyVideoDao.upsertAll(history)
            } catch (e: Exception) {
                Log.e(TAG, "Error importing YouTube Takeout", e)
            }
            _isImporting.value = false
            setupHourlyTask(ctx)
        }
    }

    fun exportSubscriptions(uri: Uri) {
        val ctx = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val subs = subscriptionDao.getAll()
                val json = Json.encodeToString(subs)
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
            } catch (e: Exception) {
                Log.e(TAG, "Error exporting subscriptions", e)
            }
        }
    }

    fun restoreSubscriptions(uri: Uri) {
        val ctx = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            _isImporting.value = true
            try {
                val json = ctx.contentResolver.openInputStream(uri)!!.bufferedReader().readText()
                val subs = Json.decodeFromString<List<Subscription>>(json)
                subscriptionDao.clearAll()
                subscriptionDao.upsertAll(subs)
            } catch (e: Exception) {
                Log.e(TAG, "Error restoring subscriptions", e)
            }
            _isImporting.value = false
            setupHourlyTask(ctx)
        }
    }

    fun importNewPipe(uri: Uri) {
        val ctx = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            _isImporting.value = true
            _importProgress.value = 0f
            try {
                val jsonString = ctx.contentResolver.openInputStream(uri)!!.bufferedReader().readText()
                val json = Json.parseToJsonElement(jsonString).jsonObject
                val subsArray = json["subscriptions"]?.jsonArray
                if (subsArray != null) {
                    val total = subsArray.size
                    val subs = mutableListOf<Subscription>()
                    subsArray.forEachIndexed { index, element ->
                        try {
                            var url = element.jsonObject["url"]?.jsonPrimitive?.content ?: ""
                            if (!url.startsWith("http")) url = "https://$url"
                            val channelInfo = getChannelInfoFromURL(url)
                            subs.add(channelInfo.toSubscription())
                        } catch (e: Exception) {
                            Log.e(TAG, "Error importing channel", e)
                        }
                        _importProgress.value = (index + 1).toFloat() / total
                    }
                    subscriptionDao.clearAll()
                    subscriptionDao.upsertAll(subs)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error importing NewPipe subscriptions", e)
            }
            _isImporting.value = false
            setupHourlyTask(ctx)
        }
    }

    // ===================== Hourly fetch task =====================

    init {
        setupHourlyTask(application)
        viewModelScope.launch(Dispatchers.IO) {
            val cutoff = Clock.System.now() - 30.days
            cachedRelatedVideoDao.deleteOlderThan(cutoff)
        }
    }

    companion object {
        private const val TAG = "YouPipeViewModel"

        val ALL_SPONSOR_CATEGORIES = setOf(
            "sponsor", "selfpromo", "interaction", "intro", "outro",
            "preview", "music_offtopic", "filler"
        )
        val DEFAULT_SPONSOR_CATEGORIES = setOf(
            "sponsor", "selfpromo", "interaction", "intro", "outro",
            "preview", "music_offtopic", "filler"
        )

        val SPONSOR_CATEGORY_LABELS = mapOf(
            "sponsor" to "Sponsor",
            "selfpromo" to "Self Promotion",
            "interaction" to "Interaction Reminder",
            "intro" to "Intro",
            "outro" to "Outro/Endcards",
            "preview" to "Preview/Recap",
            "music_offtopic" to "Non-Music in Music Videos",
            "filler" to "Filler",
        )
    }
}

/** Factory for constructing [YouPipeViewModel] with the DAOs. */
class YouPipeViewModelFactory(
    private val application: Application,
    private val subscriptionDao: SubscriptionDao,
    private val subscriptionCategoryDao: SubscriptionCategoryDao,
    private val subscriptionVideoDao: SubscriptionVideoDao,
    private val historyVideoDao: HistoryVideoDao,
    private val downloadedVideoDao: DownloadedVideoDao,
    private val cachedRelatedVideoDao: CachedRelatedVideoDao,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(YouPipeViewModel::class.java)) {
            "Unexpected ViewModel class: $modelClass"
        }
        return YouPipeViewModel(
            application,
            subscriptionDao,
            subscriptionCategoryDao,
            subscriptionVideoDao,
            historyVideoDao,
            downloadedVideoDao,
            cachedRelatedVideoDao,
        ) as T
    }
}
