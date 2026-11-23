package com.vayunmathur.news.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.FilterChip
import com.vayunmathur.library.ui.IconBookmark
import com.vayunmathur.library.ui.IconBookmarkBorder
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconTextTool
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.news.R
import com.vayunmathur.news.domain.ArticleBlock
import com.vayunmathur.news.platform.NewsViewModel

/**
 * First-class reader: the extracted clean article (title, byline, date,
 * comfortable measure, adjustable size, serif/sans). Reader-only: there
 * is no original-page WebView or open-in-browser affordance, and the
 * top app bar carries no actions — bookmark, text size and serif live
 * in an in-content action row below the title.
 */
@Composable
fun ReaderScreen(viewModel: NewsViewModel, url: String, onBack: () -> Unit) {
    val body by viewModel.readerBody.collectAsState()
    val error by viewModel.readerError.collectAsState()
    val bookmarked by viewModel.readerBookmarked.collectAsState()
    val textSp by viewModel.readerTextSp.collectAsState()
    val serif by viewModel.readerSerif.collectAsState()
    LaunchedEffect(url) { viewModel.openArticle(url) }
    AppScaffold(
        title = stringResource(R.string.reader_title),
        onNavigateBack = onBack,
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            if (body != null) {
                Text(body!!.title, style = MaterialTheme.typography.headlineMedium)
                if (!body!!.byline.isNullOrBlank()) {
                    Text(
                        body!!.byline!!, style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            ReaderActionRow(
                bookmarked = bookmarked, textSp = textSp, serif = serif,
                onToggleBookmark = viewModel::toggleReaderBookmark,
                onTextSp = viewModel::setReaderTextSp, onSerif = viewModel::setReaderSerif,
            )
            when {
                error -> Text(stringResource(R.string.reader_loading_failed))
                body == null -> Text("…")
                else -> ReaderBody(textSp = textSp, serif = serif, blocks = body!!.blocks)
            }
        }
    }
}

/** In-content reader actions: bookmark, serif/sans, and text size. */
@Composable
private fun ReaderActionRow(
    bookmarked: Boolean,
    textSp: Int,
    serif: Boolean,
    onToggleBookmark: () -> Unit,
    onTextSp: (Int) -> Unit,
    onSerif: (Boolean) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        IconButton(onClick = onToggleBookmark) {
            if (bookmarked) IconBookmark() else IconBookmarkBorder()
        }
        FilterChip(
            selected = serif,
            onClick = { onSerif(!serif) },
            label = { Text(if (serif) stringResource(R.string.reader_serif) else stringResource(R.string.reader_sans)) },
            leadingIcon = { IconTextTool() },
            modifier = Modifier.padding(start = 8.dp),
        )
        FilterChip(
            selected = false,
            onClick = { onTextSp(textSp - 1) },
            label = { Text(stringResource(R.string.reader_text_smaller)) },
            modifier = Modifier.padding(start = 8.dp),
        )
        FilterChip(
            selected = false,
            onClick = { onTextSp(textSp + 1) },
            label = { Text(stringResource(R.string.reader_text_larger)) },
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun ReaderBody(
    textSp: Int,
    serif: Boolean,
    blocks: List<ArticleBlock>,
) {
    val family = if (serif) FontFamily.Serif else FontFamily.SansSerif
    for (block in blocks) {
        when (block) {
            is ArticleBlock.Heading -> Text(
                block.text,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = 20.dp, bottom = 6.dp),
            )
            is ArticleBlock.Paragraph -> Text(
                block.text,
                fontFamily = family,
                fontSize = textSp.sp,
                lineHeight = (textSp * 1.55f).sp,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            is ArticleBlock.Image -> AsyncImage(
                model = block.url,
                contentDescription = block.caption,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                contentScale = ContentScale.FillWidth,
            )
        }
    }
}
