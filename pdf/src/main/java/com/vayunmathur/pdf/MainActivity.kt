package com.vayunmathur.pdf

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.center
import androidx.compose.ui.unit.toOffset
import androidx.core.util.forEach
import androidx.pdf.PdfDocument
import androidx.pdf.PdfPasswordException
import androidx.pdf.PdfPoint
import androidx.pdf.PdfRect
import androidx.pdf.SandboxedPdfLoader
import androidx.pdf.compose.PdfViewer
import androidx.pdf.compose.PdfViewerState
import androidx.pdf.view.Highlight
import com.vayunmathur.library.ui.DynamicTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val intent: Intent = intent
        val data: Uri? = intent.data

        val pdfLoader = SandboxedPdfLoader(application)

        setContent {
            var data by rememberSaveable { mutableStateOf(data) }
            var password: String? by rememberSaveable { mutableStateOf(null) }
            var pdfDocument by remember { mutableStateOf<PdfDocument?>(null) }
            var showPasswordDialog by remember { mutableStateOf(false) }
            var passwordError by remember { mutableStateOf<String?>(null) }

            val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                uri?.let {
                    data = it
                } ?: finish()
            }

            LaunchedEffect(Unit) {
                if(data == null) {
                    filePickerLauncher.launch(arrayOf("application/pdf"))
                }
            }

            LaunchedEffect(data, password) {
                if(data != null) {
                    delay(1000)
                    try {
                        pdfDocument = pdfLoader.openDocument(data!!, password)
                    } catch(_: PdfPasswordException) {
                        if(password != null) {
                            passwordError = "Incorrect password. Please try again."
                        }
                        showPasswordDialog = true
                    }
                }
            }

            DynamicTheme {
                pdfDocument?.let {
                    PdfViewerScreen(it)
                } ?: Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))

                if (showPasswordDialog) {
                    PasswordDialog(
                        errorMessage = passwordError,
                        onPasswordEntered = {
                            password = it
                            showPasswordDialog = false
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(pdfDocument: PdfDocument) {
    val pdfState = remember { PdfViewerState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showSearchBar by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf(emptyList<PdfRect>()) }
    var searchIndex by remember(searchResults) { mutableIntStateOf(0) }
    var searchText by remember { mutableStateOf("") }

    BackHandler(showSearchBar) {
        showSearchBar = false
        searchResults = emptyList()
    }

    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(pdfDocument.uri) {
        coroutineScope.launch {
            delay(500)
            println(pdfDocument.uri)
            val restored = PdfStateStore.restore(context, pdfDocument.uri)
            if (restored != null) {
                restored(pdfState)
            }
        }
    }

    var center by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(Unit) {
        while(true) {
            delay(2000)
            PdfStateStore.save(context, pdfDocument.uri, center, pdfState)
        }
    }

    fun search() {
        scope.launch {
            val results = pdfDocument.searchDocument(searchText, 0 until pdfDocument.pageCount)
            val resultsFinal = mutableListOf<PdfRect>()
            results.forEach { page, result ->
                resultsFinal.addAll(result.mapNotNull {
                    it.bounds.firstOrNull()?.let { rect ->
                        PdfRect(page, rect)
                    }
                })
            }
            searchResults = resultsFinal
        }
    }

    LaunchedEffect(searchResults, searchIndex) {
        pdfState.setHighlights(
            searchResults.mapIndexed { idx, it ->
                Highlight(it, if(idx == searchIndex) 0xFFFFA500.toInt() else Color.Yellow.toArgb())
            }
        )
        if (searchResults.isNotEmpty()) {
            pdfState.scrollToPosition(searchResults[searchIndex].let {
                PdfPoint(it.pageNum, it.left, it.top)
            })
        }
    }

    val focusRequestor = remember { FocusRequester() }
    LaunchedEffect(showSearchBar) {
        if (showSearchBar) {
            focusRequestor.requestFocus()
            search()
        } else {
            searchResults = emptyList()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        topBar = {
            TopAppBar({ Text("PDF Viewer") }, actions = {
                if (!showSearchBar) {
                    IconButton({
                        showSearchBar = true
                    }) {
                        Icon(painterResource(R.drawable.search_24px), null)
                    }
                    IconButton({
                        val intent = Intent(Intent.ACTION_SEND)
                        intent.type = "application/pdf"
                        intent.putExtra(Intent.EXTRA_STREAM, pdfDocument.uri)
                        context.startActivity(intent)
                    }) {
                        Icon(painterResource(R.drawable.share_24px), null)
                    }
                } else {
                    if (searchResults.isNotEmpty()) {
                        Text("${searchIndex + 1} of ${searchResults.size}   ")
                    }
                }
            })
        },
        bottomBar = {
            if (showSearchBar) {
                BottomAppBar {
                    OutlinedTextField(
                        searchText,
                        { searchText = it; search() },
                        Modifier.fillMaxWidth().focusRequester(focusRequestor),
                        label = { Text("Find") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.None),
                    )
                }
            }
        },
        floatingActionButton = {
            if (showSearchBar) {
                Column {
                    SmallFloatingActionButton({
                        if (searchIndex > 0)
                            searchIndex--
                    }) {
                        Icon(painterResource(R.drawable.keyboard_arrow_up_24px), null)
                    }
                    SmallFloatingActionButton({
                        if (searchIndex < searchResults.size - 1)
                            searchIndex++
                    }) {
                        Icon(painterResource(R.drawable.keyboard_arrow_down_24px), null)
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(Modifier.padding(innerPadding)) {
            PdfViewer(pdfDocument, pdfState, Modifier.onGloballyPositioned { coordinates ->
                center = coordinates.size.center.toOffset()
            }) { uri ->
                val intent = Intent(Intent.ACTION_VIEW, uri)
                context.startActivity(intent)
                true
            }
        }
    }
}

@Composable
fun PasswordDialog(
    onPasswordEntered: (String) -> Unit,
    errorMessage: String? = null
) {
    var password by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { /* Prevent dismissing by clicking outside */ },
        title = { Text("Password Required") },
        text = {
            Column {
                Text("This PDF is password protected. Please enter the password.")
                if (errorMessage != null) {
                    Text(errorMessage, color = MaterialTheme.colorScheme.error)
                }
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    isError = errorMessage != null
                )
            }
        },
        confirmButton = {
            Button(onClick = { onPasswordEntered(password) }) {
                Text("OK")
            }
        },
        dismissButton = null
    )
}
