package com.vayunmathur.flashcards.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vayunmathur.flashcards.platform.MediaStore
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.parseMarkdown

private val imageRegex = Regex("""!\[([^\]]*)]\(([^)]+)\)""")

/**
 * Renders markdown [text] that may embed images as `![alt](filename)`. Text runs
 * render through [parseMarkdown]; image runs resolve the filename against
 * [MediaStore] and draw via the shared [AsyncImage]. Used on the review card and
 * the note-type live preview.
 */
@Composable
fun MarkdownContent(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    textAlign: TextAlign = TextAlign.Center,
) {
    val context = LocalContext.current
    val media = remember { MediaStore(context) }
    val segments = remember(text) { splitSegments(text) }

    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        segments.forEach { segment ->
            when (segment) {
                is Segment.TextRun -> if (segment.value.isNotBlank()) {
                    Text(
                        parseMarkdown(segment.value.trim(), showMarkers = false),
                        style = style,
                        textAlign = textAlign,
                    )
                }
                is Segment.ImageRun -> AsyncImage(
                    model = media.resolve(segment.name),
                    contentDescription = segment.alt,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .padding(vertical = 8.dp),
                )
            }
        }
    }
}

private sealed interface Segment {
    data class TextRun(val value: String) : Segment
    data class ImageRun(val alt: String, val name: String) : Segment
}

private fun splitSegments(text: String): List<Segment> {
    val out = mutableListOf<Segment>()
    var last = 0
    imageRegex.findAll(text).forEach { match ->
        if (match.range.first > last) {
            out.add(Segment.TextRun(text.substring(last, match.range.first)))
        }
        out.add(Segment.ImageRun(match.groupValues[1], match.groupValues[2].trim()))
        last = match.range.last + 1
    }
    if (last < text.length) out.add(Segment.TextRun(text.substring(last)))
    if (out.isEmpty()) out.add(Segment.TextRun(text))
    return out
}
