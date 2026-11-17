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

/** The matches for the current query, scrolling sideways. Tapping one commits it. */
@Composable
fun EmojiSearchResults(height: Dp, results: List<String>, onPick: (String) -> Unit) {
    val tick = rememberKeyHapticTick()
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .height(height),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(results) { emoji ->
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .clickable {
                        tick()
                        onPick(emoji)
                    }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = emoji, fontSize = 26.sp)
            }
        }
    }
}
