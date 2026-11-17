package com.vayunmathur.keyboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.keyboard.R
import com.vayunmathur.keyboard.data.EmojiCategory
import com.vayunmathur.keyboard.data.EmojiData
import com.vayunmathur.keyboard.data.RecentEmoji
import com.vayunmathur.library.ui.IconBackspace
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text

/**
 * The emoji page: a scrollable grid of the selected category plus a bottom bar with the
 * category tabs, a search key, an "ABC" return key and a backspace. Emoji are committed as
 * plain text.
 *
 * [recents] is always the first tab, even while empty. Slotting it in only once it filled up
 * would renumber every other tab under the user's finger the moment they picked their first
 * emoji.
 */
@Composable
fun EmojiPage(
    data: EmojiData,
    recents: List<String>,
    keyHeight: Dp,
    rows: Int,
    onEmoji: (String) -> Unit,
    onSearch: () -> Unit,
    onBackspace: () -> Unit,
    onBack: () -> Unit,
) {
    val categories = remember(data, recents) {
        listOf(EmojiCategory(RecentEmoji.TAB, recents)) + data.categories
    }
    // Land on recents when there are some, and on the first real tab when there are not.
    // Not keyed on `recents`, so picking an emoji never yanks the tab out from under you.
    var category by remember(data) { mutableIntStateOf(if (recents.isEmpty()) 1 else 0) }
    val selected = categories.getOrNull(category) ?: categories.first()
    // Ticks for grid commits and tab switches below — captured here since LazyVerticalGrid
    // item content can't read CompositionLocals through its own remember scope cleanly.
    val tick = rememberKeyHapticTick()
    if (selected.emojis.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(keyHeight * rows),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.emoji_you_use_show_up_here),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
            )
        }
    } else {
        LazyVerticalGrid(
            columns = GridCells.Fixed(8),
            modifier = Modifier
                .fillMaxWidth()
                .height(keyHeight * rows),
        ) {
            items(selected.emojis) { emoji ->
                Box(
                    modifier = Modifier
                        .aspectRatio(1f)
                        .clickable {
                            tick()
                            onEmoji(emoji)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = emoji, fontSize = 24.sp)
                }
            }
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SpecialKey(height = keyHeight, weight = 1.3f, onClick = onBack) {
            Text(stringResource(R.string.abc), color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp)
        }
        SpecialKey(height = keyHeight, weight = 1f, onClick = onSearch) { IconSearch() }
        categories.forEachIndexed { index, cat ->
            CategoryTab(
                label = cat.label,
                selected = index == category,
                onClick = {
                    tick()
                    category = index
                },
            )
        }
        RepeatKey(height = keyHeight, weight = 1.3f, onRepeat = onBackspace) {
            IconBackspace()
        }
    }
}

@Composable
private fun RowScope.CategoryTab(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer
    Box(
        modifier = Modifier
            .weight(1f)
            .height(44.dp)
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, fontSize = 18.sp)
    }
}
