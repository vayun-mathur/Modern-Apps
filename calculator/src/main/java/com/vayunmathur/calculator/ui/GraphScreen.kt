package com.vayunmathur.calculator.ui
import androidx.compose.ui.res.stringResource
import com.vayunmathur.calculator.R
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.calculator.domain.AngleMode
import com.vayunmathur.calculator.platform.CalculatorViewModel
import com.vayunmathur.calculator.domain.Expression
import com.vayunmathur.calculator.domain.FeatureKind
import com.vayunmathur.calculator.domain.GraphAnalysis
import com.vayunmathur.calculator.platform.GraphActions
import com.vayunmathur.calculator.platform.GraphFunction
import com.vayunmathur.calculator.platform.GraphUiState
import com.vayunmathur.calculator.domain.GraphPoint
import com.vayunmathur.calculator.domain.formatResult
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.AssistChip
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconKeyboardArrowDown
import com.vayunmathur.library.ui.IconKeyboardArrowUp
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.ToggleButton
import com.vayunmathur.library.ui.VerticalDivider
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.isCompactHeight
import com.vayunmathur.library.ui.isExpandedWidth
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * The graph screen, with no dependency on the ViewModel so it can be rendered from a
 * `@Preview` — see `src/screenshotTest`, which is where the store listing images come from.
 */
@Composable
fun GraphScreen(state: GraphUiState, actions: GraphActions) {
    AppScaffold(
        title = {},
        actions = {
            AssistChip(
                onClick = { actions.toggleAngleMode() },
                label = { Text(if (state.angleMode == AngleMode.DEGREES) "DEG" else "RAD") },
                modifier = Modifier.padding(end = 12.dp),
            )
        },
    ) { padding ->
        // Sibling tools, never list-detail: side-by-side on wide or short windows.
        if (isExpandedWidth() || isCompactHeight()) {
            Row(Modifier.fillMaxSize().padding(padding)) {
                GraphCanvas(state, actions, Modifier.weight(1.4f).fillMaxSize())
                VerticalDivider()
                Box(Modifier.weight(1f).fillMaxSize()) { FunctionEditors(state, actions) }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(padding)) {
                GraphCanvas(state, actions, Modifier.fillMaxWidth().weight(1f))
                HorizontalDivider()
                FunctionEditors(state, actions)
            }
        }
    }
}
