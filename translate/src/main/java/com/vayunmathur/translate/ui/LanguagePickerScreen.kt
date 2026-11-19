package com.vayunmathur.translate.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.CommonSearchBar
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.IconHistory
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.translate.R
import com.vayunmathur.translate.domain.Language
import com.vayunmathur.translate.domain.Languages
import com.vayunmathur.translate.platform.LanguagePickerActions
import com.vayunmathur.translate.platform.LanguagePickerUiState

/**
 * The full-screen language picker, with no dependency on the ViewModel so it can be
 * rendered from a `@Preview` — see `src/screenshotTest`, which is where the store
 * listing images come from.
 *
 * A pinned Auto-detect row (source mode only), then the recent languages, then the
 * full list — all filtered live by the search field.
 */
@Composable
fun LanguagePickerScreen(state: LanguagePickerUiState, actions: LanguagePickerActions) {
    val title = stringResource(
        if (state.forSource) R.string.select_source_language else R.string.select_target_language,
    )
    AppScaffold(
        title = title,
        onNavigateBack = actions::goBack,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            CommonSearchBar(
                value = state.query,
                onValueChange = actions::setQuery,
                placeholder = stringResource(R.string.search_languages),
            )
            val all = remember(state.forSource) {
                if (state.forSource) Languages.SOURCES else Languages.TARGETS
            }
            val filtered = remember(all, state.recents, state.query) {
                // AUTO has its own pinned row in source mode, so it never appears
                // in the main list (TARGETS never contains it anyway).
                all.filter {
                    it != Languages.AUTO && it !in state.recents && it.matches(state.query)
                }
            }
            val filteredRecents = remember(state.recents, state.query) {
                state.recents.filter { it.matches(state.query) }
            }
            val showAuto = state.forSource && Languages.AUTO.matches(state.query)
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (showAuto) {
                    item(key = Languages.AUTO.code) {
                        LanguageRow(
                            language = Languages.AUTO,
                            selected = state.selectedCode == Languages.AUTO.code,
                            leading = null,
                            onSelect = { actions.select(Languages.AUTO.code) },
                        )
                    }
                    item(key = "divider-auto") { HorizontalDivider() }
                }
                if (filteredRecents.isNotEmpty()) {
                    item(key = "header-recent") {
                        SectionHeader(text = stringResource(R.string.recent_languages))
                    }
                    items(filteredRecents, key = { "recent-${it.code}" }) { language ->
                        LanguageRow(
                            language = language,
                            selected = language.code == state.selectedCode,
                            leading = { IconHistory() },
                            onSelect = { actions.select(language.code) },
                        )
                    }
                    item(key = "divider-recent") { HorizontalDivider() }
                }
                if (filtered.isNotEmpty()) {
                    item(key = "header-all") {
                        SectionHeader(text = stringResource(R.string.all_languages))
                    }
                    items(filtered, key = { it.code }) { language ->
                        LanguageRow(
                            language = language,
                            selected = language.code == state.selectedCode,
                            leading = null,
                            onSelect = { actions.select(language.code) },
                        )
                    }
                }
                if (filtered.isEmpty() && filteredRecents.isEmpty() && !showAuto) {
                    item(key = "empty") {
                        Text(
                            text = stringResource(R.string.no_languages_match, state.query),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun Language.matches(query: String): Boolean {
    if (query.isBlank()) return true
    return nativeName.contains(query, ignoreCase = true) ||
        englishName.contains(query, ignoreCase = true) ||
        code.contains(query, ignoreCase = true)
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun LanguageRow(
    language: Language,
    selected: Boolean,
    leading: (@Composable () -> Unit)?,
    onSelect: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(language.nativeName) },
        supportingContent = { Text(language.englishName) },
        leadingContent = leading,
        trailingContent = if (selected) {
            { IconCheck() }
        } else {
            null
        },
        modifier = Modifier.clickable(onClick = onSelect),
    )
}
