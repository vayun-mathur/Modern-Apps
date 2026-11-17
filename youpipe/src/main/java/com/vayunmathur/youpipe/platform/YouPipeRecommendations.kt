package com.vayunmathur.youpipe.platform

import com.vayunmathur.library.log.Log
import androidx.lifecycle.viewModelScope
import com.vayunmathur.youpipe.data.ChannelPreference
import com.vayunmathur.youpipe.data.HistoryVideo
import com.vayunmathur.youpipe.data.KeywordPreference
import com.vayunmathur.youpipe.data.RecommendationPreferences
import com.vayunmathur.youpipe.data.Subscription
import com.vayunmathur.youpipe.ui.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Instant
import com.vayunmathur.youpipe.domain.Candidate
import com.vayunmathur.youpipe.domain.ChannelImpressionStat
import com.vayunmathur.youpipe.domain.ChannelPref
import com.vayunmathur.youpipe.domain.ContentFilters
import com.vayunmathur.youpipe.domain.InterestProfile
import com.vayunmathur.youpipe.domain.RankedVideo
import com.vayunmathur.youpipe.domain.RecSource
import com.vayunmathur.youpipe.domain.RecommendationWeights
import com.vayunmathur.youpipe.domain.buildInterestProfile
import com.vayunmathur.youpipe.domain.channelNameMatches
import com.vayunmathur.youpipe.network.channelURLtoID
import com.vayunmathur.youpipe.network.getChannelVideos
import com.vayunmathur.youpipe.network.getTrendingVideos
import com.vayunmathur.youpipe.domain.rankRecommendations
import com.vayunmathur.youpipe.network.searchVideos

private const val TAG = "YouPipeViewModel"

private const val MAX_TOP_CHANNELS = 5
private const val TOP_CHANNEL_VIDEOS = 5
private const val MAX_SEARCH_KEYWORDS = 2

internal const val CUSTOM_PRESET = "CUSTOM"
internal const val DEMOTE_MULTIPLIER = 0.3
internal const val BOOST_MULTIPLIER = 3.0
private const val REFRESH_NOISE = 0.15
private const val QUERY_TRIGRAM_KEYWORDS = 3

fun YouPipeViewModel.loadRecommendations() {
    viewModelScope.launch(Dispatchers.IO) {
        recommendationsLoadingMutable.value = true
        try {
            // Read directly from the DAOs so a cold Search screen (with no active
            // collector on the history/sub flows) still sees the persisted data.
            val history = repository.historyStore.getAllHistoryVideos()
            val subs = repository.subscriptionStore.getAllSubscriptions()
            val subNames = subs.map { it.name.lowercase() }.toSet()
            val now = Clock.System.now()

            val prefs = repository.recommendationStore.getRecommendationPreferences()
                ?: RecommendationPreferences()
            val weights = RecommendationWeights.fromPreferences(prefs)
            val filters = ContentFilters(
                hideShorts = prefs.hideShorts,
                hideLive = prefs.hideLive,
                hidePaid = prefs.hidePaid,
                minDurationSec = prefs.minDurationSec,
                maxDurationSec = prefs.maxDurationSec,
            )

            val channelPrefs = repository.recommendationStore.getAllChannelPreferences().associate {
                it.channelKey to ChannelPref(it.multiplier, it.blocked, it.pinned)
            }
            val mutedKeywords = repository.recommendationStore.getAllKeywordPreferences()
                .filter { it.muted }.map { it.keyword }.toSet()

            val impressions = repository.recommendationStore.getAllRecommendationImpressions()
            val watchedIds = history.map { it.id }.toSet()
            val channelStats = impressions.groupBy { it.channelKey }.mapValues { (_, imps) ->
                ChannelImpressionStat(
                    shownCount = imps.sumOf { it.shownCount },
                    watched = imps.any { it.videoID in watchedIds },
                )
            }
            val recentlyShown = impressions.associate { it.videoID to it.lastShownAt }

            val profile = buildInterestProfile(history, now, weights)
            val candidates = gatherCandidates(profile, prefs, subs, history)
            val ranked = rankRecommendations(
                candidates = candidates,
                history = history,
                subNames = subNames,
                now = now,
                weights = weights,
                channelPrefs = channelPrefs,
                mutedKeywords = mutedKeywords,
                contentFilters = filters,
                channelStats = channelStats,
                recentlyShown = recentlyShown,
                noise = { Random.nextDouble(0.0, REFRESH_NOISE) },
            )

            recommendationsMutable.value = ranked
            recordImpressions(ranked, now)
            fetchDeArrowForVideos(ranked.map { it.video.videoID })
        } catch (e: java.io.IOException) {
            Log.error(TAG, "Recommendation error", e)
        } catch (e: IllegalStateException) {
            Log.error(TAG, "Recommendation error", e)
        } catch (e: org.schabi.newpipe.extractor.exceptions.ExtractionException) {
            Log.error(TAG, "Recommendation error", e)
        }
        recommendationsLoadingMutable.value = false
    }
}

internal suspend fun YouPipeViewModel.recordImpressions(ranked: List<RankedVideo>, now: Instant) {
    try {
        ranked.forEach { rv ->
            repository.recommendationStore.recordRecommendationImpression(
                rv.video.videoID, rv.video.author.lowercase(), rv.source.name, now,
            )
        }
    } catch (e: IllegalStateException) {
        Log.error(TAG, "Impression record error", e)
    } catch (e: android.database.sqlite.SQLiteException) {
        Log.error(TAG, "Impression record error", e)
    }
}

/**
 * Pulls candidates from all enabled sources in parallel and merges them. Each
 * source is isolated in its own try/catch so one failing network call never
 * blanks the feed. Disabled sources (per [prefs]) are skipped entirely.
 */
internal suspend fun YouPipeViewModel.gatherCandidates(
    profile: InterestProfile,
    prefs: RecommendationPreferences,
    subs: List<Subscription>,
    history: List<HistoryVideo>,
): List<Candidate> = coroutineScope {
    val sourceTimestamps = history.associate { it.id to it.timestamp }
    val sourceTitles = history.associate { it.id to it.videoItem.name }

    val relatedDeferred = async(Dispatchers.IO) {
        if (!prefs.sourceRelated) return@async emptyList()
        loadRelatedCandidates(repository, sourceTimestamps, sourceTitles)
    }

    val trendingDeferred = async(Dispatchers.IO) {
        if (!prefs.sourceTrending) return@async emptyList()
        loadTrendingCandidates()
    }

    val subscriptionCandidates = if (prefs.sourceSubscription) {
        repository.subscriptionStore.getAllSubscriptionVideos().map {
            Candidate(
                VideoInfo(it.name, it.id, it.duration, it.views, it.uploadDate, it.thumbnailURL, it.author),
                RecSource.SUBSCRIPTION,
            )
        }
    } else {
        emptyList()
    }

    val topChannelDeferred = async(Dispatchers.IO) {
        if (!prefs.sourceTopChannel) return@async emptyList()
        loadTopChannelCandidates(profile, subs)
    }

    val searchDeferred = async(Dispatchers.IO) {
        if (!prefs.sourceSearch) return@async emptyList()
        loadSearchCandidates(profile)
    }

    relatedDeferred.await() + trendingDeferred.await() + subscriptionCandidates +
        topChannelDeferred.await() + searchDeferred.await()
}

/** Cached related-video candidates; a DB failure here must not blank the feed. */
internal suspend fun YouPipeViewModel.loadRelatedCandidates(
    repository: com.vayunmathur.youpipe.data.SubscriptionRepository,
    sourceTimestamps: Map<Long, Instant>,
    sourceTitles: Map<Long, String>,
): List<Candidate> = try {
    repository.recommendationStore.getAllCachedRelatedVideos().map { item ->
        Candidate(
            item.videoItem,
            RecSource.RELATED,
            sourceTimestamps[item.sourceVideoID],
            sourceTitles[item.sourceVideoID],
        )
    }
} catch (e: IllegalStateException) {
    Log.error(TAG, "Related candidates error", e); emptyList()
} catch (e: android.database.sqlite.SQLiteException) {
    Log.error(TAG, "Related candidates error", e); emptyList()
}

/** Trending candidates; a network failure here must not blank the feed. */
/**
 * Runs one candidate source, logging and returning empty on any extractor,
 * IO, or unchecked extractor failure so one source cannot blank the feed.
 */
@Suppress("TooGenericExceptionCaught") // Extractor failures arrive as unchecked exceptions too.
internal suspend fun YouPipeViewModel.candidatesOrEmpty(
    label: String,
    block: suspend () -> List<Candidate>,
): List<Candidate> = try {
    block()
} catch (e: org.schabi.newpipe.extractor.exceptions.ExtractionException) {
    Log.error(TAG, "$label candidates error", e); emptyList()
} catch (e: java.io.IOException) {
    Log.error(TAG, "$label candidates error", e); emptyList()
} catch (e: RuntimeException) {
    Log.error(TAG, "$label candidates error", e); emptyList()
}

internal suspend fun YouPipeViewModel.loadTrendingCandidates(): List<Candidate> =
    candidatesOrEmpty("Trending") {
        cachedTrending().map { Candidate(it, RecSource.TRENDING) }
    }

/** Top-channel candidates; one failing channel must not blank the feed. */
internal suspend fun YouPipeViewModel.loadTopChannelCandidates(
    profile: InterestProfile,
    subs: List<Subscription>,
): List<Candidate> = candidatesOrEmpty("Top-channel") {
    coroutineScope {
        val authors = profile.authorWeights.entries
            .sortedByDescending { it.value }
            .take(MAX_TOP_CHANNELS)
            .map { it.key }
        authors.map { author ->
            async(Dispatchers.IO) {
                val channelId = resolveChannelId(author, subs) ?: return@async emptyList()
                getChannelVideos(channelId)
                    .take(TOP_CHANNEL_VIDEOS)
                    .map { Candidate(it, RecSource.TOP_CHANNEL, sourceLabel = it.author) }
                    .toList()
            }
        }.awaitAll().flatten()
    }
}

/** Search candidates; a network failure here must not blank the feed. */
internal suspend fun YouPipeViewModel.loadSearchCandidates(
    profile: InterestProfile,
): List<Candidate> = candidatesOrEmpty("Search") {
    coroutineScope {
        searchQueries(profile).map { query ->
            async(Dispatchers.IO) {
                searchVideos(query).map { Candidate(it, RecSource.SEARCH) }
            }
        }.awaitAll().flatten()
    }
}
/** Trending results from the TTL cache, refetching only when stale. */
internal suspend fun YouPipeViewModel.cachedTrending(): List<VideoInfo> {
    val now = Clock.System.now()
    trendingCache?.let { (cachedAt, list) ->
        if (now - cachedAt < YouPipeViewModel.TRENDING_TTL) return list
    }
    val fresh = getTrendingVideos()
    trendingCache = now to fresh
    return fresh
}

/**
 * Derives SEARCH queries from the interest profile, preferring bigrams of the
 * top co-occurring keywords over single broad tokens.
 */
internal fun searchQueries(profile: InterestProfile): List<String> {
    val top = profile.keywordWeights.entries.sortedByDescending { it.value }.map { it.key }
    if (top.isEmpty()) return emptyList()
    if (top.size == 1) return listOf(top[0])
    val queries = mutableListOf("${top[0]} ${top[1]}")
    if (top.size >= QUERY_TRIGRAM_KEYWORDS) queries.add("${top[0]} ${top[2]}")
    return queries.take(MAX_SEARCH_KEYWORDS)
}

/**
 * Resolves an author name to a YouTube channel id, preferring a matching
 * subscription and falling back to a bounded channel search. The search hit is
 * only accepted when its channel name closely matches [authorName], so a
 * homonym channel can't pollute the top-channel source.
 */
internal suspend fun resolveChannelId(authorName: String, subs: List<Subscription>): String? {
    subs.firstOrNull { it.name.equals(authorName, ignoreCase = true) }?.let { return it.channelID }
    return try {
        val ex = ServiceList.YouTube.getSearchExtractor(authorName)
        ex.fetchPage()
        ex.getInitialPage().getItems().filterIsInstance<ChannelInfoItem>()
            .firstOrNull { channelNameMatches(it.name, authorName) }
            ?.let { channelURLtoID(it.url) }
    } catch (e: org.schabi.newpipe.extractor.exceptions.ExtractionException) {
        // Best-effort fallback: no channel id simply means no top-channel candidates.
        Log.status(TAG, "Channel search failed for $authorName", e)
        null
    } catch (e: java.io.IOException) {
        Log.status(TAG, "Channel search failed for $authorName", e)
        null
    }
}

