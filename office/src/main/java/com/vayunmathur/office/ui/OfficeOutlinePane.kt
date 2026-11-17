package com.vayunmathur.office.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.DrawerState
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.odf.OdfBookmark
import com.vayunmathur.office.ui.HeadingItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

import com.vayunmathur.office.R
/**
 * Outline pane shared by the drawer (compact) and the side panel (Expanded).
 *
 * Hoisted verbatim from `DocumentScreen`'s local `OutlinePane` (split for file length);
 * captures became explicit parameters, behavior identical.
 */
private val panePadding = 16.dp
private val paneHalfPadding = 8.dp
private const val PANE_INDENT = 16
private val outlineListHeight = 150.dp

@Composable
fun OfficeOutlinePane(
    bookmarks: List<OdfBookmark>,
    headings: List<HeadingItem>,
    listState: LazyListState,
    drawerState: DrawerState,
    scope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(
            stringResource(R.string.outline),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(panePadding))
        HorizontalDivider()
        if (bookmarks.isNotEmpty()) {
            Text(
                stringResource(R.string.bookmarks),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(panePadding, paneHalfPadding))
            LazyColumn(modifier = Modifier.height(outlineListHeight)) {
                items(bookmarks) { bk ->
                    Text(
                        "🔖 ${bk.name}",
                        modifier = Modifier.fillMaxWidth()
                            .clickable {
                                scope.launch {
                                    listState.animateScrollToItem(bk.contentIndex)
                                    drawerState.close()
                                }
                            }
                            .padding(panePadding, paneHalfPadding),
                    )
                }
            }
            HorizontalDivider()
        }
        LazyColumn {
            items(headings) { heading ->
                Text(
                    heading.text,
                    style = when (heading.level) {
                        1 -> MaterialTheme.typography.titleMedium
                        2 -> MaterialTheme.typography.titleSmall
                        else -> MaterialTheme.typography.bodyMedium
                    },
                    fontWeight = if (heading.level <= 2) FontWeight.Bold else null,
                    modifier = Modifier.fillMaxWidth()
                        .clickable {
                            scope.launch {
                                listState.animateScrollToItem(heading.contentIndex)
                                drawerState.close()
                            }
                        }
                        .padding(
                            start = (PANE_INDENT + (heading.level - 1) * PANE_INDENT).dp,
                            top = paneHalfPadding,
                            bottom = paneHalfPadding,
                            end = panePadding),
                    maxLines = 2)
            }
        }
    }
}
