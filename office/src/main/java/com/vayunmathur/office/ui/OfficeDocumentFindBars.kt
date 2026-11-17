package com.vayunmathur.office.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.ExpandVisibility
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Slider
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TextField
import com.vayunmathur.library.ui.TextFieldDefaults
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.office.platform.OfficeViewModel
import com.vayunmathur.office.platform.findMatchBlocks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

import com.vayunmathur.office.R
import com.vayunmathur.office.platform.replaceInDocument
/**
 * Timer, search/replace, font-size and word-count bars for [DocumentScreen].
 *
 * Moved verbatim (split for file length); the screen's locals became [DocumentScreenState]
 * reads/writes, behavior identical.
 */
private const val TIMER_FORMAT = "⏱ %02d:%02d"
private const val SECONDS_PER_MINUTE = 60

@Composable
fun DocumentFindBars(
    s: DocumentScreenState,
    document: OdfDocument,
    viewModel: OfficeViewModel,
    isTextDoc: Boolean,
    isPresentation: Boolean,
    wordCount: Int,
    charCount: Int,
    readingTime: Int,
    scope: CoroutineScope,
    listState: LazyListState,
) {
    TimerBar(s, isPresentation)
    ExpandVisibility(visible = s.showSearch) {
        Column {
            SearchField(s, isTextDoc)
            MatchNavigator(s, document, viewModel, isTextDoc, scope, listState)
            ReplaceBar(s, viewModel)
        }
    }
    FontScaleBar(s)
    WordCountBar(s, isTextDoc, wordCount, charCount, readingTime)
}

/** Presentation timer bar. */
@Composable
private fun TimerBar(s: DocumentScreenState, isPresentation: Boolean) {
    ExpandVisibility(visible = s.showTimer && isPresentation) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(
                    TIMER_FORMAT.format(s.timerSeconds / SECONDS_PER_MINUTE, s.timerSeconds % SECONDS_PER_MINUTE),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { s.timerSeconds = 0 }) { Text(stringResource(UiR.string.reset)) }
                TextButton(onClick = { s.showTimer = false }) { Text(stringResource(UiR.string.stop)) }
            }
        }
    }
}

/** Search query field. */
@Composable
private fun SearchField(s: DocumentScreenState, isTextDoc: Boolean) {
    TextField(
        value = s.searchQuery,
        onValueChange = { s.searchQuery = it },
        placeholder = { Text(stringResource(R.string.search_hint)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant),
        trailingIcon = {
            Row {
                if (isTextDoc) TextButton(onClick = { s.showReplaceBar = !s.showReplaceBar }) {
                    Text(
                        if (s.showReplaceBar) {
                            stringResource(R.string.hide)
                        } else {
                            stringResource(R.string.replace_1)
                        },
                    )
                }
                IconButton(onClick = {
                    s.showSearch = false
                    s.searchQuery = ""
                    s.showReplaceBar = false
                }) { IconClose() }
            }
        })
}

/** Prev/next match navigation. */
@Composable
private fun MatchNavigator(
    s: DocumentScreenState,
    document: OdfDocument,
    viewModel: OfficeViewModel,
    isTextDoc: Boolean,
    scope: CoroutineScope,
    listState: LazyListState,
) {
    if (!isTextDoc || s.searchQuery.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        val total = remember(
            s.searchQuery,
            s.matchCase,
            s.wholeWord,
            document) { viewModel.findMatchBlocks(s.searchQuery, s.matchCase, s.wholeWord).size }
        TextButton(onClick = { jumpMatch(s, document, viewModel, scope, listState, -1) }) {
            Text(stringResource(R.string.prev))
        }
        TextButton(onClick = { jumpMatch(s, document, viewModel, scope, listState, 1) }) {
            Text(stringResource(UiR.string.next))
        }
        Text(
            if (total > 0) "${(s.findIndex % total) + 1}/$total" else "0/0",
            style = MaterialTheme.typography.labelMedium)
    }
}

/** Jumps the list to the match at [delta] offset. */
private fun jumpMatch(
    s: DocumentScreenState,
    document: OdfDocument,
    viewModel: OfficeViewModel,
    scope: CoroutineScope,
    listState: LazyListState,
    delta: Int,
) {
    val matches = viewModel.findMatchBlocks(s.searchQuery, s.matchCase, s.wholeWord)
    s.findMatches = matches
    if (matches.isEmpty()) return
    s.findIndex = ((s.findIndex + delta) % matches.size + matches.size) % matches.size
    scope.launch { listState.animateScrollToItem(matches[s.findIndex].coerceIn(
        0,
        (document as? OdfDocument.TextDocument)?.content?.size?.minus(1) ?: 0)) }
}
/** Replace bar (query + one/all + match options). */
@Composable
private fun ReplaceBar(s: DocumentScreenState, viewModel: OfficeViewModel) {
    ExpandVisibility(visible = s.showReplaceBar && s.searchQuery.isNotEmpty()) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = s.replaceText,
                    onValueChange = { s.replaceText = it },
                    placeholder = { Text(stringResource(R.string.replace_hint)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant))
                TextButton(onClick = { viewModel.replaceInDocument(
                    s.searchQuery,
                    s.replaceText,
                    false,
                    s.matchCase,
                    s.wholeWord) }) { Text(stringResource(R.string.one)) }
                TextButton(onClick = { replaceAll(s, viewModel) }) { Text(stringResource(UiR.string.all)) }
            }
            MatchOptionsRow(s)
        }
    }
}

/** Replaces all matches and clears the query when something changed. */
internal fun replaceAll(s: DocumentScreenState, viewModel: OfficeViewModel) {
    val n = viewModel.replaceInDocument(
        s.searchQuery,
        s.replaceText,
        true,
        s.matchCase,
        s.wholeWord)
    if (n > 0) s.searchQuery = ""
}

/** Match-case / whole-word toggles. */
@Composable
private fun MatchOptionsRow(s: DocumentScreenState) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { s.matchCase = !s.matchCase }) {
            Text(
                if (s.matchCase) {
                    stringResource(R.string.case_on)
                } else {
                    stringResource(R.string.case_1)
                },
            )
        }
        TextButton(onClick = { s.wholeWord = !s.wholeWord }) {
            Text(
                if (s.wholeWord) {
                    stringResource(R.string.word)
                } else {
                    stringResource(R.string.word_1)
                },
            )
        }
    }
}

/** Font-scale bar. */
@Composable
private fun FontScaleBar(s: DocumentScreenState) {
    ExpandVisibility(visible = s.showFontControl) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text("A", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = s.fontSizeMultiplier,
                onValueChange = { s.fontSizeMultiplier = it },
                valueRange = 0.5f..2.0f,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
            Text("A", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { s.fontSizeMultiplier = 1f }) { Text(stringResource(UiR.string.reset)) }
        }
    }
}

/** Word-count bar. */
@Composable
private fun WordCountBar(
    s: DocumentScreenState,
    isTextDoc: Boolean,
    wordCount: Int,
    charCount: Int,
    readingTime: Int,
) {
    ExpandVisibility(visible = s.showWordBar && isTextDoc) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
            Text(stringResource(R.string.words_characters_min_read, wordCount, charCount, readingTime),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
        }
    }
}
