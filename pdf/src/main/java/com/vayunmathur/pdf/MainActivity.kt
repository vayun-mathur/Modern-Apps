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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.center
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toOffset
import androidx.core.util.forEach
import androidx.pdf.EditablePdfDocument
import androidx.pdf.PdfDocument
import androidx.pdf.PdfPasswordException
import androidx.pdf.PdfPoint
import androidx.pdf.PdfRect
import androidx.pdf.SandboxedPdfLoader
import androidx.pdf.compose.PdfViewer
import androidx.pdf.compose.PdfViewerState
import androidx.pdf.models.FormEditInfo
import androidx.pdf.models.FormWidgetInfo
import androidx.pdf.models.FormWidgetInfo.Companion.WIDGET_TYPE_CHECKBOX
import androidx.pdf.view.Highlight
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.IconShare
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import kotlin.collections.forEach

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
            var pdfDocument by remember { mutableStateOf<EditablePdfDocument?>(null) }
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
                        pdfDocument = pdfLoader.openDocument(data!!, password) as EditablePdfDocument
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
fun PdfViewerScreen(pdfDocument: EditablePdfDocument) {
    val pdfState = remember { PdfViewerState() }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var showSearchBar by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf(emptyList<PdfRect>()) }
    var searchIndex by remember(searchResults) { mutableIntStateOf(0) }
    var searchText by remember { mutableStateOf("") }

    var formWidgets by remember {mutableStateOf(listOf<Pair<Int, FormWidgetInfo>>())}
    val formStrings = remember { mutableStateMapOf<Pair<Int, Int>, String>() }

    LaunchedEffect(pdfDocument.uri) {
        coroutineScope.launch {
            val allWidgets = mutableListOf<Pair<Int, FormWidgetInfo>>()
            for(i in 0 until pdfDocument.pageCount) {
                allWidgets += pdfDocument.getFormWidgetInfos(i).map { i to it }
            }
            formWidgets = allWidgets
            formStrings.clear()
        }
    }

    DisposableEffect(Unit) {
        val listener = object : PdfDocument.OnPdfContentInvalidatedListener {
            override fun onPdfContentInvalidated(
                pageNumber: Int,
                dirtyAreas: List<android.graphics.Rect>
            ) {
                coroutineScope.launch {
                    formWidgets =
                        formWidgets.filter { it.first != pageNumber } + pdfDocument.getFormWidgetInfos(
                            pageNumber
                        ).map { pageNumber to it }
                }
            }
        }
        pdfDocument.addOnPdfContentInvalidatedListener(Executors.newSingleThreadExecutor(), listener)
        onDispose {
            pdfDocument.removeOnPdfContentInvalidatedListener(listener)
        }
    }

    BackHandler(showSearchBar) {
        showSearchBar = false
        searchResults = emptyList()
    }


    LaunchedEffect(pdfDocument.uri) {
        coroutineScope.launch {
            delay(500)
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
        coroutineScope.launch {
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

    var changesMade by remember { mutableStateOf(false) }

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
                    IconButton({ showSearchBar = true }) { IconSearch() }
                    IconButton({
                        val intent = Intent(Intent.ACTION_SEND)
                        intent.type = "application/pdf"
                        intent.putExtra(Intent.EXTRA_STREAM, pdfDocument.uri)
                        context.startActivity(intent)
                    }) {
                        IconShare()
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
            Column {
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
                if(changesMade) {
                    FloatingActionButton({
                        changesMade = false
                        coroutineScope.launch {
                            context.contentResolver.openFileDescriptor(pdfDocument.uri, "wt")?.use { pfd ->
                                pdfDocument.createWriteHandle().writeTo(pfd)
                            }
                        }
                    }) {
                        IconSave()
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(Modifier.padding(innerPadding)) {
            Box(Modifier.fillMaxSize()) {
                PdfViewer(pdfDocument, pdfState, Modifier.onGloballyPositioned { coordinates ->
                    center = coordinates.size.center.toOffset()
                }, isFormFillingEnabled = true) { uri ->
                    val intent = Intent(Intent.ACTION_VIEW, uri)
                    context.startActivity(intent)
                    true
                }
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val density = LocalDensity.current.density

                    val viewportWidth = constraints.maxWidth.toFloat()
                    val viewportHeight = constraints.maxHeight.toFloat()
                    val viewportRect = Rect(0f, 0f, viewportWidth, viewportHeight)

                    formWidgets.forEach { widgetInfo ->
                        // 1. Memoize the calculation so it only updates when the scroll/offset changes
                        val rectByOffset by remember(widgetInfo, pdfState.firstVisiblePageOffset) {
                            derivedStateOf {
                                val topLeft = (pdfState.pdfPointToVisibleOffset(
                                    PdfPoint(
                                        widgetInfo.first,
                                        widgetInfo.second.widgetRect.left.toFloat(),
                                        widgetInfo.second.widgetRect.top.toFloat()
                                    )
                                ) ?: Offset.Zero)
                                val bottomRight = (pdfState.pdfPointToVisibleOffset(
                                    PdfPoint(
                                        widgetInfo.first,
                                        widgetInfo.second.widgetRect.right.toFloat(),
                                        widgetInfo.second.widgetRect.bottom.toFloat()
                                    )
                                ) ?: Offset.Zero)
                                Rect(topLeft, bottomRight)
                            }
                        }

                        if (!viewportRect.overlaps(rectByOffset)) {
                            return@forEach
                        }

                        Box(
                            Modifier
                                .graphicsLayer {
                                    // 2. Use graphicsLayer to bypass layout phase
                                    translationX = rectByOffset.left
                                    translationY = rectByOffset.top
                                }
                                .size(
                                    (rectByOffset.width/density).dp,
                                    (rectByOffset.height/density).dp
                                )
                                .background(Color.Red.copy(alpha = 0.5f)) // Use alpha to see PDF underneath
                        ) {
                            if(widgetInfo.second.widgetType == WIDGET_TYPE_CHECKBOX) {
                                Box(Modifier.clickable{
                                    coroutineScope.launch {
                                        pdfDocument.applyEdit(
                                            FormEditInfo.createClick(
                                                widgetInfo.second.widgetIndex,
                                                PdfPoint(widgetInfo.first, widgetInfo.second.widgetRect.exactCenterX(), widgetInfo.second.widgetRect.exactCenterY())
                                            )
                                        )
                                        changesMade = true
                                    }
                                }.fillMaxSize())
                            } else if(widgetInfo.second.widgetType == FormWidgetInfo.WIDGET_TYPE_TEXTFIELD) {
                                BasicTextField(
                                    value = widgetInfo.second.textValue ?: "",
                                    onValueChange = {
                                        if(it.length > widgetInfo.second.maxLength && widgetInfo.second.maxLength > 0) return@BasicTextField
                                        coroutineScope.launch {
                                            pdfDocument.applyEdit(
                                                FormEditInfo.createSetText(
                                                    widgetInfo.first,
                                                    widgetInfo.second.widgetIndex,
                                                    it
                                                )
                                            )
                                            changesMade = true
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .align(Alignment.Center),
                                    // 1. Make text invisible but keep layout metrics
                                    textStyle = TextStyle.Default.copy(
                                        color = Color.Transparent,
                                        fontSize = widgetInfo.second.fontSize.sp * pdfState.zoom / density
                                    ),
                                    // 2. Ensure the cursor remains visible
                                    cursorBrush = SolidColor(Color.Black),
                                    decorationBox = { innerTextField ->
                                        Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.CenterStart // Match PDF alignment
                                        ) {
                                            innerTextField()
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
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
