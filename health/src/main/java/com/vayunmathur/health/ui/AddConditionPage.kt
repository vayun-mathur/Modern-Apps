@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.vayunmathur.health.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import com.vayunmathur.health.R
import com.vayunmathur.health.Route
import com.vayunmathur.health.data.ConditionStatus
import com.vayunmathur.health.platform.MedicalViewModel
import com.vayunmathur.health.platform.saveConditionDraft
import com.vayunmathur.health.ui.components.PickerField
import com.vayunmathur.library.ui.DetailScaffold
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FormSection
import com.vayunmathur.library.ui.LabeledTextField
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.SettingsExposedSelectRow
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.dialog.DateSelection
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.ResultEffect
import kotlinx.datetime.LocalDate

private const val ONSET_KEY = "add-condition/onset"
private const val RESOLVED_KEY = "add-condition/resolved"

/**
 * The form for adding or editing a diagnosis.
 *
 * The diagnosis picker is backed by ICD-10-CM, which is exhaustively specific — most of its 75,000
 * codes describe an encounter type or a laterality — so the picker ranks the shortest description
 * first and free text is always available for anything it cannot express.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddConditionPage(backStack: NavBackStack<Route>, viewModel: MedicalViewModel) {
    val draft by viewModel.conditionDraft.collectAsState()

    ResultEffect<LocalDate>(ONSET_KEY) { picked ->
        viewModel.editConditionDraft { it.copy(onsetOn = picked) }
    }
    ResultEffect<DateSelection>(RESOLVED_KEY) { picked ->
        viewModel.editConditionDraft { it.copy(resolvedOn = picked.date) }
    }

    DetailScaffold(
        title = stringResource(
            if (draft.editingId == null) R.string.add_condition else R.string.edit_condition
        ),
        backStack = backStack,
        actions = {
            TextButton(
                enabled = draft.displayName.isNotBlank(),
                onClick = {
                    viewModel.saveConditionDraft()
                    backStack.pop()
                },
            ) { Text(stringResource(UiR.string.save)) }
        },
    ) {
        FormSection(title = stringResource(R.string.section_what)) {
            PickerField(
                label = stringResource(R.string.field_condition),
                value = draft.displayName,
                placeholder = stringResource(R.string.choose_a_condition),
                onClick = { backStack.add(Route.CatalogPicker(CatalogKind.Condition)) },
            )
        }

        FormSection(title = stringResource(R.string.section_when)) {
            val statusLabels = ConditionStatus.entries.associateWith {
                stringResource(it.labelRes())
            }
            SettingsExposedSelectRow(
                label = stringResource(R.string.field_status),
                selected = draft.status,
                options = ConditionStatus.entries,
                itemLabel = { statusLabels.getValue(it) },
                onSelect = { picked -> viewModel.editConditionDraft { it.copy(status = picked) } },
            )
            PickerField(
                label = stringResource(R.string.field_diagnosed),
                value = medicalDateString(draft.onsetOn.toInstant()),
                placeholder = "",
                onClick = { backStack.add(Route.MedicalDatePicker(ONSET_KEY, draft.onsetOn)) },
            )
            if (draft.status == ConditionStatus.Resolved) {
                PickerField(
                    label = stringResource(R.string.field_resolved),
                    value = draft.resolvedOn?.let { medicalDateString(it.toInstant()) }.orEmpty(),
                    placeholder = stringResource(R.string.not_recorded),
                    onClick = {
                        backStack.add(
                            Route.MedicalDatePicker(
                                RESOLVED_KEY,
                                draft.resolvedOn ?: draft.onsetOn,
                                allowClear = true,
                            )
                        )
                    },
                )
            }
        }

        FormSection(title = stringResource(R.string.section_details)) {
            LabeledTextField(
                draft.note,
                { value -> viewModel.editConditionDraft { it.copy(note = value) } },
                stringResource(R.string.field_note),
                singleLine = false,
            )
        }
    }
}

internal fun ConditionStatus.labelRes() = when (this) {
    ConditionStatus.Active -> R.string.label_active
    ConditionStatus.Recurrence -> R.string.condition_status_recurrence
    ConditionStatus.Remission -> R.string.condition_status_remission
    ConditionStatus.Resolved -> R.string.condition_status_resolved
}
