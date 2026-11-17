package com.vayunmathur.uniflix.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.uniflix.domain.MediaItem

/** A horizontal row of posters for one home section. */
@Composable
fun MediaRow(title: String, items: List<MediaItem>, onOpen: (MediaItem) -> Unit) {
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { "${it.mediaType}:${it.tmdbId}" }) { item ->
                // Fixed width: LazyRow's horizontal constraint is unbounded,
                // so fillMaxWidth collapses to zero and the aspect-ratio image
                // measures 0x0 (blank cards with visible titles).
                MediaCard(item = item, onOpen = { onOpen(item) }, modifier = Modifier.width(120.dp))
            }
        }
    }
}
