package com.vayunmathur.notes.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.ConfirmDialog
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconEdit
import com.vayunmathur.library.ui.Text
import com.vayunmathur.notes.R
import com.vayunmathur.notes.domain.TagCount
import com.vayunmathur.notes.domain.tagKey
import com.vayunmathur.notes.ui.dialogs.RenameTagDialog
import com.vayunmathur.library.ui.R as UiR

/**
 * The notes list's tag filter: an "All" chip (with the total note count) and
 * one chip per tag (with its note count), in [tags] order — most used first.
 *
 * Single-select: tapping a tag shows only notes carrying it, tapping it again
 * or tapping All shows everything. [selected] is the active tag, null for All.
 * Long-pressing a tag chip opens a small menu: Rename (every note; merges into
 * an existing tag of that name) and Delete (strips the tag from every note,
 * never deletes a note), each confirmed in a dialog.
 */
@Composable
fun TagFilterRow(
    tags: List<TagCount>,
    totalCount: Int,
    selected: String?,
    onSelect: (String?) -> Unit,
    onRename: (from: String, to: String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuFor by rememberSaveable { mutableStateOf<String?>(null) }
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedKey = selected?.let(::tagKey)

    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "all") {
            TagFilterChip(
                label = stringResource(UiR.string.all),
                count = totalCount,
                selected = selectedKey == null,
                onClick = { onSelect(null) },
            )
        }
        items(tags, key = { "tag:" + tagKey(it.name) }) { tag ->
            val isSelected = tagKey(tag.name) == selectedKey
            Box {
                TagFilterChip(
                    label = tag.name,
                    count = tag.count,
                    selected = isSelected,
                    onClick = { onSelect(if (isSelected) null else tag.name) },
                    onLongClick = { menuFor = tag.name },
                    onLongClickLabel = stringResource(R.string.tag_options),
                )
                DropdownMenu(expanded = menuFor == tag.name, onDismissRequest = { menuFor = null }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(UiR.string.rename)) },
                        leadingIcon = { IconEdit() },
                        onClick = { menuFor = null; renaming = tag.name },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(UiR.string.delete)) },
                        leadingIcon = { IconDelete() },
                        onClick = { menuFor = null; deleting = tag.name },
                    )
                }
            }
        }
    }

    renaming?.let { from ->
        RenameTagDialog(
            tag = from,
            existingTags = tags.map { it.name },
            onRename = { to -> onRename(from, to) },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { tag ->
        val count = tags.firstOrNull { tagKey(it.name) == tagKey(tag) }?.count ?: 0
        ConfirmDialog(
            title = stringResource(R.string.delete_tag_title, tag),
            message = pluralStringResource(R.plurals.delete_tag_message, count, count),
            confirmLabel = stringResource(UiR.string.delete),
            dismissLabel = stringResource(UiR.string.cancel),
            destructive = true,
            onConfirm = { onDelete(tag) },
            onDismiss = { deleting = null },
        )
    }
}
