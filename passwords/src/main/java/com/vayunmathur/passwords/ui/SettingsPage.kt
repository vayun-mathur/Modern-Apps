package com.vayunmathur.passwords.ui

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.github.doyaaaaaken.kotlincsv.client.CsvReader
import com.github.doyaaaaaken.kotlincsv.dsl.context.CsvReaderContext
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.passwords.Password
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(backStack: androidx.navigation3.runtime.NavBackStack<com.vayunmathur.passwords.Route>, viewModel: DatabaseViewModel) {
    val context = LocalContext.current
    var importing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    // Side-effect to handle selected URI: we observe the last picked Uri via a remembered state holder
    var pickedUri by remember { mutableStateOf<Uri?>(null) }

    // Recreate a launcher that sets pickedUri so we can process in LaunchedEffect
    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        pickedUri = uri
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Settings") }, navigationIcon = {
            IconNavigation(backStack)
        })
    }) { paddingValues ->
        Column(Modifier
            .padding(paddingValues)
            .fillMaxSize()
            .padding(16.dp), verticalArrangement = Arrangement.Top) {

            Text("Importing a CSV will bring plaintext credentials into this app. Please delete the export after importing.")
            Spacer(Modifier.height(16.dp))

            Button(onClick = {
                // Open document types for CSV/plain text
                pickLauncher.launch(arrayOf("text/csv", "text/plain", "application/octet-stream", "text/comma-separated-values"))
            }, enabled = !importing) {
                Text("Import from Bitwarden (CSV)")
            }

            Spacer(Modifier.height(16.dp))

            if (importing) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                }
            }

            message?.let {
                Spacer(Modifier.height(8.dp))
                Text(it)
            }
        }
    }

    // Observe pickedUri and process
    LaunchedEffect(pickedUri) {
        val uri = pickedUri ?: return@LaunchedEffect
        importing = true
        message = null
        try {
            // take persistable permission if available
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}

            val cr = context.contentResolver
            val result = importBitwardenCsvFromUri(cr, uri, context, viewModel)
            message = "Imported ${result.inserted} rows, skipped ${result.skipped} rows"
        } catch (e: Exception) {
            message = "Import failed: ${e.message}"
        } finally {
            importing = false
            pickedUri = null
        }
    }
}

private data class ImportResult(val inserted: Int, val skipped: Int)

private suspend fun importBitwardenCsvFromUri(contentResolver: ContentResolver, uri: Uri, context: Context, viewModel: DatabaseViewModel): ImportResult {
    return kotlinx.coroutines.withContext(Dispatchers.IO) {
        val inputStream = contentResolver.openInputStream(uri) ?: throw Exception("Unable to open selected file")
        val csvReader = CsvReader(CsvReaderContext())
        val rows = csvReader.readAll(inputStream)
        if (rows.isEmpty()) return@withContext ImportResult(0, 0)

        // First row is header - map column indices
        val header = rows.first().map { it.trim().lowercase() }
        val nameIdx = header.indexOf("name")
        val loginUsernameIdx = header.indexOf("login_username").let { if (it >= 0) it else header.indexOf("username") }
        val loginPasswordIdx = header.indexOf("login_password").let { if (it >= 0) it else header.indexOf("password") }
        val loginUriIdx = header.indexOf("login_uri").let { if (it >= 0) it else header.indexOf("uri") }
        val loginTotpIdx = header.indexOf("login_totp").let { if (it >= 0) it else header.indexOf("totp") }
        val notesIdx = header.indexOf("notes")

        var inserted = 0
        var skipped = 0

        // Process each data row
        val dataRows = rows.drop(1)
        for (row in dataRows) {
            try {
                val name = if (nameIdx >= 0 && nameIdx < row.size) row[nameIdx] else ""
                val username = if (loginUsernameIdx >= 0 && loginUsernameIdx < row.size) row[loginUsernameIdx] else ""
                val password = if (loginPasswordIdx >= 0 && loginPasswordIdx < row.size) row[loginPasswordIdx] else ""
                val uriField = if (loginUriIdx >= 0 && loginUriIdx < row.size) row[loginUriIdx] else ""
                val totp = if (loginTotpIdx >= 0 && loginTotpIdx < row.size) row[loginTotpIdx] else null

                val websites = uriField.split(';', '\n', '\r').mapNotNull { it.trim().takeIf { it.isNotEmpty() } }

                val pass = Password(name = name, userId = username, password = password, totpSecret = totp, websites = websites)
                viewModel.upsert(pass)
                inserted++
            } catch (e: Exception) {
                skipped++
            }
        }

        ImportResult(inserted, skipped)
    }
}
