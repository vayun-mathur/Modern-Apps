@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.vayunmathur.health.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vayunmathur.health.R
import com.vayunmathur.health.Route
import com.vayunmathur.health.platform.MedicalViewModel
import com.vayunmathur.health.platform.saveLabDraft
import com.vayunmathur.health.ui.components.PickerField
import com.vayunmathur.library.ui.DetailScaffold
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FormSection
import com.vayunmathur.library.ui.LabeledTextField
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.ResultEffect
import kotlinx.datetime.LocalDate

private const val TAKEN_KEY = "add-lab/taken"

/**
 * The form for adding or editing a lab result.
 *
 * The value is a free text field rather than a numeric one, because plenty of real results are
 * words — "positive", "trace", "not detected". Whatever parses as a number is stored as one and gets
 * a unit and a reference range; whatever does not is kept verbatim. See
 * `MedicalViewModel.saveLabDraft`.
 *
 * Picking a test from the catalogue fills in the unit LOINC suggests for it, which is right far more
 * often than it is wrong and is still editable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddLabResultPage(backStack: NavBackStack<Route>, viewModel: MedicalViewModel) {
    val draft by viewModel.labDraft.collectAsState()

    ResultEffect<LocalDate>(TAKEN_KEY) { picked ->
        viewModel.editLabDraft { it.copy(takenOn = picked) }
    }

    DetailScaffold(
        title = stringResource(
            if (draft.editingId == null) R.string.add_lab_result else R.string.edit_lab_result
        ),
        backStack = backStack,
        actions = {
            TextButton(
                enabled = draft.displayName.isNotBlank(),
                onClick = {
                    viewModel.saveLabDraft()
                    backStack.pop()
                },
            ) { Text(stringResource(UiR.string.save)) }
        },
    ) {
        FormSection(title = stringResource(R.string.section_what)) {
            PickerField(
                label = stringResource(R.string.field_lab_test),
                value = draft.displayName,
                placeholder = stringResource(R.string.choose_a_lab_test),
                onClick = { backStack.add(Route.CatalogPicker(CatalogKind.LabTest)) },
            )
        }

        FormSection(title = stringResource(R.string.field_result)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LabeledTextField(
                    draft.value,
                    { value -> viewModel.editLabDraft { it.copy(value = value) } },
                    stringResource(R.string.field_result),
                    modifier = Modifier.weight(2f),
                )
                LabeledTextField(
                    draft.unit,
                    { value -> viewModel.editLabDraft { it.copy(unit = value) } },
                    stringResource(R.string.field_unit),
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LabeledTextField(
                    draft.referenceLow,
                    { value -> viewModel.editLabDraft { it.copy(referenceLow = value) } },
                    stringResource(R.string.field_reference_low),
                    keyboardType = KeyboardType.Decimal,
                    modifier = Modifier.weight(1f),
                )
                LabeledTextField(
                    draft.referenceHigh,
                    { value -> viewModel.editLabDraft { it.copy(referenceHigh = value) } },
                    stringResource(R.string.field_reference_high),
                    keyboardType = KeyboardType.Decimal,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        FormSection(title = stringResource(R.string.section_when)) {
            PickerField(
                label = stringResource(R.string.field_taken_on),
                value = medicalDateString(draft.takenOn.toInstant()),
                placeholder = "",
                onClick = { backStack.add(Route.MedicalDatePicker(TAKEN_KEY, draft.takenOn)) },
            )
            LabeledTextField(
                draft.note,
                { value -> viewModel.editLabDraft { it.copy(note = value) } },
                stringResource(R.string.field_note),
                singleLine = false,
            )
        }
    }
}
