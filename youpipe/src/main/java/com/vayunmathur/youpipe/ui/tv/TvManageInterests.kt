package com.vayunmathur.youpipe.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.vayunmathur.library.tv.TvButton
import com.vayunmathur.library.tv.TvInitialFocus
import com.vayunmathur.library.tv.TvListRow
import com.vayunmathur.library.tv.tvContentPadding
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.platform.YouPipeViewModel
import com.vayunmathur.youpipe.platform.clearChannelPreference
import com.vayunmathur.youpipe.network.decodeHtml
import com.vayunmathur.youpipe.platform.pinChannel
import com.vayunmathur.youpipe.platform.removeInterest
import com.vayunmathur.youpipe.platform.unmuteKeyword

/**
 * TV interests: channels/topics/blocked/muted as [TvListRow]s with pin/remove trailing
 * actions on a second focus pass.
 *
 * Same `interestProfile`/`channelPreferences`/`keywordPreferences` bindings as the phone.
 * Each row is one focus target; its actions row sits directly under it so DOWN reaches
 * them with no nesting.
 */
@Composable
fun TvManageInterestsPage(
    ypvm: YouPipeViewModel,
) {
    val profile by ypvm.interestProfile.collectAsState()
    val channelPrefs by ypvm.channelPreferences.collectAsState()
    val keywordPrefs by ypvm.keywordPreferences.collectAsState()
    val firstFocus = remember { FocusRequester() }
    val blockedKeys = channelPrefs.filter { it.blocked }.map { it.channelKey }.toSet()
    val pinnedKeys = channelPrefs.filter { it.pinned }.map { it.channelKey }.toSet()
    val topChannels = remember(profile, blockedKeys) {
        profile.authorWeights.entries
            .filter { it.key.isNotBlank() && it.key !in blockedKeys }
            .sortedByDescending { it.value }
            .take(30)
            .map { it.key }
    }
    val topKeywords = remember(profile) {
        profile.keywordWeights.entries
            .sortedByDescending { it.value }
            .take(30)
            .map { it.key }
    }
    val blockedChannels = remember(channelPrefs) {
        channelPrefs.filter { it.blocked }.map { it.channelKey }
    }
    val mutedKeywords = remember(keywordPrefs) {
        keywordPrefs.filter { it.muted }.map { it.keyword }
    }
    TvInitialFocus(firstFocus)
    TvManageInterestsList(
        ypvm = ypvm,
        topChannels = topChannels,
        pinnedKeys = pinnedKeys,
        topKeywords = topKeywords,
        blockedChannels = blockedChannels,
        mutedKeywords = mutedKeywords,
    )
}

@Composable
private fun TvManageInterestsList(
    ypvm: YouPipeViewModel,
    topChannels: List<String>,
    pinnedKeys: Set<String>,
    topKeywords: List<String>,
    blockedChannels: List<String>,
    mutedKeywords: List<String>,
) {
    TvSettingsBody {
        LazyColumn(
            state = rememberLazyListState(),
            modifier = Modifier.fillMaxSize(),
            contentPadding = tvContentPadding(top = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            tvInterestsTitle()
            tvChannelsSection(ypvm, topChannels, pinnedKeys)
            tvKeywordsSection(ypvm, topKeywords)
            tvBlockedSection(ypvm, blockedChannels)
            tvMutedSection(ypvm, mutedKeywords)
        }
    }
}

private fun LazyListScope.tvInterestsTitle() {
    item(key = "interests-title") {
        Text(
            text = stringResource(R.string.title_manage_interests),
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun LazyListScope.tvChannelsSection(
    ypvm: YouPipeViewModel,
    topChannels: List<String>,
    pinnedKeys: Set<String>,
) {
    item(key = "ch-header") {
        TvInterestSectionHeader(stringResource(R.string.label_your_channels))
    }
    if (topChannels.isEmpty()) {
        item(key = "ch-empty") {
            TvInterestEmpty(stringResource(R.string.label_no_channels))
        }
    }
    items(topChannels, key = { "ch-$it" }) { channelKey ->
        val pinned = channelKey in pinnedKeys
        TvInterestRow(
            label = channelKey.decodeHtml(),
            badge = if (pinned) stringResource(R.string.label_pinned) else null,
            onPrimary = {
                if (pinned) ypvm.clearChannelPreference(channelKey) else ypvm.pinChannel(channelKey)
            },
            secondaryAction = stringResource(UiR.string.remove),
            onSecondary = { ypvm.removeInterest(channelKey = channelKey) },
        )
    }
}

private fun LazyListScope.tvKeywordsSection(
    ypvm: YouPipeViewModel,
    topKeywords: List<String>,
) {
    item(key = "kw-header") {
        TvInterestSectionHeader(stringResource(R.string.label_your_topics))
    }
    if (topKeywords.isEmpty()) {
        item(key = "kw-empty") {
            TvInterestEmpty(stringResource(R.string.label_no_topics))
        }
    }
    items(topKeywords, key = { "kw-$it" }) { keyword ->
        TvInterestRow(
            label = keyword,
            onPrimary = { ypvm.removeInterest(keyword = keyword) },
        )
    }
}

private fun LazyListScope.tvBlockedSection(
    ypvm: YouPipeViewModel,
    blockedChannels: List<String>,
) {
    item(key = "bl-header") {
        TvInterestSectionHeader(stringResource(R.string.label_blocked_channels))
    }
    if (blockedChannels.isEmpty()) {
        item(key = "bl-empty") {
            TvInterestEmpty(stringResource(R.string.label_no_blocked_channels))
        }
    }
    items(blockedChannels, key = { "bl-$it" }) { channelKey ->
        TvInterestRow(
            label = channelKey.decodeHtml(),
            onPrimary = { ypvm.clearChannelPreference(channelKey) },
        )
    }
}

private fun LazyListScope.tvMutedSection(
    ypvm: YouPipeViewModel,
    mutedKeywords: List<String>,
) {
    item(key = "mk-header") {
        TvInterestSectionHeader(stringResource(R.string.label_muted_keywords))
    }
    if (mutedKeywords.isEmpty()) {
        item(key = "mk-empty") {
            TvInterestEmpty(stringResource(R.string.label_no_muted_keywords))
        }
    }
    items(mutedKeywords, key = { "mk-$it" }) { keyword ->
        TvInterestRow(
            label = keyword,
            onPrimary = { ypvm.unmuteKeyword(keyword) },
        )
    }
}

@Composable
private fun TvInterestSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp),
    )
}

@Composable
private fun TvInterestEmpty(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * One interest row: the row itself fires the primary action; a secondary action (when
 * present) is a [TvButton] on the line below so DOWN always reaches it with no nesting.
 */
@Composable
private fun TvInterestRow(
    label: String,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
    secondaryAction: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth()) {
        TvListRow(
            headline = label,
            supporting = badge,
            onClick = onPrimary,
        )
        if (secondaryAction != null && onSecondary != null) {
            TvButton(
                text = secondaryAction,
                onClick = onSecondary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
