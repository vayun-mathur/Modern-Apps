package com.vayunmathur.notes.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.staggeredgrid.items

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconUpload
import com.vayunmathur.library.ui.BackupButtons
import com.vayunmathur.library.util.BiometricDatabaseHelper
import androidx.compose.runtime.remember
import com.vayunmathur.library.ui.ListPageR
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.notes.Route
import com.vayunmathur.notes.data.Note

fun getFileName(context: Context, uri: Uri): String? {
    var result: String? = null
    if (uri.scheme == "content") {
        try {
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
        } catch (e: Exception) {
            Log.e("NotesListPage", "Error querying file name from URI: $uri", e)
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
                try {
                    // Handle the selected file URI (e.g., read text and save to DB)
                    val content = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    if (content != null) {
                        viewModel.upsertAsync(Note(0, getFileName(context, uri) ?: "Untitled Note", content))
                    }
                } catch (e: Exception) {
                    Log.e("NotesListPage", "Error reading file content from URI: $uri", e)
                }
            }
        }
    )


    val allNotes by viewModel.data<Note>().collectAsState(initial = emptyList())
    var searchQuery by remember { mutableStateOf("") }
    val notes = remember(searchQuery, allNotes) {
        if (searchQuery.isBlank()) allNotes else allNotes.filter { it.title.contains(searchQuery, true) || it.content.contains(searchQuery, true) }
    }
    
    Scaffold(
        topBar = {
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                androidx.compose.material3.SearchBar(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    onSearch = {},
                    active = false,
                    onActiveChange = {},
                    placeholder = { Text("Search your notes") },
                    leadingIcon = { Icon(painterResource(com.vayunmathur.library.R.drawable.search_24px), null) },
                    trailingIcon = {
                        IconButton(onClick = { /* Grid/List toggle */ }) {
                            Icon(painterResource(com.vayunmathur.library.R.drawable.grid_view_24px), null)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(32.dp))
                ) {}
            }
        },
        floatingActionButton = {
            androidx.compose.material3.FloatingActionButton(
                onClick = { backStack.add(Route.Note(0)) },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                elevation = androidx.compose.material3.FloatingActionButtonDefaults.elevation(6.dp)
            ) {
                Icon(painterResource(com.vayunmathur.library.R.drawable.add_24px), null)
            }
        },
        bottomBar = {
            androidx.compose.material3.BottomAppBar(
                actions = {
                    IconButton(onClick = { /* Checkbox */ }) { Icon(painterResource(com.vayunmathur.library.R.drawable.check_box_24px), null) }
                    IconButton(onClick = { /* Paint */ }) { Icon(painterResource(com.vayunmathur.library.R.drawable.palette_24px), null) }
                    IconButton(onClick = { /* Mic */ }) { Icon(painterResource(com.vayunmathur.library.R.drawable.mic_24px), null) }
                    IconButton(onClick = { /* Image */ }) { Icon(painterResource(com.vayunmathur.library.R.drawable.image_24px), null) }
                }
            )
        }
    ) { padding ->
        androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid(
            columns = androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells.Fixed(2),
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalItemSpacing = 8.dp
        ) {
            items(notes) { note ->
                androidx.compose.material3.OutlinedCard(
                    modifier = Modifier.fillMaxWidth().clickable { backStack.add(Route.Note(note.id)) },
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        if (note.title.isNotBlank()) {
                            Text(note.title, style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                        }
                        if (note.content.isNotBlank()) {
                            Text(note.content, style = MaterialTheme.typography.bodyMedium, maxLines = 10, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }

}