package com.vayunmathur.pdf.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.DrawerValue
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.ExternalIntents
import com.vayunmathur.library.ui.rememberDrawerState
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.pdf.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Read-only + overlay-editing PDF viewer that never touches the system PDF
 * stack: pages are parsed in Rust ([com.vayunmathur.pdf.platform.SafePdfDocument])
 * and drawn from plain primitives on a Compose Canvas. Editing (annotations,
 * form filling) is written back through lopdf and saved via SAF.
 *
 * Thin binder: document lifecycle ([rememberSafePdfDocument]), zoom
 * ([rememberSafePdfZoomState]), effects ([SafePdfScreenEffects]), launchers
 * ([rememberSafePdfLaunchers]), chrome ([SafePdfViewerChrome]) and pending
 * dialogs ([SafePdfPendingDialogs]) each live in their own file so this entry
 * point stays under the FileLength limit.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SafePdfViewerScreen(uri: Uri, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sharePdfLabel = stringResource(R.string.share_pdf)
    val pdfSavedMsg = stringResource(R.string.pdf_saved)
    val pdfSaveErrorMsg = stringResource(R.string.pdf_save_error)

    val state = rememberSafePdfViewerState()
    val bundle = rememberSafePdfDocument(context, uri, state, onBack)
    val document = bundle.document

    val scroll = rememberSafePdfScrollState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val (zoom, transform) = rememberSafePdfZoomState(scroll)

    val launchers = rememberSafePdfLaunchers(
        onSaveCopyPicked = { outUri ->
            val doc = document
            if (doc != null) {
                scope.launch {
                    val bytes = doc.save()
                    if (bytes != null) {
                        runCatching {
                            context.contentResolver.openOutputStream(outUri)?.use { it.write(bytes) }
                        }
                    }
                }
            }
        },
        onSaveEncryptedPicked = { outUri ->
            val doc = document
            val pw = state.pendingEncryptPw
            state.pendingEncryptPw = null
            if (doc != null && pw != null) scope.launch {
                val bytes = doc.saveEncrypted(pw, "")
                if (bytes != null) withContext(Dispatchers.IO) {
                    runCatching { context.contentResolver.openOutputStream(outUri)?.use { it.write(bytes) } }
                }
            }
        },
        onImagePicked = { imgUri -> state.onImagePicked(document, context, imgUri) },
    )

    SafePdfScreenEffects(
        context = context,
        uri = uri,
        state = state,
        document = document,
        scroll = scroll,
        zoom = zoom,
    )

    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(state.searching) {
        if (state.searching) runCatching { searchFocus.requestFocus() }
    }

    BackHandler {
        when {
            drawerState.isOpen -> scope.launch { drawerState.close() }
            state.searching -> { state.searching = false; state.query = "" }
            state.editMode -> { state.editMode = false; state.selected = null }
            else -> onBack()
        }
    }

    // "Save": overwrite the original file in place.
    val saveInPlace: () -> Unit = {
        val doc = document
        if (doc != null) {
            scope.launch {
                val bytes = doc.save()
                val ok = bytes != null && runCatching {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } != null
                }.getOrDefault(false)
                AppMessages.show(if (ok) pdfSavedMsg else pdfSaveErrorMsg)
            }
        }
    }

    val shareAction = {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ExternalIntents.launch(context, Intent.createChooser(intent, sharePdfLabel))
    }

    SafePdfViewerChrome(
        state = state,
        bundle = bundle,
        scroll = scroll,
        zoom = zoom,
        drawerState = drawerState,
        pageViewer = { modifier ->
            SafePdfPageViewer(
                modifier = modifier,
                bundle = bundle,
                state = state,
                zoom = zoom,
                transform = transform,
                scroll = scroll,
                launchers = launchers,
            )
        },
        searchFocus = searchFocus,
        onBack = onBack,
        onShare = { shareAction() },
        onSaveInPlace = { saveInPlace() },
        onSaveCopyName = { name -> launchers.saveCopy(uri.lastPathSegment ?: name) },
    )

    SafePdfPendingDialogs(
        state = state,
        document = document,
        launchers = launchers,
    )
}
