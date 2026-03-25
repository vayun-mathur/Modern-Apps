package com.vayunmathur.notes.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.vayunmathur.library.ui.IconUpload
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.ui.ListPageR
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.notes.Route
import com.vayunmathur.notes.data.Note

fun getFileName(context: Context, uri: Uri): String? {
    var result: String? = null
    if (uri.scheme == "content") {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                // Column name for the display name
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    result = it.getString(nameIndex)
                }
            }
        }
    }

    // Fallback for file:// URIs or if the provider doesn't give a name
    if (result == null) {
        result = uri.path
        val cut = result?.lastIndexOf('/') ?: -1
        if (cut != -1) {
            result = result?.substring(cut + 1)
        }
    }
    return result
}

@Composable
fun NotesListPage(backStack: NavBackStack<Route>, viewModel: DatabaseViewModel) {
    val context = LocalContext.current
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri: Uri? ->
            uri?.let { uri ->
                // Handle the selected file URI (e.g., read text and save to DB)
                val content = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                if (content != null) {
                    viewModel.upsertAsync(Note(0, getFileName(context, uri) ?: "Untitled Note", content))
                }
            }
        }
    )

    ListPageR<Note, Route, Route.Note>(backStack, viewModel, "Notes", {
        Text(it.title)
    }, {
        Text(it.content.substringBefore('\n').take(40))
    }, { Route.Note(it) }, { Route.Note(0) }, searchEnabled = true, otherActions = {
        IconButton({
            filePickerLauncher.launch(arrayOf("text/plain", "text/markdown"))
        }) {
            IconUpload()
        }
    })
}