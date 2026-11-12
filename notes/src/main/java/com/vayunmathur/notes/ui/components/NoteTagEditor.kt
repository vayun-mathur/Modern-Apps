package com.vayunmathur.notes.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AssistChip
import com.vayunmathur.library.ui.IconClear
import com.vayunmathur.library.ui.IconTag
import com.vayunmathur.library.ui.InputChip
import com.vayunmathur.library.ui.LocalContentColor
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.notes.R
import com.vayunmathur.notes.domain.MAX_TAGS_PER_NOTE
import com.vayunmathur.notes.domain.suggestTags

private const val MAX_SUGGESTIONS = 8

/**
 * The editor's tag row, under the title: the note's [tags] as chips (tap the
 * chip or its x to remove), then an "Add tag" field. Done or a comma commits
 * what was typed (leaving the field does not); while it has focus, [knownTags] (every tag in use,
 * most used first) that match the text are offered as one-tap suggestions.
 * At [MAX_TAGS_PER_NOTE] the field is replaced by a short note saying so.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NoteTagEditor(
    tags: List<String>,
    knownTags: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var input by rememberSaveable { mutableStateOf("") }
    var focused by rememberSaveable { mutableStateOf(false) }
    val commit = {
        if (input.isNotBlank()) onAdd(input)
        input = ""
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            tags.forEach { tag ->
                val removeLabel = stringResource(R.string.remove_tag, tag)
                InputChip(
                    selected = false,
                    onClick = { onRemove(tag) },
                    label = { Text(tag, Modifier.widthIn(max = 200.dp), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.semantics { contentDescription = removeLabel },
                    trailingIcon = { IconClear(Modifier.size(18.dp), contentDescription = null) },
                )
            }
            if (tags.size < MAX_TAGS_PER_NOTE) {
                TagInputField(
                    value = input,
                    onValueChange = { typed ->
                        // A comma ends a tag, so "a, b, c" pasted or typed adds three.
                        if (',' in typed) {
                            typed.split(',').dropLast(1).filter { it.isNotBlank() }.forEach(onAdd)
                            input = typed.substringAfterLast(',')
                        } else {
                            input = typed
                        }
                    },
                    onDone = commit,
                    // Leaving the field keeps any half-typed text there rather than
                    // committing it, so tapping elsewhere never creates a stray tag.
                    onFocusChange = { focused = it },
                )
            } else {
                Text(
                    pluralStringResource(R.plurals.tag_limit_reached, MAX_TAGS_PER_NOTE, MAX_TAGS_PER_NOTE),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val suggestions = if (focused) suggestTags(knownTags, input, tags, MAX_SUGGESTIONS) else emptyList()
        if (suggestions.isNotEmpty() && tags.size < MAX_TAGS_PER_NOTE) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                suggestions.forEach { s ->
                    AssistChip(
                        onClick = {
                            onAdd(s)
                            input = ""
                        },
                        label = { Text(s, maxLines = 1) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TagInputField(
    value: String,
    onValueChange: (String) -> Unit,
    onDone: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
) {
    val style = MaterialTheme.typography.bodyMedium.copy(color = LocalContentColor.current)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .widthIn(min = 120.dp, max = 240.dp)
            .onFocusChanged { onFocusChange(it.isFocused) },
        singleLine = true,
        textStyle = style,
        cursorBrush = SolidColor(LocalContentColor.current),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        decorationBox = { inner ->
            // 48dp: the chips' touch-target height, so the field lines up with them in the row.
            Row(
                Modifier.heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                IconTag(
                    Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    contentDescription = null,
                )
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(
                            stringResource(R.string.add_tag),
                            style = style.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                        )
                    }
                    inner()
                }
            }
        },
    )
}
