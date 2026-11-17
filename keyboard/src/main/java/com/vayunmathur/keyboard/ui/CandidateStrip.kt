package com.vayunmathur.keyboard.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.VerticalDivider

/**
 * The candidate strip for the composed scripts: the characters a pinyin or bopomofo spelling
 * could be, scrolling sideways because a syllable like `shi` has dozens of them.
 *
 * Separate from [SuggestionStrip] because the two are doing different jobs. A word suggestion
 * is an offer you can ignore; a Chinese candidate *is* the input — you have not typed a
 * character until you pick one — so these are sized to their content and never truncated.
 */
@Composable
fun CandidateStrip(
    height: Dp,
    candidates: List<String>,
    onPick: (String) -> Unit,
) {
    val tick = rememberKeyHapticTick()
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .height(height),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(candidates) { index, candidate ->
            if (index > 0) {
                VerticalDivider(
                    modifier = Modifier
                        .height(height)
                        .padding(vertical = 8.dp),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .clickable {
                        tick()
                        onPick(candidate)
                    }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = candidate,
                    // The first candidate is what space commits, so it is the one that has
                    // to look chosen rather than merely first in a list.
                    color = if (index == 0) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    fontSize = 22.sp,
                    maxLines = 1,
                )
            }
        }
    }
}
