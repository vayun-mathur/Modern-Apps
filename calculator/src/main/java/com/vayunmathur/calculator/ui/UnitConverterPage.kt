package com.vayunmathur.calculator.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import com.vayunmathur.calculator.R
import com.vayunmathur.calculator.platform.CalculatorViewModel
import com.vayunmathur.calculator.domain.UnitCategory
import com.vayunmathur.calculator.platform.UnitConverterActions
import com.vayunmathur.calculator.platform.UnitConverterUiState
import com.vayunmathur.calculator.domain.UnitDef
import com.vayunmathur.library.ui.AppBarAlignment
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.DesktopMaxWidthContainer
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.FilledTonalIconButton
import com.vayunmathur.library.ui.IconArrowDropDown
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.IconSwapLanguages
import com.vayunmathur.library.ui.LoadingIndicator
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.SelectableDropdownMenuItem
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.ToggleButton

/** Binds [CalculatorViewModel] to the stateless [UnitConverterScreen]. */
@Composable
fun UnitConverterPage(viewModel: CalculatorViewModel) {
    UnitConverterScreen(state = viewModel.unitConverterUiState, actions = viewModel)
}


/**
 * The category selector, built from [OutlinedButton] + [DropdownMenu] rather than the library's
 * `ExposedDropdownMenu` wrapper, which currently recurses into itself.
 */
@Composable
internal fun CategoryDropdown(
    categories: List<UnitCategory>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(
                categories.getOrNull(selectedIndex)?.name.orEmpty(),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Start,
                maxLines = 1,
            )
            IconArrowDropDown()
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            categories.forEachIndexed { index, cat ->
                SelectableDropdownMenuItem(
                    selected = index == selectedIndex,
                    onClick = {
                        onSelect(index)
                        expanded = false
                    },
                    text = { Text(cat.name) },
                    selectedLeadingIcon = { IconCheck() },
                )
            }
        }
    }
}

/**
 * The Currency tab's placeholder while it has no units yet: a spinner during the fetch, or an
 * error with a retry button if it failed.
 */
@Composable
internal fun CurrencyStatus(loading: Boolean, error: String?, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        if (loading) {
            LoadingIndicator()
            Text(
                stringResource(R.string.currency_loading),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                error ?: stringResource(R.string.currency_unavailable),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onRetry) { Text(stringResource(R.string.currency_retry)) }
        }
    }
}

@Composable
internal fun LabeledUnitDropdown(
    label: String,
    units: List<UnitDef>,
    selectedToken: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        UnitDropdown(units, selectedToken, onSelect, Modifier.fillMaxWidth().padding(top = 4.dp))
    }
}

/**
 * A unit selector built from [OutlinedButton] + [DropdownMenu] rather than the library's
 * `ExposedDropdownMenu` wrapper, which currently recurses into itself.
 */
@Composable
private fun UnitDropdown(
    units: List<UnitDef>,
    selectedToken: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = units.firstOrNull { it.token == selectedToken }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(
                selected?.symbol.orEmpty(),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Start,
                maxLines = 1,
            )
            IconArrowDropDown()
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            units.forEach { unit ->
                SelectableDropdownMenuItem(
                    selected = unit.token == selectedToken,
                    onClick = {
                        onSelect(unit.token)
                        expanded = false
                    },
                    text = { Text("${unit.symbol} — ${unit.name}") },
                    selectedLeadingIcon = { IconCheck() },
                )
            }
        }
    }
}
