package com.vayunmathur.health.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import com.vayunmathur.health.R
import com.vayunmathur.health.Route
import com.vayunmathur.health.data.MedicationStatus
import com.vayunmathur.health.platform.MedicalViewModel
import com.vayunmathur.health.platform.saveMedicationDraft
import com.vayunmathur.health.ui.components.PickerField
import com.vayunmathur.health.ui.components.ScheduleSection
import com.vayunmathur.library.ui.DetailScaffold
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FormSection
import com.vayunmathur.library.ui.LabeledTextField
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.SettingsExposedSelectRow
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.dialog.DateSelection
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.ResultEffect
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

private const val START_KEY = "add-medication/start"
private const val END_KEY = "add-medication/end"
private const val TIME_KEY = "add-medication/time"
private const val UNTIL_KEY = "add-medication/until"

/**
 * The form for adding or editing a medication.
 *
 * Two pickers rather than one, because RxNorm is organised that way: the ingredient narrows ~21,000
 * products down to the handful that share it, and the second step is then a short list of strengths
 * and dose forms rather than a scroll through everything. Picking a product supplies the RXCUI that
 * `medicationCodeableConcept` needs.
 *
 * Like [AddVaccinationPage], the whole form lives in the ViewModel's draft because opening a
 * full-screen picker disposes this screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddMedicationPage(backStack: NavBackStack<Route>, viewModel: MedicalViewModel) {
    val draft by viewModel.medicationDraft.collectAsState()

    ResultEffect<LocalDate>(START_KEY) { picked ->
        viewModel.editMedicationDraft { it.copy(startedOn = picked) }
    }
    ResultEffect<DateSelection>(END_KEY) { picked ->
        viewModel.editMedicationDraft { it.copy(endedOn = picked.date) }
    }
    ResultEffect<DateSelection>(UNTIL_KEY) { picked ->
        viewModel.editMedicationDraft { it.copy(remindersUntil = picked.date) }
    }
    // One picker route serves every dose row, so which row it was opened for is carried in the
    // draft — this screen is not composed while the dialog is up.
    ResultEffect<LocalTime>(TIME_KEY) { picked ->
        viewModel.editMedicationDraft { draft ->
            val index = draft.editingTimeIndex ?: return@editMedicationDraft draft
            val seconds = picked.toSecondOfDay()
            val times =
                if (index in draft.times.indices) {
                    draft.times.toMutableList().also { it[index] = seconds }
                } else {
                    draft.times + seconds
                }
            draft.copy(times = times.distinct().sorted(), editingTimeIndex = null)
        }
    }

    DetailScaffold(
        title = stringResource(
            if (draft.editingId == null) R.string.add_medication else R.string.edit_medication
        ),
        backStack = backStack,
        actions = {
            TextButton(
                enabled = draft.ingredient.isNotBlank(),
                onClick = {
                    viewModel.saveMedicationDraft()
                    backStack.pop()
                },
            ) { Text(stringResource(UiR.string.save)) }
        },
    ) {
        FormSection(title = stringResource(R.string.section_what)) {
            PickerField(
                label = stringResource(R.string.field_medication),
                value = draft.ingredient,
                placeholder = stringResource(R.string.choose_a_medication),
                onClick = {
                    backStack.add(Route.CatalogPicker(CatalogKind.MedicationIngredient))
                },
            )
            PickerField(
                label = stringResource(R.string.field_strength_and_form),
                value = detailLine(draft.strength, draft.doseForm),
                placeholder = stringResource(R.string.choose_a_strength),
                enabled = draft.ingredient.isNotBlank(),
                onClick = {
                    backStack.add(
                        Route.CatalogPicker(CatalogKind.MedicationProduct, draft.ingredient)
                    )
                },
            )
        }

        FormSection(title = stringResource(R.string.section_when)) {
            val statusLabels = MedicationStatus.entries.associateWith {
                stringResource(it.selectorLabelRes())
            }
            SettingsExposedSelectRow(
                label = stringResource(R.string.field_status),
                selected = draft.status,
                options = MedicationStatus.entries,
                itemLabel = { statusLabels.getValue(it) },
                onSelect = { picked -> viewModel.editMedicationDraft { it.copy(status = picked) } },
            )
            PickerField(
                label = stringResource(R.string.field_started),
                value = medicalDateString(draft.startedOn.toInstant()),
                placeholder = "",
                onClick = { backStack.add(Route.MedicalDatePicker(START_KEY, draft.startedOn)) },
            )
            if (draft.status != MedicationStatus.Active) {
                PickerField(
                    label = stringResource(R.string.field_ended),
                    value = draft.endedOn?.let { medicalDateString(it.toInstant()) }.orEmpty(),
                    placeholder = "",
                    onClick = {
                        backStack.add(
                            Route.MedicalDatePicker(
                                END_KEY,
                                draft.endedOn ?: draft.startedOn,
                                allowClear = true,
                            )
                        )
                    },
                )
            }
        }

        FormSection(title = stringResource(R.string.section_details)) {
            LabeledTextField(
                draft.dosage,
                { value -> viewModel.editMedicationDraft { it.copy(dosage = value) } },
                stringResource(R.string.field_dosage),
            )
            LabeledTextField(
                draft.note,
                { value -> viewModel.editMedicationDraft { it.copy(note = value) } },
                stringResource(R.string.field_note),
                singleLine = false,
            )
        }

        FormSection(title = stringResource(R.string.section_reminders)) {
            ScheduleSection(
                draft = draft,
                onEdit = { transform -> viewModel.editMedicationDraft(transform) },
                onPickTime = { index ->
                    viewModel.editMedicationDraft { it.copy(editingTimeIndex = index) }
                    backStack.add(
                        Route.MedicalTimePicker(TIME_KEY, LocalTime(9, 0))
                    )
                },
                onPickUntil = {
                    backStack.add(
                        Route.MedicalDatePicker(
                            UNTIL_KEY,
                            draft.remindersUntil ?: draft.startedOn,
                            allowClear = true,
                        )
                    )
                },
            )
        }
    }
}

internal fun MedicationStatus.selectorLabelRes() = when (this) {
    MedicationStatus.Active -> R.string.medication_status_active
    MedicationStatus.Completed -> R.string.medication_status_completed
    MedicationStatus.Stopped -> R.string.medication_status_stopped
}
