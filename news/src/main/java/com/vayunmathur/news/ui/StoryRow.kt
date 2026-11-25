package com.vayunmathur.news.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.ui.IconBookmark
import com.vayunmathur.library.ui.IconBookmarkBorder
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.news.domain.Story

/**
 * One feed story card: headline, hero image, latest update time and
 * the story's source count ("1 source" / "N sources"). No source is
 * named on the card — the count is the story's identity signal, for
 * multi-source and singleton stories alike. [Story.oneLiner] is a
 * server-generated summary, never extracted article text.
 */
@Composable
fun StoryRow(
    story: Story,
    bookmarked: Boolean,
    onClick: () -> Unit,
    onToggleBookmark: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            val meta = buildString {
                append(story.sourceCountLabel)
                val whenText = relativeTime(story.latestMillis)
                if (whenText.isNotEmpty()) append(" · $whenText")
            }
            Text(meta, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(story.title, style = MaterialTheme.typography.titleMedium)
            if (story.oneLiner != null) {
                Text(
                    story.oneLiner!!,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        if (story.heroImageUrl != null) {
            AsyncImage(
                model = story.heroImageUrl,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                contentScale = ContentScale.Crop,
            )
        }
        IconButton(onClick = onToggleBookmark) {
            if (bookmarked) IconBookmark() else IconBookmarkBorder()
        }
    }
}
