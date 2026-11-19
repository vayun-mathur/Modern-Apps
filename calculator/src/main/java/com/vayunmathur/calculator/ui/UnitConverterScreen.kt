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

/**
 * The units tab: a plain from/to converter with no equations. The twenty-odd categories are picked
 * from a single collapsed dropdown, which keeps the converter itself the focus of the screen.
 * Stateless so it can be rendered from a `@Preview` — see `src/screenshotTest`.
 */
@Composable
fun UnitConverterScreen(state: UnitConverterUiState, actions: UnitConverterActions) {
    AppScaffold(
        title = stringResource(R.string.units),
        alignment = AppBarAlignment.Center,
    ) { padding ->
        val category = state.categories.getOrNull(state.selectedCategoryIndex)
        // Sibling tool, never list-detail: a single converter form, letterboxed
        // on expanded windows so it keeps a readable measure.
        DesktopMaxWidthContainer(Modifier.padding(padding)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            CategoryDropdown(
                categories = state.categories,
                selectedIndex = state.selectedCategoryIndex,
                onSelect = actions::selectCategory,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.xs),
            )
            if (category != null) {
                if (state.isCurrencyCategory && category.units.isEmpty()) {
                    CurrencyStatus(
                        loading = state.currencyLoading,
                        error = state.currencyError,
                        onRetry = actions::retryCurrency,
                    )
                } else {
                    Column(
                        Modifier.fillMaxWidth().padding(Spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(Spacing.md),
                    ) {
                        OutlinedTextField(
                            value = state.inputText,
                            onValueChange = actions::setConverterInput,
                            label = { Text(stringResource(R.string.converter_value)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(verticalAlignment = Alignment.Bottom) {
                            LabeledUnitDropdown(
                                label = stringResource(R.string.converter_from),
                                units = category.units,
                                selectedToken = state.fromToken,
                                onSelect = actions::setFrom,
                                modifier = Modifier.weight(1f),
                            )
                            FilledTonalIconButton(
                                onClick = actions::swapUnits,
                                modifier = Modifier.padding(horizontal = Spacing.sm),
                            ) { IconSwapLanguages() }
                            LabeledUnitDropdown(
                                label = stringResource(R.string.converter_to),
                                units = category.units,
                                selectedToken = state.toToken,
                                onSelect = actions::setTo,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        val toSymbol = category.units.firstOrNull { it.token == state.toToken }?.symbol.orEmpty()
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(Spacing.lg)) {
                                Text(
                                    stringResource(R.string.converter_result),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 14.sp,
                                )
                                Text(
                                    if (state.outputText.isEmpty()) "—" else "${state.outputText} $toSymbol",
                                    fontSize = 28.sp,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
        }
    }
}
