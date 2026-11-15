package com.vayunmathur.youpipe.util

import androidx.lifecycle.viewModelScope
import com.vayunmathur.youpipe.data.ChannelPreference
import com.vayunmathur.youpipe.data.KeywordPreference
import com.vayunmathur.youpipe.data.RecommendationPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "YouPipeViewModel"

/**
 * User-facing recommendation controls: feed-mix dials, source toggles, content
 * filters, channel overrides and keyword mutes.
 *
 * Split from YouPipeRecommendations.kt (file-level TooManyFunctions); behavior
 * identical, same package so internal access is unchanged.
 */
private fun YouPipeViewModel.updatePrefs(transform: (RecommendationPreferences) -> RecommendationPreferences) {
    viewModelScope.launch(Dispatchers.IO) {
        val current = repository.recommendationStore.getRecommendationPreferences() ?: RecommendationPreferences()
        repository.recommendationStore.upsertRecommendationPreferences(transform(current))
    }
}

fun YouPipeViewModel.setPreset(preset: RecommendationPreset) = updatePrefs {
    it.copy(
        preset = preset.name,
        discoveryFamiliar = preset.discoveryFamiliar,
        freshEvergreen = preset.freshEvergreen,
        focusedDiverse = preset.focusedDiverse,
    )
}

fun YouPipeViewModel.setDiscoveryFamiliar(value: Float) =
    updatePrefs { it.copy(discoveryFamiliar = value, preset = CUSTOM_PRESET) }
fun YouPipeViewModel.setFreshEvergreen(value: Float) =
    updatePrefs { it.copy(freshEvergreen = value, preset = CUSTOM_PRESET) }
fun YouPipeViewModel.setFocusedDiverse(value: Float) =
    updatePrefs { it.copy(focusedDiverse = value, preset = CUSTOM_PRESET) }

fun YouPipeViewModel.toggleSource(source: RecSource) = updatePrefs {
    when (source) {
        RecSource.RELATED -> it.copy(sourceRelated = !it.sourceRelated)
        RecSource.TRENDING -> it.copy(sourceTrending = !it.sourceTrending)
        RecSource.SUBSCRIPTION -> it.copy(sourceSubscription = !it.sourceSubscription)
        RecSource.TOP_CHANNEL -> it.copy(sourceTopChannel = !it.sourceTopChannel)
        RecSource.SEARCH -> it.copy(sourceSearch = !it.sourceSearch)
    }
}

fun YouPipeViewModel.setHideShorts(value: Boolean) = updatePrefs { it.copy(hideShorts = value) }
fun YouPipeViewModel.setHideLive(value: Boolean) = updatePrefs { it.copy(hideLive = value) }
fun YouPipeViewModel.setHidePaid(value: Boolean) = updatePrefs { it.copy(hidePaid = value) }
fun YouPipeViewModel.setMinDuration(seconds: Long) = updatePrefs { it.copy(minDurationSec = seconds.coerceAtLeast(0)) }
fun YouPipeViewModel.setMaxDuration(seconds: Long) = updatePrefs { it.copy(maxDurationSec = seconds.coerceAtLeast(0)) }

private fun YouPipeViewModel.updateChannelPref(
    channelKey: String,
    transform: (ChannelPreference) -> ChannelPreference,
) {
    val key = channelKey.lowercase()
    viewModelScope.launch(Dispatchers.IO) {
        val current = repository.recommendationStore.getChannelPreference(key) ?: ChannelPreference(key)
        repository.recommendationStore.upsertChannelPreference(transform(current))
    }
}

fun YouPipeViewModel.blockChannel(channelKey: String) = updateChannelPref(channelKey) { it.copy(blocked = true) }
fun YouPipeViewModel.demoteChannel(channelKey: String) =
    updateChannelPref(channelKey) { it.copy(multiplier = DEMOTE_MULTIPLIER) }
fun YouPipeViewModel.boostChannel(channelKey: String) =
    updateChannelPref(channelKey) { it.copy(multiplier = BOOST_MULTIPLIER) }
fun YouPipeViewModel.pinChannel(channelKey: String) = updateChannelPref(channelKey) { it.copy(pinned = true) }

fun YouPipeViewModel.clearChannelPreference(channelKey: String) {
    viewModelScope.launch(Dispatchers.IO) {
        repository.recommendationStore.deleteChannelPreference(channelKey.lowercase())
    }
}

fun YouPipeViewModel.muteKeyword(keyword: String) {
    viewModelScope.launch(Dispatchers.IO) {
        repository.recommendationStore.upsertKeywordPreference(KeywordPreference(keyword.lowercase(), muted = true))
    }
}

fun YouPipeViewModel.unmuteKeyword(keyword: String) {
    viewModelScope.launch(Dispatchers.IO) {
        repository.recommendationStore.deleteKeywordPreference(keyword.lowercase())
    }
}

/**
 * Removes an inferred interest by zeroing its signal: a channel gets a 0.0
 * score multiplier, a keyword gets muted.
 */
fun YouPipeViewModel.removeInterest(channelKey: String? = null, keyword: String? = null) {
    channelKey?.let { updateChannelPref(it) { pref -> pref.copy(multiplier = 0.0) } }
    keyword?.let { muteKeyword(it) }
}

/** Clears all learned/overridden recommendation state and resets the dials. */
fun YouPipeViewModel.resetAlgorithm() {
    viewModelScope.launch(Dispatchers.IO) {
        repository.recommendationStore.clearAllRecommendationImpressions()
        repository.recommendationStore.clearAllChannelPreferences()
        repository.recommendationStore.clearAllKeywordPreferences()
        repository.recommendationStore.clearAllRecommendationPreferences()
        trendingCache = null
    }
}
