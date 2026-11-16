package com.vayunmathur.office

import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle
import com.vayunmathur.library.ui.MaActivity
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.DrawerValue
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconDownload
import com.vayunmathur.library.ui.IconHome
import com.vayunmathur.library.ui.IconRedo
import com.vayunmathur.library.ui.IconRefresh
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.IconShare
import com.vayunmathur.library.ui.IconUndo
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.Typography
import com.vayunmathur.library.ui.dynamicDarkColorScheme
import com.vayunmathur.library.ui.dynamicLightColorScheme
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.rememberDrawerState
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.office.R
import com.vayunmathur.office.util.OfficeViewModel
import com.vayunmathur.office.util.clearDocument
import com.vayunmathur.office.util.initSync
import com.vayunmathur.office.util.loadDocument
import com.vayunmathur.office.util.openOnlineDocument
import com.vayunmathur.office.util.requestToJoin
import com.vayunmathur.office.util.runParagraphIndexAt
import kotlinx.serialization.Serializable


// Public so `src/screenshotTest` can wrap a document exactly the way DocumentScreen does;
// without it the listing images would show the paper in the wrong scheme.
@Composable
fun OfficeLightTheme(content: @Composable () -> Unit) {
    // Light scheme — used only for the rendered document (the "paper"), which stays light in dark mode.
    val colorScheme = dynamicLightColorScheme(LocalContext.current)
    MaterialTheme(colorScheme = colorScheme, typography = Typography(), content = content)
}

// Inverts luminance (white paper <-> black) while preserving hue, matching the
// "invert(1) hue-rotate(180deg)" trick used for document dark modes like Google Docs.
private val ErrorPadding = 16.dp
private val DrawerWidth = 280.dp
private val SavingSpinnerSize = 20.dp
private const val TIMER_TICK_MS = 1000L

private val DocumentDarkModeColorMatrix = ColorMatrix(
    floatArrayOf(
        0.574f, -1.430f, -0.144f, 0f, 255f,
        -0.426f, -0.430f, -0.144f, 0f, 255f,
        -0.426f, -1.430f, 0.856f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f,
    ),
)

/**
 * Renders the content into an offscreen layer and paints it back through a
 * luminance-inverting color filter, so the displayed document appears dark
 * without altering the underlying document colors. View-only (#479).
 */
fun Modifier.documentDarkModeInvert(): Modifier = this.drawWithContent {
    val paint = Paint().apply {
        colorFilter = ColorFilter.colorMatrix(DocumentDarkModeColorMatrix)
    }
    drawIntoCanvas { canvas ->
        canvas.saveLayer(Rect(0f, 0f, size.width, size.height), paint)
        drawContent()
        canvas.restore()
    }
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
    /** Offline home tab (sibling route, the start destination). */
    @Serializable data object OfflineHome : OfficeRoute
    /** Online cloud-docs home tab (sibling route). */
    @Serializable data object OnlineHome : OfficeRoute
    /** Editing a local/offline document (optionally identified by its source uri). */
    @Serializable data class OfflineEditor(val uri: String? = null) : OfficeRoute
    /** Editing a cloud-synced document, identified by its document id. */
    @Serializable data class OnlineEditor(val docId: String) : OfficeRoute
}

/** Main Activity: a ma activity implementation for main activity. */
class MainActivity : MaActivity() {
    private val viewModel: OfficeViewModel by viewModels()
    private var intentDocumentUri: Uri? = null

    override fun onCreateBeforeContent() {
        // FIRST_PARTY: api.maproj.org + data.maproj.org -> ISRG+GTS
        NetworkClient.init(this, TrustBundle.FIRST_PARTY)
        viewModel.loadSettings(this)

        // office://join links request access to someone else's doc — handle them here and keep
        // them out of the document-loading path (their scheme isn't content/file).
        val joinLink = parseJoinLink(intent)
        if (joinLink != null) handleJoinLink(joinLink)
        intentDocumentUri = if (joinLink == null) extractDocumentUri(intent) else null
    }

    @Composable
    override fun AppTheme(content: @Composable () -> Unit) {
        OfficeAppTheme(content = content)
    }

    @Composable
    override fun Content() {
        OfficeRoot(intentDocumentUri)
    }

    /** Root content: nav graph over home + editor routes. */
    @Composable
    private fun OfficeRoot(intentUri: Uri?) {
        val startedWithIntent = intentUri != null
        var documentUri by rememberSaveable { mutableStateOf(intentUri) }
        val state by viewModel.state.collectAsState()
        val backStack = rememberNavBackStack<OfficeRoute>(
            if (intentUri != null) OfficeRoute.OfflineEditor(intentUri.toString()) else OfficeRoute.OfflineHome
        )

        LaunchedEffect(Unit) { viewModel.initSync() }

        if (documentUri != null && state is OfficeViewModel.ViewState.Empty) {
            viewModel.loadDocument(documentUri!!, resolveDisplayName(this@MainActivity, documentUri!!))
        }

        val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let {
                persistUriGrant(it)
                documentUri = it
                viewModel.loadDocument(it, resolveDisplayName(this@MainActivity, it))
                backStack.add(OfficeRoute.OfflineEditor(it.toString()))
            }
        }

        fun leaveEditor() {
            if (startedWithIntent) finish()
            else {
                documentUri = null
                viewModel.clearDocument()
                if (backStack.backStack.size > 1) backStack.pop()
            }
        }

        OfficeAppTheme {
            OfficeNavGraph(state, filePickerLauncher, { leaveEditor() }, backStack)
        }
    }

    /**
     * Resolves a human-readable file name (with extension) for an opened uri. Content
     * uris from other apps often expose an opaque document id as their last path
     * segment with no extension; DocumentImporter routes by extension, so query
     * OpenableColumns.DISPLAY_NAME first to keep extension-based routing working for
     * files opened via intents (falling back to the last path segment).
     */
    private fun resolveDisplayName(context: android.content.Context, uri: Uri): String {
        if (uri.scheme == "content") {
            runCatching {
                context.contentResolver.query(
                    uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null,
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) c.getString(idx)?.takeIf { it.isNotBlank() }?.let { return it }
                    }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "document"
    }

    /** Navigation graph for home + editor routes. */
    @Composable
    private fun OfficeNavGraph(
        state: OfficeViewModel.ViewState,
        filePickerLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>,
        onLeaveEditor: () -> Unit,
        backStack: com.vayunmathur.library.util.NavBackStack<OfficeRoute>,
    ) {
        val editorContent: @Composable () -> Unit = {
            when (val s = state) {
                is OfficeViewModel.ViewState.Loaded -> DocumentScreen(
                    document = s.document, viewModel = viewModel, activity = this@MainActivity,
                    onBack = { onLeaveEditor() },
                    // When an offline doc is shared it becomes a cloud doc — switch its route.
                    onBecameOnline = { id -> backStack.setLast(OfficeRoute.OnlineEditor(id)) }
                )
                is OfficeViewModel.ViewState.Error -> EditorError(s.message, onLeaveEditor)
                else -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        }
        OfficeTabs(
            backStack = backStack,
            viewModel = viewModel,
            onOpenDocument = { filePickerLauncher.launch(odfMimeTypes) },
            onNavigateEditor = { backStack.add(OfficeRoute.OfflineEditor()) },
            onOpenOnlineDoc = { meta ->
                viewModel.openOnlineDocument(meta)
                backStack.add(OfficeRoute.OnlineEditor(meta.docId))
            },
            editorContent = editorContent,
        )
    }

    /** MIME types accepted by the document picker. */
    private val odfMimeTypes = arrayOf(
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

    /**
     * Holds on to the picker's grant so Save can still write back to the chosen document after the
     * transient grant expires. Falls back to a read-only grant for providers that refuse write.
     */
    private fun persistUriGrant(uri: Uri) {
        val readWrite = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        if (runCatching { contentResolver.takePersistableUriPermission(uri, readWrite) }.isFailure) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTop: warm-start join links arrive here rather than through a fresh onCreate.
        setIntent(intent)
        parseJoinLink(intent)?.let { handleJoinLink(it) }
    }

    /** Fires a sealed join request to the owner and confirms with a toast. */
    private fun handleJoinLink(link: Pair<String, String>) {
        val (docId, ownerId) = link
        AppMessages.show(getString(R.string.join_request_sending))
        viewModel.requestToJoin(docId, ownerId) { ok ->
            AppMessages.show(
                getString(if (ok) R.string.join_request_sent else R.string.join_request_failed),
                duration = AppMessages.Duration.Long,
            )
        }
    }

    /** Parses `office://join/<docId>?owner=<ownerDeviceId>`; null for any other intent. */
    private fun parseJoinLink(intent: Intent?): Pair<String, String>? {
        val data = intent?.data ?: return null
        if (data.scheme != "office" || data.host != "join") return null
        val docId = data.lastPathSegment?.takeIf { it.isNotBlank() } ?: return null
        val ownerId = data.getQueryParameter("owner")?.takeIf { it.isNotBlank() } ?: return null
        return docId to ownerId
    }

    /**
     * Resolves the document uri to open from an intent. VIEW/edit intents carry it
     * in [Intent.getData]; SEND intents (files shared from other apps) carry it in
     * [Intent.EXTRA_STREAM].
     */
    private fun extractDocumentUri(intent: Intent): Uri? = when (intent.action) {
        Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        else -> intent.data
    }
}

/**
 * Error body for a document that failed to load: see DocumentScreenShell.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentScreen(
    document: OdfDocument,
    viewModel: OfficeViewModel,
    activity: ComponentActivity,
    onBack: () -> Unit,
    onBecameOnline: (String) -> Unit = {}) {
    val s = rememberDocumentScreenState()
    val sel = rememberScreenSelection(s)

    val onRunSelectionChange = rememberRunSelectionHandler(sel, viewModel)

    val chrome = rememberDocumentChrome(viewModel, document)
    val focusedPara = if (sel.activeRunStart >= 0 && chrome.isTextDoc) viewModel.runParagraphIndexAt(
        sel.activeRunStart,
        sel.activeRunEnd,
        sel.selStart) else -1

    DocumentEffects(s, chrome, onBack, { sel.showUnsavedDialog = true })

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val launchers = rememberDocumentLaunchers(
        viewModel = viewModel,
        holder = s,
        onPickImageBytes = rememberImagePickerHandler(
            document, viewModel, focusedPara, sel.activeSlide, sel.activeCell))

    DocumentDrawerShell(
        s = s,
        document = document,
        viewModel = viewModel,
        activity = activity,
        chrome = chrome,
        launchers = launchers,
        scope = scope,
        drawerState = drawerState,
        listState = listState,
        onToggleSearch = { sel.showSearch = !sel.showSearch },
        onShowUnsavedDialog = { sel.showUnsavedDialog = true },
        sel = ScreenSelection(
            sel.activeRunStart, sel.activeRunEnd, sel.activeTableBlock, sel.activeTableRow, sel.activeTableCol,
            sel.selStart, sel.selEnd, sel.activeCell, sel.activeSlide, sel.activeSlideEl),
        onRunSelectionChange = onRunSelectionChange,
        onCellFocus = { bi, r, c -> sel.activeTableBlock = bi; sel.activeTableRow = r; sel.activeTableCol = c },
        onCellSelected = { si, r, c -> sel.activeCell = Triple(si, r, c) },
        onSlideChange = { sel.activeSlide = it },
        onSlideElementSelected = { si, e -> sel.activeSlide = si; sel.activeSlideEl = e },
        focusedPara = focusedPara,
        onBack = onBack,
        onBecameOnline = onBecameOnline,
    )
}

/** Run-selection + shell sections: see DocumentScreenChrome / DocumentScreenShell. */
