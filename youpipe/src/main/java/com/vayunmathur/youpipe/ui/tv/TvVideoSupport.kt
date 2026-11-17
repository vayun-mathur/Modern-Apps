package com.vayunmathur.youpipe.ui.tv

import android.webkit.WebView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.vayunmathur.library.image.ImageRequest
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.tv.TvButton
import com.vayunmathur.library.tv.TvDialog
import com.vayunmathur.library.tv.TvMediaCard
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.ui.Comment
import com.vayunmathur.youpipe.ui.LinkifiedText
import com.vayunmathur.youpipe.ui.VideoChapter

/** One chapter: preview art + title + timestamp, seeking on press. Fills its grid cell. */
@Composable
internal fun TvChapterCard(
    chapter: VideoChapter,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    TvMediaCard(
        onClick = onClick,
        modifier = modifier,
        title = chapter.title,
        subtitle = android.text.format.DateUtils.formatElapsedTime(chapter.time.toLong() / MS_PER_SECOND),
        image = {
            if (!chapter.previewURL.isNullOrEmpty()) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(chapter.previewURL)
                        .memoryCacheKey("chapter-${chapter.time}")
                        .build(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        },
    )
}

/** Whether the system WebView can mint PO tokens on this TV box. */
internal sealed interface TvWebViewStatus {
    data object Ready : TvWebViewStatus
    data class Unavailable(val reason: String) : TvWebViewStatus
}

/**
 * Probe the system WebView once: missing package or a version too old to run the attestation
 * JS means every load would fail — surface it as a message, not a stall.
 */
internal fun probeTvWebView(): TvWebViewStatus {
    return try {
        val info = WebView.getCurrentWebViewPackage() ?: return TvWebViewStatus.Unavailable("missing")
        // Attestation JS needs a reasonably current Chromium; boxes that lag years behind
        // cannot mint PO tokens. 100 is a floor, not a target — current is 120+.
        val major = info.versionName?.substringBefore('.')?.toIntOrNull() ?: 0
        if (major < MIN_TV_WEBVIEW_MAJOR) {
            TvWebViewStatus.Unavailable("outdated:${info.versionName}")
        } else {
            TvWebViewStatus.Ready
        }
    } catch (_: Exception) {
        TvWebViewStatus.Unavailable("missing")
    }
}

private const val MIN_TV_WEBVIEW_MAJOR = 100
private const val MS_PER_SECOND = 1000L
internal const val TV_VIDEO_COMMENTS_COUNT = 20

/**
 * Comments under a section title, as plain text rather than focusable rows so the D-pad never
 * lands on a dead end. Mirrors the phone `ui/VideoSections.kt` CommentItem without its chrome.
 */
@Composable
internal fun TvVideoComments(comments: List<Comment>, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier.widthIn(max = 900.dp)) {
        Text(
            text = stringResource(R.string.label_comments),
            style = MaterialTheme.typography.titleLarge,
            color = scheme.onSurface,
        )
        Column(Modifier.padding(top = 8.dp)) {
            comments.take(TV_VIDEO_COMMENTS_COUNT).forEach { comment ->
                Column(Modifier.padding(vertical = 8.dp)) {
                    Text(
                        text = comment.author,
                        style = MaterialTheme.typography.titleSmall,
                        color = scheme.onSurface,
                    )
                    val likes = pluralStringResource(
                        R.plurals.tv_comment_likes,
                        comment.likes,
                        comment.likes.toString(),
                    )
                    LinkifiedText(
                        text = "$likes  •  ${comment.text}",
                        style = MaterialTheme.typography.bodyMedium.copy(color = scheme.onSurfaceVariant),
                    )
                }
            }
        }
    }
}

/** The full linkified description, opened from the detail page's Description button. */
@Composable
internal fun TvDescriptionDialog(title: String, description: String, onDismiss: () -> Unit) {
    TvDialog(
        title = title,
        onDismissRequest = onDismiss,
        buttons = {
            TvButton(text = stringResource(R.string.action_close), onClick = onDismiss, primary = true)
        },
        content = {
            LinkifiedText(text = description, style = MaterialTheme.typography.bodyLarge)
        },
    )
}
