package com.vayunmathur.library.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/** Start inset for content on the current screen (smaller inside [TvAppFrame]). */
@Composable
fun tvContentStart(): Dp = LocalTvContentStart.current

/**
 * Padding for a screen-level lazy list or grid: aligned with [TvScreenHeader] on the start,
 * overscan-safe on the end, and room at the bottom so a scaled-up focused card is not clipped.
 */
@Composable
fun tvContentPadding(top: Dp = 0.dp, bottom: Dp = TvDimens.SafeVertical + 16.dp): PaddingValues =
    PaddingValues(start = tvContentStart(), end = TvDimens.SafeHorizontal, top = top, bottom = bottom)

/**
 * Screen title row. Large type, no app bar: on a TV the content is the chrome. [actions] sit at
 * the end of the row (buttons, chips) and are reachable with UP from the content.
 */
@Composable
fun TvScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(
                start = tvContentStart(),
                end = TvDimens.SafeHorizontal,
                top = TvDimens.SafeVertical,
                bottom = 8.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        if (actions != null) {
            Spacer(Modifier.padding(start = 24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                actions()
            }
        }
    }
}

/** A section label aligned with the screen's content start. */
@Composable
fun TvSectionTitle(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(
        modifier.fillMaxWidth().padding(start = tvContentStart(), end = TvDimens.SafeHorizontal),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f, fill = false),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (trailing != null) {
            Text(
                text = trailing,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

/**
 * A titled horizontal row of cards. Entering the row restores focus to the card last focused
 * in it, so UP/DOWN through a stack of shelves keeps each shelf's position.
 */
@Composable
fun TvShelf(
    title: String?,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    state: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        if (title != null) TvSectionTitle(title, trailing = trailing)
        LazyRow(
            state = state,
            modifier = Modifier.fillMaxWidth().focusRestorer(),
            contentPadding = PaddingValues(
                start = tvContentStart(),
                end = TvDimens.SafeHorizontal,
                top = 14.dp,
                bottom = 14.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(TvDimens.ItemGap),
            content = content,
        )
    }
}
