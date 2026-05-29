package com.vayunmathur.health.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.health.R
import com.vayunmathur.health.Route
import com.vayunmathur.health.data.RecordType
import com.vayunmathur.health.util.HealthViewModel
import com.vayunmathur.health.util.displayString
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.round
import com.vayunmathur.library.ui.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.datetime.*

data class NutrientDV(
        val name: String,
        val type: RecordType,
        val dailyValue: Double,
        val unit: String,
        val sumFunction: (RecordType, Instant, Instant) -> kotlinx.coroutines.flow.Flow<Double>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NutritionDetailsPage(backStack: NavBackStack<Route>, viewModel: HealthViewModel) {
    val initialPage = 999
    val pagerState = rememberPagerState(initialPage = initialPage) { 1000 }
    val tz = TimeZone.currentSystemDefault()
    val today = Clock.System.todayIn(tz)

    val nutrients = remember(viewModel) {
        listOf(
                NutrientDV("Protein", RecordType.Nutrition, 50.0, "g") { t, s, e ->
                    viewModel.sumProteinInRange(t, s, e)
                },
                NutrientDV("Carbohydrates", RecordType.Nutrition, 275.0, "g") { t, s, e ->
                    viewModel.sumCarbsInRange(t, s, e)
                },
                NutrientDV("Fat", RecordType.Nutrition, 78.0, "g") { t, s, e ->
                    viewModel.sumFatInRange(t, s, e)
                },
                NutrientDV("Fiber", RecordType.Nutrition, 28.0, "g") { t, s, e ->
                    viewModel.sumFiberInRange(t, s, e)
                },
                NutrientDV("Sugar", RecordType.Nutrition, 50.0, "g") { t, s, e ->
                    viewModel.sumSugarInRange(t, s, e)
                },
                NutrientDV("Sodium", RecordType.Nutrition, 2300.0, "mg") { t, s, e ->
                    viewModel.sumSodiumInRange(t, s, e)
                },
                NutrientDV("Cholesterol", RecordType.Nutrition, 300.0, "mg") { t, s, e ->
                    viewModel.sumCholesterolInRange(t, s, e)
                },
                NutrientDV("Saturated Fat", RecordType.Nutrition, 20.0, "g") { t, s, e ->
                    viewModel.sumSaturatedFatInRange(t, s, e)
                },
                NutrientDV("Trans Fat", RecordType.Nutrition, 2.0, "g") { t, s, e ->
                    viewModel.sumTransFatInRange(t, s, e)
                },
                NutrientDV("Vitamin A", RecordType.Nutrition, 900.0, "µg") { t, s, e ->
                    viewModel.sumVitaminAInRange(t, s, e)
                },
                NutrientDV("Vitamin C", RecordType.Nutrition, 90.0, "mg") { t, s, e ->
                    viewModel.sumVitaminCInRange(t, s, e)
                },
                NutrientDV("Vitamin D", RecordType.Nutrition, 20.0, "µg") { t, s, e ->
                    viewModel.sumVitaminDInRange(t, s, e)
                },
                NutrientDV("Vitamin E", RecordType.Nutrition, 15.0, "mg") { t, s, e ->
                    viewModel.sumVitaminEInRange(t, s, e)
                },
                NutrientDV("Vitamin K", RecordType.Nutrition, 120.0, "µg") { t, s, e ->
                    viewModel.sumVitaminKInRange(t, s, e)
                },
                NutrientDV("Vitamin B6", RecordType.Nutrition, 1.7, "mg") { t, s, e ->
                    viewModel.sumVitaminB6InRange(t, s, e)
                },
                NutrientDV("Vitamin B12", RecordType.Nutrition, 2.4, "µg") { t, s, e ->
                    viewModel.sumVitaminB12InRange(t, s, e)
                },
                NutrientDV("Thiamin", RecordType.Nutrition, 1.2, "mg") { t, s, e ->
                    viewModel.sumThiaminInRange(t, s, e)
                },
                NutrientDV("Riboflavin", RecordType.Nutrition, 1.3, "mg") { t, s, e ->
                    viewModel.sumRiboflavinInRange(t, s, e)
                },
                NutrientDV("Niacin", RecordType.Nutrition, 16.0, "mg") { t, s, e ->
                    viewModel.sumNiacinInRange(t, s, e)
                },
                NutrientDV("Folate", RecordType.Nutrition, 400.0, "µg") { t, s, e ->
                    viewModel.sumFolateInRange(t, s, e)
                },
                NutrientDV("Biotin", RecordType.Nutrition, 30.0, "µg") { t, s, e ->
                    viewModel.sumBiotinInRange(t, s, e)
                },
                NutrientDV("Pantothenic Acid", RecordType.Nutrition, 5.0, "mg") { t, s, e ->
                    viewModel.sumPantothenicAcidInRange(t, s, e)
                },
                NutrientDV("Calcium", RecordType.Nutrition, 1300.0, "mg") { t, s, e ->
                    viewModel.sumCalciumInRange(t, s, e)
                },
                NutrientDV("Iron", RecordType.Nutrition, 18.0, "mg") { t, s, e ->
                    viewModel.sumIronInRange(t, s, e)
                },
                NutrientDV("Magnesium", RecordType.Nutrition, 420.0, "mg") { t, s, e ->
                    viewModel.sumMagnesiumInRange(t, s, e)
                },
                NutrientDV("Phosphorus", RecordType.Nutrition, 1250.0, "mg") { t, s, e ->
                    viewModel.sumPhosphorusInRange(t, s, e)
                },
                NutrientDV("Iodine", RecordType.Nutrition, 150.0, "µg") { t, s, e ->
                    viewModel.sumIodineInRange(t, s, e)
                },
                NutrientDV("Zinc", RecordType.Nutrition, 11.0, "mg") { t, s, e ->
                    viewModel.sumZincInRange(t, s, e)
                },
                NutrientDV("Selenium", RecordType.Nutrition, 55.0, "µg") { t, s, e ->
                    viewModel.sumSeleniumInRange(t, s, e)
                },
                NutrientDV("Copper", RecordType.Nutrition, 0.9, "mg") { t, s, e ->
                    viewModel.sumCopperInRange(t, s, e)
                },
                NutrientDV("Manganese", RecordType.Nutrition, 2.3, "mg") { t, s, e ->
                    viewModel.sumManganeseInRange(t, s, e)
                },
                NutrientDV("Chromium", RecordType.Nutrition, 35.0, "µg") { t, s, e ->
                    viewModel.sumChromiumInRange(t, s, e)
                },
                NutrientDV("Molybdenum", RecordType.Nutrition, 45.0, "µg") { t, s, e ->
                    viewModel.sumMolybdenumInRange(t, s, e)
                },
                NutrientDV("Chloride", RecordType.Nutrition, 2300.0, "mg") { t, s, e ->
                    viewModel.sumChlorideInRange(t, s, e)
                },
                NutrientDV("Potassium", RecordType.Nutrition, 4700.0, "mg") { t, s, e ->
                    viewModel.sumPotassiumInRange(t, s, e)
                },
                NutrientDV("Caffeine", RecordType.Nutrition, 400.0, "mg") { t, s, e ->
                    viewModel.sumCaffeineInRange(t, s, e)
                },
                NutrientDV("Hydration", RecordType.Hydration, 3.0, "L") { t, s, e ->
                    viewModel.sumInRange(t, s, e)
                }
        )
    }

    var showFabMenu by remember { androidx.compose.runtime.mutableStateOf(false) }
    var showHydrationDialog by remember { androidx.compose.runtime.mutableStateOf(false) }
    var showMealDialog by remember { androidx.compose.runtime.mutableStateOf(false) }

    val selectedDay = remember(pagerState.currentPage) {
        today.minus(initialPage - pagerState.currentPage, DateTimeUnit.DAY)
    }
    val selectedDayInstant = remember(selectedDay) {
        if (selectedDay == today) null
        else java.time.Instant.ofEpochMilli(selectedDay.atStartOfDayIn(tz).toEpochMilliseconds())
    }

    if (showHydrationDialog) {
        LogHydrationDialog(viewModel = viewModel, initialTime = selectedDayInstant, onDismiss = { showHydrationDialog = false })
    }
    if (showMealDialog) {
        LogMealDialog(viewModel = viewModel, initialTime = selectedDayInstant, onDismiss = { showMealDialog = false })
    }

    Scaffold(
            topBar = {
                TopAppBar(
                        title = { Text(stringResource(R.string.label_nutrition_details)) },
                )
            },
            floatingActionButton = {
                Column(horizontalAlignment = Alignment.End) {
                    if (showFabMenu) {
                        ExtendedFloatingActionButton(
                            onClick = {
                                showFabMenu = false
                                showHydrationDialog = true
                            },
                            icon = { IconFire() },
                            text = { Text("Log Hydration") },
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        ExtendedFloatingActionButton(
                            onClick = {
                                showFabMenu = false
                                showMealDialog = true
                            },
                            icon = { IconFire() },
                            text = { Text("Log Meal") },
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        ExtendedFloatingActionButton(
                            onClick = {
                                showFabMenu = false
                                backStack.add(Route.RecipeManagement)
                            },
                            icon = { IconAdd() },
                            text = { Text("Create/Edit Recipes") },
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    FloatingActionButton(onClick = { showFabMenu = !showFabMenu }) {
                        IconAdd()
                    }
                }
            }
    ) { padding ->
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            val day = today.minus(initialPage - page, DateTimeUnit.DAY)
            val dayStart = day.atStartOfDayIn(tz)
            val dayEnd = dayStart.plus(24.hours)

            val totalCalories by remember(dayStart, dayEnd) { viewModel.sumInRange(RecordType.Nutrition, dayStart, dayEnd) }.collectAsState(0.0)
            val totalProtein by remember(dayStart, dayEnd) { viewModel.sumProteinInRange(RecordType.Nutrition, dayStart, dayEnd) }.collectAsState(0.0)
            val totalCarbs by remember(dayStart, dayEnd) { viewModel.sumCarbsInRange(RecordType.Nutrition, dayStart, dayEnd) }.collectAsState(0.0)
            val totalFat by remember(dayStart, dayEnd) { viewModel.sumFatInRange(RecordType.Nutrition, dayStart, dayEnd) }.collectAsState(0.0)

            val loggedMeals by remember(dayStart, dayEnd) { viewModel.getAllRecordsInRange(RecordType.Nutrition, dayStart, dayEnd) }.collectAsState(emptyList())
            val loggedHydration by remember(dayStart, dayEnd) { viewModel.getAllRecordsInRange(RecordType.Hydration, dayStart, dayEnd) }.collectAsState(emptyList())
            val allLogs = (loggedMeals + loggedHydration).sortedByDescending { it.startTime }

            LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(
                        top = 16.dp + padding.calculateTopPadding(),
                        bottom = 80.dp + padding.calculateBottomPadding()
                    )
            ) {
                item {
                    Column(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                                text = if (day == today) stringResource(R.string.label_today) else day.displayString(),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.secondary
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // Compact Summary Grid
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Calories", style = MaterialTheme.typography.labelMedium)
                                    Text("${totalCalories.round(0).toInt()}/2000", style = MaterialTheme.typography.titleMedium)
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Protein", style = MaterialTheme.typography.labelMedium)
                                    Text("${totalProtein.round(0).toInt()}/50g", style = MaterialTheme.typography.titleSmall)
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Carbs", style = MaterialTheme.typography.labelMedium)
                                    Text("${totalCarbs.round(0).toInt()}/275g", style = MaterialTheme.typography.titleSmall)
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Fat", style = MaterialTheme.typography.labelMedium)
                                    Text("${totalFat.round(0).toInt()}/78g", style = MaterialTheme.typography.titleSmall)
                                }
                            }
                        }
                    }
                }


                if (allLogs.isNotEmpty()) {
                    item {
                        Text(
                            text = "Logged Items",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                        )
                    }
                    items(allLogs) { log ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = log.metadata ?: (if (log.type == RecordType.Hydration) "Hydration" else "Meal"),
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                    if (log.type == RecordType.Nutrition) {
                                        Text(
                                            text = "${log.nutritionData?.calories?.round(0)?.toInt() ?: 0} kcal • ${log.nutritionData?.protein?.round(1) ?: 0}g P • ${log.nutritionData?.carbohydrates?.round(1) ?: 0}g C • ${log.nutritionData?.fat?.round(1) ?: 0}g F",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.outline
                                        )
                                    } else {
                                        Text(
                                            text = "${log.value.round(2)} L",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.outline
                                        )
                                    }
                                }
                                IconButton(onClick = { viewModel.deleteRecord(log) }) {
                                    Icon(
                                        painter = painterResource(R.drawable.baseline_delete_24),
                                        contentDescription = "Unlog",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    Text(
                        text = "Nutrient Breakdown",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                    )
                }
                
                // Other nutrients (excluding Protein, Carbs, Fat since they are in the summary)
                val otherNutrients = nutrients.filter { it.name !in listOf("Protein", "Carbohydrates", "Fat") }
                items(otherNutrients.chunked(2)) { rowNutrients ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowNutrients.forEach { nutrient ->
                            Box(modifier = Modifier.weight(1f)) {
                                NutrientProgressCard(nutrient, dayStart, dayEnd)
                            }
                        }
                        // Fill remaining space if row has less than 2 items
                        repeat(2 - rowNutrients.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NutrientProgressCard(nutrient: NutrientDV, start: Instant, end: Instant) {
    val currentAmount by remember(nutrient, start, end) { nutrient.sumFunction(nutrient.type, start, end) }.collectAsState(0.0)
    val progress = (currentAmount / nutrient.dailyValue).toFloat().coerceIn(0f, 1f)

    Card(
            modifier = Modifier.fillMaxWidth(),
            colors =
                    CardDefaults.cardColors(
                            containerColor =
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                        text = nutrient.name,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                )
                Text(
                        text = "${currentAmount.round(1)}/${nutrient.dailyValue.round(0).toInt()}${nutrient.unit}",
                        style = MaterialTheme.typography.labelSmall
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color =
                            if (progress >= 1f) Color(0xFF4CAF50)
                            else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                    strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
            )
        }
    }
}
