package com.vayunmathur.youpipe.data

import kotlin.time.Instant

/**
 * Recommendation-related reads/writes (cached related videos, impressions, preferences,
 * channel/keyword overrides), split from [SubscriptionRepository] (TooManyFunctions cap).
 *
 * Same package and module; behavior identical. Access via
 * [SubscriptionRepository.recommendationStore].
 */
class RecommendationStore(
    private val cachedRelatedVideoDao: CachedRelatedVideoDao,
    private val recommendationImpressionDao: RecommendationImpressionDao,
    private val recommendationPreferencesDao: RecommendationPreferencesDao,
    private val channelPreferenceDao: ChannelPreferenceDao,
    private val keywordPreferenceDao: KeywordPreferenceDao,
) {
    suspend fun getAllCachedRelatedVideos(): List<CachedRelatedVideo> = cachedRelatedVideoDao.getAll()

    suspend fun upsertCachedRelatedVideos(values: List<CachedRelatedVideo>) =
        cachedRelatedVideoDao.upsertAll(values)

    suspend fun deleteCachedRelatedOlderThan(cutoff: Instant) =
        cachedRelatedVideoDao.deleteOlderThan(cutoff)

    suspend fun getAllRecommendationImpressions(): List<RecommendationImpression> =
        recommendationImpressionDao.getAll()

    suspend fun recordRecommendationImpression(
        videoID: Long,
        channelKey: String,
        source: String,
        now: Instant,
    ) = recommendationImpressionDao.recordImpression(videoID, channelKey, source, now)

    suspend fun deleteRecommendationImpressionsOlderThan(cutoff: Instant) =
        recommendationImpressionDao.deleteOlderThan(cutoff)

    suspend fun clearAllRecommendationImpressions() = recommendationImpressionDao.clearAll()

    suspend fun getRecommendationPreferences(): RecommendationPreferences? = recommendationPreferencesDao.get()

    suspend fun upsertRecommendationPreferences(value: RecommendationPreferences) =
        recommendationPreferencesDao.upsert(value)

    suspend fun clearAllRecommendationPreferences() = recommendationPreferencesDao.clearAll()

    suspend fun getAllChannelPreferences(): List<ChannelPreference> = channelPreferenceDao.getAll()

    suspend fun getChannelPreference(channelKey: String): ChannelPreference? =
        channelPreferenceDao.get(channelKey)

    suspend fun upsertChannelPreference(value: ChannelPreference) = channelPreferenceDao.upsert(value)

    suspend fun deleteChannelPreference(channelKey: String) = channelPreferenceDao.delete(channelKey)

    suspend fun clearAllChannelPreferences() = channelPreferenceDao.clearAll()

    suspend fun getAllKeywordPreferences(): List<KeywordPreference> = keywordPreferenceDao.getAll()

    suspend fun upsertKeywordPreference(value: KeywordPreference) = keywordPreferenceDao.upsert(value)

    suspend fun deleteKeywordPreference(keyword: String) = keywordPreferenceDao.delete(keyword)

    suspend fun clearAllKeywordPreferences() = keywordPreferenceDao.clearAll()
}
