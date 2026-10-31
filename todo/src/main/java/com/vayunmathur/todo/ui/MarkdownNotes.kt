package com.vayunmathur.todo.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.todo.R

/**
 * Notes field that grows with its text. While not being edited it renders light markdown:
 * `- ` / `* ` bullets, `[ ]` / `[x]` checkboxes, `**bold**`, and tappable links. The stored
 * text stays plain.
 */
@Composable
fun MarkdownNotes(notes: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    var editing by remember { mutableStateOf(notes.isEmpty()) }
    val focus = remember { FocusRequester() }
    if (editing || notes.isBlank()) {
        var hadFocus by remember { mutableStateOf(false) }
        OutlinedTextField(
            value = notes,
            onValueChange = onChange,
            modifier = modifier.fillMaxWidth().focusRequester(focus).onFocusChanged {
                if (hadFocus && !it.isFocused && notes.isNotBlank()) editing = false
                hadFocus = it.isFocused
            },
            label = { Text(stringResource(R.string.todo_notes)) },
            minLines = 3,
        )
        LaunchedEffect(editing) { if (editing && notes.isNotEmpty()) focus.requestFocus() }
    } else {
        val link = MaterialTheme.colorScheme.primary
        val rendered = remember(notes, link) { renderMarkdown(notes, link) }
        Text(
            rendered,
            modifier = modifier.fillMaxWidth().clickable { editing = true }.padding(vertical = 8.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

private val URL = Regex("https?://[^\\s)]+")
private val BOLD = Regex("\\*\\*(.+?)\\*\\*")

internal fun renderMarkdown(text: String, linkColor: Color): AnnotatedString = buildAnnotatedString {
    text.lines().forEachIndexed { i, raw ->
        if (i > 0) append('\n')
        val line = when {
            raw.startsWith("- [ ] ") -> "☐ " + raw.removePrefix("- [ ] ")
            raw.startsWith("- [x] ", ignoreCase = true) -> "☑ " + raw.substring(6)
            raw.startsWith("- ") || raw.startsWith("* ") -> "•  " + raw.substring(2)
            else -> raw
        }
        appendInline(line, linkColor)
    }
}

private fun AnnotatedString.Builder.appendInline(line: String, linkColor: Color) {
    var cursor = 0
    val bolds = BOLD.findAll(line).toList()
    val plain = StringBuilder()
    val boldRanges = mutableListOf<IntRange>()
    for (m in bolds) {
        plain.append(line, cursor, m.range.first)
        val start = plain.length
        plain.append(m.groupValues[1])
        boldRanges += start until plain.length
        cursor = m.range.last + 1
    }
    plain.append(line.substring(cursor))
    val offset = length
    append(plain.toString())
    boldRanges.forEach { addStyle(SpanStyle(fontWeight = FontWeight.Bold), offset + it.first, offset + it.last + 1) }
    URL.findAll(plain).forEach { m ->
        addLink(
            LinkAnnotation.Url(
                m.value,
                TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
            ),
            offset + m.range.first,
            offset + m.range.last + 1,
        )
    }
}
