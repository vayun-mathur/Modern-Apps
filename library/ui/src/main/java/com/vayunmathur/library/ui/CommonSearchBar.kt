@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.vayunmathur.library.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp

/**
 * The scaffold-owned inline search field: an always-visible local filter, not an
 * expandable global-search bar.
 *
 * App screens do NOT call this directly (the fatal `ScaffoldSearch` lint flags
 * it in app code): declare search through a scaffold's `search` parameter
 * ([ScaffoldSearch]) and the scaffold renders this field in its top-bar area.
 * This composable remains the rendering primitive the scaffolds share, plus
 * the sanctioned field for genuinely non-screen contexts that carry a
 * `// SCAFFOLD SEARCH EXCEPTION: <reason>` marker (sheet/drawer-local pickers).
 *
 * This is deliberately built on [SearchBarInputField] (M3
 * `SearchBarDefaults.InputField`) rather than the full [SearchBar] (or the newer
 * `SearchBarState` family): every call-site filters an in-memory list live and has
 * no overlay content, so an expand-to-fullscreen bar would hide the very list being
 * filtered, add an expand/collapse cycle on every focus, and fight the `TopAppBar`
 * title slot it usually sits in — pure regression for zero benefit. The input field
 * alone gives real search semantics (IME search action, search text style/colors,
 * a11y role) with the same inline layout footprint as before.
 *
 * [onSearch] overrides the IME search action; by default live filtering has
 * already applied the query, so the action just dismisses the keyboard.
 * Explicit-submit screens (shop, via [ScaffoldSearch.onSearch]) pass one.
 */
@Composable
fun CommonSearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search",
    padding: PaddingValues = PaddingValues(16.dp),
    onSearch: ((String) -> Unit)? = null,
) {
    val focusManager = LocalFocusManager.current
    SearchBarInputField(
        query = value,
        onQueryChange = onValueChange,
        onSearch = { submitted ->
            if (onSearch != null) onSearch(submitted) else focusManager.clearFocus()
        },
        expanded = false,
        onExpandedChange = {},
        modifier = modifier
            .fillMaxWidth()
            .padding(padding),
        placeholder = { Text(placeholder) },
        leadingIcon = {
            IconSearch()
        },
        trailingIcon = if (value.isNotEmpty()) {
            {
                IconButton(onClick = { onValueChange("") }) {
                    IconClose()
                }
            }
        } else null,
    )
}
