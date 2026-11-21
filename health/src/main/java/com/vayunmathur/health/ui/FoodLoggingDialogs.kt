package com.vayunmathur.health.ui

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vayunmathur.health.R
import com.vayunmathur.health.data.Ingredient
import com.vayunmathur.health.data.Recipe
import com.vayunmathur.health.domain.Liters
import com.vayunmathur.health.platform.HealthViewModel
import java.time.Instant

/** Kinds of hydration unit. */
enum class HydrationUnit(@StringRes val displayName: Int, val toLiters: Double) {
    Liters(R.string.hydration_liters, 1.0),
    Milliliters(R.string.hydration_milliliters, 0.001),
    Ounces(R.string.hydration_ounces, 0.0295735),
    Cups(R.string.hydration_cups, 0.236588)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogHydrationDialog(viewModel: HealthViewModel, initialTime: Instant? = null, onDismiss: () -> Unit) {
    var quantityStr by remember { mutableStateOf("") }
    var selectedUnit by remember { mutableStateOf(HydrationUnit.Liters) }
    var unitExpanded by remember { mutableStateOf(false) }

    val quantity = quantityStr.toDoubleOrNull()
    val isValid = quantity != null && quantity > 0

    AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.log_hydration)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                            value = quantityStr,
                            onValueChange = { quantityStr = it },
                            label = { Text(stringResource(R.string.quantity)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth(),
                            trailingIcon = {
                                Row(
                                        modifier = Modifier.clickable { unitExpanded = true },
                                        verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                            stringResource(selectedUnit.displayName),
                                            style = MaterialTheme.typography.bodyMedium
                                    )
                                    IconArrowDropDown()
                                }
                                DropdownMenu(
                                        expanded = unitExpanded,
                                        onDismissRequest = { unitExpanded = false }
                                ) {
                                    HydrationUnit.entries.forEach { unit ->
                                        SelectableDropdownMenuItem(
                                                selected = unit == selectedUnit,
                                                onClick = {
                                                    selectedUnit = unit
                                                    unitExpanded = false
                                                },
                                                text = { Text(stringResource(unit.displayName)) },
                                                selectedLeadingIcon = { IconCheck() },
                                        )
                                    }
                                }
                            }
                    )
                }
            },
            confirmButton = {
                Button(
                        enabled = isValid,
                        onClick = {
                            if (quantity != null) {
                                val time = initialTime ?: Instant.now()
                                viewModel.logHydration(Liters(quantity * selectedUnit.toLiters), time)
                                onDismiss()
                            }
                        }
                ) { Text(stringResource(UiR.string.save)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.cancel)) } }
    )
}

/** Type representing loggable. */
sealed class Loggable {
    abstract val id: String
    abstract val name: String

    /** Immutable value describing recipe wrapper; carries recipe. */
    data class RecipeWrapper(val recipe: Recipe) : Loggable() {
        override val id = recipe.id
        override val name = recipe.name
    }

    /** Immutable value describing ingredient wrapper; carries ingredient. */
    data class IngredientWrapper(val ingredient: Ingredient) : Loggable() {
        override val id = ingredient.id
        override val name = ingredient.displayName
    }
}

