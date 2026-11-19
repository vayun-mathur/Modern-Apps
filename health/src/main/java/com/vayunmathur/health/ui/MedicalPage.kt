package com.vayunmathur.health.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.health.R
import com.vayunmathur.health.Route
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
import com.vayunmathur.health.platform.importFromHealthConnect
import com.vayunmathur.health.platform.recordDoseTaken
import com.vayunmathur.health.ui.components.HealthRow
import com.vayunmathur.health.ui.components.MedicalStorageNotice
import com.vayunmathur.library.ui.ConfirmDialog
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.ExperimentalMaterial3ExpressiveApi
import com.vayunmathur.library.ui.FloatingActionButtonMenu
import com.vayunmathur.library.ui.FloatingActionButtonMenuItem
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconMedication
import com.vayunmathur.library.ui.IconMonitorHeart
import com.vayunmathur.library.ui.IconScience
import com.vayunmathur.library.ui.IconVaccine
import com.vayunmathur.library.ui.IconWarning
import com.vayunmathur.library.ui.LazyListScaffold
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.SwipeActionsBox
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.ToggleFloatingActionButton
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.NavBackStack

/**
 * The Medical tab: every FHIR-backed log in one place, expanded in place.
 *
 * Each category is a tappable header — icon plus name, the same [HealthRow] kit the Today and Body
 * pages use — that opens into a vertical group of segmented rows in the contacts-groups manner.
 * Item rows are icon plus name with the record's date in the trailing slot; tapping one opens the
 * unchanged add/edit form for that entry. Medications folds in as a category with a nested
 * past-medications row (see [MedicalMedicationsSection]). About you sits at the top as an
 * expandable group of its own: one row per question, the question as the headline and the current
 * answer beneath it (see [MedicalAboutYouSection]).
 *
 * No top bar: [LazyListScaffold] draws one only when given a title or actions, and a bar carrying
 * nothing but a word already on the tab beneath it is a row of wasted height.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MedicalPage(backStack: NavBackStack<Route>, viewModel: MedicalViewModel) {
    val vaccinations by viewModel.vaccinations.collectAsState()
    val allergies by viewModel.allergies.collectAsState()
    val conditions by viewModel.conditions.collectAsState()
    val labResults by viewModel.labResults.collectAsState()
    val medications by viewModel.medications.collectAsState()

    LaunchedEffect(Unit) { viewModel.importFromHealthConnect() }

    val expanded = remember { mutableStateListOf<String>() }
    var pastMedsExpanded by remember { mutableStateOf(false) }
    var fabExpanded by remember { mutableStateOf(false) }

    var pendingVaccinationDelete by remember { mutableStateOf<VaccinationEntry?>(null) }
    var pendingAllergyDelete by remember { mutableStateOf<AllergyEntry?>(null) }
    var pendingConditionDelete by remember { mutableStateOf<ConditionEntry?>(null) }
    var pendingLabDelete by remember { mutableStateOf<LabResultEntry?>(null) }
    var pendingMedicationDelete by remember { mutableStateOf<MedicationEntry?>(null) }

    pendingVaccinationDelete?.let { entry ->
        ConfirmDialog(
            title = stringResource(R.string.delete_record_confirm, entry.displayName),
            confirmLabel = stringResource(UiR.string.delete),
            destructive = true,
            onConfirm = {
                viewModel.deleteVaccination(entry)
                pendingVaccinationDelete = null
            },
            onDismiss = { pendingVaccinationDelete = null },
        )
    }
    pendingAllergyDelete?.let { entry ->
        ConfirmDialog(
            title = stringResource(R.string.delete_record_confirm, entry.displayName),
            confirmLabel = stringResource(UiR.string.delete),
            destructive = true,
            onConfirm = {
                viewModel.deleteAllergy(entry)
                pendingAllergyDelete = null
            },
            onDismiss = { pendingAllergyDelete = null },
        )
    }
    pendingConditionDelete?.let { entry ->
        ConfirmDialog(
            title = stringResource(R.string.delete_record_confirm, entry.displayName),
            confirmLabel = stringResource(UiR.string.delete),
            destructive = true,
            onConfirm = {
                viewModel.deleteCondition(entry)
                pendingConditionDelete = null
            },
            onDismiss = { pendingConditionDelete = null },
        )
    }
    pendingLabDelete?.let { entry ->
        ConfirmDialog(
            title = stringResource(R.string.delete_record_confirm, entry.displayName),
            confirmLabel = stringResource(UiR.string.delete),
            destructive = true,
            onConfirm = {
                viewModel.deleteLabResult(entry)
                pendingLabDelete = null
            },
            onDismiss = { pendingLabDelete = null },
        )
    }
    pendingMedicationDelete?.let { entry ->
        ConfirmDialog(
            title = stringResource(R.string.delete_record_confirm, entry.displayName),
            confirmLabel = stringResource(UiR.string.delete),
            destructive = true,
            onConfirm = {
                viewModel.deleteMedication(entry)
                pendingMedicationDelete = null
            },
            onDismiss = { pendingMedicationDelete = null },
        )
    }

    fun toggle(key: String, hasEntries: Boolean) {
        if (!hasEntries) return
        if (key in expanded) expanded.remove(key) else expanded.add(key)
    }

    fun openAdd(edit: Route) {
        fabExpanded = false
        backStack.add(edit)
    }

    LazyListScaffold(
        horizontalPadding = 16.dp,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        floatingActionButton = {
            FloatingActionButtonMenu(
                expanded = fabExpanded,
                button = {
                    ToggleFloatingActionButton(fabExpanded, { fabExpanded = it }) {
                        if (!fabExpanded) IconAdd() else IconClose()
                    }
                },
            ) {
                FloatingActionButtonMenuItem(
                    onClick = {
                        viewModel.startVaccinationDraft()
                        openAdd(Route.EditVaccination())
                    },
                    text = { Text(stringResource(R.string.add_vaccination)) },
                    icon = { IconVaccine() },
                )
                FloatingActionButtonMenuItem(
                    onClick = {
                        viewModel.startAllergyDraft()
                        openAdd(Route.EditAllergy())
                    },
                    text = { Text(stringResource(R.string.add_allergy)) },
                    icon = { IconWarning() },
                )
                FloatingActionButtonMenuItem(
                    onClick = {
                        viewModel.startConditionDraft()
                        openAdd(Route.EditCondition())
                    },
                    text = { Text(stringResource(R.string.add_condition)) },
                    icon = { IconMonitorHeart() },
                )
                FloatingActionButtonMenuItem(
                    onClick = {
                        viewModel.startLabDraft()
                        openAdd(Route.EditLabResult())
                    },
                    text = { Text(stringResource(R.string.add_lab_result)) },
                    icon = { IconScience() },
                )
                FloatingActionButtonMenuItem(
                    onClick = {
                        viewModel.startMedicationDraft()
                        openAdd(Route.EditMedication())
                    },
                    text = { Text(stringResource(R.string.add_medication)) },
                    icon = { IconMedication() },
                )
            }
        },
    ) {
        if (!viewModel.healthConnectAvailable) {
            item { MedicalStorageNotice() }
        }

        item(key = "medical-about-you") {
            MedicalAboutYouSection(
                backStack = backStack,
                viewModel = viewModel,
                isExpanded = CATEGORY_ABOUT_YOU in expanded,
                // About you always has its questions, so there is always something to open.
                onToggle = { toggle(CATEGORY_ABOUT_YOU, true) },
            )
        }

        item(key = "medical-vaccinations") {
            MedicalCategoryGroup(
                title = stringResource(R.string.vaccinations),
                icon = { modifier, tint -> IconVaccine(modifier, tint) },
                isExpanded = CATEGORY_VACCINATIONS in expanded,
                onToggle = { toggle(CATEGORY_VACCINATIONS, vaccinations.isNotEmpty()) },
                itemCount = vaccinations.size,
            ) { index ->
                val entry = vaccinations[index]
                SwipeActionsBox(
                    enableStartToEnd = false,
                    onEndToStart = { pendingVaccinationDelete = entry },
                ) {
                    HealthRow(
                        headline = entry.displayName,
                        leadingIcon = { modifier, tint -> IconVaccine(modifier, tint) },
                        leadingTint = HealthColors.Medical,
                        trailing = { Text(medicalDateString(entry.occurredAt)) },
                        onClick = {
                            viewModel.startVaccinationDraft(entry.id)
                            backStack.add(Route.EditVaccination(entry.id))
                        },
                    )
                }
            }
        }

        item(key = "medical-allergies") {
            MedicalCategoryGroup(
                title = stringResource(R.string.allergies),
                icon = { modifier, tint -> IconWarning(modifier, tint) },
                isExpanded = CATEGORY_ALLERGIES in expanded,
                onToggle = { toggle(CATEGORY_ALLERGIES, allergies.isNotEmpty()) },
                itemCount = allergies.size,
            ) { index ->
                val entry = allergies[index]
                SwipeActionsBox(
                    enableStartToEnd = false,
                    onEndToStart = { pendingAllergyDelete = entry },
                ) {
                    HealthRow(
                        headline = entry.displayName,
                        leadingIcon = { modifier, tint -> IconWarning(modifier, tint) },
                        leadingTint = HealthColors.Medical,
                        trailing = {
                            entry.onsetAt?.let { Text(medicalDateString(it)) }
                        },
                        onClick = {
                            viewModel.startAllergyDraft(entry.id)
                            backStack.add(Route.EditAllergy(entry.id))
                        },
                    )
                }
            }
        }

        item(key = "medical-conditions") {
            MedicalConditionsSection(
                entries = conditions,
                isExpanded = CATEGORY_CONDITIONS in expanded,
                onToggle = { toggle(CATEGORY_CONDITIONS, conditions.isNotEmpty()) },
                onEdit = { id ->
                    viewModel.startConditionDraft(id)
                    backStack.add(Route.EditCondition(id))
                },
                onDelete = { pendingConditionDelete = it },
            )
        }

        item(key = "medical-labs") {
            MedicalLabSection(
                entries = labResults,
                isExpanded = CATEGORY_LABS in expanded,
                onToggle = { toggle(CATEGORY_LABS, labResults.isNotEmpty()) },
                onEdit = { id ->
                    viewModel.startLabDraft(id)
                    backStack.add(Route.EditLabResult(id))
                },
                onDelete = { pendingLabDelete = it },
            )
        }

        item(key = "medical-medications") {
            MedicalMedicationsSection(
                entries = medications,
                isExpanded = CATEGORY_MEDICATIONS in expanded,
                onToggle = { toggle(CATEGORY_MEDICATIONS, medications.isNotEmpty()) },
                pastExpanded = pastMedsExpanded,
                onTogglePast = { pastMedsExpanded = !pastMedsExpanded },
                onEdit = { id ->
                    viewModel.startMedicationDraft(id)
                    backStack.add(Route.EditMedication(id))
                },
                onDelete = { pendingMedicationDelete = it },
                onTakeDose = { viewModel.recordDoseTaken(it) },
            )
        }
    }
}

private const val CATEGORY_VACCINATIONS = "vaccinations"
private const val CATEGORY_ALLERGIES = "allergies"
