package com.vayunmathur.youpipe.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.appBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.R
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.youpipe.Route
import com.vayunmathur.youpipe.platform.YouPipeViewModel
import com.vayunmathur.youpipe.platform.clearChannelPreference
import com.vayunmathur.youpipe.network.decodeHtml
import com.vayunmathur.youpipe.platform.pinChannel
import com.vayunmathur.youpipe.platform.removeInterest
import com.vayunmathur.youpipe.platform.unmuteKeyword

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageInterestsPage(
    backStack: NavBackStack<Route>,
    ypvm: YouPipeViewModel,
) {
    val profile by ypvm.interestProfile.collectAsState()
    val channelPrefs by ypvm.channelPreferences.collectAsState()
    val keywordPrefs by ypvm.keywordPreferences.collectAsState()

    val blockedKeys = channelPrefs.filter { it.blocked }.map { it.channelKey }.toSet()
    val pinnedKeys = channelPrefs.filter { it.pinned }.map { it.channelKey }.toSet()

    val topChannels = profile.authorWeights.entries
        .filter { it.key.isNotBlank() && it.key !in blockedKeys }
        .sortedByDescending { it.value }
        .take(30)
        .map { it.key }
    val topKeywords = profile.keywordWeights.entries
        .sortedByDescending { it.value }
        .take(30)
        .map { it.key }

    val blockedChannels = channelPrefs.filter { it.blocked }.map { it.channelKey }
    val mutedKeywords = keywordPrefs.filter { it.muted }.map { it.keyword }

    AppScaffold(
        title = stringResource(R.string.title_manage_interests),
        backStack = backStack,
    ) { paddingValues ->
        LazyColumn(Modifier.padding(paddingValues)) {
            item { SectionHeader(stringResource(R.string.label_your_channels)) }
            if (topChannels.isEmpty()) {
                item { EmptyRow(stringResource(R.string.label_no_channels)) }
            }
            items(topChannels, key = { "ch-$it" }) { channelKey ->
                val pinned = channelKey in pinnedKeys
                ListItem(
                    content = { Text(channelKey.decodeHtml(), style = MaterialTheme.typography.titleMedium) },
                    supportingContent = { if (pinned) Text(stringResource(R.string.label_pinned)) },
                    trailingContent = {
                        androidx.compose.foundation.layout.Row {
                            TextButton(onClick = {
                                if (pinned) ypvm.clearChannelPreference(channelKey) else ypvm.pinChannel(channelKey)
                            }) { Text(stringResource(if (pinned) R.string.action_unpin else R.string.action_pin)) }
                            TextButton(onClick = { ypvm.removeInterest(channelKey = channelKey) }) {
                                Text(stringResource(UiR.string.remove))
                            }
                        }
                    },
                )
            }

            item { SectionHeader(stringResource(R.string.label_your_topics)) }
            if (topKeywords.isEmpty()) {
                item { EmptyRow(stringResource(R.string.label_no_topics)) }
            }
            items(topKeywords, key = { "kw-$it" }) { keyword ->
                ListItem(
                    content = { Text(keyword, style = MaterialTheme.typography.titleMedium) },
                    trailingContent = {
                        TextButton(onClick = { ypvm.removeInterest(keyword = keyword) }) {
                            Text(stringResource(UiR.string.remove))
                        }
                    },
                )
            }

            item { SectionHeader(stringResource(R.string.label_blocked_channels)) }
            if (blockedChannels.isEmpty()) {
                item { EmptyRow(stringResource(R.string.label_no_blocked_channels)) }
            }
            items(blockedChannels, key = { "bl-$it" }) { channelKey ->
                ListItem(
                    content = { Text(channelKey.decodeHtml(), style = MaterialTheme.typography.titleMedium) },
                    trailingContent = {
                        TextButton(onClick = { ypvm.clearChannelPreference(channelKey) }) {
                            Text(stringResource(R.string.action_unblock))
                        }
                    },
                )
            }

            item { SectionHeader(stringResource(R.string.label_muted_keywords)) }
            if (mutedKeywords.isEmpty()) {
                item { EmptyRow(stringResource(R.string.label_no_muted_keywords)) }
            }
            items(mutedKeywords, key = { "mk-$it" }) { keyword ->
                ListItem(
                    content = { Text(keyword, style = MaterialTheme.typography.titleMedium) },
                    trailingContent = {
                        TextButton(onClick = { ypvm.unmuteKeyword(keyword) }) {
                            Text(stringResource(R.string.action_unmute))
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun EmptyRow(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
    )
}
