package com.vayunmathur.health.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
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
import com.vayunmathur.health.ui.components.GroupedSection
import com.vayunmathur.health.ui.components.MetricRing
import com.vayunmathur.health.util.HealthViewModel
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.round
import com.vayunmathur.library.ui.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlinx.datetime.*

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
 * Slim nutrition home — answers "how am I doing on nutrition today?" at a glance.
 * Full meal log + nutrient breakdown live in [NutritionDetailsPage].
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NutritionPage(backStack: NavBackStack<Route>, viewModel: HealthViewModel) {
    val tz = TimeZone.currentSystemDefault()
    val today = Clock.System.todayIn(tz)
    val dayStart = today.atStartOfDayIn(tz)
    val dayEnd = dayStart.plus(24.hours)

    val totals by remember(dayStart, dayEnd) {
        viewModel.sumNutritionInRange(RecordType.Nutrition, dayStart, dayEnd)
    }.collectAsState(NutritionData())
    val totalCalories = totals.calories
    val totalProtein = totals.protein
    val totalCarbs = totals.carbohydrates
    val totalFat = totals.fat
    val loggedMeals by remember(dayStart, dayEnd) {
        viewModel.getAllRecordsInRange(RecordType.Nutrition, dayStart, dayEnd)
    }.collectAsState(emptyList())

    var fabExpanded by remember { mutableStateOf(false) }
    var showHydrationDialog by remember { mutableStateOf(false) }
    var showMealDialog by remember { mutableStateOf(false) }

    if (showHydrationDialog) {
        LogHydrationDialog(viewModel = viewModel, initialTime = null, onDismiss = { showHydrationDialog = false })
    }
    if (showMealDialog) {
        LogMealDialog(viewModel = viewModel, initialTime = null, onDismiss = { showMealDialog = false })
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.label_nutrition_details)) })
        },
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
                    onClick = { fabExpanded = false; showHydrationDialog = true },
                    text = { Text(stringResource(R.string.log_hydration)) },
                    icon = { IconFire() }
                )
                FloatingActionButtonMenuItem(
                    onClick = { fabExpanded = false; showMealDialog = true },
                    text = { Text(stringResource(R.string.log_meal)) },
                    icon = { IconFire() }
                )
                FloatingActionButtonMenuItem(
                    onClick = { fabExpanded = false; backStack.add(Route.RecipeManagement) },
                    text = { Text(stringResource(R.string.recipes)) },
                    icon = { IconAdd() }
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Calorie ring
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
                        label = "kcal",
                        value = totalCalories.round(0).toInt().toString(),
                        modifier = Modifier.size(140.dp),
                        color = HealthColors.Nutrition,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Calories",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "${totalCalories.round(0).toInt()} / ${caloriesGoal.toInt()} kcal",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            // Macros as 3 compact rings
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                CompactMacroRing("Protein", totalProtein, 50.0, "g", proteinColor)
                CompactMacroRing("Carbs", totalCarbs, 275.0, "g", carbsColor)
                CompactMacroRing("Fat", totalFat, 78.0, "g", fatColor)
            }

            // Meals link + breakdown CTA
            GroupedSection(accentColor = HealthColors.Nutrition) {
                val totalCal = loggedMeals.sumOf { it.nutritionData?.calories ?: 0.0 }
                ListItem(
                    content = {
                        Text(
                            if (loggedMeals.isEmpty()) "No meals logged today"
                            else "${loggedMeals.size} meals · ${totalCal.round(0).toInt()} cal"
                        )
                    },
                    supportingContent = { Text("View full breakdown") },
                    trailingContent = {
                        Icon(
                            painter = painterResource(R.drawable.outline_arrow_forward_24),
                            contentDescription = null,
                            tint = HealthColors.Nutrition,
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { backStack.add(Route.NutritionFullBreakdown) },
                )
            }
        }
    }
}

@Composable
private fun CompactMacroRing(
    label: String,
    value: Double,
    goal: Double,
    unit: String,
    color: Color,
) {
    val progress = (value / goal).toFloat().coerceIn(0f, 1f)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        MetricRing(
            progress = progress,
            label = "",
            value = "${value.round(0).toInt()}",
            modifier = Modifier.size(72.dp),
            color = color,
            strokeWidth = 6.dp,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = color.copy(alpha = 0.95f),
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "${value.round(0).toInt()}/${goal.toInt()}$unit",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Single nutrient row used by NutritionDetailsPage. */
@Composable
internal fun NutrientProgressRow(nutrient: NutrientDV, currentAmount: Double) {
    val progress = (currentAmount / nutrient.dailyValue).toFloat().coerceIn(0f, 1f)
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = nutrient.name,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${currentAmount.round(1)} / ${nutrient.dailyValue.round(0).toInt()}${nutrient.unit}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(4.dp),
            color = HealthColors.Nutrition,
            trackColor = HealthColors.Nutrition.copy(alpha = 0.18f),
            strokeCap = StrokeCap.Round,
        )
    }
}
