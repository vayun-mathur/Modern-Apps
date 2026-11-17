package com.vayunmathur.office.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TextField
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfPageSetup
import com.vayunmathur.office.platform.OfficeViewModel
import com.vayunmathur.office.platform.setPageSetup

import com.vayunmathur.office.R
/** Page-setup dialog (split from OfficeDocumentOverlays.kt). */

@Composable
internal fun PageSetupOverlays(
    state: DocumentOverlayState,
    document: OdfDocument,
    viewModel: OfficeViewModel,
) {
    if (!state.showPageSetup) return
    val cur = (document as? OdfDocument.TextDocument)?.pageSetup ?: OdfPageSetup()
    var landscape by remember(state.showPageSetup) { mutableStateOf(cur.isLandscape) }
    var paper by remember(state.showPageSetup) {
        val wcm = minOf(cur.widthPx, cur.heightPx) / 37.795f
        mutableStateOf(PAPERS.minByOrNull { kotlin.math.abs(it.wCm - wcm) }?.name ?: "A4")
    }
    var marginCm by remember(state.showPageSetup) { mutableFloatStateOf(cur.marginLeftPx / 37.795f) }
    AlertDialog(
        onDismissRequest = { state.showPageSetup = false },
        title = { Text(stringResource(R.string.page_setup_1)) },
        text = {
            Column {
                PaperSizeRow(paper, { paper = it })
                OrientationRow(landscape, { landscape = it })
                MarginsRow(marginCm, { marginCm = it })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val p = PAPERS.first { it.name == paper }
                val wPx = (if (landscape) p.hCm else p.wCm) * 37.795f
                val hPx = (if (landscape) p.wCm else p.hCm) * 37.795f
                val m = marginCm * 37.795f
                viewModel.setPageSetup(OdfPageSetup(wPx, hPx, m, m, m, m))
                state.showPageSetup = false
            }) { Text(stringResource(UiR.string.apply)) }
        },
        dismissButton =
            { TextButton(onClick = { state.showPageSetup = false }) { Text(stringResource(UiR.string.cancel)) } })
}

/** Paper sizes. */
private data class Paper(val name: String, val wCm: Float, val hCm: Float)

private val PAPERS = listOf(Paper("A4", 21f, 29.7f), Paper("Letter", 21.59f, 27.94f), Paper("Legal", 21.59f, 35.56f))

/** Paper-size selector row. */
@Composable
private fun PaperSizeRow(paper: String, onSelect: (String) -> Unit) {
    Text(
        stringResource(R.string.paper_size),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold)
    Row {
        PAPERS.forEach { p ->
            TextButton(onClick = { onSelect(p.name) }) {
                Text(if (paper == p.name) "● ${p.name}" else p.name)
            }
        }
    }
}

/** Orientation selector row. */
@Composable
private fun OrientationRow(landscape: Boolean, onSelect: (Boolean) -> Unit) {
    Text(
        stringResource(R.string.orientation),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold)
    Row {
        TextButton(onClick = { onSelect(false) }) {
            Text(
                if (!landscape) {
                    stringResource(R.string.portrait)
                } else {
                    stringResource(R.string.portrait_1)
                },
            )
        }
        TextButton(onClick = { onSelect(true) }) {
            Text(
                if (landscape) {
                    stringResource(R.string.landscape)
                } else {
                    stringResource(R.string.landscape_1)
                },
            )
        }
    }
}

/** Margins selector row. */
@Composable
private fun MarginsRow(marginCm: Float, onSelect: (Float) -> Unit) {
    Text(
        stringResource(R.string.margins),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold)
    Row {
        TextButton(onClick = { onSelect(MARGIN_NARROW_CM) }) {
            Text(
                if (marginCm < MARGIN_NARROW_MAX) {
                    stringResource(R.string.narrow)
                } else {
                    stringResource(R.string.narrow_1)
                },
            )
        }
        TextButton(onClick = { onSelect(MARGIN_NORMAL_CM) }) {
            Text(
                if (marginCm in MARGIN_NARROW_MAX..MARGIN_WIDE_MIN) {
                    stringResource(R.string.normal_1)
                } else {
                    stringResource(R.string.normal)
                },
            )
        }
        TextButton(onClick = { onSelect(MARGIN_WIDE_CM) }) {
            Text(
                if (marginCm > MARGIN_WIDE_MIN) {
                    stringResource(R.string.wide)
                } else {
                    stringResource(R.string.wide_1)
                },
            )
        }
    }
}
