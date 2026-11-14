package com.vayunmathur.office.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconArrowDropDown
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.LocalContentColor
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.office.util.OfficeViewModel
import androidx.compose.ui.res.stringResource
import com.vayunmathur.office.R

/** What the shared bottom bar is currently formatting. (Phase 2) */
sealed interface FormatTarget {
    /** Singleton holding none state and operations. */
    data object None : FormatTarget
    /** Immutable value describing text run; carries runStart, runEnd, selStart, selEnd. */
    data class TextRun(val runStart: Int, val runEnd: Int, val selStart: Int, val selEnd: Int) : FormatTarget
    /** Immutable value describing cell; carries sheet, row, col. */
    data class Cell(val sheet: Int, val row: Int, val col: Int) : FormatTarget
    /** Immutable value describing element; carries slide, element. */
    data class Element(val slide: Int, val element: Int) : FormatTarget
}

/** Which insert actions are available for the current document type. (Phase 2) */
data class DocCaps(
    val insertImage: Boolean = false,
    val insertShape: Boolean = false,
    val insertChart: Boolean = false,
    val insertTable: Boolean = false
)

/** Kinds of shape kind. */
enum class ShapeKind { RECT, ELLIPSE, LINE }

/** Callbacks the shared bottom bar may invoke. */
data class BottomBarActions(
    val onTextColor: () -> Unit = {},
    val onCellTextColor: () -> Unit = {},
    val onCellBgColor: () -> Unit = {},
    val onSlideTextColor: () -> Unit = {},
    val onSlideFill: () -> Unit = {},
    val onSlideStroke: () -> Unit = {},
    val onFontSize: () -> Unit = {},
    val onInsertImage: () -> Unit = {},
    val onInsertShape: (ShapeKind) -> Unit = {},
    val onInsertChart: () -> Unit = {},
    val onInsertTable: () -> Unit = {},
    val onDeleteElement: () -> Unit = {},
    val onCellBorder: () -> Unit = {},
    val onCellComment: () -> Unit = {},
    val onCellResize: () -> Unit = {},
    val onSlideNotes: () -> Unit = {},
    val onSlideBackground: () -> Unit = {},
    val onSlideTransition: () -> Unit = {}
)

@Composable
internal fun FmtIcon(active: Boolean, enabled: Boolean, icon: @Composable () -> Unit, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(40.dp)) {
        CompositionLocalProvider(
            LocalContentColor provides if (active && enabled) MaterialTheme.colorScheme.primary else LocalContentColor.current
        ) {
            icon()
        }
    }
}

/**
 * Single shared format + insert bar hosted in the Scaffold bottom bar for all document types.
 * Renders a type-appropriate formatting branch plus a unified Insert menu. (Phase 2)
 */
@Composable
fun OfficeBottomBar(
    document: OdfDocument,
    target: FormatTarget,
    caps: DocCaps,
    viewModel: OfficeViewModel,
    actions: BottomBarActions,
    activeTableBlock: Int = -1,
    activeTableRow: Int = -1,
    activeTableCol: Int = -1
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).navigationBarsPadding().padding(
                horizontal = 4.dp,
                vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (document) {
                is OdfDocument.TextDocument -> TextFormatControls(
                    document,
                    target as? FormatTarget.TextRun,
                    viewModel,
                    activeTableBlock,
                    activeTableRow,
                    activeTableCol,
                    actions)
                is OdfDocument.Spreadsheet -> CellFormatControls(target as? FormatTarget.Cell, viewModel, actions)
                is OdfDocument.Presentation -> ElementFormatControls(
                    target as? FormatTarget.Element,
                    viewModel,
                    actions)
                else -> {}
            }
            InsertControl(caps, actions)
        }
    }
}

@Composable
private fun InsertControl(caps: DocCaps, actions: BottomBarActions) {
    if (!(caps.insertImage || caps.insertShape || caps.insertChart || caps.insertTable)) return
    var menu by remember { mutableStateOf(false) }
    var shapeMenu by remember { mutableStateOf(false) }
    Box {
        FmtIcon(false, true, { IconAdd() }) { menu = true }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (caps.insertImage) DropdownMenuItem(
                text = { Text(stringResource(R.string.image_1)) },
                onClick = { menu = false; actions.onInsertImage() })
            if (caps.insertShape) DropdownMenuItem(
                text = { Text(stringResource(R.string.shape)) },
                trailingIcon = { IconArrowDropDown() },
                onClick = { shapeMenu = true })
            if (caps.insertChart) DropdownMenuItem(
                text = { Text(stringResource(R.string.chart_1)) },
                onClick = { menu = false; actions.onInsertChart() })
            if (caps.insertTable) DropdownMenuItem(
                text = { Text(stringResource(R.string.table_1)) },
                onClick = { menu = false; actions.onInsertTable() })
        }
        DropdownMenu(expanded = shapeMenu, onDismissRequest = { shapeMenu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.rectangle)) },
                onClick = { shapeMenu = false; menu = false; actions.onInsertShape(ShapeKind.RECT) })
            DropdownMenuItem(
                text = { Text(stringResource(R.string.ellipse)) },
                onClick = { shapeMenu = false; menu = false; actions.onInsertShape(ShapeKind.ELLIPSE) })
            DropdownMenuItem(
                text = { Text(stringResource(R.string.line)) },
                onClick = { shapeMenu = false; menu = false; actions.onInsertShape(ShapeKind.LINE) })
        }
    }
}
