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
import com.vayunmathur.news.data.CachedArticle
import com.vayunmathur.news.domain.SourceCatalog

/** One feed row: source, relative time, title and thumbnail when the feed carried one. */
@Composable
fun ArticleRow(
    article: CachedArticle,
    onClick: () -> Unit,
    onToggleBookmark: () -> Unit,
    alsoFromSources: Int = 0,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${SourceCatalog.byId(article.sourceId)?.name ?: article.sourceId} · ${relativeTime(article.publishedAtMillis)}" + if (alsoFromSources > 0) " · +$alsoFromSources sources" else "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(article.title, style = MaterialTheme.typography.titleMedium)
        }
        if (article.imageUrl != null) {
            AsyncImage(
                model = article.imageUrl,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                contentScale = ContentScale.Crop,
            )
        }
        IconButton(onClick = onToggleBookmark) {
            if (article.bookmarked) {
                IconBookmark()
            } else {
                IconBookmarkBorder()
            }
        }
    }
}

private fun relativeTime(millis: Long): String {
    if (millis <= 0L) return ""
    val minutes = ((System.currentTimeMillis() - millis) / 60_000L).coerceAtLeast(0L)
    return when {
        minutes < 1L -> "now"
        minutes < 60L -> "${minutes}m ago"
        minutes < 60L * 24L -> "${minutes / 60L}h ago"
        else -> "${minutes / (60L * 24L)}d ago"
    }
}
