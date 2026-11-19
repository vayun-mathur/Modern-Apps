package com.vayunmathur.health.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.health.R
import com.vayunmathur.health.Route
import com.vayunmathur.health.data.ReferenceCatalog
import com.vayunmathur.health.data.VaccineCatalog
import com.vayunmathur.health.platform.MedicalViewModel
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.CommonSearchBar
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.NavBackStack
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable

/** How long to wait for typing to stop before searching. */
private const val SEARCH_DEBOUNCE_MS = 180L

/** Which catalogue [CatalogPickerPage] is searching, and therefore which draft field it fills in. */
@Serializable
enum class CatalogKind {
    /** CVX vaccine codes. */
    Vaccine,

    /** RxNorm ingredients — the first medication step. */
    MedicationIngredient,

    /** RxNorm products for one ingredient — the second step, giving strength and dose form. */
    MedicationProduct,

    /** RxNorm ingredients again, but recorded as an allergen rather than something taken. */
    Allergen,

    /** ICD-10-CM diagnoses. */
    Condition,

    /** LOINC laboratory tests. */
    LabTest,
}

/**
 * The searchable picker behind every "choose from the database" field.
 *
 * A full destination rather than a dropdown because the medication catalogue is ~21,000 rows and
 * every dropdown component in `:library:ui` renders its options eagerly.
 *
 * It writes the choice straight into the ViewModel's draft rather than posting it back through
 * `LocalNavResultRegistry`. That registry only works for dialog destinations: `NavDisplay` composes
 * one destination at a time, so a full-screen picker disposes the form underneath it, and a result
 * posted to a `SharedFlow` with no replay would arrive at a collector that no longer exists.
 *
 * Free text is always available as the last row. The medication catalogue is a gitignored build
 * artefact, so a clean checkout has no medication list at all, and even with one a user may be
 * taking something RxNorm's prescribable subset does not list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogPickerPage(
    backStack: NavBackStack<Route>,
    viewModel: MedicalViewModel,
    route: Route.CatalogPicker,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }

    val catalogStatus by ReferenceCatalog.status.collectAsState()

    LaunchedEffect(route.kind) {
        if (route.kind != CatalogKind.Vaccine) ReferenceCatalog.prepare()
    }

    // Searching on every keystroke restarts the query and rebuilds the whole list between two
    // frames of typing. The queries themselves are sub-millisecond, but the churn is visible, so
    // wait for a pause. Clearing is exempt: emptying the field should feel instant.
    var settledQuery by remember { mutableStateOf("") }
    LaunchedEffect(query) {
        if (query.isEmpty()) {
            settledQuery = ""
        } else {
            delay(SEARCH_DEBOUNCE_MS)
            settledQuery = query
        }
    }

    var vaccines by remember { mutableStateOf<List<VaccineCatalog.Vaccine>>(emptyList()) }
    var ingredients by remember { mutableStateOf<List<String>>(emptyList()) }
    var products by remember { mutableStateOf<List<ReferenceCatalog.Medication>>(emptyList()) }
    var allergens by remember { mutableStateOf<List<ReferenceCatalog.Ingredient>>(emptyList()) }
    var conditions by remember { mutableStateOf<List<ReferenceCatalog.Condition>>(emptyList()) }
    var labs by remember { mutableStateOf<List<ReferenceCatalog.LabTest>>(emptyList()) }

    LaunchedEffect(route.kind, route.ingredient, settledQuery, catalogStatus) {
        when (route.kind) {
            CatalogKind.Vaccine -> vaccines = VaccineCatalog.search(context, settledQuery)
            CatalogKind.MedicationIngredient ->
                ingredients = if (settledQuery.isBlank()) emptyList()
                else ReferenceCatalog.searchIngredients(settledQuery)
            CatalogKind.MedicationProduct ->
                products = ReferenceCatalog.productsFor(route.ingredient.orEmpty())
                    .filter { product ->
                        settledQuery.isBlank() ||
                            product.name.contains(settledQuery, ignoreCase = true)
                    }
            CatalogKind.Allergen ->
                allergens = if (settledQuery.isBlank()) emptyList()
                else ReferenceCatalog.searchAllergens(settledQuery)
            CatalogKind.Condition ->
                conditions = if (settledQuery.isBlank()) emptyList()
                else ReferenceCatalog.searchConditions(settledQuery)
            CatalogKind.LabTest ->
                labs = if (settledQuery.isBlank()) emptyList()
                else ReferenceCatalog.searchLabs(settledQuery)
        }
    }

    fun chooseVaccine(code: String?, name: String) {
        viewModel.editVaccinationDraft { it.copy(cvxCode = code, displayName = name) }
        backStack.pop()
    }

    // Clears the strength and RXCUI too: a strength carried over from the previous drug would
    // otherwise survive silently into the saved record.
    fun chooseIngredient(name: String) {
        viewModel.editMedicationDraft {
            it.copy(ingredient = name, rxcui = null, strength = null, doseForm = null)
        }
        backStack.pop()
    }

    fun chooseProduct(rxcui: String?, strength: String?, doseForm: String?) {
        viewModel.editMedicationDraft {
            it.copy(rxcui = rxcui, strength = strength, doseForm = doseForm)
        }
        backStack.pop()
    }

    fun chooseAllergen(rxcui: String?, name: String) {
        viewModel.editAllergyDraft { it.copy(rxcui = rxcui, displayName = name) }
        backStack.pop()
    }

    fun chooseCondition(code: String?, description: String) {
        viewModel.editConditionDraft { it.copy(icd10Code = code, displayName = description) }
        backStack.pop()
    }

    // The unit LOINC suggests comes along with the test, which is right far more often than not and
    // stays editable. It is only filled in when the field is still empty, so it cannot overwrite a
    // unit the user typed before going to pick the test.
    fun chooseLab(code: String?, name: String, unit: String?) {
        viewModel.editLabDraft {
            it.copy(
                loincCode = code,
                displayName = name,
                unit = if (it.unit.isBlank()) unit.orEmpty() else it.unit,
            )
        }
        backStack.pop()
    }

    // Back clears the search before leaving, matching ListPage. With the field in the app bar
    // there is nowhere else for a back press to sensibly go first.
    BackHandler(enabled = query.isNotEmpty()) { query = "" }

    AppScaffold(
        // The search field *is* the title. These lists are the whole point of the screen, and a
        // separate title bar above the field costs a row of height to say what the placeholder
        // already says.
        title = {
            CommonSearchBar(
                value = query,
                onValueChange = { query = it },
                placeholder = stringResource(
                    when (route.kind) {
                        CatalogKind.Vaccine -> R.string.search_vaccines
                        CatalogKind.MedicationIngredient -> R.string.search_medications
                        CatalogKind.MedicationProduct -> R.string.search_strengths
                        CatalogKind.Allergen -> R.string.search_allergens
                        CatalogKind.Condition -> R.string.search_conditions
                        CatalogKind.LabTest -> R.string.search_lab_tests
                    }
                ),
                padding = PaddingValues(0.dp),
            )
        },
        onNavigateBack = { backStack.pop() },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {

            val notice = when {
                route.kind == CatalogKind.Vaccine -> null
                catalogStatus is ReferenceCatalog.Status.Preparing ->
                    stringResource(R.string.preparing_medication_catalog)
                catalogStatus is ReferenceCatalog.Status.Ready -> null
                else -> stringResource(R.string.medication_catalog_absent)
            }
            if (notice != null) {
                Text(
                    notice,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                val empty = when (route.kind) {
                    CatalogKind.Vaccine -> vaccines.isEmpty()
                    CatalogKind.MedicationIngredient -> ingredients.isEmpty()
                    CatalogKind.MedicationProduct -> products.isEmpty()
                    CatalogKind.Allergen -> allergens.isEmpty()
                    CatalogKind.Condition -> conditions.isEmpty()
                    CatalogKind.LabTest -> labs.isEmpty()
                }
                // Against the settled query, not the live one — otherwise "nothing matches" flashes
                // up between keystrokes while the results for the previous one are still showing.
                if (empty && settledQuery.isNotBlank()) {
                    item {
                        Text(
                            stringResource(R.string.no_matches),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }

                when (route.kind) {
                    CatalogKind.Vaccine -> items(vaccines, key = { it.code }) { vaccine ->
                        ListItem(
                            headlineContent = { Text(vaccine.name) },
                            supportingContent = { Text(vaccine.fullName) },
                            overlineContent = if (vaccine.active) null else {
                                { Text(stringResource(R.string.cvx_retired)) }
                            },
                            trailingContent = {
                                Text(
                                    vaccine.code,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            modifier = Modifier.clickable {
                                chooseVaccine(vaccine.code, vaccine.name)
                            },
                        )
                    }

                    CatalogKind.MedicationIngredient -> items(ingredients, key = { it }) { name ->
                        ListItem(
                            headlineContent = { Text(name) },
                            modifier = Modifier.clickable { chooseIngredient(name) },
                        )
                    }

                    CatalogKind.MedicationProduct -> items(products, key = { it.rxcui }) { product ->
                        ListItem(
                            headlineContent = { Text(product.strength ?: product.name) },
                            supportingContent = product.doseForm?.let { form -> { Text(form) } },
                            modifier = Modifier.clickable {
                                chooseProduct(product.rxcui, product.strength, product.doseForm)
                            },
                        )
                    }

                    CatalogKind.Allergen -> items(allergens, key = { it.rxcui }) { allergen ->
                        ListItem(
                            headlineContent = { Text(allergen.name) },
                            modifier = Modifier.clickable {
                                chooseAllergen(allergen.rxcui, allergen.name)
                            },
                        )
                    }

                    CatalogKind.Condition -> items(conditions, key = { it.code }) { condition ->
                        ListItem(
                            headlineContent = { Text(condition.description) },
                            trailingContent = {
                                Text(
                                    condition.code,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            modifier = Modifier.clickable {
                                chooseCondition(condition.code, condition.description)
                            },
                        )
                    }

                    CatalogKind.LabTest -> items(labs, key = { it.code }) { lab ->
                        ListItem(
                            headlineContent = { Text(lab.name) },
                            supportingContent = lab.unit?.let { unit -> { Text(unit) } },
                            modifier = Modifier.clickable {
                                chooseLab(lab.code, lab.name, lab.unit)
                            },
                        )
                    }
                }

                val typed = query.trim()
                if (typed.isNotEmpty()) {
                    item {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.use_typed_name, typed)) },
                            modifier = Modifier.fillMaxWidth().clickable {
                                when (route.kind) {
                                    CatalogKind.Vaccine -> chooseVaccine(null, typed)
                                    CatalogKind.MedicationIngredient -> chooseIngredient(typed)
                                    CatalogKind.MedicationProduct -> chooseProduct(null, typed, null)
                                    CatalogKind.Allergen -> chooseAllergen(null, typed)
                                    CatalogKind.Condition -> chooseCondition(null, typed)
                                    CatalogKind.LabTest -> chooseLab(null, typed, null)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
