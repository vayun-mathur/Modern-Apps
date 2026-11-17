package com.vayunmathur.health.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.health.R
import com.vayunmathur.health.Route
import com.vayunmathur.health.data.NutritionData
import com.vayunmathur.health.data.RecordType
import com.vayunmathur.health.ui.components.HealthRow
import com.vayunmathur.library.ui.DashboardSection
import com.vayunmathur.library.ui.DashboardSectionDivider
import com.vayunmathur.health.platform.HealthViewModel
import com.vayunmathur.health.platform.displayString
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.round
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

/** Full per-day nutrition breakdown — heavy content lives here. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NutritionDetailsPage(backStack: NavBackStack<Route>, viewModel: HealthViewModel) {
    val initialPage = 999
    val pagerState = rememberPagerState(initialPage = initialPage) { 1000 }
    val tz = TimeZone.currentSystemDefault()
    val today = Clock.System.todayIn(tz)

    val nutrients = nutrientCatalog

    AppScaffold(
        title = stringResource(R.string.nutrition_breakdown),
        backStack = backStack,
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            val day = today.minus(initialPage - page, DateTimeUnit.DAY)
            val dayStart = day.atStartOfDayIn(tz)
            val dayEnd = dayStart.plus(24.hours)

            val loggedMeals by remember(dayStart, dayEnd) {
                viewModel.getAllRecordsInRange(RecordType.Nutrition, dayStart, dayEnd)
            }.collectAsState(emptyList())
            val loggedHydration by remember(dayStart, dayEnd) {
                viewModel.getAllRecordsInRange(RecordType.Hydration, dayStart, dayEnd)
            }.collectAsState(emptyList())
            val nutritionTotals by remember(dayStart, dayEnd) {
                viewModel.sumNutritionInRange(RecordType.Nutrition, dayStart, dayEnd)
            }.collectAsState(NutritionData())
            val hydrationTotal by remember(dayStart, dayEnd) {
                viewModel.sumInRange(RecordType.Hydration, dayStart, dayEnd)
            }.collectAsState(0.0)
            val allLogs = (loggedMeals + loggedHydration).sortedByDescending { it.startTime }
            val otherNutrients =
                nutrients.filter { it.name !in listOf("Protein", "Carbohydrates", "Fat") }
            val breakdownRows =
                otherNutrients.map { it to it.accessor(nutritionTotals) } +
                    (hydrationNutrient to hydrationTotal)

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(
                    top = 8.dp + padding.calculateTopPadding(),
                    bottom = 24.dp + padding.calculateBottomPadding()
                )
            ) {
                item {
                    Text(
                        text = if (day == today) stringResource(R.string.nav_today) else day.displayString(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                }

                if (allLogs.isNotEmpty()) {
                    item {
                        DashboardSection(title = "Logged today", accentColor = HealthColors.Nutrition) {
                            allLogs.forEachIndexed { idx, log ->
                                if (idx > 0) DashboardSectionDivider(insetStart = 16.dp)
                                val headline = log.metadata
                                    ?: if (log.type == RecordType.Hydration) "Hydration" else "Meal"
                                val supporting = if (log.type == RecordType.Nutrition) {
                                    "${log.nutritionData?.calories?.round(0)?.toInt() ?: 0} kcal • " +
                                        "${log.nutritionData?.protein?.round(1) ?: 0}g P • " +
                                        "${log.nutritionData?.carbohydrates?.round(1) ?: 0}g C • " +
                                        "${log.nutritionData?.fat?.round(1) ?: 0}g F"
                                } else {
                                    "${log.value.round(2)} L"
                                }
                                HealthRow(
                                    headline = headline,
                                    supporting = supporting,
                                    trailing = {
                                        IconButton(onClick = { viewModel.deleteRecord(log) }) {
                                            IconDelete(
                                                tint = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                item {
                    DashboardSection(title = "Nutrient breakdown", accentColor = HealthColors.Nutrition) {
                        breakdownRows.forEachIndexed { idx, (nutrient, value) ->
                            if (idx > 0) DashboardSectionDivider(insetStart = 16.dp)
                            NutrientProgressRow(nutrient, value)
                        }
                    }
                }
            }
        }
    }
}
