package com.vayunmathur.health.ui
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconArrowDropDown
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.SelectableDropdownMenuItem
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vayunmathur.health.R
import com.vayunmathur.health.platform.HealthViewModel
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogMealDialog(viewModel: HealthViewModel, initialTime: Instant? = null, onDismiss: () -> Unit) {
    val recipes by viewModel.allRecipes.collectAsState(emptyList())
    val ingredientRecipes by viewModel.ingredientsAsRecipes.collectAsState(emptyList())

    val allLoggables =
            remember(recipes, ingredientRecipes) {
                recipes.map { Loggable.RecipeWrapper(it) } +
                        ingredientRecipes.map { Loggable.IngredientWrapper(it) }
            }

    var selectedLoggable by remember { mutableStateOf<Loggable?>(null) }
    var quantityStr by remember { mutableStateOf("1") }
    var expanded by remember { mutableStateOf(false) }

    val quantity = quantityStr.toDoubleOrNull()
    val isValid = selectedLoggable != null && quantity != null && quantity > 0

    AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.log_meal)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                                value = selectedLoggable?.name ?: stringResource(R.string.select_recipe),
                                onValueChange = {},
                                readOnly = true,
                                trailingIcon = { IconArrowDropDown() },
                                modifier = Modifier.fillMaxWidth()
                        )
                        DropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false }
                        ) {
                            allLoggables.sortedBy { it.name }.forEach { loggable ->
                                SelectableDropdownMenuItem(
                                        selected = loggable == selectedLoggable,
                                        onClick = {
                                            selectedLoggable = loggable
                                            expanded = false
                                        },
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(loggable.name)
                                                if (loggable is Loggable.IngredientWrapper) {
                                                    Spacer(Modifier.width(4.dp))
                                                    Text(
                                                            stringResource(R.string.ingredient),
                                                            style =
                                                                    MaterialTheme.typography
                                                                            .bodySmall,
                                                            color =
                                                                    MaterialTheme.colorScheme
                                                                            .outline
                                                    )
                                                }
                                            }
                                        },
                                        selectedLeadingIcon = { IconCheck() },
                                )
                            }
                        }
                        Box(Modifier.matchParentSize().clickable { expanded = true })
                    }

                    val labelText = if (selectedLoggable is Loggable.IngredientWrapper) {
                        stringResource(R.string.quantity_x_100g)
                    } else {
                        stringResource(R.string.servings)
                    }
                    OutlinedTextField(
                            value = quantityStr,
                            onValueChange = { quantityStr = it },
                            label = { Text(labelText) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                        enabled = isValid,
                        onClick = {
                            val q = quantity
                            val target = selectedLoggable
                            if (q != null && target != null) {
                                val time = initialTime ?: Instant.now()
                                val logTarget = when (target) {
                                    is Loggable.RecipeWrapper ->
                                        HealthViewModel.LogMealTarget.FromRecipe(target.recipe.id, target.recipe.name)
                                    is Loggable.IngredientWrapper ->
                                        HealthViewModel.LogMealTarget.FromIngredient(target.ingredient)
                                }
                                viewModel.logMeal(logTarget, q, time)
                                onDismiss()
                            }
                        }
                ) { Text(stringResource(UiR.string.save)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.cancel)) } }
    )
}
