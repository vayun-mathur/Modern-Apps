package com.vayunmathur.keyboard.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.keyboard.R
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text

/**
 * Emoji search, which is a query bar and a row of matches sitting on top of the ordinary
 * letter keys — the keys are what types into it.
 *
 * There is deliberately no [androidx.compose.material3.TextField] here. A focusable field
 * inside the IME takes focus away from the field the user is actually typing into, so the
 * query is plain state the service appends to (see `KeyboardState.emojiQuery`) and this bar
 * only renders it.
 */
@Composable
fun EmojiSearchBar(height: Dp, query: String, onClose: () -> Unit) {
    val tick = rememberKeyHapticTick()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconSearch(
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = query.ifEmpty { stringResource(R.string.search_emoji) },
            color = if (query.isEmpty()) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            fontSize = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp),
        )
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .clickable {
                    tick()
                    onClose()
                }
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            IconClose(
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
