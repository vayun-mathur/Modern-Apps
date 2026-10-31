package com.vayunmathur.todo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.todo.domain.TodoSearch

/** [text] with every match of [query] emphasised. */
@Composable
fun HighlightedText(
    text: String,
    query: String,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    textDecoration: TextDecoration? = null,
) {
    val highlight = MaterialTheme.colorScheme.primary
    val annotated: AnnotatedString = remember(text, query, highlight) {
        buildAnnotatedString {
            append(text)
            TodoSearch.matchRanges(text, query).forEach { range ->
                addStyle(SpanStyle(color = highlight, fontWeight = FontWeight.Bold), range.first, range.last + 1)
            }
        }
    }
    Text(
        annotated,
        modifier = modifier,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        textDecoration = textDecoration,
    )
}
