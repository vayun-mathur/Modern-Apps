package com.vayunmathur.office.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.office.R

import com.vayunmathur.office.platform.save
@Composable
fun HeaderFooterDialog(
    initialHeader: String,
    initialFooter: String,
    onSave: (header: String, footer: String) -> Unit,
    onDismiss: () -> Unit) {
    var header by remember { mutableStateOf(initialHeader) }
    var footer by remember { mutableStateOf(initialFooter) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.header_footer_1)) },
        text = {
            Column {
                TextField(
                    value = header,
                    onValueChange = { header = it },
                    label = { Text(stringResource(R.string.header)) },
                    modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = footer,
                    onValueChange = { footer = it },
                    label = { Text(stringResource(R.string.footer)) },
                    modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onSave(
            header,
            footer); onDismiss() }) { Text(stringResource(UiR.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.cancel)) } })
}
