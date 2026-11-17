package com.vayunmathur.office.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.office.platform.OfficeViewModel
import com.vayunmathur.office.platform.acceptAllChanges
import com.vayunmathur.office.platform.acceptChange

import com.vayunmathur.office.platform.rejectAllChanges
import com.vayunmathur.office.platform.rejectChange
import com.vayunmathur.office.R
/** Tracked-changes dialog (split from OfficeDocumentOverlays.kt). */

@Composable
internal fun ChangesOverlays(
    state: DocumentOverlayState,
    document: OdfDocument,
    viewModel: OfficeViewModel,
) {
    if (!state.showChanges) return
val td = document as? OdfDocument.TextDocument
val changes = td?.changes ?: emptyList()
AlertDialog(onDismissRequest = { state.showChanges = false }, title = { Text(stringResource(
    R.string.tracked_changes,
    changes.size)) },
    text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (changes.isEmpty()) Text(stringResource(R.string.no_tracked_changes_in_this_document))
            changes.forEach { ch ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(stringResource(
                        R.string.tracked_change_author_date,
                        ch.type.replaceFirstChar { it.uppercase() },
                        ch.author ?: stringResource(UiR.string.unknown),
                        ch.date?.let { " · ${it.take(SHARED_DATE_PREFIX_LENGTH)}" } ?: ""),
                        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Row {
                        TextButton(onClick = { viewModel.acceptChange(ch.id) }) {
                            Text(stringResource(R.string.accept))
                        }
                        TextButton(onClick = { viewModel.rejectChange(ch.id) }) {
                            Text(stringResource(R.string.reject))
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    },
    confirmButton = {
        Row {
            if (changes.isNotEmpty()) {
                TextButton(onClick = { viewModel.acceptAllChanges() }) {
                    Text(stringResource(R.string.accept_all))
                }
                TextButton(onClick = { viewModel.rejectAllChanges() }) {
                    Text(stringResource(R.string.reject_all))
                }
            }
            TextButton(onClick = { state.showChanges = false }) { Text(stringResource(R.string.close_search)) }
        }
    })
}
