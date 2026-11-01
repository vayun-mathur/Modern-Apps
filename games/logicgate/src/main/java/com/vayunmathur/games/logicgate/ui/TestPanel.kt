package com.vayunmathur.games.logicgate.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.logicgate.R
import com.vayunmathur.games.logicgate.domain.model.Pin
import com.vayunmathur.games.logicgate.domain.sim.CaseResult
import com.vayunmathur.games.logicgate.domain.sim.VerifyProblem
import com.vayunmathur.games.logicgate.platform.GameUiState
import com.vayunmathur.games.logicgate.platform.LogicActions
import com.vayunmathur.games.logicgate.domain.model.formatValue
import com.vayunmathur.library.ui.ExpandVisibility
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconKeyboardArrowDown
import com.vayunmathur.library.ui.IconKeyboardArrowLeft
import com.vayunmathur.library.ui.IconKeyboardArrowRight
import com.vayunmathur.library.ui.IconKeyboardArrowUp
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton

/**
 * Test results under the board. Collapsed it is one status line; expanded it lists every row with
 * inputs, wanted and actual outputs. Tapping a row probes it, putting that row's values on the
 * wires; sequential levels step tick by tick with ◀ ▶.
 */
@Composable
fun TestPanel(state: GameUiState, actions: LogicActions, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val verify = state.verify
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val (text, color) = when {
                    verify == null -> stringResource(R.string.lg_checking) to MaterialTheme.colorScheme.onSurfaceVariant
                    verify.solved -> stringResource(R.string.lg_tests_pass, verify.passed, verify.total) to MaterialTheme.colorScheme.primary
                    else -> stringResource(
                        R.string.lg_tests_fail, verify.passed, verify.total, verify.firstFailure?.title.orEmpty(),
                    ) to MaterialTheme.colorScheme.error
                }
                Text(
                    text,
                    color = color,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(stringResource(R.string.lg_cost, state.cost), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(Spacing.sm))
                StarRating(state.stars, size = 16.dp)
                if (expanded) IconKeyboardArrowDown() else IconKeyboardArrowUp()
            }
            ExpandVisibility(expanded) {
                Column(Modifier.padding(horizontal = Spacing.lg).padding(bottom = Spacing.sm)) {
                    Notes(state)
                    ProbeControls(state, actions)
                    if (verify != null) CaseList(state, verify.cases, actions)
                }
            }
        }
    }
}

@Composable
private fun Notes(state: GameUiState) {
    val verify = state.verify ?: return
    if (verify.oscillating.isNotEmpty()) {
        Text(stringResource(R.string.lg_oscillating), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    if (verify.problem == VerifyProblem.NO_RAM) {
        Text(stringResource(R.string.lg_no_ram), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ProbeControls(state: GameUiState, actions: LogicActions) {
    val total = state.verify?.total ?: 0
    val probe = state.probeCase
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (probe == null) {
            val hint = stringResource(R.string.lg_probe_hint)
            val live = if (state.level.inputs.isEmpty()) "" else " " + stringResource(R.string.lg_live_hint)
            Text(
                hint + live,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = Spacing.sm),
            )
            return@Row
        }
        IconButton(onClick = { actions.probe(probe - 1) }, enabled = probe > 0) { IconKeyboardArrowLeft() }
        val title = state.verify?.cases?.getOrNull(probe)?.title.orEmpty()
        Text(
            if (state.level.isSequential) stringResource(R.string.lg_tick, probe + 1, title) else title,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        IconButton(onClick = { actions.probe(probe + 1) }, enabled = probe + 1 < total) { IconKeyboardArrowRight() }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { actions.probe(null) }) { Text(stringResource(R.string.lg_back_to_live)) }
    }
}

@Composable
private fun CaseList(state: GameUiState, cases: List<CaseResult>, actions: LogicActions) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.probeCase) { state.probeCase?.let { listState.animateScrollToItem(it) } }
    CaseRow(
        title = stringResource(R.string.lg_col_test),
        actual = stringResource(R.string.lg_col_got),
        background = Color.Transparent,
        actualColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    LazyColumn(Modifier.heightIn(max = LIST_MAX_HEIGHT), state = listState) {
        itemsIndexed(cases) { i, c ->
            val background = when {
                state.probeCase == i -> MaterialTheme.colorScheme.primaryContainer
                !c.ok -> MaterialTheme.colorScheme.error.copy(alpha = 0.18f)
                else -> Color.Transparent
            }
            CaseRow(
                title = c.title,
                actual = c.actual?.let { format(it, state.level.outputs) } ?: "–",
                background = background,
                actualColor = if (c.ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                modifier = Modifier.clickable { actions.probe(if (state.probeCase == i) null else i) },
            )
        }
    }
}

private fun format(values: List<Int?>, pins: List<Pin>): String =
    values.mapIndexed { k, v -> v?.let { formatValue(it, pins.getOrNull(k)?.width ?: 1) } ?: "–" }.joinToString(" ")

@Composable
private fun CaseRow(
    title: String,
    actual: String,
    background: Color,
    actualColor: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(background)
            .padding(vertical = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        val style = MaterialTheme.typography.bodySmall
        Text(title, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(TITLE_WEIGHT))
        Text(
            actual,
            style = style,
            color = actualColor,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

private val LIST_MAX_HEIGHT = 200.dp
private const val TITLE_WEIGHT = 3f
