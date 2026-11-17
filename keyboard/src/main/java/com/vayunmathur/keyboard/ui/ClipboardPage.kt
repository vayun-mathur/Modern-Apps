package com.vayunmathur.keyboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.keyboard.R
import com.vayunmathur.keyboard.data.ClipItem
import com.vayunmathur.library.ui.IconBackspace
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconHistory
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text

/**
 * The clipboard page: everything the keyboard has captured, newest first. Tapping a row
 * pastes it, the X deletes just that one, and "clear all" empties the history.
 *
 * Sized like [EmojiPage]'s grid so switching between the two doesn't resize the keyboard.
 */
@Composable
fun ClipboardPage(
    clips: List<ClipItem>,
    keyHeight: Dp,
    rows: Int,
    onPaste: (ClipItem) -> Unit,
    onDelete: (ClipItem) -> Unit,
    onClearAll: () -> Unit,
    onBackspace: () -> Unit,
    onBack: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(keyHeight * rows),
    ) {
        if (clips.isEmpty()) {
            EmptyClipboard()
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(clips, key = { it.id }) { clip ->
                    ClipRow(clip, onPaste = { onPaste(clip) }, onDelete = { onDelete(clip) })
                }
            }
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SpecialKey(height = keyHeight, weight = 1.4f, onClick = onBack) {
            Text(stringResource(R.string.abc), color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp)
        }
        SpecialKey(height = keyHeight, weight = 3f, onClick = onClearAll) {
            Text(stringResource(R.string.clear_clipboard), fontSize = 14.sp)
        }
        RepeatKey(height = keyHeight, weight = 1.4f, onRepeat = onBackspace) {
            IconBackspace()
        }
    }
}

@Composable
private fun ClipRow(clip: ClipItem, onPaste: () -> Unit, onDelete: () -> Unit) {
    val tick = rememberKeyHapticTick()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .height(52.dp)
                .clickable {
                    tick()
                    onPaste()
                }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ClipPreview(clip, Modifier.weight(1f), fontSize = 15.sp)
        }
        Box(
            modifier = Modifier
                .height(52.dp)
                .clickable {
                    tick()
                    onDelete()
                }
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            IconClose(
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyClipboard() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        IconHistory(
            modifier = Modifier.size(28.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.copied_text_shows_up_here),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 8.dp, start = 24.dp, end = 24.dp),
        )
    }
}
