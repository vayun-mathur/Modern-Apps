package com.vayunmathur.health.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.ExperimentalMaterial3ExpressiveApi
import com.vayunmathur.library.ui.FloatingActionButtonMenu
import com.vayunmathur.library.ui.FloatingActionButtonMenuItem
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconArrowForward
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconFire
import com.vayunmathur.library.ui.LazyListScaffold
import com.vayunmathur.library.ui.LinearProgressIndicator
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.ToggleFloatingActionButton
import com.vayunmathur.library.ui.appBarScrollBehavior
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.health.R
import com.vayunmathur.health.Route
import com.vayunmathur.health.data.NutritionData
import com.vayunmathur.health.data.RecordType
import com.vayunmathur.health.ui.components.MetricRing
import com.vayunmathur.library.ui.DashboardSection
import com.vayunmathur.health.util.HealthViewModel
import com.vayunmathur.health.util.NutritionActions
import com.vayunmathur.health.util.NutritionUiState
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.round
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

/** Immutable value describing nutrient dv. */
data class NutrientDV(
    val name: String,
    val dailyValue: Double,
    val unit: String,
    val accessor: (NutritionData) -> Double,
)

/**
 * Shared nutrient catalog used by both NutritionPage and NutritionDetailsPage.
 * Each entry reads its value off a single summed [NutritionData] (one query) via [NutrientDV.accessor].
 */
internal val nutrientCatalog: List<NutrientDV> = listOf(
    NutrientDV("Protein", 50.0, "g") { it.protein },
    NutrientDV("Carbohydrates", 275.0, "g") { it.carbohydrates },
    NutrientDV("Fat", 78.0, "g") { it.fat },
    NutrientDV("Fiber", 28.0, "g") { it.fiber },
    NutrientDV("Sugar", 50.0, "g") { it.sugar },
    NutrientDV("Sodium", 2300.0, "mg") { it.sodium },
    NutrientDV("Cholesterol", 300.0, "mg") { it.cholesterol },
    NutrientDV("Saturated Fat", 20.0, "g") { it.saturatedFat },
    NutrientDV("Trans Fat", 2.0, "g") { it.transFat },
    NutrientDV("Vitamin A", 900.0, "µg") { it.vitaminA },
    NutrientDV("Vitamin C", 90.0, "mg") { it.vitaminC },
    NutrientDV("Vitamin D", 20.0, "µg") { it.vitaminD },
    NutrientDV("Vitamin E", 15.0, "mg") { it.vitaminE },
    NutrientDV("Vitamin K", 120.0, "µg") { it.vitaminK },
    NutrientDV("Vitamin B6", 1.7, "mg") { it.vitaminB6 },
    NutrientDV("Vitamin B12", 2.4, "µg") { it.vitaminB12 },
    NutrientDV("Thiamin", 1.2, "mg") { it.thiamin },
    NutrientDV("Riboflavin", 1.3, "mg") { it.riboflavin },
    NutrientDV("Niacin", 16.0, "mg") { it.niacin },
    NutrientDV("Folate", 400.0, "µg") { it.folate },
    NutrientDV("Biotin", 30.0, "µg") { it.biotin },
    NutrientDV("Pantothenic Acid", 5.0, "mg") { it.pantothenicAcid },
    NutrientDV("Calcium", 1300.0, "mg") { it.calcium },
    NutrientDV("Iron", 18.0, "mg") { it.iron },
    NutrientDV("Magnesium", 420.0, "mg") { it.magnesium },
    NutrientDV("Phosphorus", 1250.0, "mg") { it.phosphorus },
    NutrientDV("Iodine", 150.0, "µg") { it.iodine },
    NutrientDV("Zinc", 11.0, "mg") { it.zinc },
    NutrientDV("Selenium", 55.0, "µg") { it.selenium },
    NutrientDV("Copper", 0.9, "mg") { it.copper },
    NutrientDV("Manganese", 2.3, "mg") { it.manganese },
    NutrientDV("Chromium", 35.0, "µg") { it.chromium },
    NutrientDV("Molybdenum", 45.0, "µg") { it.molybdenum },
    NutrientDV("Chloride", 2300.0, "mg") { it.chloride },
    NutrientDV("Potassium", 4700.0, "mg") { it.potassium },
    NutrientDV("Caffeine", 400.0, "mg") { it.caffeine },
)

/** Hydration is its own RecordType (not part of NutritionData); its value is supplied separately. */
internal val hydrationNutrient = NutrientDV("Hydration", 3.0, "L") { 0.0 }

/**
 * Binds [HealthViewModel] and the back stack to the stateless [NutritionScreen], and hosts
 * the two logging dialogs — those still need the ViewModel, so the screen only asks for
 * them to be opened.
 */
@Composable
fun NutritionPage(backStack: NavBackStack<Route>, viewModel: HealthViewModel) {
    val tz = TimeZone.currentSystemDefault()
    val today = Clock.System.todayIn(tz)
    val dayStart = today.atStartOfDayIn(tz)
    val dayEnd = dayStart.plus(24.hours)

    val totals by remember(dayStart, dayEnd) {
        viewModel.sumNutritionInRange(RecordType.Nutrition, dayStart, dayEnd)
    }.collectAsState(NutritionData())
    val loggedMeals by remember(dayStart, dayEnd) {
        viewModel.getAllRecordsInRange(RecordType.Nutrition, dayStart, dayEnd)
    }.collectAsState(emptyList())

    var showHydrationDialog by remember { mutableStateOf(false) }
    var showMealDialog by remember { mutableStateOf(false) }

    if (showHydrationDialog) {
        LogHydrationDialog(viewModel = viewModel, initialTime = null, onDismiss = { showHydrationDialog = false })
    }
    if (showMealDialog) {
        LogMealDialog(viewModel = viewModel, initialTime = null, onDismiss = { showMealDialog = false })
    }

    NutritionScreen(
        state = NutritionUiState(
            totals = totals,
            mealCount = loggedMeals.size,
            mealCalories = loggedMeals.sumOf { it.nutritionData?.calories ?: 0.0 },
        ),
        actions = object : NutritionActions {
            override fun logHydration() {
                showHydrationDialog = true
            }

            override fun logMeal() {
                showMealDialog = true
            }

            override fun openRecipes() {
                backStack.add(Route.RecipeManagement)
            }

            override fun openFullBreakdown() {
                backStack.add(Route.NutritionFullBreakdown)
            }
        },
    )
}

/**
 * Slim nutrition home — answers "how am I doing on nutrition today?" at a glance.
 * Full meal log + nutrient breakdown live in [NutritionDetailsPage].
 *
 * No dependency on the ViewModel so it can be rendered from a `@Preview` — see
 * `src/screenshotTest`, which is where the store listing images come from.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NutritionScreen(state: NutritionUiState, actions: NutritionActions) {
    val totalCalories = state.totals.calories
    val totalProtein = state.totals.protein
    val totalCarbs = state.totals.carbohydrates
    val totalFat = state.totals.fat

    var fabExpanded by remember { mutableStateOf(false) }

    LazyListScaffold(
        floatingActionButton = {
            FloatingActionButtonMenu(
                expanded = fabExpanded,
                button = {
                    ToggleFloatingActionButton(fabExpanded, { fabExpanded = it }) {
                        if (!fabExpanded) IconAdd() else IconClose()
                    }
                }
            ) {
                FloatingActionButtonMenuItem(
                    onClick = { fabExpanded = false; actions.logHydration() },
                    text = { Text(stringResource(R.string.log_hydration)) },
                    icon = { IconFire() }
                )
                FloatingActionButtonMenuItem(
                    onClick = { fabExpanded = false; actions.logMeal() },
                    text = { Text(stringResource(R.string.log_meal)) },
                    icon = { IconFire() }
                )
                FloatingActionButtonMenuItem(
                    onClick = { fabExpanded = false; actions.openRecipes() },
                    text = { Text(stringResource(R.string.recipes)) },
                    icon = { IconAdd() }
                )
            }
        },
        horizontalPadding = 16.dp,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        scrollBehavior = appBarScrollBehavior(),
    ) {
        // Calorie ring
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    val caloriesGoal = 2000.0
                    val calorieProgress = (totalCalories / caloriesGoal).toFloat().coerceIn(0f, 1f)
                    MetricRing(
                        progress = calorieProgress,
                        label = stringResource(com.vayunmathur.health.R.string.kcal_2),
                        value = totalCalories.round(0).toInt().toString(),
                        modifier = Modifier.size(140.dp),
                        color = HealthColors.Nutrition,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.calories),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(R.string.kcal, totalCalories.round(0).toInt(), caloriesGoal.toInt()),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }

        // Macros as 3 compact rings
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                CompactMacroRing("Protein", totalProtein, 50.0, "g", proteinColor)
                CompactMacroRing("Carbs", totalCarbs, 275.0, "g", carbsColor)
                CompactMacroRing("Fat", totalFat, 78.0, "g", fatColor)
            }
        }

        // Meals link + breakdown CTA
        item {
            DashboardSection(accentColor = HealthColors.Nutrition) {
                ListItem(
                    content = {
                        Text(
                            if (state.mealCount == 0) stringResource(R.string.no_meals_logged_today)
                            else stringResource(R.string.meals_cal, state.mealCount, state.mealCalories.round(0).toInt())
                        )
                    },
                    supportingContent = { Text(stringResource(R.string.view_full_breakdown)) },
                    trailingContent = {
                        IconArrowForward(
                            tint = HealthColors.Nutrition,
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { actions.openFullBreakdown() },
                )
            }
        }
    }
}
