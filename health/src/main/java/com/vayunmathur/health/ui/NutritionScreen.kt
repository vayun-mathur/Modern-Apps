package com.vayunmathur.health.ui
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.ToggleFloatingActionButton
import com.vayunmathur.library.ui.appBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.health.R
import com.vayunmathur.health.ui.components.MetricRing
import com.vayunmathur.library.ui.DashboardSection
import com.vayunmathur.health.platform.NutritionActions
import com.vayunmathur.health.platform.NutritionUiState
import com.vayunmathur.library.util.round

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
