package com.vayunmathur.web.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.DesktopMaxWidthContainer
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.web.R
import com.vayunmathur.web.platform.WebViewModel

/**
 * The editing omnibox: the focused address field plus its suggestions. A short
 * window (landscape phone, squat desktop split) has no room for a bottom field
 * plus suggestions plus keyboard, so the field docks at the top regardless of
 * the toolbar-edge setting.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OmniboxEditor(
    viewModel: WebViewModel,
    bookmarks: List<com.vayunmathur.web.data.Bookmark>,
    history: List<com.vayunmathur.web.data.HistoryEntry>,
    searchFocusRequester: FocusRequester,
    onNavigate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val currentDraft = viewModel.searchDraft
    val filteredBookmarks = remember(currentDraft, bookmarks) {
        if (currentDraft.isBlank()) bookmarks.take(5)
        else bookmarks.filter { it.url.contains(currentDraft, true) || it.title.contains(currentDraft, true) }.take(8)
    }
    val filteredHistory = remember(currentDraft, history) {
        if (currentDraft.isBlank()) history.take(10)
        else history.filter { it.url.contains(currentDraft, true) || it.title.contains(currentDraft, true) }.take(15)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val effectiveAtBottom = viewModel.searchBarAtBottom && maxHeight >= 480.dp
        // Local field state, keyed once: re-creating TextFieldValue on every
        // keystroke (remember(searchDraft)) pinned the cursor to the end and
        // reset the internal scroll offset, so mid-URL edits were impossible
        // and dragging the cursor left never scrolled. External changes
        // (tab switch) still reset via the LaunchedEffect below.
        var fieldValue by remember {
            mutableStateOf(TextFieldValue(viewModel.searchDraft, TextRange(viewModel.searchDraft.length)))
        }
        LaunchedEffect(viewModel.searchDraft) {
            if (viewModel.searchDraft != fieldValue.text) {
                fieldValue = TextFieldValue(viewModel.searchDraft, TextRange(viewModel.searchDraft.length))
            }
        }
        val omniboxField: @Composable (Modifier) -> Unit = { fieldModifier ->
            OutlinedTextField(
                value = fieldValue,
                onValueChange = {
                    fieldValue = it
                    viewModel.searchDraft = it.text
                },
                modifier = fieldModifier
                    .focusRequester(searchFocusRequester)
                    .onFocusChanged { focus ->
                        if (focus.isFocused) {
                            fieldValue = fieldValue.copy(selection = TextRange(0, fieldValue.text.length))
                        }
                    },
                // Same text size as the read-only address pill in
                // BrowserChrome, so tapping the bar to edit does not
                // shrink the text under the user's finger. The editing
                // field keeps the Material field's natural height,
                // which holds bodyMedium comfortably.
                textStyle = MaterialTheme.typography.bodyMedium,
                placeholder = { Text(stringResource(R.string.search_or_enter_address), style = MaterialTheme.typography.bodyMedium) },
                leadingIcon = { IconSearch() },
                trailingIcon = if (fieldValue.text.isNotEmpty()) {
                    {
                        IconButton(onClick = {
                            fieldValue = TextFieldValue("")
                            viewModel.searchDraft = ""
                        }) { IconClose() }
                    }
                } else null,
                shape = RoundedCornerShape(28.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Search,
                ),
                keyboardActions = KeyboardActions(onSearch = {
                    if (fieldValue.text.isNotBlank()) {
                        onNavigate(fieldValue.text)
                    }
                })
            )
        }
        val dismissOmnibox: @Composable () -> Unit = {
            IconButton(onClick = onDismiss) { IconClose() }
        }
        val suggestions: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit = { paddingValues ->
            OmniboxSuggestions(
                currentDraft = currentDraft,
                filteredBookmarks = filteredBookmarks,
                filteredHistory = filteredHistory,
                searchEngine = viewModel.searchEngine,
                onNavigate = onNavigate,
                paddingValues = paddingValues,
                atBottom = effectiveAtBottom,
            )
        }
        if (effectiveAtBottom) {
            // RAW SCAFFOLD EXCEPTION: with the toolbar at the bottom the editing
            // omnibox has no top bar at all, which AppScaffold cannot express - its
            // top bar is mandatory and would leave an empty bar hanging above the
            // suggestions.
            Scaffold(
                bottomBar = {
                    Surface(Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .navigationBarsPadding()
                                .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            dismissOmnibox()
                            omniboxField(Modifier.weight(1f))
                        }
                    }
                },
            ) { paddingValues -> suggestions(paddingValues) }
        } else {
            AppScaffold(
                title = { omniboxField(Modifier.fillMaxWidth()) },
                navigationIcon = dismissOmnibox,
            ) { paddingValues -> suggestions(paddingValues) }
        }
    }
}
