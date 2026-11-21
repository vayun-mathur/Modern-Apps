package com.vayunmathur.health.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.vayunmathur.health.R
import com.vayunmathur.health.Route
import com.vayunmathur.health.data.PregnancyStatus
import com.vayunmathur.health.data.SmokingStatus
import com.vayunmathur.health.domain.SocialHistoryQuestions
import com.vayunmathur.health.platform.MedicalViewModel
import com.vayunmathur.health.platform.clearSocialHistoryAnswer
import com.vayunmathur.health.platform.setPregnancyStatus
import com.vayunmathur.health.platform.setSmokingStatus
import com.vayunmathur.health.platform.setSocialHistoryAnswer
import com.vayunmathur.health.platform.today
import com.vayunmathur.health.ui.components.AnswerPickerDialog
import com.vayunmathur.health.ui.components.HealthRow
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.IconPerson
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.RadioButton
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.dialog.DateSelection
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.ResultEffect

/**
 * About you as an expandable category: one row per question, the question as the headline and the
 * current answer beneath it, tapping a row opening its answers.
 *
 * Every question starts at *Not answered* and can be returned to it. Defaulting to the first real
 * option would put "Never" against alcohol for someone who has never opened this screen, and the
 * record would then assert something the user never said.
 */
@Composable
fun MedicalAboutYouSection(
    backStack: NavBackStack<Route>,
    viewModel: MedicalViewModel,
    isExpanded: Boolean,
    onToggle: () -> Unit,
) {
    val profile by viewModel.profile.collectAsState()
    val answers by viewModel.profileAnswers.collectAsState()

    var askingQuestion by remember { mutableStateOf<SocialHistoryQuestions.Question?>(null) }
    var askingPregnancy by remember { mutableStateOf(false) }
    var askingSmoking by remember { mutableStateOf(false) }

    // The due-date picker posts back through the nav result registry; applying it here keeps the
    // result next to the row that asked for it.
    ResultEffect<DateSelection>(ABOUT_YOU_DUE_DATE_KEY) { picked ->
        viewModel.setPregnancyStatus(profile.pregnancyStatus, picked.date)
    }

    askingQuestion?.let { question ->
        AnswerPickerDialog(
            question = question,
            selected = question.answers
                .firstOrNull { it.code == answers[question.loinc]?.answerCode },
            onPick = { picked ->
                if (picked == null) {
                    viewModel.clearSocialHistoryAnswer(question)
                } else {
                    viewModel.setSocialHistoryAnswer(question, picked)
                }
                askingQuestion = null
            },
            onDismiss = { askingQuestion = null },
        )
    }
    if (askingPregnancy) {
        AboutYouOptionDialog(
            title = stringResource(R.string.about_you_pregnancy_question),
            options = PregnancyStatus.entries.map { stringResource(it.labelRes()) },
            selectedIndex = PregnancyStatus.entries.indexOf(profile.pregnancyStatus),
            onPick = { index ->
                val picked = PregnancyStatus.entries[index]
                // Clearing the status clears the date with it: an expected delivery date on a
                // "not pregnant" record is contradictory.
                viewModel.setPregnancyStatus(
                    picked,
                    if (picked == PregnancyStatus.Pregnant) {
                        profile.dueDate?.toLocalDate()
                    } else {
                        null
                    },
                )
                askingPregnancy = false
            },
            onDismiss = { askingPregnancy = false },
        )
    }
    if (askingSmoking) {
        AboutYouOptionDialog(
            title = stringResource(R.string.about_you_smoking_question),
            options = SmokingStatus.entries.map { stringResource(it.labelRes()) },
            selectedIndex = SmokingStatus.entries.indexOf(profile.smokingStatus),
            onPick = { index ->
                viewModel.setSmokingStatus(SmokingStatus.entries[index])
                askingSmoking = false
            },
            onDismiss = { askingSmoking = false },
        )
    }

    val rows = buildList {
        add(
            AboutYouRow(
                headline = stringResource(R.string.about_you_pregnancy_question),
                supporting = stringResource(profile.pregnancyStatus.labelRes()),
                onClick = { askingPregnancy = true },
            ),
        )
        if (profile.pregnancyStatus == PregnancyStatus.Pregnant) {
            add(
                AboutYouRow(
                    headline = stringResource(R.string.field_due_date),
                    supporting = profile.dueDate?.let { medicalDateString(it) }
                        ?: stringResource(R.string.not_recorded),
                    onClick = {
                        backStack.add(
                            Route.MedicalDatePicker(
                                ABOUT_YOU_DUE_DATE_KEY,
                                profile.dueDate?.toLocalDate() ?: today(),
                                allowClear = true,
                            ),
                        )
                    },
                ),
            )
        }
        add(
            AboutYouRow(
                headline = stringResource(R.string.about_you_smoking_question),
                supporting = stringResource(profile.smokingStatus.labelRes()),
                onClick = { askingSmoking = true },
            ),
        )
        SocialHistoryQuestions.ALL.forEach { question ->
            val selected = question.answers
                .firstOrNull { it.code == answers[question.loinc]?.answerCode }
            add(
                AboutYouRow(
                    headline = stringResource(question.titleRes),
                    supporting = selected?.let { stringResource(it.labelRes) }
                        ?: stringResource(R.string.not_answered),
                    onClick = { askingQuestion = question },
                ),
            )
        }
    }

    MedicalCategoryGroup(
        title = stringResource(R.string.about_you),
        icon = { modifier, tint -> IconPerson(modifier, tint) },
        isExpanded = isExpanded,
        onToggle = onToggle,
        itemCount = rows.size,
    ) { index ->
        val row = rows[index]
        HealthRow(
            headline = row.headline,
            supporting = row.supporting,
            leadingIcon = { modifier, tint -> IconPerson(modifier, tint) },
            leadingTint = HealthColors.Medical,
            onClick = row.onClick,
        )
    }
}

/** One question and its current answer, and what tapping it opens. */
private data class AboutYouRow(
    val headline: String,
    val supporting: String,
    val onClick: () -> Unit,
)

/** Picks one option from a short list, dismissing on choice like [AnswerPickerDialog] does. */
@Composable
private fun AboutYouOptionDialog(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                options.forEachIndexed { index, label ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = index == selectedIndex,
                                onClick = { onPick(index) },
                                role = Role.RadioButton,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RadioButton(
                            selected = index == selectedIndex,
                            onClick = null,
                        )
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.cancel)) }
        },
    )
}

internal fun PregnancyStatus.labelRes() = when (this) {
    PregnancyStatus.Unknown -> R.string.answer_unknown
    PregnancyStatus.Pregnant -> R.string.pregnancy_pregnant
    PregnancyStatus.NotPregnant -> R.string.pregnancy_not_pregnant
}

internal fun SmokingStatus.labelRes() = when (this) {
    SmokingStatus.Unknown -> R.string.answer_unknown
    SmokingStatus.Never -> R.string.smoking_never
    SmokingStatus.Former -> R.string.smoking_former
    SmokingStatus.Current -> R.string.smoking_current
}

/** The nav result key for the due-date picker opened from the About you rows. */
internal const val ABOUT_YOU_DUE_DATE_KEY = "about-you/due-date"

/** The expandable-group key for the About you category. */
internal const val CATEGORY_ABOUT_YOU = "about-you"
