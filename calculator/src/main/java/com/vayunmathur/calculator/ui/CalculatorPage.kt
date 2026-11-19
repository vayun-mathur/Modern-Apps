package com.vayunmathur.calculator.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.calculator.platform.CalculatorActions
import com.vayunmathur.calculator.platform.CalculatorUiState
import com.vayunmathur.calculator.platform.CalculatorViewModel
import com.vayunmathur.library.ui.AppBarAlignment
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconRuler
import com.vayunmathur.library.ui.VerticalDivider
import com.vayunmathur.library.ui.isCompactHeight
import com.vayunmathur.library.ui.isExpandedWidth

/** Binds [CalculatorViewModel] to the stateless [CalculatorScreen]. */
@Composable
fun CalculatorPage(viewModel: CalculatorViewModel) {
    CalculatorScreen(state = viewModel.calculatorUiState, actions = viewModel)
}

