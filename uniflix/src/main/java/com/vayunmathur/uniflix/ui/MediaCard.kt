package com.vayunmathur.uniflix.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.uniflix.data.TmdbImageUrl
import com.vayunmathur.uniflix.domain.MediaItem

/** A poster card: artwork + title + year, opening detail on tap. */
@Composable
fun MediaCard(item: MediaItem, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier = modifier.clickable(onClick = onOpen)) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AsyncImage(
                model = TmdbImageUrl.poster(item.posterPath),
                contentDescription = item.title,
                modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
                contentScale = ContentScale.Crop,
            )
            Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
            item.year?.let { Text("$it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}
