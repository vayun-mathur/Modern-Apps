package com.vayunmathur.youpipe.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.adaptiveGridCells
import com.vayunmathur.library.ui.invisibleClickable
import com.vayunmathur.library.ui.isExpandedWidth
import com.vayunmathur.youpipe.platform.VideoRowState


/**
 * A video feed that is a single column on phones and an adaptive grid on expanded widths.
 *
 * Phones keep the [LazyColumn] they always had; large tablets, ChromeOS and desktop windows
 * (see [isExpandedWidth]) get a [LazyVerticalGrid] of [adaptiveGridCells], so a feed fills a
 * wide window with more tiles instead of stretching one column across it. A freeform window
 * resized across the threshold switches between the two automatically.
 */
@Composable
fun <T : Any> FeedGrid(
    rows: List<T>,
    key: (T) -> Any,
    modifier: Modifier = Modifier,
    itemContent: @Composable (T) -> Unit,
) {
    if (isExpandedWidth()) {
        LazyVerticalGrid(
            columns = adaptiveGridCells(320.dp),
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(rows, key = { key(it) }) { itemContent(it) }
        }
    } else {
        LazyColumn(modifier = modifier) {
            items(rows, key = { key(it) }) { itemContent(it) }
        }
    }
}
