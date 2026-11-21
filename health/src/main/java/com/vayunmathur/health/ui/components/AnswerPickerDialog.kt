package com.vayunmathur.health.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.vayunmathur.health.R
import com.vayunmathur.health.domain.SocialHistoryQuestions
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.RadioButton
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton

/**
 * Picks one answer to a social history question.
 *
 * A dialog rather than a dropdown because both halves need room: the question is a full sentence
 * from a screening instrument, and several of the answers are sentences too. A dropdown anchored to
 * a field label would truncate both.
 *
 * Choosing dismisses immediately — there is no confirm button, because a single tap on a radio row
 * is unambiguous and a second tap to agree with yourself is just friction.
 */
@Composable
internal fun AnswerPickerDialog(
    question: SocialHistoryQuestions.Question,
    selected: SocialHistoryQuestions.Answer?,
    onPick: (SocialHistoryQuestions.Answer?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(question.titleRes)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Leaving a question unanswered has to stay reachable, not just be the state you
                // start in — otherwise a mistap is permanent.
                AnswerRow(
                    label = stringResource(R.string.not_answered),
                    selected = selected == null,
                    onClick = { onPick(null) },
                )
                question.answers.forEach { answer ->
                    AnswerRow(
                        label = stringResource(answer.labelRes),
                        selected = answer == selected,
                        onClick = { onPick(answer) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.cancel)) }
        },
    )
}

@Composable
private fun AnswerRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // selectable on the row, so the whole line is the target rather than the radio alone.
            // The row carries the RadioButton role; the inner radio is visual only
            // (onClick = null) so there is a single toggle target for TalkBack.
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
