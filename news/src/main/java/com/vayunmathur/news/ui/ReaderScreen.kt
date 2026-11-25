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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.DesktopMaxWidthContainer
import com.vayunmathur.library.ui.IconBookmark
import com.vayunmathur.library.ui.IconBookmarkBorder
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconTextTool
import com.vayunmathur.library.ui.IconZoomIn
import com.vayunmathur.library.ui.IconZoomOut
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.news.R
import com.vayunmathur.news.domain.ArticleBlock
import com.vayunmathur.news.platform.NewsViewModel

/**
 * First-class reader: the extracted clean article (title, byline, date,
 * comfortable measure, adjustable size, serif/sans). Reader-only: there
 * is no original-page WebView or open-in-browser affordance. The top
 * app bar is empty apart from back navigation (no generic title), and
 * bookmark, text size and serif live in an icon-only in-content action
 * row below the title.
 */
@Composable
fun ReaderScreen(viewModel: NewsViewModel, url: String, onBack: () -> Unit) {
    val body by viewModel.readerBody.collectAsState()
    val error by viewModel.readerError.collectAsState()
    val bookmarked by viewModel.readerBookmarked.collectAsState()
    val textSp by viewModel.readerTextSp.collectAsState()
    val serif by viewModel.readerSerif.collectAsState()
    LaunchedEffect(url) { viewModel.openArticle(url) }
    ReaderContent(
        body = body,
        error = error,
        bookmarked = bookmarked,
        textSp = textSp,
        serif = serif,
        onToggleBookmark = viewModel::toggleReaderBookmark,
        onTextSp = viewModel::setReaderTextSp,
        onSerif = viewModel::setReaderSerif,
        onBack = onBack,
    )
}

/**
 * Stateless reader body behind [ReaderScreen]: every value is a parameter, so
 * Compose previews (src/screenshotTest) render the real screen from a literal
 * [com.vayunmathur.news.domain.ExtractedArticle] fixture.
 */
@Composable
internal fun ReaderContent(
    body: com.vayunmathur.news.domain.ExtractedArticle?,
    error: Boolean,
    bookmarked: Boolean,
    textSp: Int,
    serif: Boolean,
    onToggleBookmark: () -> Unit,
    onTextSp: (Int) -> Unit,
    onSerif: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    AppScaffold(
        // No generic "Article" title: the article's own headline is in
        // the content, so the bar carries only back navigation (the
        // empty-title placement the feed uses for its search bar).
        title = "",
        onNavigateBack = onBack,
    ) { padding ->
        // Article text keeps a comfortable measure on wide windows instead
        // of stretching edge to edge; the scroll and actions are unchanged.
        DesktopMaxWidthContainer(modifier = Modifier.padding(padding)) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
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
                onToggleBookmark = onToggleBookmark,
                onTextSp = onTextSp, onSerif = onSerif,
            )
            when {
                error -> Text(stringResource(R.string.reader_loading_failed))
                body == null -> Text("…")
                else -> ReaderBody(textSp = textSp, serif = serif, blocks = body!!.blocks)
            }
        }
        }
    }
}

/**
 * In-content reader actions: bookmark, serif/sans, and text size.
 * Icon-only (no text labels); each button carries a content description
 * for accessibility, and the serif toggle is tinted primary while serif
 * is active, mirroring the bookmark's filled state.
 */
@Composable
private fun ReaderActionRow(
    bookmarked: Boolean,
    textSp: Int,
    serif: Boolean,
    onToggleBookmark: () -> Unit,
    onTextSp: (Int) -> Unit,
    onSerif: (Boolean) -> Unit,
) {
    val serifDescription = stringResource(if (serif) R.string.reader_serif else R.string.reader_sans)
    val smallerDescription = stringResource(R.string.reader_text_smaller)
    val largerDescription = stringResource(R.string.reader_text_larger)
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        IconButton(onClick = onToggleBookmark) {
            if (bookmarked) IconBookmark() else IconBookmarkBorder()
        }
        IconButton(
            onClick = { onSerif(!serif) },
            modifier = Modifier.semantics { contentDescription = serifDescription },
        ) {
            IconTextTool(
                tint = if (serif) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        IconButton(
            onClick = { onTextSp(textSp - 1) },
            modifier = Modifier.semantics { contentDescription = smallerDescription },
        ) {
            IconZoomOut()
        }
        IconButton(
            onClick = { onTextSp(textSp + 1) },
            modifier = Modifier.semantics { contentDescription = largerDescription },
        ) {
            IconZoomIn()
        }
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
