package com.vayunmathur.flashcards.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.flashcards.R
import com.vayunmathur.flashcards.ui.TemplateDraft
import com.vayunmathur.flashcards.domain.TemplateEngine
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text

@Composable
fun NoteTypeEditorSection(draft: TemplateDraft, fields: List<String>, isCloze: Boolean) {
    val sample = if (isCloze) {
        fields.associateWith { name ->
            if (name.equals("Text", true) || fields.firstOrNull() == name) "The {{c1::answer}} here" else name
        }
    } else {
        fields.associateWith { it }
    }
    val (front, back) = TemplateEngine.render(
        draft.qfmt,
        draft.afmt,
        sample,
        clozeOrd = if (isCloze) 0 else null,
    )
    Column(Modifier.padding(top = 8.dp)) {
        Text(stringResource(R.string.preview), style = MaterialTheme.typography.labelMedium)
        MarkdownContent(text = front, style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Start)
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        MarkdownContent(text = back, style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Start)
    }
}
