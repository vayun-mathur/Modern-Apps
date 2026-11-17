package com.vayunmathur.code.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.code.R
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconChevronRight
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconKeyboardArrowUp
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton

/** The find/replace bar: two fields, prev/next navigation, a match count and replace actions. */
@Composable
fun FindBar(
    query: String,
    replacement: String,
    caseSensitive: Boolean,
    useRegex: Boolean,
    regexValid: Boolean,
    matchCount: Int,
    activeMatch: Int,
    onQueryChange: (String) -> Unit,
    onReplacementChange: (String) -> Unit,
    onToggleCase: () -> Unit,
    onToggleRegex: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onReplace: () -> Unit,
    onReplaceAll: () -> Unit,
    onClose: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.find)) },
                singleLine = true,
                isError = !regexValid,
                supportingText = if (!regexValid) {
                    { Text(stringResource(R.string.invalid_regex)) }
                } else {
                    null
                },
            )
            val label = if (matchCount == 0) "0/0" else "${activeMatch + 1}/$matchCount"
            Text(label, modifier = Modifier.padding(horizontal = 8.dp))
            IconButton(onClick = onPrev, enabled = matchCount > 0) { IconKeyboardArrowUp() }
            IconButton(onClick = onNext, enabled = matchCount > 0) { IconChevronRight() }
            IconButton(onClick = onClose) { IconClose() }
        }
        Spacer(Modifier.size(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = replacement,
                onValueChange = onReplacementChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.replace)) },
                singleLine = true,
            )
            TextButton(onClick = onReplace, enabled = matchCount > 0) { Text(stringResource(R.string.replace)) }
            TextButton(onClick = onReplaceAll, enabled = matchCount > 0) { Text(stringResource(UiR.string.all)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToggleChip(
                label = stringResource(R.string.match_case),
                selected = caseSensitive,
                onClick = onToggleCase,
            )
            ToggleChip(
                label = stringResource(R.string.use_regex),
                selected = useRegex,
                onClick = onToggleRegex,
            )
        }
    }
}

/** A small text toggle that colours itself when active; used for the find-bar options. */
@Composable
private fun ToggleChip(label: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(
            label,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

