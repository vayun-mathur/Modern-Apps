@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.vayunmathur.health.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import com.vayunmathur.health.R
import com.vayunmathur.health.Route
import com.vayunmathur.health.data.AllergyCategory
import com.vayunmathur.health.data.AllergyCriticality
import com.vayunmathur.health.platform.MedicalViewModel
import com.vayunmathur.health.platform.saveAllergyDraft
import com.vayunmathur.health.ui.components.PickerField
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
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

private const val ONSET_KEY = "add-allergy/onset"

/**
 * The form for adding or editing an allergy.
 *
 * The allergen picker is offered only for medication allergies. RxNorm is the one terminology
 * shipped with the app and it covers drugs alone, so a food or environmental allergen is recorded by
 * name — which is still valid FHIR, just uncoded. Coding those would need SNOMED CT, which cannot be
 * redistributed in an APK.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAllergyPage(backStack: NavBackStack<Route>, viewModel: MedicalViewModel) {
    val draft by viewModel.allergyDraft.collectAsState()

    ResultEffect<DateSelection>(ONSET_KEY) { picked ->
        viewModel.editAllergyDraft { it.copy(onsetOn = picked.date) }
    }

    DetailScaffold(
        title = stringResource(
            if (draft.editingId == null) R.string.add_allergy else R.string.edit_allergy
        ),
        backStack = backStack,
        actions = {
            TextButton(
                enabled = draft.displayName.isNotBlank(),
                onClick = {
                    viewModel.saveAllergyDraft()
                    backStack.pop()
                },
            ) { Text(stringResource(UiR.string.save)) }
        },
    ) {
        FormSection(title = stringResource(R.string.section_what)) {
            val categoryLabels = AllergyCategory.entries.associateWith {
                stringResource(it.labelRes())
            }
            SettingsExposedSelectRow(
                label = stringResource(R.string.field_allergy_category),
                selected = draft.category,
                options = AllergyCategory.entries,
                itemLabel = { categoryLabels.getValue(it) },
                // Changing category drops the code: an RxNorm code on a food allergy would be
                // wrong, and silently keeping it would produce a mis-coded FHIR resource.
                onSelect = { picked ->
                    viewModel.editAllergyDraft {
                        if (picked == it.category) it else it.copy(category = picked, rxcui = null)
                    }
                },
            )
            if (draft.category == AllergyCategory.Medication) {
                PickerField(
                    label = stringResource(R.string.field_allergen),
                    value = draft.displayName,
                    placeholder = stringResource(R.string.choose_an_allergen),
                    onClick = { backStack.add(Route.CatalogPicker(CatalogKind.Allergen)) },
                )
            } else {
                LabeledTextField(
                    draft.displayName,
                    { value -> viewModel.editAllergyDraft { it.copy(displayName = value) } },
                    stringResource(R.string.field_allergen),
                )
            }
        }

        FormSection(title = stringResource(R.string.section_details)) {
            val criticalityLabels = AllergyCriticality.entries.associateWith {
                stringResource(it.selectorLabelRes())
            }
            SettingsExposedSelectRow(
                label = stringResource(R.string.field_criticality),
                selected = draft.criticality,
                options = AllergyCriticality.entries,
                itemLabel = { criticalityLabels.getValue(it) },
                onSelect = { picked -> viewModel.editAllergyDraft { it.copy(criticality = picked) } },
            )
            LabeledTextField(
                draft.reaction,
                { value -> viewModel.editAllergyDraft { it.copy(reaction = value) } },
                stringResource(R.string.field_reaction),
            )
            PickerField(
                label = stringResource(R.string.field_first_noticed),
                value = draft.onsetOn?.let { medicalDateString(it.toInstant()) }.orEmpty(),
                placeholder = stringResource(R.string.not_recorded),
                onClick = {
                    backStack.add(
                        Route.MedicalDatePicker(
                            ONSET_KEY,
                            draft.onsetOn ?: Clock.System.now()
                                .toLocalDateTime(TimeZone.currentSystemDefault()).date,
                            allowClear = true,
                        )
                    )
                },
            )
            LabeledTextField(
                draft.note,
                { value -> viewModel.editAllergyDraft { it.copy(note = value) } },
                stringResource(R.string.field_note),
                singleLine = false,
            )
        }
    }
}

/** Always labelled, unlike the list row, because a selector needs a value for every option. */
private fun AllergyCriticality.selectorLabelRes() = when (this) {
    AllergyCriticality.Low -> R.string.allergy_criticality_low
    AllergyCriticality.High -> R.string.allergy_criticality_high
    AllergyCriticality.Unknown -> R.string.allergy_criticality_unknown
}

internal fun com.vayunmathur.health.data.AllergyCategory.labelRes() = when (this) {
    com.vayunmathur.health.data.AllergyCategory.Medication -> R.string.field_medication
    com.vayunmathur.health.data.AllergyCategory.Food -> R.string.allergy_category_food
    com.vayunmathur.health.data.AllergyCategory.Environment -> R.string.allergy_category_environment
    com.vayunmathur.health.data.AllergyCategory.Biologic -> R.string.allergy_category_biologic
}
