package com.vayunmathur.youpipe.platform

import com.vayunmathur.youpipe.data.HistoryVideo
import com.vayunmathur.youpipe.data.RecommendationPreferences
import com.vayunmathur.youpipe.ui.VideoInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import com.vayunmathur.youpipe.domain.Candidate
import com.vayunmathur.youpipe.domain.ChannelImpressionStat
import com.vayunmathur.youpipe.domain.ChannelPref
import com.vayunmathur.youpipe.domain.ContentFilters
import com.vayunmathur.youpipe.domain.InterestProfile
import com.vayunmathur.youpipe.domain.RecSource
import com.vayunmathur.youpipe.domain.RecommendationWeights
import com.vayunmathur.youpipe.domain.buildInterestProfile
import com.vayunmathur.youpipe.domain.channelNameMatches
import com.vayunmathur.youpipe.domain.computeIdf
import com.vayunmathur.youpipe.domain.explorationBonus
import com.vayunmathur.youpipe.domain.rankRecommendations
import com.vayunmathur.youpipe.domain.scoreCandidate
import com.vayunmathur.youpipe.domain.tokenize

class RecommenderTest {

    private val now = Instant.fromEpochSeconds(1_700_000_000L)

    private fun video(
        id: Long,
        name: String = "",
        author: String = "",
        duration: Long = 0,
        uploadDate: Instant = now,
    ) = VideoInfo(name, id, duration, 0, uploadDate, "", author)

    private fun history(
        video: VideoInfo,
        progress: Long = 0,
        timestamp: Instant = now,
    ) = HistoryVideo(video.videoID, progress, video, timestamp)

    // ===================== buildInterestProfile =====================

    @Test
    fun `author affinity is engagement weighted`() {
        val finished = history(video(1, author = "Finisher", duration = 100), progress = 100_000)
        val abandoned = history(video(2, author = "Abandoner", duration = 100), progress = 1_000)

        val profile = buildInterestProfile(listOf(finished, abandoned), now)

        assertTrue(
            profile.authorWeights.getValue("finisher") > profile.authorWeights.getValue("abandoner"),
        )
    }

    @Test
    fun `author affinity is recency weighted`() {
        val recent = history(video(1, author = "Recent"), timestamp = now)
        val old = history(video(2, author = "Old"), timestamp = now - 28.days)

        val profile = buildInterestProfile(listOf(recent, old), now)

        assertTrue(profile.authorWeights.getValue("recent") > profile.authorWeights.getValue("old"))
    }

    @Test
    fun `duration zero uses neutral completion default`() {
        val imported = history(video(1, author = "Imported", duration = 0), progress = 0, timestamp = now)

        val profile = buildInterestProfile(listOf(imported), now)

        // recency == 1 at now, so weight == neutralCompletion default (0.5).
        assertEquals(0.5, profile.authorWeights.getValue("imported"), 1e-9)
    }

    @Test
    fun `completion saturation counts partial watch of a long video`() {
        // 20 minutes watched of a 3-hour video: fractional completion is tiny, but
        // absolute watch time saturates, so the author still earns strong affinity.
        val longVideo = video(1, author = "LongForm", duration = 3 * 3600)
        val h = history(longVideo, progress = 20 * 60 * 1000L)

        val plain = buildInterestProfile(listOf(h), now).authorWeights.getValue("longform")
        val saturated = buildInterestProfile(
            listOf(h),
            now,
            RecommendationWeights(watchTimeSaturationMinutes = 15.0),
        ).authorWeights.getValue("longform")

        assertTrue(saturated > plain)
        assertEquals(1.0, saturated, 1e-9)
    }

    // ===================== tokenize =====================

    @Test
    fun `tokenize lowercases and strips punctuation and stopwords`() {
        val tokens = tokenize("The Amazing Cats! How-To (2024)")

        assertTrue(tokens.contains("amazing"))
        assertTrue(tokens.contains("cats"))
        assertTrue(tokens.contains("2024"))
        assertFalse(tokens.contains("the"))
        assertFalse(tokens.contains("how"))
        assertFalse(tokens.contains("to"))
    }

    // ===================== IDF =====================

    @Test
    fun `idf down-weights common tokens`() {
        val videos = listOf(
            video(1, name = "kotlin news"),
            video(2, name = "python news"),
            video(3, name = "rust news"),
        )
        val idf = computeIdf(videos)

        // "news" appears in every document, distinctive tokens appear once.
        assertTrue(idf.getValue("news") < idf.getValue("kotlin"))
    }

    @Test
    fun `idf lowers keyword score of common tokens in scoreCandidate`() {
        val profile = InterestProfile(emptyMap(), mapOf("news" to 1.0, "kotlin" to 1.0))
        val commonCand = Candidate(video(1, name = "news"), RecSource.TRENDING)
        val distinctiveCand = Candidate(video(2, name = "kotlin"), RecSource.TRENDING)
        val idf = mapOf("news" to 0.1, "kotlin" to 2.0)

        val common = scoreCandidate(commonCand, profile, now, idf = idf)
        val distinctive = scoreCandidate(distinctiveCand, profile, now, idf = idf)

        assertTrue(distinctive > common)
    }

    // ===================== scoreCandidate =====================

    @Test
    fun `author affinity raises score`() {
        val profile = buildInterestProfile(
            listOf(history(video(1, author = "Creator", duration = 10), progress = 10_000)),
            now,
        )
        val known = Candidate(video(2, author = "Creator"), RecSource.TRENDING)
        val unknown = Candidate(video(3, author = "Nobody"), RecSource.TRENDING)

        assertTrue(scoreCandidate(known, profile, now) > scoreCandidate(unknown, profile, now))
    }

    @Test
    fun `keyword overlap raises score`() {
        val profile = buildInterestProfile(
            listOf(history(video(1, name = "kotlin programming tutorial"))),
            now,
        )
        val relevant = Candidate(video(2, name = "kotlin news"), RecSource.TRENDING)
        val irrelevant = Candidate(video(3, name = "cooking recipe"), RecSource.TRENDING)

        assertTrue(scoreCandidate(relevant, profile, now) > scoreCandidate(irrelevant, profile, now))
    }

    @Test
    fun `keyword score is capped`() {
        val bigProfile = InterestProfile(
            emptyMap(),
            (1..20).associate { "tok$it" to 100.0 },
        )
        val name = (1..20).joinToString(" ") { "tok$it" }
        val cand = Candidate(video(1, name = name), RecSource.TRENDING)
        val weights = RecommendationWeights(keywordScoreCap = 8.0)

        // Freshness is 1.0 at now; exploration base adds 0.5.
        val score = scoreCandidate(cand, bigProfile, now, weights)

        assertTrue(score <= weights.keywordScoreCap + weights.explorationBase + 1e-9)
    }

    @Test
    fun `freshness decays with upload age`() {
        val profile = InterestProfile(emptyMap(), emptyMap())
        val fresh = Candidate(video(1, uploadDate = now), RecSource.TRENDING)
        val stale = Candidate(video(2, uploadDate = now - 100.days), RecSource.TRENDING)

        assertTrue(scoreCandidate(fresh, profile, now) > scoreCandidate(stale, profile, now))
    }

    @Test
    fun `freshness floor keeps an old high-relevance item alive`() {
        val profile = InterestProfile(mapOf("evergreen" to 5.0), emptyMap())
        val oldRelevant = Candidate(video(1, author = "Evergreen", uploadDate = now - 400.days), RecSource.TRENDING)

        val noFloor = scoreCandidate(oldRelevant, profile, now, RecommendationWeights(freshnessFloor = 0.0))
        val withFloor = scoreCandidate(oldRelevant, profile, now, RecommendationWeights(freshnessFloor = 0.5))

        assertTrue(noFloor < 0.5) // nearly zeroed by pure multiplier
        assertTrue(withFloor > noFloor)
    }

    @Test
    fun `exploration base keeps zero affinity candidates above zero`() {
        val profile = InterestProfile(emptyMap(), emptyMap())
        val weights = RecommendationWeights()
        val candidate = Candidate(video(1, uploadDate = now), RecSource.TRENDING)

        val score = scoreCandidate(candidate, profile, now, weights)

        assertEquals(weights.explorationBase, score, 1e-9)
    }

    // ===================== exploration bonus (UCB) =====================

    @Test
    fun `exploration bonus favors rarely shown channels`() {
        val weights = RecommendationWeights(explorationUcbWeight = 1.0)
        val stats = mapOf(
            "rare" to ChannelImpressionStat(shownCount = 1, watched = false),
            "frequent" to ChannelImpressionStat(shownCount = 50, watched = false),
        )

        val rare = explorationBonus("rare", stats, weights)
        val frequent = explorationBonus("frequent", stats, weights)

        assertTrue(rare > frequent)
    }

    @Test
    fun `exploration bonus is zero when disabled`() {
        val stats = mapOf("x" to ChannelImpressionStat(1, false))
        assertEquals(0.0, explorationBonus("x", stats, RecommendationWeights()), 1e-9)
    }

    // ===================== rankRecommendations =====================

    @Test
    fun `dedup keeps a video once across sources`() {
        val candidates = listOf(
            Candidate(video(1, author = "A", uploadDate = now), RecSource.TRENDING),
            Candidate(video(1, author = "A", uploadDate = now), RecSource.SEARCH),
        )

        val ranked = rankRecommendations(candidates, emptyList(), emptySet(), now)

        assertEquals(1, ranked.size)
    }

    @Test
    fun `videos watched at least ninety percent are filtered out`() {
        val watched = video(1, author = "A", duration = 100)
        val history = listOf(history(watched, progress = 95_000))
        val candidates = listOf(Candidate(watched, RecSource.RELATED, now))

        val ranked = rankRecommendations(candidates, history, emptySet(), now)

        assertTrue(ranked.none { it.video.videoID == 1L })
    }

    @Test
    fun `subscriptions are demoted but not excluded`() {
        val subCandidate = Candidate(video(1, author = "SubChannel", uploadDate = now), RecSource.SUBSCRIPTION)
        val discoverCandidate = Candidate(video(2, author = "Discover", uploadDate = now), RecSource.TRENDING)
        val subNames = setOf("subchannel")

        val ranked = rankRecommendations(
            listOf(subCandidate, discoverCandidate),
            emptyList(),
            subNames,
            now,
        )

        assertTrue(ranked.any { it.video.videoID == 1L }) // sub not excluded
        assertTrue(ranked.indexOfFirst { it.video.videoID == 2L } < ranked.indexOfFirst { it.video.videoID == 1L })
    }

    @Test
    fun `subscription soft quota is respected when non-subs are available`() {
        val weights = RecommendationWeights(maxResults = 10, maxSubscriptionFraction = 0.3, perAuthorCap = 3)
        val subCandidates = (1L..10L).map {
            Candidate(video(it, author = "Sub$it", uploadDate = now), RecSource.SUBSCRIPTION)
        }
        // Exactly enough non-subs to fill (maxResults - subQuota) slots, so the
        // sub soft cap binds while the feed still fills to maxResults.
        val nonSub = (11L..17L).map {
            Candidate(video(it, author = "Free$it", uploadDate = now), RecSource.TRENDING)
        }
        val subNames = (1L..10L).map { "sub$it" }.toSet()

        val ranked = rankRecommendations(subCandidates + nonSub, emptyList(), subNames, now, weights)

        assertEquals(10, ranked.size)
        val subCount = ranked.count { it.video.author.lowercase().startsWith("sub") }
        assertEquals(3, subCount) // floor(10 * 0.3)
    }

    @Test
    fun `subscription quota backfills when only subs remain`() {
        val weights = RecommendationWeights(maxResults = 10, maxSubscriptionFraction = 0.3, perAuthorCap = 3)
        val subCandidates = (1L..10L).map {
            Candidate(video(it, author = "Sub$it", uploadDate = now), RecSource.SUBSCRIPTION)
        }
        val subNames = (1L..10L).map { "sub$it" }.toSet()

        val ranked = rankRecommendations(subCandidates, emptyList(), subNames, now, weights)

        // Never shorten the feed just because only sub items are eligible.
        assertEquals(10, ranked.size)
    }

    @Test
    fun `per author cap limits domination`() {
        val weights = RecommendationWeights(perAuthorCap = 3)
        val dominant = (1L..6L).map {
            Candidate(video(it, author = "Loud", uploadDate = now), RecSource.TRENDING)
        }
        val others = (7L..9L).map {
            Candidate(video(it, author = "Other$it", uploadDate = now), RecSource.TRENDING)
        }

        val ranked = rankRecommendations(dominant + others, emptyList(), emptySet(), now, weights)

        assertEquals(3, ranked.count { it.video.author == "Loud" })
    }

    // ===================== user-control filters =====================

    @Test
    fun `blocked channel is hard filtered`() {
        val candidates = listOf(
            Candidate(video(1, author = "Blocked", uploadDate = now), RecSource.TRENDING),
            Candidate(video(2, author = "Allowed", uploadDate = now), RecSource.TRENDING),
        )
        val prefs = mapOf("blocked" to ChannelPref(blocked = true))

        val ranked = rankRecommendations(candidates, emptyList(), emptySet(), now, channelPrefs = prefs)

        assertTrue(ranked.none { it.video.videoID == 1L })
        assertTrue(ranked.any { it.video.videoID == 2L })
    }

    @Test
    fun `muted keyword is hard filtered`() {
        val candidates = listOf(
            Candidate(video(1, name = "spoiler review", uploadDate = now), RecSource.TRENDING),
            Candidate(video(2, name = "cooking video", uploadDate = now), RecSource.TRENDING),
        )

        val ranked = rankRecommendations(candidates, emptyList(), emptySet(), now, mutedKeywords = setOf("spoiler"))

        assertTrue(ranked.none { it.video.videoID == 1L })
    }

    @Test
    fun `hide shorts content filter drops short videos`() {
        val candidates = listOf(
            Candidate(video(1, author = "A", duration = 30, uploadDate = now), RecSource.TRENDING),
            Candidate(video(2, author = "B", duration = 600, uploadDate = now), RecSource.TRENDING),
        )
        val filters = ContentFilters(hideShorts = true)

        val ranked = rankRecommendations(candidates, emptyList(), emptySet(), now, contentFilters = filters)

        assertTrue(ranked.none { it.video.videoID == 1L })
        assertTrue(ranked.any { it.video.videoID == 2L })
    }

    @Test
    fun `boost and demote multipliers reorder equal candidates`() {
        val candidates = listOf(
            Candidate(video(1, author = "Boosted", uploadDate = now), RecSource.TRENDING),
            Candidate(video(2, author = "Demoted", uploadDate = now), RecSource.TRENDING),
        )
        val prefs = mapOf(
            "boosted" to ChannelPref(multiplier = 5.0),
            "demoted" to ChannelPref(multiplier = 0.1),
        )

        val ranked = rankRecommendations(candidates, emptyList(), emptySet(), now, channelPrefs = prefs)

        assertTrue(ranked.indexOfFirst { it.video.videoID == 1L } < ranked.indexOfFirst { it.video.videoID == 2L })
    }

    @Test
    fun `pinned channel outranks an equally relevant channel`() {
        val candidates = listOf(
            Candidate(video(1, author = "Normal", uploadDate = now), RecSource.TRENDING),
            Candidate(video(2, author = "Pinned", uploadDate = now), RecSource.TRENDING),
        )
        val prefs = mapOf("pinned" to ChannelPref(pinned = true))

        val ranked = rankRecommendations(candidates, emptyList(), emptySet(), now, channelPrefs = prefs)

        assertEquals(2L, ranked.first().video.videoID)
    }

    // ===================== recent-impression suppression =====================

    @Test
    fun `recently shown low relevance videos are suppressed`() {
        val weights = RecommendationWeights(
            recentSuppressionWindowHours = 24.0,
            recentSuppressionRelevanceThreshold = 2.0, // force everything below threshold
        )
        val candidates = listOf(
            Candidate(video(1, author = "A", uploadDate = now), RecSource.TRENDING),
            Candidate(video(2, author = "B", uploadDate = now), RecSource.TRENDING),
        )
        val recentlyShown = mapOf(1L to now - 1.hours)

        val ranked = rankRecommendations(
            candidates, emptyList(), emptySet(), now, weights, recentlyShown = recentlyShown,
        )

        assertTrue(ranked.none { it.video.videoID == 1L })
        assertTrue(ranked.any { it.video.videoID == 2L })
    }

    // ===================== determinism & provenance =====================

    @Test
    fun `ranking is deterministic with zero noise`() {
        val candidates = (1L..10L).map {
            Candidate(video(it, author = "A$it", name = "video $it", uploadDate = now), RecSource.TRENDING)
        }

        val first = rankRecommendations(candidates, emptyList(), emptySet(), now).map { it.video.videoID }
        val second = rankRecommendations(candidates, emptyList(), emptySet(), now).map { it.video.videoID }

        assertEquals(first, second)
    }

    @Test
    fun `reason reflects provenance`() {
        val related = Candidate(video(1, uploadDate = now), RecSource.RELATED, now, sourceLabel = "Cool Video")
        val trending = Candidate(video(2, uploadDate = now), RecSource.TRENDING)
        val topChannel = Candidate(video(3, uploadDate = now), RecSource.TOP_CHANNEL, sourceLabel = "My Channel")

        val ranked = rankRecommendations(listOf(related, trending, topChannel), emptyList(), emptySet(), now)
        val byId = ranked.associateBy { it.video.videoID }

        assertEquals("Because you watched Cool Video", byId.getValue(1L).reason)
        assertEquals("Trending", byId.getValue(2L).reason)
        assertEquals("From My Channel", byId.getValue(3L).reason)
    }

    // ===================== fromPreferences =====================

    @Test
    fun `fromPreferences maps dial extremes`() {
        val discovery = RecommendationWeights.fromPreferences(
            RecommendationPreferences(discoveryFamiliar = 1.0f, freshEvergreen = 0.5f, focusedDiverse = 0.5f),
        )
        val familiar = RecommendationWeights.fromPreferences(
            RecommendationPreferences(discoveryFamiliar = 0.0f, freshEvergreen = 0.5f, focusedDiverse = 0.5f),
        )
        assertTrue(discovery.explorationBase > familiar.explorationBase)
        assertTrue(discovery.maxSubscriptionFraction < familiar.maxSubscriptionFraction)

        val fresh = RecommendationWeights.fromPreferences(
            RecommendationPreferences(freshEvergreen = 1.0f),
        )
        val evergreen = RecommendationWeights.fromPreferences(
            RecommendationPreferences(freshEvergreen = 0.0f),
        )
        assertTrue(fresh.uploadFreshnessDecay > evergreen.uploadFreshnessDecay)
        assertTrue(evergreen.freshnessFloor > fresh.freshnessFloor)

        val diverse = RecommendationWeights.fromPreferences(
            RecommendationPreferences(focusedDiverse = 1.0f),
        )
        val focused = RecommendationWeights.fromPreferences(
            RecommendationPreferences(focusedDiverse = 0.0f),
        )
        assertTrue(focused.mmrLambda > diverse.mmrLambda)
        assertTrue(focused.perAuthorCap > diverse.perAuthorCap)
    }

    // ===================== channelNameMatches =====================

    @Test
    fun `channel name match accepts close names and rejects homonyms`() {
        assertTrue(channelNameMatches("Veritasium", "veritasium"))
        assertTrue(channelNameMatches("Veritasium Official", "Veritasium"))
        assertFalse(channelNameMatches("Some Other Channel", "Veritasium"))
        assertFalse(channelNameMatches("", "Veritasium"))
    }
}
