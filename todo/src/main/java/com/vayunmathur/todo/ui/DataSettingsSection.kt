package com.vayunmathur.todo.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.SettingsRow
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.rememberMessenger
import com.vayunmathur.todo.R
import com.vayunmathur.todo.domain.format.ExportFormat
import com.vayunmathur.todo.domain.format.ImportedTask
import com.vayunmathur.todo.platform.TodoViewModel
import kotlinx.coroutines.launch
import com.vayunmathur.library.ui.R as UiR

/** Backup/restore plus import (todo.txt, CSV, iCalendar — detected) and export in each format. */
@Composable
fun DataSettingsSection(viewModel: TodoViewModel) {
    val messenger = rememberMessenger()
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<List<ImportedTask>?>(null) }
    val exported = stringResource(R.string.export_done)
    val nothing = stringResource(R.string.import_nothing)

    val launchers = ExportFormat.entries.associateWith { format ->
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(format.mimeType)) { uri ->
            uri?.let { viewModel.export(it, format) { messenger.show(exported) } }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val tasks = viewModel.readImport(uri)
            if (tasks.isEmpty()) messenger.show(nothing) else preview = tasks
        }
    }

    Row(Modifier.padding(horizontal = 16.dp)) { TodoBackupButtons() }
    SettingsRow(
        title = stringResource(R.string.import_tasks),
        supportingText = stringResource(R.string.import_summary),
        onClick = { importLauncher.launch(arrayOf("text/*", "application/octet-stream")) },
    )
    ExportFormat.entries.forEach { format ->
        SettingsRow(
            title = stringResource(exportLabel(format)),
            onClick = { launchers.getValue(format).launch("tasks.${format.extension}") },
        )
    }

    preview?.let { tasks ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text(pluralStringResource(R.plurals.import_confirm, tasks.size, tasks.size)) },
            text = { Text(tasks.take(PREVIEW).joinToString("\n") { "• " + it.title } + if (tasks.size > PREVIEW) "\n…" else "") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.import(tasks) { n -> messenger.show(resources.getQuantityString(R.plurals.imported, n, n)) }
                    preview = null
                }) { Text(stringResource(R.string.import_action)) }
            },
            dismissButton = { TextButton(onClick = { preview = null }) { Text(stringResource(UiR.string.cancel)) } },
        )
    }
}

private fun exportLabel(format: ExportFormat) = when (format) {
    ExportFormat.TODO_TXT -> R.string.export_todo_txt
    ExportFormat.CSV -> R.string.export_csv
    ExportFormat.ICALENDAR -> R.string.export_ics
}

private const val PREVIEW = 8
