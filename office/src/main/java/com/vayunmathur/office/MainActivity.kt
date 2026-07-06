package com.vayunmathur.office

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import com.vayunmathur.library.util.BottomBarItem
import com.vayunmathur.library.util.BottomNavBar
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.serialization.Serializable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Card
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.zIndex
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.Typography
import com.vayunmathur.office.odf.*
import com.vayunmathur.library.ui.odf.*
import com.vayunmathur.office.ui.*
import com.vayunmathur.office.util.OfficeViewModel
import kotlinx.coroutines.launch

@Composable
private fun OfficeLightTheme(content: @Composable () -> Unit) {
    // Light scheme — used only for the rendered document (the "paper"), which stays light in dark mode.
    val colorScheme = dynamicLightColorScheme(LocalContext.current)
    MaterialTheme(colorScheme = colorScheme, typography = Typography(), content = content)
}

@Composable
private fun OfficeAppTheme(content: @Composable () -> Unit) {
    // The app chrome (menus, toolbars, home) is dark; the document content re-wraps in the light theme.
    val colorScheme = dynamicDarkColorScheme(LocalContext.current)
    MaterialTheme(colorScheme = colorScheme, typography = Typography(), content = content)
}

/** Top-level navigation routes for the Office app (shared nav framework). */
@Serializable
sealed interface OfficeRoute : NavKey {
    @Serializable data object Offline : OfficeRoute
    @Serializable data object Online : OfficeRoute
    /** Editing a local/offline document (optionally identified by its source uri). */
    @Serializable data class OfflineEditor(val uri: String? = null) : OfficeRoute
    /** Editing a cloud-synced document, identified by its document id. */
    @Serializable data class OnlineEditor(val docId: String) : OfficeRoute
}

class MainActivity : ComponentActivity() {
    private val viewModel: OfficeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        viewModel.loadSettings(this)

        val intentUri: Uri? = intent.data

        setContent {
            val startedWithIntent = intentUri != null
            var documentUri by rememberSaveable { mutableStateOf(intentUri) }
            val state by viewModel.state.collectAsState()
            val backStack = rememberNavBackStack<OfficeRoute>(
                if (intentUri != null) OfficeRoute.OfflineEditor(intentUri.toString()) else OfficeRoute.Offline
            )

            LaunchedEffect(Unit) { viewModel.initSync() }

            if (documentUri != null && state is OfficeViewModel.ViewState.Empty) {
                viewModel.loadDocument(documentUri!!, documentUri?.lastPathSegment ?: "document")
            }

            val odfMimeTypes = arrayOf(
                "application/vnd.oasis.opendocument.text",
                "application/vnd.oasis.opendocument.spreadsheet",
                "application/vnd.oasis.opendocument.presentation",
                "application/vnd.oasis.opendocument.graphics",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                "text/csv",
                "text/comma-separated-values",
                "text/tab-separated-values",
                "text/markdown",
                "text/plain",
                "text/xml",
                "application/xml"
            )

            val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                uri?.let {
                    documentUri = it
                    viewModel.loadDocument(it, it.lastPathSegment ?: "document")
                    backStack.add(OfficeRoute.OfflineEditor(it.toString()))
                }
            }

            val pages: List<BottomBarItem<out OfficeRoute>> = listOf(
                BottomBarItem("Offline", OfficeRoute.Offline, com.vayunmathur.library.R.drawable.home_24px),
                BottomBarItem("Online", OfficeRoute.Online, com.vayunmathur.library.R.drawable.outline_file_download_24)
            )

            fun leaveEditor() {
                if (startedWithIntent) finish()
                else {
                    documentUri = null
                    viewModel.clear()
                    if (backStack.backStack.size > 1) backStack.pop()
                }
            }

            OfficeAppTheme {
                val editorContent: @Composable () -> Unit = {
                    when (val s = state) {
                        is OfficeViewModel.ViewState.Loaded -> DocumentScreen(
                            document = s.document, viewModel = viewModel, activity = this@MainActivity,
                            onBack = { leaveEditor() },
                            // When an offline doc is shared it becomes a cloud doc — switch its route.
                            onBecameOnline = { id -> backStack.setLast(OfficeRoute.OnlineEditor(id)) }
                        )
                        is OfficeViewModel.ViewState.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(stringResource(R.string.error_loading), style = MaterialTheme.typography.titleMedium)
                                Text(s.message, Modifier.padding(16.dp))
                                Button(onClick = { leaveEditor() }) { Text(stringResource(R.string.open_document)) }
                            }
                        }
                        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                }
                MainNavigation(
                    backStack,
                    bottomBar = {
                        val cur = backStack.last()
                        if (cur is OfficeRoute.Offline || cur is OfficeRoute.Online) BottomNavBar(backStack, pages, cur)
                    }
                ) {
                    entry<OfficeRoute.Offline> {
                        InitialScreen(
                            viewModel = viewModel,
                            onOpenDocument = { filePickerLauncher.launch(odfMimeTypes) },
                            onNavigateEditor = { backStack.add(OfficeRoute.OfflineEditor()) }
                        )
                    }
                    entry<OfficeRoute.Online> {
                        OnlineTab(viewModel = viewModel, onOpenDoc = { meta ->
                            viewModel.openOnlineDocument(meta)
                            backStack.add(OfficeRoute.OnlineEditor(meta.docId))
                        })
                    }
                    entry<OfficeRoute.OfflineEditor> { editorContent() }
                    entry<OfficeRoute.OnlineEditor> { editorContent() }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InitialScreen(viewModel: OfficeViewModel, onOpenDocument: () -> Unit, onNavigateEditor: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("Office") }) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).background(MaterialTheme.colorScheme.background).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(24.dp))
            Text("Open Document Format Viewer & Editor", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(32.dp))

            Button(onClick = onOpenDocument, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.open_document)) }
            Spacer(Modifier.height(4.dp))
            Text("Opens ODF, Word/Excel/PowerPoint, CSV, TSV, Markdown & text files",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { viewModel.createNewTextDocument(); onNavigateEditor() }, Modifier.weight(1f)) { Text("New Doc") }
                OutlinedButton(onClick = { viewModel.createNewSpreadsheet(); onNavigateEditor() }, Modifier.weight(1f)) { Text("New Sheet") }
                OutlinedButton(onClick = { viewModel.createNewPresentation(); onNavigateEditor() }, Modifier.weight(1f)) { Text("New Slides") }
            }
        }
    }
}

/** Initializes online sync once when the Online tab first appears. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OnlineInit(viewModel: OfficeViewModel) {
    LaunchedEffect(Unit) { viewModel.initSync() }
}

/** Dialog to copy the current document into the online folder and share it with a device id. */
@Composable
fun ShareOnlineDialog(
    deviceId: String,
    isOwner: Boolean,
    isOnline: Boolean,
    initialName: String,
    myRole: String,
    members: List<com.vayunmathur.office.util.OfficeMember>,
    onShare: (String, String, String, (String?) -> Unit) -> Unit,
    onSetRole: (String, String) -> Unit,
    onTransferOwner: (String) -> Unit,
    onRename: (String) -> Unit,
    onComputeCode: (String, (String?) -> Unit) -> Unit,
    onDismiss: () -> Unit
) {
    var recipient by remember { mutableStateOf("") }
    var docName by remember { mutableStateOf(initialName) }
    var addRole by remember { mutableStateOf(com.vayunmathur.office.util.OfficeRoles.EDITOR) }
    var roleMenu by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf<String?>(null) }
    var computing by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var memberMenu by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share online") },
        text = {
            Column {
                if (members.isNotEmpty()) {
                    Text("People with access:", style = MaterialTheme.typography.labelMedium)
                    members.filter { it.role != com.vayunmathur.office.util.OfficeRoles.REVOKED }.forEach { m ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            val label = (if (m.name.isNotBlank()) m.name else m.id.take(8)) + if (m.id == deviceId) " (you)" else ""
                            Text("• $label — ${m.role}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            if (isOwner && m.id != deviceId) {
                                Box {
                                    TextButton(onClick = { memberMenu = m.id }) { Text("Manage") }
                                    DropdownMenu(expanded = memberMenu == m.id, onDismissRequest = { memberMenu = null }) {
                                        if (m.role == com.vayunmathur.office.util.OfficeRoles.EDITOR)
                                            DropdownMenuItem(text = { Text("Make viewer") }, onClick = { memberMenu = null; onSetRole(m.id, com.vayunmathur.office.util.OfficeRoles.VIEWER) })
                                        else
                                            DropdownMenuItem(text = { Text("Make editor") }, onClick = { memberMenu = null; onSetRole(m.id, com.vayunmathur.office.util.OfficeRoles.EDITOR) })
                                        DropdownMenuItem(text = { Text("Make owner") }, onClick = { memberMenu = null; onTransferOwner(m.id) })
                                        DropdownMenuItem(text = { Text("Remove") }, onClick = { memberMenu = null; onSetRole(m.id, com.vayunmathur.office.util.OfficeRoles.REVOKED) })
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                if (!isOwner) {
                    Text("You have $myRole access. Only the owner can change sharing.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Text("Your device id:", style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(deviceId.ifEmpty { "…" }, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { if (deviceId.isNotEmpty()) clipboard.setText(AnnotatedString(deviceId)) }) { Text("Copy") }
                    }
                } else {
                    if (!isOnline) {
                        // Owner names the document before it first goes online.
                        OutlinedTextField(
                            value = docName, onValueChange = { docName = it },
                            label = { Text("Document name") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                    } else {
                        // Already online: owner can rename; the new name is published to all members.
                        OutlinedTextField(
                            value = docName, onValueChange = { docName = it },
                            label = { Text("Document name") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                        TextButton(enabled = docName.isNotBlank() && docName.trim() != initialName, onClick = { onRename(docName.trim()) }) { Text("Rename") }
                        Spacer(Modifier.height(8.dp))
                    }
                    Text("Add someone by device id (copies this document into your online folder, end-to-end encrypted):")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = recipient, onValueChange = { recipient = it; code = null },
                        label = { Text("Recipient device id") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Role:", style = MaterialTheme.typography.bodySmall)
                        Box {
                            TextButton(onClick = { roleMenu = true }) { Text(addRole) }
                            DropdownMenu(expanded = roleMenu, onDismissRequest = { roleMenu = false }) {
                                DropdownMenuItem(text = { Text("editor") }, onClick = { addRole = com.vayunmathur.office.util.OfficeRoles.EDITOR; roleMenu = false })
                                DropdownMenuItem(text = { Text("viewer") }, onClick = { addRole = com.vayunmathur.office.util.OfficeRoles.VIEWER; roleMenu = false })
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Your device id:", style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(deviceId.ifEmpty { "…" }, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { if (deviceId.isNotEmpty()) clipboard.setText(AnnotatedString(deviceId)) }) { Text("Copy") }
                    }
                    TextButton(
                        enabled = recipient.isNotBlank() && !computing,
                        onClick = { computing = true; code = null; onComputeCode(recipient.trim()) { c -> code = c; computing = false } }
                    ) { Text(if (computing) "Computing…" else "Show security code") }
                    code?.let {
                        Text("Compare with the recipient out-of-band — it must match on both devices:", style = MaterialTheme.typography.bodySmall)
                        Text(it, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                    }
                    status?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        },
        confirmButton = {
            if (isOwner) TextButton(
                enabled = recipient.isNotBlank() && (isOnline || docName.isNotBlank()) && !sharing,
                onClick = {
                    sharing = true; status = null
                    onShare(recipient.trim(), addRole, docName.trim()) { err ->
                        sharing = false
                        status = err ?: "Added ✓"
                        if (err == null) recipient = ""
                    }
                }
            ) { Text(if (sharing) "Adding…" else "Add") }
            else TextButton(onClick = onDismiss) { Text("Close") }
        },
        dismissButton = { if (isOwner) TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

/** Lists documents shared by you or with you; tap to pull + open. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlineTab(viewModel: OfficeViewModel, onOpenDoc: (com.vayunmathur.office.util.OfficeDocMeta) -> Unit) {
    OnlineInit(viewModel)
    val docs by viewModel.onlineDocs.collectAsState()
    val deviceId = viewModel.syncDeviceId
    val clipboard = LocalClipboardManager.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Online") },
                actions = {
                    IconButton(onClick = { viewModel.refreshOnline() }) {
                        Icon(painterResource(com.vayunmathur.library.R.drawable.refresh_24px), "Refresh")
                    }
                }
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
            Text("Your device id (share this so others can send you documents):",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(deviceId.ifEmpty { "…" }, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { if (deviceId.isNotEmpty()) clipboard.setText(AnnotatedString(deviceId)) }) { Text("Copy") }
            }
            Spacer(Modifier.height(12.dp))
            if (docs.isEmpty()) {
                Text("No online documents yet. Open a document and use \u201CShare\u201D to copy it here and send it to someone, or ask them to share to your device id, then tap refresh.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(docs) { meta ->
                        Card(Modifier.fillMaxWidth().clickable { onOpenDoc(meta) }) {
                            ListItem(
                                headlineContent = { Text(meta.title) },
                                supportingContent = { Text(if (meta.owner) "Shared by you" else "Shared with you") }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentScreen(document: OdfDocument, viewModel: OfficeViewModel, activity: ComponentActivity, onBack: () -> Unit, onBecameOnline: (String) -> Unit = {}) {
    var showMetadata by remember { mutableStateOf(false) }
    var showShareDialog by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showFontControl by remember { mutableStateOf(false) }
    var fontSizeMultiplier by remember { mutableFloatStateOf(1f) }
    var activeRunStart by remember { mutableIntStateOf(-1) }
    var activeRunEnd by remember { mutableIntStateOf(-1) }
    var activeTableBlock by remember { mutableIntStateOf(-1) }
    var activeTableRow by remember { mutableIntStateOf(-1) }
    var activeTableCol by remember { mutableIntStateOf(-1) }
    var showChartEditor by remember { mutableStateOf(false) }
    var editingChartBlock by remember { mutableIntStateOf(-1) }
    var showUnsavedDialog by remember { mutableStateOf(false) }
    var showReplaceBar by remember { mutableStateOf(false) }
    var replaceText by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var showColorPicker by remember { mutableStateOf(false) }
    var showFontSizePicker by remember { mutableStateOf(false) }
    var showInsertTable by remember { mutableStateOf(false) }
    var showInsertLink by remember { mutableStateOf(false) }
    var showAddBookmark by remember { mutableStateOf(false) }
    var showSpecialChars by remember { mutableStateOf(false) }
    var showFootnote by remember { mutableStateOf(false) }
    var showComment by remember { mutableStateOf(false) }
    var showHeaderFooter by remember { mutableStateOf(false) }
    var matchCase by remember { mutableStateOf(false) }
    var wholeWord by remember { mutableStateOf(false) }
    var showTimer by remember { mutableStateOf(false) }
    var timerSeconds by remember { mutableIntStateOf(0) }
    var selStart by remember { mutableIntStateOf(0) }
    var selEnd by remember { mutableIntStateOf(0) }
    var fileMenu by remember { mutableStateOf(false) }
    var exportMenu by remember { mutableStateOf(false) }
    var insertMenu by remember { mutableStateOf(false) }
    var viewMenu by remember { mutableStateOf(false) }
    // Hoisted selection for the shared bottom bar (Phase 2).
    var activeCell by remember { mutableStateOf<Triple<Int, Int, Int>?>(null) }
    var activeSlide by remember { mutableIntStateOf(0) }
    var activeSlideEl by remember { mutableIntStateOf(-1) }
    var showCellTextColor by remember { mutableStateOf(false) }
    var showCellBgColor by remember { mutableStateOf(false) }
    var showCellBorderColor by remember { mutableStateOf(false) }
    var showSlideTextColor by remember { mutableStateOf(false) }
    var showSlideFillColor by remember { mutableStateOf(false) }
    var showSlideStrokeColor by remember { mutableStateOf(false) }
    var showCellComment by remember { mutableStateOf(false) }
    var showCellResize by remember { mutableStateOf(false) }
    var showSlideNotes by remember { mutableStateOf(false) }
    var showSlideBackground by remember { mutableStateOf(false) }
    var showSlideTransition by remember { mutableStateOf(false) }
    var chartForSlide by remember { mutableStateOf(false) }
    var chartForSheet by remember { mutableStateOf(false) }
    var cropImageBlock by remember { mutableIntStateOf(-1) }
    var cropSlideTarget by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var cropSheetTarget by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    val isEditMode by viewModel.isEditMode.collectAsState()
    val hasUnsavedChanges by viewModel.hasUnsavedChanges.collectAsState()
    val isOnline by viewModel.isOnline.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()
    val canUndo by viewModel.canUndo.collectAsState()
    val canRedo by viewModel.canRedo.collectAsState()
    val autoSaveEnabled by viewModel.autoSaveEnabled.collectAsState()
    val nightMode by viewModel.nightMode.collectAsState()
    var showWordBar by remember { mutableStateOf(false) }
    var showComments by remember { mutableStateOf(false) }
    var showChanges by remember { mutableStateOf(false) }
    var showPageSetup by remember { mutableStateOf(false) }
    var findMatches by remember { mutableStateOf<List<Int>>(emptyList()) }
    var findIndex by remember { mutableIntStateOf(0) }
    val selectionInvalidation by viewModel.selectionInvalidation.collectAsState()

    // A4: reset hoisted selection whenever the document changes shape (undo/redo) so
    // formatting/color/delete never target a stale cell/element.
    LaunchedEffect(selectionInvalidation) {
        activeCell = null
        activeSlideEl = -1
        activeRunStart = -1; activeRunEnd = -1
        activeTableBlock = -1; activeTableRow = -1; activeTableCol = -1
    }

    val isTextDoc = document is OdfDocument.TextDocument
    val isSpreadsheet = document is OdfDocument.Spreadsheet
    val isPresentation = document is OdfDocument.Presentation
    val canEdit = isTextDoc || isSpreadsheet || isPresentation
    val saveAsName = document.title.substringBeforeLast('.').ifBlank { "Untitled" } +
        when { isTextDoc -> ".odt"; isSpreadsheet -> ".ods"; isPresentation -> ".odp"; else -> ".odg" }
    val focusedPara = if (activeRunStart >= 0 && isTextDoc) viewModel.runParagraphIndexAt(activeRunStart, activeRunEnd, selStart) else -1

    val headings = remember(document) { if (document is OdfDocument.TextDocument) extractHeadings(document) else emptyList() }
    val wordCount = remember(document) { if (document is OdfDocument.TextDocument) countWords(document) else 0 }
    val charCount = remember(document) { if (document is OdfDocument.TextDocument) countChars(document) else 0 }
    val readingTime = remember(document) { if (document is OdfDocument.TextDocument) readingTimeMinutes(document) else 0 }
    val bookmarks = remember(document) { if (document is OdfDocument.TextDocument) document.bookmarks else emptyList() }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val context = LocalContext.current

    val saveAsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> uri?.let { viewModel.save(it) } }
    val csvExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let {
            val csv = viewModel.exportCsv()
            context.contentResolver.openOutputStream(it)?.writer()?.use { w -> w.write(csv) }
        }
    }
    val imagePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            try {
                val bytes = context.contentResolver.openInputStream(it)?.use { s -> s.readBytes() } ?: return@let
                val name = it.lastPathSegment?.substringAfterLast('/') ?: "image.png"
                when (document) {
                    is OdfDocument.TextDocument -> if (focusedPara >= 0) viewModel.insertImage(focusedPara, name, bytes)
                    is OdfDocument.Presentation -> viewModel.insertImageIntoSlide(activeSlide, name, bytes)
                    is OdfDocument.Spreadsheet -> viewModel.insertImageIntoSheet(activeCell?.first ?: 0, name, bytes)
                    else -> {}
                }
            } catch (_: Exception) {}
        }
    }
    val flatExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/xml")) { uri ->
        uri?.let {
            val xml = viewModel.exportFlat()
            if (xml.isNotEmpty()) context.contentResolver.openOutputStream(it)?.writer()?.use { w -> w.write(xml) }
        }
    }
    val markdownExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        uri?.let { val t = viewModel.exportMarkdown(); if (t.isNotEmpty()) context.contentResolver.openOutputStream(it)?.writer()?.use { w -> w.write(t) } }
    }
    val txtExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let { val t = viewModel.exportAsPlainText(); if (t.isNotEmpty()) context.contentResolver.openOutputStream(it)?.writer()?.use { w -> w.write(t) } }
    }
    val tsvExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/tab-separated-values")) { uri ->
        uri?.let { val t = viewModel.exportCsv('\t'); if (t.isNotEmpty()) context.contentResolver.openOutputStream(it)?.writer()?.use { w -> w.write(t) } }
    }
    val ooxmlExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri?.let { val bytes = viewModel.exportOoxml(); if (bytes.isNotEmpty()) context.contentResolver.openOutputStream(it)?.use { o -> o.write(bytes) } }
    }
    val htmlExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri ->
        uri?.let { val t = viewModel.exportHtml(); if (t.isNotEmpty()) context.contentResolver.openOutputStream(it)?.writer()?.use { w -> w.write(t) } }
    }
    val rtfExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/rtf")) { uri ->
        uri?.let { val t = viewModel.exportRtf(); if (t.isNotEmpty()) context.contentResolver.openOutputStream(it)?.writer()?.use { w -> w.write(t) } }
    }
    val latexExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-tex")) { uri ->
        uri?.let { val t = viewModel.exportLatex(); if (t.isNotEmpty()) context.contentResolver.openOutputStream(it)?.writer()?.use { w -> w.write(t) } }
    }
    val epubExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/epub+zip")) { uri ->
        uri?.let { val bytes = viewModel.exportEpub(); if (bytes.isNotEmpty()) context.contentResolver.openOutputStream(it)?.use { o -> o.write(bytes) } }
    }
    val pdfExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        uri?.let { val bytes = viewModel.exportPdf(); if (bytes.isNotEmpty()) context.contentResolver.openOutputStream(it)?.use { o -> o.write(bytes) } }
    }
    // Pending non-ODF export, shown behind a data-loss confirmation. (Set to launch on confirm.)
    var exportWarning by remember { mutableStateOf<(() -> Unit)?>(null) }
    // Replace-image picker: invokes the pending replace action with the chosen bytes. (C4)
    var pendingReplace by remember { mutableStateOf<((String, ByteArray) -> Unit)?>(null) }
    val replaceImageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val fn = pendingReplace; pendingReplace = null
        uri?.let {
            try {
                val bytes = context.contentResolver.openInputStream(it)?.use { s -> s.readBytes() } ?: return@let
                val name = it.lastPathSegment?.substringAfterLast('/') ?: "image.png"
                fn?.invoke(name, bytes)
            } catch (_: Exception) {}
        }
    }

    // Presentation timer
    LaunchedEffect(showTimer) {
        if (showTimer) {
            timerSeconds = 0
            while (showTimer) { kotlinx.coroutines.delay(1000); timerSeconds++ }
        }
    }

    // Always intercept back inside a document so it returns to the home screen instead of exiting
    // the app. Online docs sync live (nothing to save); offline docs with edits prompt to save.
    BackHandler(enabled = true) {
        if (!isOnline && hasUnsavedChanges) showUnsavedDialog = true else onBack()
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = isTextDoc && (headings.isNotEmpty() || bookmarks.isNotEmpty()),
        drawerContent = {
            if (isTextDoc) {
                ModalDrawerSheet(Modifier.width(280.dp)) {
                    Text(stringResource(R.string.outline), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
                    HorizontalDivider()
                    if (bookmarks.isNotEmpty()) {
                        Text("Bookmarks", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(16.dp, 8.dp))
                        LazyColumn(modifier = Modifier.height(150.dp)) {
                            items(bookmarks) { bk ->
                                Text("🔖 ${bk.name}", modifier = Modifier.fillMaxWidth()
                                    .clickable { scope.launch { listState.animateScrollToItem(bk.contentIndex); drawerState.close() } }
                                    .padding(16.dp, 8.dp))
                            }
                        }
                        HorizontalDivider()
                    }
                    LazyColumn {
                        items(headings) { heading ->
                            Text(heading.text,
                                style = when (heading.level) { 1 -> MaterialTheme.typography.titleMedium; 2 -> MaterialTheme.typography.titleSmall; else -> MaterialTheme.typography.bodyMedium },
                                fontWeight = if (heading.level <= 2) FontWeight.Bold else null,
                                modifier = Modifier.fillMaxWidth().clickable { scope.launch { listState.animateScrollToItem(heading.contentIndex); drawerState.close() } }
                                    .padding(start = (16 + (heading.level - 1) * 16).dp, top = 12.dp, bottom = 12.dp, end = 16.dp),
                                maxLines = 2)
                        }
                    }
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                Column {
                    TopAppBar(
                        expandedHeight = 56.dp,
                        title = { Text(document.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium) },
                        navigationIcon = {
                            IconButton(onClick = { if (!isOnline && hasUnsavedChanges) showUnsavedDialog = true else onBack() }) {
                                Icon(painterResource(com.vayunmathur.library.R.drawable.arrow_back_24px), contentDescription = "Back")
                            }
                        },
                        actions = {
                            if (canEdit) IconButton(onClick = { showSearch = !showSearch }) { Icon(painterResource(com.vayunmathur.library.R.drawable.outline_search_24), contentDescription = "Search") }
                            IconButton(onClick = { viewModel.undo() }, enabled = canUndo) { Icon(painterResource(com.vayunmathur.library.R.drawable.undo_24px), contentDescription = "Undo") }
                            IconButton(onClick = { viewModel.redo() }, enabled = canRedo) { Icon(painterResource(R.drawable.redo_24px), contentDescription = "Redo") }
                            if (!isOnline) {
                                IconButton(onClick = { if (viewModel.needsSaveAs()) saveAsLauncher.launch(saveAsName) else viewModel.save() }, enabled = hasUnsavedChanges && !isSaving) {
                                    if (isSaving) CircularProgressIndicator(Modifier.size(20.dp)) else Icon(painterResource(com.vayunmathur.library.R.drawable.save_24px), contentDescription = "Save")
                                }
                            }
                        }
                    )
                    // Office-style menu bar
                    Surface(tonalElevation = 2.dp) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp)) {
                            // File
                            Box {
                                TextButton(onClick = { fileMenu = true }) { Text("File") }
                                DropdownMenu(expanded = fileMenu, onDismissRequest = { fileMenu = false }) {
                                    if (!isOnline) {
                                        DropdownMenuItem(text = { Text("Save") }, enabled = hasUnsavedChanges, leadingIcon = { Icon(painterResource(com.vayunmathur.library.R.drawable.save_24px), null) }, onClick = { fileMenu = false; if (viewModel.needsSaveAs()) saveAsLauncher.launch(saveAsName) else viewModel.save() })
                                        DropdownMenuItem(text = { Text("Save As…") }, onClick = { fileMenu = false; saveAsLauncher.launch(saveAsName) })
                                    } else {
                                        DropdownMenuItem(text = { Text("Synced to cloud") }, enabled = false, leadingIcon = { Icon(painterResource(com.vayunmathur.library.R.drawable.save_24px), null) }, onClick = {})
                                    }
                                    DropdownMenuItem(text = { Text("Share online…") }, leadingIcon = { Icon(painterResource(com.vayunmathur.library.R.drawable.share_24px), null) }, onClick = { fileMenu = false; showShareDialog = true })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.print_doc)) }, onClick = { fileMenu = false; printDocument(activity, document) })
                                    viewModel.documentUri?.let { uri ->
                                        DropdownMenuItem(text = { Text(stringResource(R.string.share)) }, leadingIcon = { Icon(painterResource(com.vayunmathur.library.R.drawable.share_24px), null) }, onClick = { fileMenu = false; context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "*/*"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, null)) })
                                    }
                                    DropdownMenuItem(text = { Text("Export ▸") }, leadingIcon = { Icon(painterResource(com.vayunmathur.library.R.drawable.outline_file_download_24), null) }, onClick = { fileMenu = false; exportMenu = true })
                                    HorizontalDivider()
                                    DropdownMenuItem(text = { Text("Settings") }, leadingIcon = { Icon(painterResource(com.vayunmathur.library.R.drawable.settings_24px), null) }, onClick = { fileMenu = false; showSettings = true })
                                }
                                // Export submenu (opened from File ▸ Export)
                                DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }) {
                                    val baseName = document.title.substringBeforeLast('.').ifBlank { "document" }
                                    DropdownMenuItem(text = { Text("Export as Text") }, onClick = { exportMenu = false; val t = viewModel.exportAsPlainText(); if (t.isNotEmpty()) context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, t) }, null)) })
                                    DropdownMenuItem(text = { Text("Flat ODF…") }, onClick = { exportMenu = false; val ext = when { isTextDoc -> ".fodt"; isSpreadsheet -> ".fods"; isPresentation -> ".fodp"; else -> ".fodg" }; flatExportLauncher.launch(document.title.substringBeforeLast('.') + ext) })
                                    if (isTextDoc) {
                                        DropdownMenuItem(text = { Text("Word (.docx)…") }, onClick = { exportMenu = false; exportWarning = { ooxmlExportLauncher.launch("$baseName.docx") } })
                                        DropdownMenuItem(text = { Text("PDF (.pdf)…") }, onClick = { exportMenu = false; exportWarning = { pdfExportLauncher.launch("$baseName.pdf") } })
                                        DropdownMenuItem(text = { Text("HTML (.html)…") }, onClick = { exportMenu = false; exportWarning = { htmlExportLauncher.launch("$baseName.html") } })
                                        DropdownMenuItem(text = { Text("Rich Text (.rtf)…") }, onClick = { exportMenu = false; exportWarning = { rtfExportLauncher.launch("$baseName.rtf") } })
                                        DropdownMenuItem(text = { Text("EPUB (.epub)…") }, onClick = { exportMenu = false; exportWarning = { epubExportLauncher.launch("$baseName.epub") } })
                                        DropdownMenuItem(text = { Text("LaTeX (.tex)…") }, onClick = { exportMenu = false; exportWarning = { latexExportLauncher.launch("$baseName.tex") } })
                                        DropdownMenuItem(text = { Text("Markdown (.md)…") }, onClick = { exportMenu = false; exportWarning = { markdownExportLauncher.launch("$baseName.md") } })
                                        DropdownMenuItem(text = { Text("Text (.txt)…") }, onClick = { exportMenu = false; exportWarning = { txtExportLauncher.launch("$baseName.txt") } })
                                    }
                                    if (isSpreadsheet) {
                                        DropdownMenuItem(text = { Text("Excel (.xlsx)…") }, onClick = { exportMenu = false; exportWarning = { ooxmlExportLauncher.launch("$baseName.xlsx") } })
                                        DropdownMenuItem(text = { Text("CSV…") }, onClick = { exportMenu = false; exportWarning = { csvExportLauncher.launch("$baseName.csv") } })
                                        DropdownMenuItem(text = { Text("TSV…") }, onClick = { exportMenu = false; exportWarning = { tsvExportLauncher.launch("$baseName.tsv") } })
                                    }
                                    if (isPresentation) {
                                        DropdownMenuItem(text = { Text("PowerPoint (.pptx)…") }, onClick = { exportMenu = false; exportWarning = { ooxmlExportLauncher.launch("$baseName.pptx") } })
                                    }
                                }
                            }
                            // Edit menu removed: Search moved to a top-bar icon; paragraph ops live in the bottom bar's ⋮ menu.
                            // Insert
                            if (isTextDoc) Box {
                                TextButton(onClick = { insertMenu = true }) { Text("Insert") }
                                DropdownMenu(expanded = insertMenu, onDismissRequest = { insertMenu = false }) {
                                    DropdownMenuItem(text = { Text("Image…") }, enabled = focusedPara >= 0, onClick = { insertMenu = false; imagePickerLauncher.launch("image/*") })
                                    DropdownMenuItem(text = { Text("Chart") }, enabled = focusedPara >= 0, onClick = { insertMenu = false; editingChartBlock = -1; showChartEditor = true })
                                    DropdownMenuItem(text = { Text("Special character…") }, enabled = activeRunStart >= 0, onClick = { insertMenu = false; showSpecialChars = true })
                                    DropdownMenuItem(text = { Text("Date (field)") }, enabled = activeRunStart >= 0, onClick = { insertMenu = false; if (activeRunStart >= 0) viewModel.insertFieldInRun(activeRunStart, activeRunEnd, selStart, "date", viewModel.fieldDisplayValue("date")) })
                                    DropdownMenuItem(text = { Text("Time (field)") }, enabled = activeRunStart >= 0, onClick = { insertMenu = false; if (activeRunStart >= 0) viewModel.insertFieldInRun(activeRunStart, activeRunEnd, selStart, "time", viewModel.fieldDisplayValue("time")) })
                                    DropdownMenuItem(text = { Text("Page number") }, enabled = activeRunStart >= 0, onClick = { insertMenu = false; if (activeRunStart >= 0) viewModel.insertFieldInRun(activeRunStart, activeRunEnd, selStart, "page-number", viewModel.fieldDisplayValue("page-number")) })
                                    DropdownMenuItem(text = { Text("Page count") }, enabled = activeRunStart >= 0, onClick = { insertMenu = false; if (activeRunStart >= 0) viewModel.insertFieldInRun(activeRunStart, activeRunEnd, selStart, "page-count", viewModel.fieldDisplayValue("page-count")) })
                                    DropdownMenuItem(text = { Text("File name") }, enabled = activeRunStart >= 0, onClick = { insertMenu = false; if (activeRunStart >= 0) viewModel.insertFieldInRun(activeRunStart, activeRunEnd, selStart, "file-name", viewModel.fieldDisplayValue("file-name")) })
                                    DropdownMenuItem(text = { Text("Author") }, enabled = activeRunStart >= 0, onClick = { insertMenu = false; if (activeRunStart >= 0) viewModel.insertFieldInRun(activeRunStart, activeRunEnd, selStart, "author-name", viewModel.fieldDisplayValue("author-name")) })
                                    DropdownMenuItem(text = { Text("Title (field)") }, enabled = activeRunStart >= 0, onClick = { insertMenu = false; if (activeRunStart >= 0) viewModel.insertFieldInRun(activeRunStart, activeRunEnd, selStart, "title", viewModel.fieldDisplayValue("title")) })
                                    DropdownMenuItem(text = { Text("Bookmark") }, enabled = focusedPara >= 0, onClick = { insertMenu = false; showAddBookmark = true })
                                    DropdownMenuItem(text = { Text("Footnote…") }, enabled = focusedPara >= 0, onClick = { insertMenu = false; showFootnote = true })
                                    DropdownMenuItem(text = { Text("Comment…") }, enabled = focusedPara >= 0, onClick = { insertMenu = false; showComment = true })
                                    DropdownMenuItem(text = { Text("Table of contents") }, enabled = focusedPara >= 0, onClick = { insertMenu = false; viewModel.insertTableOfContents(focusedPara) })
                                    DropdownMenuItem(text = { Text("Header & footer…") }, onClick = { insertMenu = false; showHeaderFooter = true })
                                    DropdownMenuItem(text = { Text("Horizontal line") }, enabled = focusedPara >= 0, onClick = { insertMenu = false; viewModel.insertHorizontalLine(focusedPara) })
                                    DropdownMenuItem(text = { Text("Page break") }, enabled = focusedPara >= 0, onClick = { insertMenu = false; viewModel.insertPageBreak(focusedPara) })
                                }
                            }
                            // Format menu removed: font size, clear formatting, and list level/restart moved to the bottom bar's ⋮ menu.
                            // View
                            Box {
                                TextButton(onClick = { viewMenu = true }) { Text("View") }
                                DropdownMenu(expanded = viewMenu, onDismissRequest = { viewMenu = false }) {
                                    if (isTextDoc && (headings.isNotEmpty() || bookmarks.isNotEmpty())) DropdownMenuItem(text = { Text(stringResource(R.string.outline)) }, onClick = { viewMenu = false; scope.launch { drawerState.open() } })
                                    DropdownMenuItem(text = { Text("Zoom text") }, onClick = { viewMenu = false; showFontControl = !showFontControl })
                                    DropdownMenuItem(text = { Text(if (nightMode) "✓ Night reading mode" else "Night reading mode") }, onClick = { viewMenu = false; viewModel.toggleNightMode() })
                                    if (isTextDoc) DropdownMenuItem(text = { Text(if (showWordBar) "✓ Word count bar" else "Word count bar") }, onClick = { viewMenu = false; showWordBar = !showWordBar })
                                    if (isTextDoc) DropdownMenuItem(text = { Text("Comments…") }, onClick = { viewMenu = false; showComments = true })
                                    if (isTextDoc) DropdownMenuItem(text = { Text("Track changes…") }, onClick = { viewMenu = false; showChanges = true })
                                    if (isTextDoc) DropdownMenuItem(text = { Text("Page setup…") }, onClick = { viewMenu = false; showPageSetup = true })
                                    if (isTextDoc && wordCount > 0) DropdownMenuItem(text = { Text("$wordCount words · $charCount chars · ~${readingTime} min") }, enabled = false, onClick = { })
                                    if (isPresentation) DropdownMenuItem(text = { Text("Presentation timer") }, onClick = { viewMenu = false; showTimer = !showTimer })
                                }
                            }
                        }
                    }
                    AnimatedVisibility(visible = showTimer && isPresentation) {
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("⏱ %02d:%02d".format(timerSeconds / 60, timerSeconds % 60), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = { timerSeconds = 0 }) { Text("Reset") }
                                TextButton(onClick = { showTimer = false }) { Text("Stop") }
                            }
                        }
                    }
                    AnimatedVisibility(visible = showSearch) {
                        Column {
                            TextField(value = searchQuery, onValueChange = { searchQuery = it }, placeholder = { Text(stringResource(R.string.search_hint)) }, singleLine = true,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                                colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant),
                                trailingIcon = {
                                    Row {
                                        if (isTextDoc) TextButton(onClick = { showReplaceBar = !showReplaceBar }) { Text(if (showReplaceBar) "Hide" else "Replace") }
                                        IconButton(onClick = { showSearch = false; searchQuery = ""; showReplaceBar = false }) { Icon(painterResource(com.vayunmathur.library.R.drawable.close_24px), contentDescription = "Close search") }
                                    }
                                })
                            if (isTextDoc && searchQuery.isNotEmpty()) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    fun jump(delta: Int) {
                                        val matches = viewModel.findMatchBlocks(searchQuery, matchCase, wholeWord)
                                        findMatches = matches
                                        if (matches.isEmpty()) return
                                        findIndex = ((findIndex + delta) % matches.size + matches.size) % matches.size
                                        scope.launch { listState.animateScrollToItem(matches[findIndex].coerceIn(0, (document as OdfDocument.TextDocument).content.size - 1)) }
                                    }
                                    val total = remember(searchQuery, matchCase, wholeWord, document) { viewModel.findMatchBlocks(searchQuery, matchCase, wholeWord).size }
                                    TextButton(onClick = { jump(-1) }) { Text("◀ Prev") }
                                    TextButton(onClick = { jump(1) }) { Text("Next ▶") }
                                    Text(if (total > 0) "${(findIndex % total) + 1}/$total" else "0/0", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                            AnimatedVisibility(visible = showReplaceBar && searchQuery.isNotEmpty()) {
                                Column {
                                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                        TextField(value = replaceText, onValueChange = { replaceText = it }, placeholder = { Text(stringResource(R.string.replace_hint)) }, singleLine = true, modifier = Modifier.weight(1f),
                                            colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant))
                                        TextButton(onClick = { viewModel.replaceInDocument(searchQuery, replaceText, false, matchCase, wholeWord) }) { Text("One") }
                                        TextButton(onClick = { val n = viewModel.replaceInDocument(searchQuery, replaceText, true, matchCase, wholeWord); if (n > 0) searchQuery = "" }) { Text("All") }
                                    }
                                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                        TextButton(onClick = { matchCase = !matchCase }) { Text(if (matchCase) "☑ Case" else "☐ Case") }
                                        TextButton(onClick = { wholeWord = !wholeWord }) { Text(if (wholeWord) "☑ Word" else "☐ Word") }
                                    }
                                }
                            }
                        }
                    }
                    AnimatedVisibility(visible = showFontControl) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                            Text("A", style = MaterialTheme.typography.bodySmall)
                            Slider(value = fontSizeMultiplier, onValueChange = { fontSizeMultiplier = it }, valueRange = 0.5f..2.0f, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                            Text("A", style = MaterialTheme.typography.headlineSmall)
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = { fontSizeMultiplier = 1f }) { Text(stringResource(R.string.reset)) }
                        }
                    }
                    AnimatedVisibility(visible = showWordBar && isTextDoc) {
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                            Text("$wordCount words · $charCount characters · ~$readingTime min read",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
                        }
                    }
                }
            },
            bottomBar = {
                if (canEdit) {
                    val formatTarget: FormatTarget = when {
                        isTextDoc && activeRunStart >= 0 -> FormatTarget.TextRun(activeRunStart, activeRunEnd, selStart, selEnd)
                        isSpreadsheet && (activeCell?.second ?: -1) >= 0 -> FormatTarget.Cell(activeCell!!.first, activeCell!!.second, activeCell!!.third)
                        isPresentation && activeSlideEl >= 0 -> FormatTarget.Element(activeSlide, activeSlideEl)
                        else -> FormatTarget.None
                    }
                    val caps = when {
                        isTextDoc -> DocCaps(insertImage = true, insertChart = true, insertTable = true)
                        isPresentation -> DocCaps(insertImage = true, insertShape = true, insertChart = true)
                        isSpreadsheet -> DocCaps(insertImage = true, insertShape = true, insertChart = true)
                        else -> DocCaps()
                    }
                    val activeSheet = activeCell?.first ?: 0
                    val actions = BottomBarActions(
                        onTextColor = { showColorPicker = true },
                        onCellTextColor = { showCellTextColor = true },
                        onCellBgColor = { showCellBgColor = true },
                        onSlideTextColor = { showSlideTextColor = true },
                        onSlideFill = { showSlideFillColor = true },
                        onSlideStroke = { showSlideStrokeColor = true },
                        onFontSize = { showFontSizePicker = true },
                        onInsertImage = { imagePickerLauncher.launch("image/*") },
                        onInsertShape = { kind ->
                            when {
                                isPresentation -> viewModel.addShapeToSlide(activeSlide, kind.name.lowercase())
                                isSpreadsheet -> viewModel.addShapeToSheet(activeSheet, kind.name.lowercase())
                            }
                        },
                        onInsertChart = { editingChartBlock = -1; chartForSlide = isPresentation; chartForSheet = isSpreadsheet; showChartEditor = true },
                        onInsertTable = { showInsertTable = true },
                        onDeleteElement = { if (activeSlideEl >= 0) { viewModel.deleteSlideElement(activeSlide, activeSlideEl); activeSlideEl = -1 } },
                        onCellBorder = { showCellBorderColor = true },
                        onCellComment = { showCellComment = true },
                        onCellResize = { showCellResize = true },
                        onSlideNotes = { showSlideNotes = true },
                        onSlideBackground = { showSlideBackground = true },
                        onSlideTransition = { showSlideTransition = true }
                    )
                    OfficeBottomBar(document, formatTarget, caps, viewModel, actions, activeTableBlock, activeTableRow, activeTableCol)
                }
            }
        ) { paddingValues ->
            Box(Modifier.padding(paddingValues)) {
                val presence by viewModel.remotePresence.collectAsState()
                val remoteCarets = presence.mapNotNull { p ->
                    p.caret?.let { com.vayunmathur.library.ui.odf.RemoteCaret(it, 0xFF000000L or (p.id.hashCode().toLong() and 0xFFFFFFL), p.name) }
                }
                if (presence.isNotEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.align(Alignment.TopCenter).zIndex(1f).fillMaxWidth()
                    ) {
                        Text(
                            presence.joinToString(", ") { it.name + (it.loc?.let { l -> " · $l" } ?: "") + if (it.typing) " (typing…)" else "" }
                                .let { "Online: $it" },
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                    }
                }
                OfficeLightTheme {
                  Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                when (document) {
                    is OdfDocument.TextDocument -> TextDocumentView(doc = document, searchQuery = searchQuery, fontSizeMultiplier = fontSizeMultiplier, listState = listState,
                        remoteCarets = remoteCarets,
                        onRunSelectionChange = { rs, re, gs, ge -> activeRunStart = rs; activeRunEnd = re; selStart = gs; selEnd = ge; activeTableBlock = -1; activeTableRow = -1; activeTableCol = -1; viewModel.setLocalCaret(gs) },
                        onRunTextChange = { rs, re, text -> viewModel.updateParagraphRun(rs, re, text) },
                        onRunEnter = { rs, re, gPos -> viewModel.handleListEnter(rs, re, gPos) },
                        onRunBackspace = { rs, re, gPos -> viewModel.handleListBackspace(rs, re, gPos) },
                        onToggleCheckbox = { idx ->
                            val p = (document.content.getOrNull(idx) as? OdfContentBlock.Paragraph)?.paragraph
                            if (p != null) viewModel.setCheckboxChecked(idx, !p.listChecked)
                        },
                        onDeletePrevBlock = { runStart -> viewModel.deleteBlockBefore(runStart) },
                        onCellTextChange = { bi, r, c, text -> viewModel.updateTextTableCell(bi, r, c, text) },
                        onCellFocus = { bi, r, c -> activeTableBlock = bi; activeTableRow = r; activeTableCol = c },
                        onChartClick = { bi -> editingChartBlock = bi; showChartEditor = true },
                        onCropImage = { bi -> cropImageBlock = bi })
                    is OdfDocument.Spreadsheet -> SpreadsheetView(doc = document, searchQuery = searchQuery, fontSizeMultiplier = fontSizeMultiplier, isEditMode = isEditMode,
                        onCellTextChange = { s, r, c, t -> viewModel.updateCellText(s, r, c, t) }, onAddRow = { s, r -> viewModel.addRow(s, r) }, onAddColumn = { s -> viewModel.addColumn(s) },
                        onDeleteRow = { s, r -> viewModel.deleteRow(s, r) }, onDeleteColumn = { s, c -> viewModel.deleteColumn(s, c) },
                        onRenameSheet = { s, n -> viewModel.renameSheet(s, n) }, onAddSheet = { viewModel.addSheet() }, onDeleteSheet = { s -> viewModel.deleteSheet(s) },
                        onCellBold = { s, r, c -> viewModel.setCellBold(s, r, c) }, onCellItalic = { s, r, c -> viewModel.setCellItalic(s, r, c) },
                        onCellColor = { s, r, c, clr -> viewModel.setCellColor(s, r, c, clr) }, onCellBgColor = { s, r, c, clr -> viewModel.setCellBgColor(s, r, c, clr) },
                        onCellAlignment = { s, r, c, a -> viewModel.setCellAlignment(s, r, c, a) },
                        onMergeCells = { s, sr, sc, er, ec -> viewModel.mergeCells(s, sr, sc, er, ec) }, onUnmergeCells = { s, r, c -> viewModel.unmergeCells(s, r, c) },
                        onSort = { s, col, asc -> viewModel.sortRows(s, col, asc) },
                        onCellSelected = { s, r, c -> activeCell = Triple(s, r, c); viewModel.setLocalLocation("Sheet ${s + 1} · ${('A' + c)}${r + 1}") },
                        onFloatingBoundsChange = { s, e, x, y, w, h -> viewModel.setSheetElementBounds(s, e, x, y, w, h) },
                        onFloatingTextChange = { s, e, t -> viewModel.updateSheetElementText(s, e, t) },
                        onFloatingDelete = { s, e -> viewModel.deleteSheetElement(s, e) },
                        onFloatingCrop = { s, e -> cropSheetTarget = s to e },
                        onSetFreeze = { s, r, c -> viewModel.setSheetFreeze(s, r, c) })
                    is OdfDocument.Presentation -> PresentationView(doc = document, isEditMode = isEditMode,
                        onAddSlide = { viewModel.addSlide(it) }, onDeleteSlide = { viewModel.deleteSlide(it) },
                        onDuplicateSlide = { viewModel.duplicateSlide(it) }, onMoveSlideUp = { viewModel.moveSlideUp(it) }, onMoveSlideDown = { viewModel.moveSlideDown(it) },
                        onElementTextChange = { s, e, t -> viewModel.updateSlideElementText(s, e, t) }, onAddTextBox = { viewModel.addTextBoxToSlide(it) },
                        onElementBoundsChange = { s, e, x, y, w, h -> viewModel.setSlideElementBounds(s, e, x, y, w, h) },
                        onDeleteElement = { s, e -> viewModel.deleteSlideElement(s, e) },
                        selectedElement = activeSlideEl,
                        onSlideChange = { activeSlide = it; viewModel.setLocalLocation("Slide ${it + 1}") },
                        onElementSelected = { s, e -> activeSlide = s; activeSlideEl = e },
                        onCropImage = { s, e -> cropSlideTarget = s to e })
                    is OdfDocument.Drawing -> DrawingView(document)
                }
                  }
                }
                // Night reading mode: a view-only dimming scrim (does not modify or save the document). (C4)
                if (nightMode) Box(Modifier.matchParentSize().background(Color(0x660E1116)))
            }
        }
    }

    if (showMetadata) MetadataDialog(metadata = document.metadata, onSave = { m -> viewModel.updateMetadata { m } }, onDismiss = { showMetadata = false })
    LaunchedEffect(Unit) { viewModel.initSync() }
    if (showShareDialog) {
        var members by remember { mutableStateOf<List<com.vayunmathur.office.util.OfficeMember>>(emptyList()) }
        LaunchedEffect(showShareDialog) { viewModel.documentMembers { members = it } }
        ShareOnlineDialog(
            deviceId = viewModel.syncDeviceId,
            isOwner = viewModel.currentDocRole() == com.vayunmathur.office.util.OfficeRoles.OWNER,
            isOnline = isOnline,
            initialName = document.title,
            myRole = viewModel.currentDocRole(),
            members = members,
            onShare = { recipientId, role, name, cb ->
                viewModel.shareCurrentDocument(recipientId, role, name) { err ->
                    if (err == null) {
                        viewModel.documentMembers { members = it } // refresh roster on success
                        viewModel.currentOnlineDocId()?.let { onBecameOnline(it) } // now a cloud doc
                    }
                    cb(err)
                }
            },
            onSetRole = { memberId, role ->
                viewModel.setMemberRole(memberId, role) {
                    viewModel.documentMembers { members = it }
                }
            },
            onRename = { name -> viewModel.renameDocument(name) },
            onTransferOwner = { memberId ->
                viewModel.transferOwnership(memberId) { viewModel.documentMembers { members = it } }
            },
            onComputeCode = { id, cb -> viewModel.securityCodeWith(id, cb) },
            onDismiss = { showShareDialog = false }
        )
    }
    if (showUnsavedDialog) AlertDialog(onDismissRequest = { showUnsavedDialog = false }, title = { Text(stringResource(R.string.unsaved_changes)) },
        text = { Text(stringResource(R.string.unsaved_changes_message)) },
        confirmButton = { TextButton(onClick = { showUnsavedDialog = false; onBack() }) { Text(stringResource(R.string.discard), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { Row { TextButton(onClick = { showUnsavedDialog = false }) { Text(stringResource(R.string.cancel)) }
            TextButton(onClick = { showUnsavedDialog = false; if (viewModel.needsSaveAs()) saveAsLauncher.launch(saveAsName) else viewModel.save() }) { Text(stringResource(R.string.save), fontWeight = FontWeight.Bold) } } })
    exportWarning?.let { action ->
        AlertDialog(onDismissRequest = { exportWarning = null }, title = { Text("Export to non-ODF format") },
            text = { Text("Some formatting and features may be lost when exporting outside the Open Document Format. Continue?") },
            confirmButton = { TextButton(onClick = { exportWarning = null; action() }) { Text("Export", fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = { exportWarning = null }) { Text(stringResource(R.string.cancel)) } })
    }
    if (showSettings) SettingsDialog(autoSave = viewModel.getAutoSaveEnabled(context), autoSaveInterval = viewModel.getAutoSaveInterval(context),
        defaultFontSize = viewModel.getDefaultFontSize(context),
        onSave = { a, i, f -> viewModel.saveSettings(context, a, i, f) }, onDismiss = { showSettings = false })
    if (showColorPicker) ColorPickerDialog("Text Color", onColorSelected = { c -> if (activeRunStart >= 0) viewModel.applyRunSpanStyle(activeRunStart, activeRunEnd, selStart, selEnd) { it.copy(color = c) } }, onDismiss = { showColorPicker = false })
    if (showFontSizePicker) FontSizePickerDialog(onSizeSelected = { sz -> if (activeRunStart >= 0) viewModel.applyRunSpanStyle(activeRunStart, activeRunEnd, selStart, selEnd) { it.copy(fontSize = sz) } }, onDismiss = { showFontSizePicker = false })
    if (showInsertTable) InsertTableDialog(onInsert = { r, c -> viewModel.insertTable(maxOf(0, focusedPara), r, c) }, onDismiss = { showInsertTable = false })
    if (showInsertLink) InsertHyperlinkDialog(onInsert = { t, u -> viewModel.insertHyperlink(maxOf(0, focusedPara), t, u) }, onDismiss = { showInsertLink = false })
    if (showAddBookmark) AddBookmarkDialog(onAdd = { viewModel.addBookmark(it, maxOf(0, focusedPara)) }, onDismiss = { showAddBookmark = false })
    if (showSpecialChars) SpecialCharsDialog(onPick = { ch -> if (activeRunStart >= 0) viewModel.insertTextInRun(activeRunStart, activeRunEnd, selStart, ch) }, onDismiss = { showSpecialChars = false })
    if (showFootnote) FootnoteDialog(onAdd = { body -> if (activeRunStart >= 0) viewModel.insertFootnote(activeRunStart, activeRunEnd, selStart, body) }, onDismiss = { showFootnote = false })
    if (showComment) CommentDialog(onAdd = { author, text -> if (focusedPara >= 0) viewModel.insertComment(focusedPara, author, text) }, onDismiss = { showComment = false })
    if (showComments) {
        val td = document as? OdfDocument.TextDocument
        val comments = remember(document) {
            buildList {
                td?.content?.forEachIndexed { bi, block ->
                    if (block is OdfContentBlock.Paragraph) block.paragraph.spans.forEachIndexed { si, span ->
                        span.annotation?.let { add(Triple(bi, si, it)) }
                    }
                }
            }
        }
        AlertDialog(onDismissRequest = { showComments = false }, title = { Text("Comments (${comments.size})") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (comments.isEmpty()) Text("No comments yet. Use Insert ▸ Comment to add one.")
                    comments.forEach { (bi, si, ann) ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text("${ann.author ?: "Anonymous"}${ann.date?.let { " · $it" } ?: ""}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            Text(ann.paragraphs.joinToString("\n") { p -> p.spans.joinToString("") { it.text } }, style = MaterialTheme.typography.bodySmall)
                            Row {
                                TextButton(onClick = { scope.launch { listState.animateScrollToItem(bi.coerceIn(0, (td?.content?.size ?: 1) - 1)) } }) { Text("Go to") }
                                TextButton(onClick = { viewModel.resolveComment(bi, si) }) { Text("Resolve") }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showComments = false }) { Text("Close") } })
    }
    if (showChanges) {
        val td = document as? OdfDocument.TextDocument
        val changes = td?.changes ?: emptyList()
        AlertDialog(onDismissRequest = { showChanges = false }, title = { Text("Tracked changes (${changes.size})") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (changes.isEmpty()) Text("No tracked changes in this document.")
                    changes.forEach { ch ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text("${ch.type.replaceFirstChar { it.uppercase() }} · ${ch.author ?: "Unknown"}${ch.date?.let { " · ${it.take(10)}" } ?: ""}",
                                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            Row {
                                TextButton(onClick = { viewModel.acceptChange(ch.id) }) { Text("Accept") }
                                TextButton(onClick = { viewModel.rejectChange(ch.id) }) { Text("Reject") }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            },
            confirmButton = {
                Row {
                    if (changes.isNotEmpty()) {
                        TextButton(onClick = { viewModel.acceptAllChanges() }) { Text("Accept all") }
                        TextButton(onClick = { viewModel.rejectAllChanges() }) { Text("Reject all") }
                    }
                    TextButton(onClick = { showChanges = false }) { Text("Close") }
                }
            })
    }
    if (showPageSetup) {
        val cur = (document as? OdfDocument.TextDocument)?.pageSetup ?: OdfPageSetup()
        data class Paper(val name: String, val wCm: Float, val hCm: Float)
        val papers = listOf(Paper("A4", 21f, 29.7f), Paper("Letter", 21.59f, 27.94f), Paper("Legal", 21.59f, 35.56f))
        var landscape by remember(showPageSetup) { mutableStateOf(cur.isLandscape) }
        var paper by remember(showPageSetup) {
            val wcm = minOf(cur.widthPx, cur.heightPx) / 37.795f
            mutableStateOf(papers.minByOrNull { kotlin.math.abs(it.wCm - wcm) }?.name ?: "A4")
        }
        var marginCm by remember(showPageSetup) { mutableStateOf(cur.marginLeftPx / 37.795f) }
        AlertDialog(onDismissRequest = { showPageSetup = false }, title = { Text("Page setup") },
            text = {
                Column {
                    Text("Paper size", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Row { papers.forEach { p -> TextButton(onClick = { paper = p.name }) { Text(if (paper == p.name) "● ${p.name}" else p.name) } } }
                    Text("Orientation", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Row {
                        TextButton(onClick = { landscape = false }) { Text(if (!landscape) "● Portrait" else "Portrait") }
                        TextButton(onClick = { landscape = true }) { Text(if (landscape) "● Landscape" else "Landscape") }
                    }
                    Text("Margins", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Row {
                        TextButton(onClick = { marginCm = 1.27f }) { Text(if (marginCm < 1.5f) "● Narrow" else "Narrow") }
                        TextButton(onClick = { marginCm = 2f }) { Text(if (marginCm in 1.5f..2.3f) "● Normal" else "Normal") }
                        TextButton(onClick = { marginCm = 2.54f }) { Text(if (marginCm > 2.3f) "● Wide" else "Wide") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val p = papers.first { it.name == paper }
                    val wPx = (if (landscape) p.hCm else p.wCm) * 37.795f
                    val hPx = (if (landscape) p.wCm else p.hCm) * 37.795f
                    val m = marginCm * 37.795f
                    viewModel.setPageSetup(OdfPageSetup(wPx, hPx, m, m, m, m))
                    showPageSetup = false
                }) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { showPageSetup = false }) { Text("Cancel") } })
    }
    if (showHeaderFooter) {
        val td = document as? OdfDocument.TextDocument
        HeaderFooterDialog(
            initialHeader = td?.headerParagraphs?.joinToString("\n") { p -> p.spans.joinToString("") { it.text } } ?: "",
            initialFooter = td?.footerParagraphs?.joinToString("\n") { p -> p.spans.joinToString("") { it.text } } ?: "",
            onSave = { h, f -> viewModel.setHeaderText(h); viewModel.setFooterText(f) },
            onDismiss = { showHeaderFooter = false }
        )
    }
    if (showCellTextColor && (activeCell?.second ?: -1) >= 0) {
        val (s, r, c) = activeCell!!
        ColorPickerDialog("Text Color", onColorSelected = { viewModel.setCellColor(s, r, c, it) }, onDismiss = { showCellTextColor = false })
    }
    if (showCellBgColor && (activeCell?.second ?: -1) >= 0) {
        val (s, r, c) = activeCell!!
        ColorPickerDialog("Background Color", onColorSelected = { viewModel.setCellBgColor(s, r, c, it) }, onDismiss = { showCellBgColor = false })
    }
    if (showCellBorderColor && (activeCell?.second ?: -1) >= 0) {
        val (s, r, c) = activeCell!!
        ColorPickerDialog("Border Color", onColorSelected = { viewModel.setCellBorder(s, r, c, it) }, onDismiss = { showCellBorderColor = false })
    }
    if (showSlideTextColor && activeSlideEl >= 0) {
        ColorPickerDialog("Text Color", onColorSelected = { viewModel.setSlideElementColor(activeSlide, activeSlideEl, it) }, onDismiss = { showSlideTextColor = false })
    }
    if (showSlideFillColor && activeSlideEl >= 0) {
        ColorPickerDialog("Fill Color", onColorSelected = { viewModel.setSlideElementFill(activeSlide, activeSlideEl, it) }, onDismiss = { showSlideFillColor = false })
    }
    if (showSlideStrokeColor && activeSlideEl >= 0) {
        ColorPickerDialog("Border Color", onColorSelected = { viewModel.setSlideElementStroke(activeSlide, activeSlideEl, it) }, onDismiss = { showSlideStrokeColor = false })
    }
    if (showCellComment && (activeCell?.second ?: -1) >= 0) {
        val (s, r, c) = activeCell!!
        var text by remember(showCellComment) { mutableStateOf(viewModel.cellCommentText(s, r, c)) }
        AlertDialog(
            onDismissRequest = { showCellComment = false },
            title = { Text("Cell comment") },
            text = { TextField(value = text, onValueChange = { text = it }, placeholder = { Text("Comment") }, modifier = Modifier.height(120.dp)) },
            confirmButton = { TextButton(onClick = { viewModel.setCellComment(s, r, c, "", text); showCellComment = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { showCellComment = false }) { Text("Cancel") } }
        )
    }
    if (showCellResize && (activeCell?.second ?: -1) >= 0) {
        val (s, r, c) = activeCell!!
        var w by remember(showCellResize) { mutableStateOf("") }
        var h by remember(showCellResize) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCellResize = false },
            title = { Text("Row / column size") },
            text = {
                Column {
                    TextField(value = w, onValueChange = { w = it }, label = { Text("Column width (px)") })
                    Spacer(Modifier.height(8.dp))
                    TextField(value = h, onValueChange = { h = it }, label = { Text("Row height (px)") })
                }
            },
            confirmButton = { TextButton(onClick = {
                w.toFloatOrNull()?.let { viewModel.setColumnWidth(s, c, it) }
                h.toFloatOrNull()?.let { viewModel.setRowHeight(s, r, it) }
                showCellResize = false
            }) { Text("Apply") } },
            dismissButton = { TextButton(onClick = { showCellResize = false }) { Text("Cancel") } }
        )
    }
    if (showSlideNotes && isPresentation) {
        var text by remember(showSlideNotes) { mutableStateOf(viewModel.slideNotesText(activeSlide)) }
        AlertDialog(
            onDismissRequest = { showSlideNotes = false },
            title = { Text("Speaker notes") },
            text = { TextField(value = text, onValueChange = { text = it }, placeholder = { Text("Notes for this slide") }, modifier = Modifier.height(160.dp)) },
            confirmButton = { TextButton(onClick = { viewModel.setSlideNotes(activeSlide, text); showSlideNotes = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { showSlideNotes = false }) { Text("Cancel") } }
        )
    }
    if (showSlideBackground && isPresentation) {
        ColorPickerDialog("Slide background", onColorSelected = { viewModel.setSlideBackgroundColor(activeSlide, it) }, onDismiss = { showSlideBackground = false })
    }
    if (showSlideTransition && isPresentation) {
        val types = listOf("none", "fade", "wipe", "dissolve", "push", "cover", "split", "blinds", "checkerboard", "circle", "wheel")
        var type by remember(showSlideTransition) { mutableStateOf((document as? OdfDocument.Presentation)?.slides?.getOrNull(activeSlide)?.transitionType ?: "none") }
        var expanded by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showSlideTransition = false },
            title = { Text("Slide transition") },
            text = {
                Box {
                    TextButton(onClick = { expanded = true }) { Text(type.replaceFirstChar { it.uppercase() }) }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        types.forEach { t -> DropdownMenuItem(text = { Text(t.replaceFirstChar { ch -> ch.uppercase() }) }, onClick = { type = t; expanded = false }) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { viewModel.setSlideTransition(activeSlide, type.takeIf { it != "none" }, "medium"); showSlideTransition = false }) { Text("Apply") } },
            dismissButton = { TextButton(onClick = { showSlideTransition = false }) { Text("Cancel") } }
        )
    }
    if (cropImageBlock >= 0) {
        val img = ((document as? OdfDocument.TextDocument)?.content?.getOrNull(cropImageBlock) as? OdfContentBlock.Image)?.image
        if (img != null) ImageCropDialog(image = img, onApply = { l, t, r, b -> viewModel.setImageCrop(cropImageBlock, l, t, r, b) }, onDismiss = { cropImageBlock = -1 },
            onRotate = { viewModel.rotateTextImage(cropImageBlock, 90f) },
            onReplace = { val bi = cropImageBlock; pendingReplace = { n, b -> viewModel.replaceTextImage(bi, n, b) }; replaceImageLauncher.launch("image/*") })
        else cropImageBlock = -1
    }
    cropSlideTarget?.let { (s, e) ->
        val img = ((document as? OdfDocument.Presentation)?.slides?.getOrNull(s)?.elements?.getOrNull(e) as? OdfSlideElement.Frame)?.frame?.image
        if (img != null) ImageCropDialog(image = img, onApply = { l, t, r, b -> viewModel.setSlideImageCrop(s, e, l, t, r, b) }, onDismiss = { cropSlideTarget = null },
            onRotate = { viewModel.rotateSlideImage(s, e, 90f) },
            onReplace = { pendingReplace = { n, b -> viewModel.replaceSlideImage(s, e, n, b) }; replaceImageLauncher.launch("image/*") })
        else cropSlideTarget = null
    }
    cropSheetTarget?.let { (s, e) ->
        val img = ((document as? OdfDocument.Spreadsheet)?.sheets?.getOrNull(s)?.floating?.getOrNull(e) as? OdfSlideElement.Frame)?.frame?.image
        if (img != null) ImageCropDialog(image = img, onApply = { l, t, r, b -> viewModel.setSheetImageCrop(s, e, l, t, r, b) }, onDismiss = { cropSheetTarget = null },
            onRotate = { viewModel.rotateSheetImage(s, e, 90f) },
            onReplace = { pendingReplace = { n, b -> viewModel.replaceSheetImage(s, e, n, b) }; replaceImageLauncher.launch("image/*") })
        else cropSheetTarget = null
    }
    if (showChartEditor) {
        val existing = if (!chartForSlide && editingChartBlock >= 0) ((document as? OdfDocument.TextDocument)?.content?.getOrNull(editingChartBlock) as? OdfContentBlock.Chart)?.chart else null
        ChartEditorDialog(
            initial = existing,
            onConfirm = { ch ->
                when {
                    chartForSlide -> viewModel.insertChartIntoSlide(activeSlide, ch)
                    chartForSheet -> viewModel.insertChartIntoSheet(activeCell?.first ?: 0, ch)
                    editingChartBlock >= 0 -> viewModel.updateChart(editingChartBlock, ch)
                    else -> if (focusedPara >= 0) viewModel.insertChart(focusedPara, ch)
                }
            },
            onDismiss = { showChartEditor = false; editingChartBlock = -1; chartForSlide = false; chartForSheet = false }
        )
    }
}

@Composable
private fun MetadataDialog(metadata: OdfMetadata, onSave: (OdfMetadata) -> Unit, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf(metadata.title ?: "") }
    var author by remember { mutableStateOf(metadata.author ?: "") }
    var subject by remember { mutableStateOf(metadata.subject ?: "") }
    var description by remember { mutableStateOf(metadata.description ?: "") }
    var keywords by remember { mutableStateOf(metadata.keywords.joinToString(", ")) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.document_info)) },
        text = {
            androidx.compose.foundation.layout.Column(Modifier.verticalScroll(rememberScrollState())) {
                TextField(value = title, onValueChange = { title = it }, label = { Text(stringResource(R.string.meta_title)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                TextField(value = author, onValueChange = { author = it }, label = { Text(stringResource(R.string.meta_author)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                TextField(value = subject, onValueChange = { subject = it }, label = { Text(stringResource(R.string.meta_subject)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                TextField(value = description, onValueChange = { description = it }, label = { Text(stringResource(R.string.meta_description)) }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                TextField(value = keywords, onValueChange = { keywords = it }, label = { Text(stringResource(R.string.meta_keywords)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                metadata.creationDate?.let { MetadataRow(stringResource(R.string.meta_created), it) }
                metadata.modifiedDate?.let { MetadataRow(stringResource(R.string.meta_modified), it) }
                metadata.pageCount?.let { MetadataRow(stringResource(R.string.meta_pages), it.toString()) }
                metadata.wordCount?.let { MetadataRow(stringResource(R.string.meta_words), it.toString()) }
                metadata.fileSize?.let { MetadataRow("File Size", formatFileSize(it)) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(metadata.copy(
                    title = title.ifBlank { null },
                    author = author.ifBlank { null },
                    subject = subject.ifBlank { null },
                    description = description.ifBlank { null },
                    keywords = keywords.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                ))
                onDismiss()
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable private fun MetadataRow(label: String, value: String) {
    Row(Modifier.padding(vertical = 2.dp)) { Text("$label: ", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium); Text(value, style = MaterialTheme.typography.bodyMedium) }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
}

// --- Print ---

private fun printDocument(activity: ComponentActivity, document: OdfDocument) {
    val printManager = activity.getSystemService(android.content.Context.PRINT_SERVICE) as? PrintManager ?: return
    val html = documentToHtml(document)
    val webView = WebView(activity)
    webView.webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView?, url: String?) {
            @Suppress("DEPRECATION")
            val printAdapter = webView.createPrintDocumentAdapter(document.title)
            printManager.print(document.title, printAdapter, PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build())
        }
    }
    webView.loadDataWithBaseURL(null, html, "text/HTML", "UTF-8", null)
}

private fun documentToHtml(document: OdfDocument): String {
    val sb = StringBuilder()
    sb.append("""<!DOCTYPE html><html><head><meta charset="UTF-8"><style>body{font-family:sans-serif;padding:16px}table{border-collapse:collapse;width:100%;margin:8px 0}td,th{border:1px solid #ccc;padding:6px 8px}h1,h2,h3,h4{margin:8px 0}.slide{border:1px solid #ccc;padding:24px;margin:16px 0;background:#f9f9f9}</style></head><body>""")
    when (document) {
        is OdfDocument.TextDocument -> {
            for (block in document.content) when (block) {
                is OdfContentBlock.Paragraph -> {
                    val tag = when (block.paragraph.style) { ParagraphStyle.HEADING1 -> "h1"; ParagraphStyle.HEADING2 -> "h2"; ParagraphStyle.HEADING3 -> "h3"; ParagraphStyle.HEADING4 -> "h4"; ParagraphStyle.LIST_ITEM -> "li"; else -> "p" }
                    sb.append("<$tag>")
                    for (span in block.paragraph.spans) { var s = span.text.replace("&", "&amp;").replace("<", "&lt;"); if (span.bold) s = "<b>$s</b>"; if (span.italic) s = "<i>$s</i>"; if (span.underline) s = "<u>$s</u>"; if (span.strikethrough) s = "<s>$s</s>"; if (span.href != null) s = """<a href="${span.href}">$s</a>"""; sb.append(s) }
                    sb.append("</$tag>")
                }
                is OdfContentBlock.Table -> { sb.append("<table>"); for (row in block.table.rows) { sb.append("<tr>"); for (cell in row.cells) { if (cell.isCovered) continue; sb.append("<td"); if (cell.colSpan > 1) sb.append(""" colspan="${cell.colSpan}""""); if (cell.rowSpan > 1) sb.append(""" rowspan="${cell.rowSpan}""""); sb.append(">"); for (para in cell.paragraphs) for (span in para.spans) sb.append(span.text.replace("&", "&amp;").replace("<", "&lt;")); sb.append("</td>") }; sb.append("</tr>") }; sb.append("</table>") }
                is OdfContentBlock.Image -> sb.append("<p>[Image]</p>")
                is OdfContentBlock.Chart -> sb.append("<p>[Chart]</p>")
                is OdfContentBlock.Formula -> sb.append("<p><i>${OdfMath.parse(block.mathml)?.let { OdfMath.toText(it) }.orEmpty().replace("&", "&amp;").replace("<", "&lt;")}</i></p>")
                is OdfContentBlock.PageBreak -> sb.append("""<hr style="page-break-after:always">""")
                is OdfContentBlock.TableOfContents -> {
                    sb.append("<h2>${block.title.replace("&", "&amp;").replace("<", "&lt;")}</h2>")
                    for (entry in block.entries) sb.append("<p>${entry.spans.joinToString("") { it.text }.replace("&", "&amp;").replace("<", "&lt;")}</p>")
                }
                is OdfContentBlock.SectionStart -> sb.append(if (block.columnCount > 1) """<div style="column-count:${block.columnCount}">""" else "<div>")
                is OdfContentBlock.SectionEnd -> sb.append("</div>")
            }
        }
        is OdfDocument.Spreadsheet -> { for (sheet in document.sheets) { sb.append("<h2>${sheet.name}</h2><table>"); for (row in sheet.rows) { sb.append("<tr>"); for (cell in row.cells) { if (cell.isCovered) continue; sb.append("<td"); if (cell.spannedColumns > 1) sb.append(""" colspan="${cell.spannedColumns}""""); sb.append(">${cell.text.replace("&", "&amp;").replace("<", "&lt;")}</td>") }; sb.append("</tr>") }; sb.append("</table>") } }
        is OdfDocument.Presentation -> { for (slide in document.slides) { sb.append("""<div class="slide"><b>${slide.name}</b>"""); for (el in slide.elements) when (el) { is OdfSlideElement.Frame -> for (p in el.frame.paragraphs) { sb.append("<p>"); for (s in p.spans) sb.append(s.text.replace("&", "&amp;").replace("<", "&lt;")); sb.append("</p>") }; is OdfSlideElement.Shape -> for (p in el.shape.text) { sb.append("<p>"); for (s in p.spans) sb.append(s.text.replace("&", "&amp;").replace("<", "&lt;")); sb.append("</p>") } }; sb.append("</div>") } }
        is OdfDocument.Drawing -> { for (page in document.pages) { sb.append("""<div class="slide"><b>${page.name}</b>"""); for (el in page.elements) when (el) { is OdfSlideElement.Frame -> for (p in el.frame.paragraphs) { sb.append("<p>"); for (s in p.spans) sb.append(s.text.replace("&", "&amp;").replace("<", "&lt;")); sb.append("</p>") }; is OdfSlideElement.Shape -> for (p in el.shape.text) { sb.append("<p>"); for (s in p.spans) sb.append(s.text.replace("&", "&amp;").replace("<", "&lt;")); sb.append("</p>") } }; sb.append("</div>") } }
    }
    sb.append("</body></html>")
    return sb.toString()
}
