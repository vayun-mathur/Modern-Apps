package com.vayunmathur.youpipe.domain

import com.vayunmathur.youpipe.data.HistoryVideo
import com.vayunmathur.youpipe.data.RecommendationPreferences
import com.vayunmathur.youpipe.ui.VideoInfo
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.time.Instant

/** Where a recommendation candidate originated. */
enum class RecSource { RELATED, TRENDING, SUBSCRIPTION, TOP_CHANNEL, SEARCH }

/**
 * A recommendation candidate plus its provenance. [sourceWatchedAt] is the time
 * the source video was watched and is only meaningful for [RecSource.RELATED].
 * [sourceLabel] is a human-readable origin (the source-video title for RELATED,
 * the channel name for TOP_CHANNEL) used to build the feed's "why" reason.
 */
data class Candidate(
    val video: VideoInfo,
    val source: RecSource,
    val sourceWatchedAt: Instant? = null,
    val sourceLabel: String? = null,
)

/** A ranked feed item: the video, the source that won dedup, and a display reason. */
data class RankedVideo(
    val video: VideoInfo,
    val source: RecSource,
    val reason: String,
)

/** A per-channel recommendation override, keyed by lowercased author name. */
data class ChannelPref(
    val multiplier: Double = 1.0,
    val blocked: Boolean = false,
    val pinned: Boolean = false,
)

/** Hard content filters applied before ranking. All default to no-op. */
data class ContentFilters(
    /** Drop videos with a duration in (0, 60) seconds. */
    val hideShorts: Boolean = false,
    /** Drop videos with unknown/zero duration (treated as live). */
    val hideLive: Boolean = false,
    /** Drop paid/paywalled videos. */
    val hidePaid: Boolean = false,
    val minDurationSec: Long = 0,
    val maxDurationSec: Long = 0,
)

/** Aggregate impression stats for a channel, used for the exploration bonus. */
data class ChannelImpressionStat(
    val shownCount: Int,
    /** Whether any recommended video from this channel was later watched. */
    val watched: Boolean,
)

/**
 * All ranking tunables in one place, replacing the scattered magic numbers of the
 * previous inline scorer. New fields default so that the previous behavior is
 * reproduced exactly (idf off, freshness floor 0, single half-life, UCB off).
 */
data class RecommendationWeights(
    val authorAffinityWeight: Double = 2.0,
    val authorAffinityCap: Double = 10.0,
    val sourceRecencyWeight: Double = 5.0,
    val keywordWeight: Double = 4.0,
    /** Upper bound on the keyword term so distinctive tokens can't dwarf others. */
    val keywordScoreCap: Double = 8.0,
    val uploadFreshnessDecay: Double = 0.03,
    /**
     * Additive floor in [0, 1] applied to the freshness multiplier so old but
     * relevant videos are not zeroed out. 0 reproduces the pure multiplier.
     */
    val freshnessFloor: Double = 0.0,
    val explorationBase: Double = 0.5,
    /** Weight of the per-channel UCB-style exploration bonus. 0 disables it. */
    val explorationUcbWeight: Double = 0.0,
    /** Multiplier applied to the exploration bonus of channels shown but never watched. */
    val unwatchedExplorationDecay: Double = 0.3,
    val mmrLambda: Double = 0.7,
    val perAuthorCap: Int = 3,
    val subscriptionDamping: Double = 0.5,
    val maxSubscriptionFraction: Double = 0.3,
    val maxResults: Int = 50,
    /** Half-life (days) for down-weighting older watches when [shortTermBlend] is 0. */
    val recencyHalfLifeDays: Double = 14.0,
    /** Short-term interest half-life (days), used when [shortTermBlend] > 0. */
    val shortTermHalfLifeDays: Double = 3.0,
    /** Long-term interest half-life (days), used when [shortTermBlend] > 0. */
    val longTermHalfLifeDays: Double = 45.0,
    /** Blend weight of the short-term half-life in [0, 1]. 0 = single half-life. */
    val shortTermBlend: Double = 0.0,
    /** Completion assumed for history items without a known duration (e.g. imports). */
    val neutralCompletion: Double = 0.5,
    /**
     * Watch-time (minutes) at which absolute engagement saturates to 1.0, blended
     * with fractional completion so meaningful watch time on long videos counts.
     * 0 disables the absolute term (pure fractional completion).
     */
    val watchTimeSaturationMinutes: Double = 0.0,
    /** Recent-window (hours) within which already-shown low-relevance videos are suppressed. 0 = off. */
    val recentSuppressionWindowHours: Double = 0.0,
    /** Normalized-relevance above which a recently-shown video is kept anyway. */
    val recentSuppressionRelevanceThreshold: Double = 0.8,
) {
    /** Shared constants and helpers for RecommendationWeights. */
    companion object {
        /**
         * Maps user-facing [RecommendationPreferences] dials onto ranking weights.
         * Each dial is a float in [0, 1]; 0.5 reproduces the balanced defaults.
         */
        fun fromPreferences(prefs: RecommendationPreferences): RecommendationWeights {
            val d = prefs.discoveryFamiliar.toDouble().coerceIn(0.0, 1.0)
            val f = prefs.freshEvergreen.toDouble().coerceIn(0.0, 1.0)
            val x = prefs.focusedDiverse.toDouble().coerceIn(0.0, 1.0)
            return RecommendationWeights(
                subscriptionDamping = lerp(
                    SUBSCRIPTION_DAMPING_FAMILIAR, SUBSCRIPTION_DAMPING_DISCOVERY, d
                ),
                maxSubscriptionFraction = lerp(
                    MAX_SUBSCRIPTION_FRACTION_FAMILIAR, MAX_SUBSCRIPTION_FRACTION_DISCOVERY, d
                ),
                explorationBase = lerp(EXPLORATION_BASE_FAMILIAR, EXPLORATION_BASE_DISCOVERY, d),
                explorationUcbWeight = lerp(EXPLORATION_UCB_FAMILIAR, EXPLORATION_UCB_DISCOVERY, d),
                uploadFreshnessDecay = lerp(FRESHNESS_DECAY_EVERGREEN, FRESHNESS_DECAY_FRESH, f),
                freshnessFloor = lerp(FRESHNESS_FLOOR_EVERGREEN, FRESHNESS_FLOOR_FRESH, f),
                mmrLambda = lerp(MMR_LAMBDA_FOCUSED, MMR_LAMBDA_DIVERSE, x),
                perAuthorCap = lerp(PER_AUTHOR_CAP_FOCUSED, PER_AUTHOR_CAP_DIVERSE, x)
                    .roundToInt().coerceAtLeast(1),
                shortTermBlend = DEFAULT_SHORT_TERM_BLEND,
                watchTimeSaturationMinutes = DEFAULT_WATCH_TIME_SATURATION_MINUTES,
                recentSuppressionWindowHours = DEFAULT_RECENT_SUPPRESSION_WINDOW_HOURS,
            )
        }
    }
}

/** Dial-mapping endpoints for [RecommendationWeights.fromPreferences]: lerp(familiar, discovery, d). */
private const val SUBSCRIPTION_DAMPING_FAMILIAR = 0.8
private const val SUBSCRIPTION_DAMPING_DISCOVERY = 0.2
private const val MAX_SUBSCRIPTION_FRACTION_FAMILIAR = 0.5
private const val MAX_SUBSCRIPTION_FRACTION_DISCOVERY = 0.1
private const val EXPLORATION_BASE_FAMILIAR = 0.1
private const val EXPLORATION_BASE_DISCOVERY = 0.9
private const val EXPLORATION_UCB_FAMILIAR = 0.0
private const val EXPLORATION_UCB_DISCOVERY = 1.0
private const val FRESHNESS_DECAY_EVERGREEN = 0.01
private const val FRESHNESS_DECAY_FRESH = 0.05
private const val FRESHNESS_FLOOR_EVERGREEN = 0.6
private const val FRESHNESS_FLOOR_FRESH = 0.0
private const val MMR_LAMBDA_FOCUSED = 0.95
private const val MMR_LAMBDA_DIVERSE = 0.45
private const val PER_AUTHOR_CAP_FOCUSED = 5.0
private const val PER_AUTHOR_CAP_DIVERSE = 1.0
private const val DEFAULT_SHORT_TERM_BLEND = 0.35
private const val DEFAULT_WATCH_TIME_SATURATION_MINUTES = 15.0
private const val DEFAULT_RECENT_SUPPRESSION_WINDOW_HOURS = 12.0

/** Preset dial values: (discovery, freshness, focus). */
private const val DISCOVER_MORE_DISCOVERY = 0.9f
private const val DISCOVER_MORE_FRESHNESS = 0.65f
private const val DISCOVER_MORE_FOCUS = 0.85f
private const val BALANCED_DISCOVERY = 0.5f
private const val BALANCED_FRESHNESS = 0.5f
private const val BALANCED_FOCUS = 0.5f
private const val MOSTLY_SUBS_DISCOVERY = 0.15f
private const val MOSTLY_SUBS_FRESHNESS = 0.5f
private const val MOSTLY_SUBS_FOCUS = 0.35f
private const val DEEP_DIVES_DISCOVERY = 0.4f
private const val DEEP_DIVES_FRESHNESS = 0.1f
private const val DEEP_DIVES_FOCUS = 0.15f

/** A named bundle of the three feed-mix dials. */
enum class RecommendationPreset(
    val discoveryFamiliar: Float,
    val freshEvergreen: Float,
    val focusedDiverse: Float,
) {
    DISCOVER_MORE(DISCOVER_MORE_DISCOVERY, DISCOVER_MORE_FRESHNESS, DISCOVER_MORE_FOCUS),
    BALANCED(BALANCED_DISCOVERY, BALANCED_FRESHNESS, BALANCED_FOCUS),
    MOSTLY_SUBSCRIPTIONS(MOSTLY_SUBS_DISCOVERY, MOSTLY_SUBS_FRESHNESS, MOSTLY_SUBS_FOCUS),
    DEEP_DIVES(DEEP_DIVES_DISCOVERY, DEEP_DIVES_FRESHNESS, DEEP_DIVES_FOCUS),
}

/** Learned interest signals derived from watch history. */
data class InterestProfile(
    val authorWeights: Map<String, Double>,
    val keywordWeights: Map<String, Double>,
)

private val STOPWORDS = setOf(
    "the", "and", "for", "with", "you", "your", "this", "that", "how",
    "why", "what", "are", "was", "were", "from", "have", "has", "not",
    "but", "all", "can", "will", "into", "out", "new", "get", "got",
)

private fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t

/** Minimum token length kept by [tokenize]; shorter tokens are noise. */
private const val MIN_TOKEN_LENGTH = 3

/** Lowercases, strips punctuation, splits on whitespace, and drops stopwords / short tokens. */
fun tokenize(title: String): List<String> =
    title.lowercase()
        .split(Regex("[^a-z0-9]+"))
        .filter { it.length >= MIN_TOKEN_LENGTH && it !in STOPWORDS }

/**
 * Whether a channel search hit [hitName] is a close enough match to the [author]
 * being resolved: case-insensitive equality or one normalized name containing the
 * other. Guards against homonym channels polluting the top-channel source.
 */
fun channelNameMatches(hitName: String, author: String): Boolean {
    val a = hitName.trim().lowercase()
    val b = author.trim().lowercase()
    if (a.isEmpty() || b.isEmpty()) return false
    return a == b || a.contains(b) || b.contains(a)
}

/** Recency decay for a watch [ageDays] old, honoring the single/dual half-life blend. */
private fun recencyDecay(ageDays: Double, weights: RecommendationWeights): Double {
    if (weights.shortTermBlend <= 0.0) {
        return exp(-ln(2.0) / weights.recencyHalfLifeDays * ageDays)
    }
    val shortTerm = exp(-ln(2.0) / weights.shortTermHalfLifeDays * ageDays)
    val longTerm = exp(-ln(2.0) / weights.longTermHalfLifeDays * ageDays)
    return (1 - weights.shortTermBlend) * longTerm + weights.shortTermBlend * shortTerm
}

/**
 * Builds an [InterestProfile] from watch history.
 *
 * Author affinity is engagement-weighted (fractional completion blended with a
 * saturating absolute-watch-time term) and recency-weighted (single or dual
 * half-life), so channels the user actually finishes recently outrank channels
 * merely opened long ago. Keyword weights are a recency-weighted term frequency
 * over watched titles.
 */
fun buildInterestProfile(
    history: List<HistoryVideo>,
    now: Instant,
    weights: RecommendationWeights = RecommendationWeights(),
): InterestProfile {
    val authorWeights = HashMap<String, Double>()
    val keywordWeights = HashMap<String, Double>()

    for (h in history) {
        val v = h.videoItem
        val completion = completionScore(h, weights)
        val ageDays = (now - h.timestamp).inWholeDays.toDouble().coerceAtLeast(0.0)
        val recency = recencyDecay(ageDays, weights)
        val contribution = completion * recency

        val author = v.author.lowercase()
        if (author.isNotBlank()) {
            authorWeights[author] = (authorWeights[author] ?: 0.0) + contribution
        }
        for (token in tokenize(v.name)) {
            keywordWeights[token] = (keywordWeights[token] ?: 0.0) + recency
        }
    }
    return InterestProfile(authorWeights, keywordWeights)
}

/** Engagement score for a watched item: fractional completion blended with saturating watch time. */
private fun completionScore(h: HistoryVideo, weights: RecommendationWeights): Double {
    val v = h.videoItem
    val fractional = if (v.duration > 0) {
        (h.progress.toDouble() / (v.duration * 1000)).coerceIn(0.0, 1.0)
    } else {
        weights.neutralCompletion
    }
    if (weights.watchTimeSaturationMinutes <= 0.0) return fractional
    val saturationMs = weights.watchTimeSaturationMinutes * 60_000.0
    val absolute = (h.progress.toDouble() / saturationMs).coerceIn(0.0, 1.0)
    return maxOf(fractional, absolute)
}

/**
 * Inverse document frequency of title tokens across [videos]. Tokens appearing in
 * many candidates get a low weight; distinctive tokens get a high one. Empty when
 * there are no videos (callers treat a missing token as idf 1.0).
 */
fun computeIdf(videos: List<VideoInfo>): Map<String, Double> {
    if (videos.isEmpty()) return emptyMap()
    val df = HashMap<String, Int>()
    for (v in videos) {
        for (token in tokenize(v.name).toSet()) {
            df[token] = (df[token] ?: 0) + 1
        }
    }
    val n = videos.size
    return df.mapValues { (_, count) -> ln((n + 1.0) / count) }
}

/**
 * Per-channel exploration bonus. Rarely-shown channels score higher; channels
 * shown often but never watched are faded via [RecommendationWeights.unwatchedExplorationDecay].
 * Returns 0 when [RecommendationWeights.explorationUcbWeight] is 0.
 */
fun explorationBonus(
    channelKey: String,
    channelStats: Map<String, ChannelImpressionStat>,
    weights: RecommendationWeights,
): Double {
    if (weights.explorationUcbWeight <= 0.0) return 0.0
    val stat = channelStats[channelKey] ?: return weights.explorationUcbWeight
    val familiarity = 1.0 / (1.0 + stat.shownCount)
    val ignoredPenalty = if (stat.watched) 1.0 else weights.unwatchedExplorationDecay
    return weights.explorationUcbWeight * familiarity * ignoredPenalty
}

/** Human-readable "why this was recommended" line. */
fun reasonFor(candidate: Candidate): String = when (candidate.source) {
    RecSource.RELATED -> candidate.sourceLabel?.let { "Because you watched $it" } ?: "Because of your history"
    RecSource.TOP_CHANNEL -> candidate.sourceLabel?.let { "From $it" } ?: "From a channel you watch"
    RecSource.TRENDING -> "Trending"
    RecSource.SUBSCRIPTION -> "From your subscriptions"
    RecSource.SEARCH -> "Matches your interests"
}

/**
 * Scores a single candidate. Relevance terms (author affinity, IDF-weighted and
 * capped keyword overlap, source recency, an exploration base, and an optional
 * per-channel exploration bonus) are summed, then scaled by an additive-floored
 * upload freshness that can be attenuated per-source.
 */
fun scoreCandidate(
    candidate: Candidate,
    profile: InterestProfile,
    now: Instant,
    weights: RecommendationWeights = RecommendationWeights(),
    idf: Map<String, Double> = emptyMap(),
    explorationBonus: Double = 0.0,
    freshnessSourceFactors: Map<RecSource, Double> = emptyMap(),
): Double {
    val v = candidate.video
    val affinity = profile.authorWeights[v.author.lowercase()] ?: 0.0
    val authorScore = minOf(affinity * weights.authorAffinityWeight, weights.authorAffinityCap)

    val keywordRaw = tokenize(v.name)
        .sumOf { (profile.keywordWeights[it] ?: 0.0) * (idf[it] ?: 1.0) }
    val keywordScore = minOf(keywordRaw * weights.keywordWeight, weights.keywordScoreCap)

    val sourceRecencyScore = if (candidate.source == RecSource.RELATED && candidate.sourceWatchedAt != null) {
        val hoursAgo = (now - candidate.sourceWatchedAt).inWholeHours.toDouble()
        maxOf(weights.sourceRecencyWeight - hoursAgo / 24.0, 0.0)
    } else {
        0.0
    }

    val uploadAgeDays = (now - v.uploadDate).inWholeDays.toDouble().coerceAtLeast(0.0)
    val decay = weights.uploadFreshnessDecay * (freshnessSourceFactors[candidate.source] ?: 1.0)
    val rawFreshness = exp(-decay * uploadAgeDays)
    val freshness = weights.freshnessFloor + (1 - weights.freshnessFloor) * rawFreshness

    return (authorScore + keywordScore + sourceRecencyScore + weights.explorationBase + explorationBonus) * freshness
}

/**
 * Ranks candidates into the final feed.
 *
 * Steps: apply hard filters (blocked channels, muted keywords, content filters);
 * dedup by video id keeping the max score across sources; drop videos already
 * watched >=90%; apply per-channel multipliers and subscription damping; suppress
 * recently-shown low-relevance videos; then greedily select with MMR (plus optional
 * refresh noise) to balance relevance against diversity, enforcing a per-author cap
 * and a subscription-fraction soft quota that backfills to a full feed.
 */
fun rankRecommendations(
    candidates: List<Candidate>,
    history: List<HistoryVideo>,
    subNames: Set<String>,
    now: Instant,
    weights: RecommendationWeights = RecommendationWeights(),
    channelPrefs: Map<String, ChannelPref> = emptyMap(),
    mutedKeywords: Set<String> = emptySet(),
    contentFilters: ContentFilters = ContentFilters(),
    channelStats: Map<String, ChannelImpressionStat> = emptyMap(),
    recentlyShown: Map<Long, Instant> = emptyMap(),
    noise: (VideoInfo) -> Double = { 0.0 },
): List<RankedVideo> {
    val profile = buildInterestProfile(history, now, weights)
    val historyById = history.associateBy { it.id }

    val eligible = candidates.filter { passesHardFilters(it.video, channelPrefs, mutedKeywords, contentFilters) }
    val idf = computeIdf(eligible.map { it.video }.distinctBy { it.videoID })

    val best = dedupAndScore(eligible, historyById, profile, now, weights, subNames, channelPrefs, channelStats, idf)
    if (best.isEmpty()) return emptyList()

    val pool = suppressionPool(best, recentlyShown, now, weights)
    if (pool.isEmpty()) return emptyList()

    val selected = mmrSelect(pool, weights, noise)
    return selected.map { RankedVideo(it.video, it.source, it.reason) }
}

private fun dedupAndScore(
    eligible: List<Candidate>,
    historyById: Map<Long, HistoryVideo>,
    profile: InterestProfile,
    now: Instant,
    weights: RecommendationWeights,
    subNames: Set<String>,
    channelPrefs: Map<String, ChannelPref>,
    channelStats: Map<String, ChannelImpressionStat>,
    idf: Map<String, Double>,
): Map<Long, Scored> {
    val best = HashMap<Long, Scored>()
    for (c in eligible) {
        scoreCandidateIntoBest(c, best, historyById, profile, now, weights, subNames, channelPrefs, channelStats, idf)
    }
    return best
}

private fun scoreCandidateIntoBest(
    c: Candidate,
    best: HashMap<Long, Scored>,
    historyById: Map<Long, HistoryVideo>,
    profile: InterestProfile,
    now: Instant,
    weights: RecommendationWeights,
    subNames: Set<String>,
    channelPrefs: Map<String, ChannelPref>,
    channelStats: Map<String, ChannelImpressionStat>,
    idf: Map<String, Double>,
) {
    val v = c.video
    if (isAlreadyWatched(v, historyById[v.videoID])) return

    val author = v.author.lowercase()
    val pref = channelPrefs[author]
    val isSub = author in subNames
    val bonus = explorationBonus(author, channelStats, weights)
    var score = scoreCandidate(c, profile, now, weights, idf, bonus)
    if (isSub) score *= weights.subscriptionDamping
    if (pref != null) score *= pref.multiplier

    val existing = best[v.videoID]
    if (existing == null || score > existing.score) {
        best[v.videoID] = Scored(
            video = v,
            score = score,
            tokens = tokenize(v.name).toSet(),
            isSub = isSub,
            pinned = pref?.pinned == true,
            source = c.source,
            reason = reasonFor(c),
        )
    }
}

private fun isAlreadyWatched(v: VideoInfo, h: HistoryVideo?): Boolean {
    if (h == null || v.duration <= 0) return false
    return h.progress.toDouble() / (v.duration * MILLIS_PER_SECOND) >= WATCHED_COMPLETION_THRESHOLD
}

private fun suppressionPool(
    best: Map<Long, Scored>,
    recentlyShown: Map<Long, Instant>,
    now: Instant,
    weights: RecommendationWeights,
): MutableList<Scored> {
    if (best.isEmpty()) return mutableListOf()
    val maxScore = best.values.maxOf { it.score }.takeIf { it > 0.0 } ?: 1.0
    return best.values.filter { scored ->
        keepAfterSuppression(scored, recentlyShown[scored.video.videoID], now, weights, maxScore)
    }.sortedByDescending { it.score }.toMutableList()
}

private fun keepAfterSuppression(
    scored: Scored,
    shownAt: Instant?,
    now: Instant,
    weights: RecommendationWeights,
    maxScore: Double,
): Boolean {
    if (scored.pinned) return true
    if (shownAt == null) return true
    if (weights.recentSuppressionWindowHours <= 0.0) return true
    val hoursAgo = (now - shownAt).inWholeHours.toDouble()
    val relevance = scored.score / maxScore
    return !(hoursAgo < weights.recentSuppressionWindowHours && relevance < weights.recentSuppressionRelevanceThreshold)
}

private fun mmrSelect(
    pool: MutableList<Scored>,
    weights: RecommendationWeights,
    noise: (VideoInfo) -> Double,
): List<Scored> {
    val maxScore = pool.maxOf { it.score }.takeIf { it > 0.0 } ?: 1.0
    val resultLimit = minOf(pool.size, weights.maxResults)
    val subQuota = (weights.maxResults * weights.maxSubscriptionFraction).toInt()

    val selected = mutableListOf<Scored>()
    val authorCount = HashMap<String, Int>()
    var subCount = 0

    // First pass respects the subscription soft cap; a second backfill pass drops
    // it so the feed is never shortened just because only sub items remain.
    for (enforceSubQuota in listOf(true, false)) {
        while (selected.size < resultLimit && pool.isNotEmpty()) {
            val bestIdx = bestMmrIndex(
                pool, selected, authorCount, subCount, subQuota,
                enforceSubQuota, weights, noise, maxScore,
            ) ?: break
            val chosen = pool.removeAt(bestIdx)
            selected.add(chosen)
            val a = chosen.video.author.lowercase()
            authorCount[a] = (authorCount[a] ?: 0) + 1
            if (chosen.isSub) subCount++
        }
        if (selected.size >= resultLimit) break
    }
    return selected
}

private fun bestMmrIndex(
    pool: List<Scored>,
    selected: List<Scored>,
    authorCount: Map<String, Int>,
    subCount: Int,
    subQuota: Int,
    enforceSubQuota: Boolean,
    weights: RecommendationWeights,
    noise: (VideoInfo) -> Double,
    maxScore: Double,
): Int? {
    var bestIdx: Int? = null
    var bestMmr = Double.NEGATIVE_INFINITY
    for (i in pool.indices) {
        val cand = pool[i]
        if (!isMmrEligible(cand, authorCount, subCount, subQuota, enforceSubQuota, weights)) continue

        val maxSim = selected.maxOfOrNull { similarity(cand, it) } ?: 0.0
        val mmr = mmrScore(cand, maxSim, weights, noise, maxScore)
        if (mmr > bestMmr) {
            bestMmr = mmr
            bestIdx = i
        }
    }
    return bestIdx
}

private fun isMmrEligible(
    cand: Scored,
    authorCount: Map<String, Int>,
    subCount: Int,
    subQuota: Int,
    enforceSubQuota: Boolean,
    weights: RecommendationWeights,
): Boolean {
    val author = cand.video.author.lowercase()
    if ((authorCount[author] ?: 0) >= weights.perAuthorCap) return false
    if (enforceSubQuota && cand.isSub && subCount >= subQuota) return false
    return true
}

private fun mmrScore(
    cand: Scored,
    maxSim: Double,
    weights: RecommendationWeights,
    noise: (VideoInfo) -> Double,
    maxScore: Double,
): Double {
    var relevance = cand.score / maxScore + noise(cand.video)
    if (cand.pinned) relevance += PINNED_RELEVANCE_BOOST
    return weights.mmrLambda * relevance - (1 - weights.mmrLambda) * maxSim
}

private const val PINNED_RELEVANCE_BOOST = 1_000.0
private const val MILLIS_PER_SECOND = 1000L
private const val WATCHED_COMPLETION_THRESHOLD = 0.9
private const val SHORTS_MAX_DURATION_SEC = 60
private const val AUTHOR_SIMILARITY_WEIGHT = 0.6
private const val TOKEN_SIMILARITY_WEIGHT = 0.4

private fun passesHardFilters(
    v: VideoInfo,
    channelPrefs: Map<String, ChannelPref>,
    mutedKeywords: Set<String>,
    filters: ContentFilters,
): Boolean {
    if (channelPrefs[v.author.lowercase()]?.blocked == true) return false
    if (isMutedKeywordMatch(v, mutedKeywords)) return false
    if (filters.hidePaid && v.isPaid) return false
    return passesDurationFilters(v.duration, filters)
}

private fun isMutedKeywordMatch(v: VideoInfo, mutedKeywords: Set<String>): Boolean {
    if (mutedKeywords.isEmpty()) return false
    return tokenize(v.name).any { it in mutedKeywords }
}

private fun passesDurationFilters(duration: Long, filters: ContentFilters): Boolean {
    if (filters.hideLive && duration <= 0) return false
    if (filters.hideShorts && duration in 1 until SHORTS_MAX_DURATION_SEC) return false
    if (duration <= 0) return true
    if (filters.minDurationSec > 0 && duration < filters.minDurationSec) return false
    if (filters.maxDurationSec > 0 && duration > filters.maxDurationSec) return false
    return true
}

private data class Scored(
    val video: VideoInfo,
    val score: Double,
    val tokens: Set<String>,
    val isSub: Boolean,
    val pinned: Boolean,
    val source: RecSource,
    val reason: String,
)

private fun similarity(a: Scored, b: Scored): Double {
    val authorSim = if (a.video.author.equals(b.video.author, ignoreCase = true)) 1.0 else 0.0
    return AUTHOR_SIMILARITY_WEIGHT * authorSim + TOKEN_SIMILARITY_WEIGHT * jaccard(a.tokens, b.tokens)
}

private fun jaccard(a: Set<String>, b: Set<String>): Double {
    if (a.isEmpty() || b.isEmpty()) return 0.0
    val intersection = a.count { it in b }
    val union = a.size + b.size - intersection
    return if (union == 0) 0.0 else intersection.toDouble() / union
}
