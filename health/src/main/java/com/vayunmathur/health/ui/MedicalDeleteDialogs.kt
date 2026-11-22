package com.vayunmathur.health.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vayunmathur.health.R
import com.vayunmathur.health.data.AllergyEntry
import com.vayunmathur.health.data.ConditionEntry
import com.vayunmathur.health.data.LabResultEntry
import com.vayunmathur.health.data.MedicationEntry
import com.vayunmathur.health.data.VaccinationEntry
import com.vayunmathur.health.platform.MedicalViewModel
import com.vayunmathur.health.platform.deleteAllergy
import com.vayunmathur.health.platform.deleteCondition
import com.vayunmathur.health.platform.deleteLabResult
import com.vayunmathur.health.platform.deleteMedication
import com.vayunmathur.health.platform.deleteVaccination
import com.vayunmathur.library.ui.ConfirmDialog
import com.vayunmathur.library.ui.R as UiR

/** Delete confirmations for the five medical record categories. */
@Composable
fun MedicalDeleteDialogs(
    viewModel: MedicalViewModel,
    pendingVaccinationDelete: VaccinationEntry?,
    onVaccinationDeleteChange: (VaccinationEntry?) -> Unit,
    pendingAllergyDelete: AllergyEntry?,
    onAllergyDeleteChange: (AllergyEntry?) -> Unit,
    pendingConditionDelete: ConditionEntry?,
    onConditionDeleteChange: (ConditionEntry?) -> Unit,
    pendingLabDelete: LabResultEntry?,
    onLabDeleteChange: (LabResultEntry?) -> Unit,
    pendingMedicationDelete: MedicationEntry?,
    onMedicationDeleteChange: (MedicationEntry?) -> Unit,
) {
    pendingVaccinationDelete?.let { entry ->
        ConfirmDialog(
            title = stringResource(R.string.delete_record_confirm, entry.displayName),
            confirmLabel = stringResource(UiR.string.delete),
            destructive = true,
            onConfirm = {
                viewModel.deleteVaccination(entry)
                onVaccinationDeleteChange(null)
            },
            onDismiss = { onVaccinationDeleteChange(null) },
        )
    }
    pendingAllergyDelete?.let { entry ->
        ConfirmDialog(
            title = stringResource(R.string.delete_record_confirm, entry.displayName),
            confirmLabel = stringResource(UiR.string.delete),
            destructive = true,
            onConfirm = {
                viewModel.deleteAllergy(entry)
                onAllergyDeleteChange(null)
            },
            onDismiss = { onAllergyDeleteChange(null) },
        )
    }
    pendingConditionDelete?.let { entry ->
        ConfirmDialog(
            title = stringResource(R.string.delete_record_confirm, entry.displayName),
            confirmLabel = stringResource(UiR.string.delete),
            destructive = true,
            onConfirm = {
                viewModel.deleteCondition(entry)
                onConditionDeleteChange(null)
            },
            onDismiss = { onConditionDeleteChange(null) },
        )
    }
    pendingLabDelete?.let { entry ->
        ConfirmDialog(
            title = stringResource(R.string.delete_record_confirm, entry.displayName),
            confirmLabel = stringResource(UiR.string.delete),
            destructive = true,
            onConfirm = {
                viewModel.deleteLabResult(entry)
                onLabDeleteChange(null)
            },
            onDismiss = { onLabDeleteChange(null) },
        )
    }
    pendingMedicationDelete?.let { entry ->
        ConfirmDialog(
            title = stringResource(R.string.delete_record_confirm, entry.displayName),
            confirmLabel = stringResource(UiR.string.delete),
            destructive = true,
            onConfirm = {
                viewModel.deleteMedication(entry)
                onMedicationDeleteChange(null)
            },
            onDismiss = { onMedicationDeleteChange(null) },
        )
    }
}
