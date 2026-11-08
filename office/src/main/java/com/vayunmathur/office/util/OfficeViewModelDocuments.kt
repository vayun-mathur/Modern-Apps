package com.vayunmathur.office.util

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.edit
import androidx.lifecycle.viewModelScope
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.odf.OdfCell
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfFrame
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfRow
import com.vayunmathur.library.ui.odf.OdfSheet
import com.vayunmathur.library.ui.odf.OdfSlide
import com.vayunmathur.library.ui.odf.OdfSlideElement
import com.vayunmathur.library.ui.odf.OdfSpan
import com.vayunmathur.library.ui.odf.ParagraphStyle
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.office.R
import com.vayunmathur.office.odf.DocumentImporter
import com.vayunmathur.office.odf.OdfWriter
import com.vayunmathur.office.util.MAX_RECENT
import com.vayunmathur.office.util.OfficeViewModel.ViewState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

fun OfficeViewModel.addToRecent(context: Context, uri: Uri, name: String) {
    val prefs = context.getSharedPreferences("office_recent", Context.MODE_PRIVATE)
    val existing = getRecentFiles(context).toMutableList()
    existing.removeAll { it.first == uri.toString() }
    existing.add(0, Pair(uri.toString(), name))
    if (existing.size > MAX_RECENT) existing.subList(MAX_RECENT, existing.size).clear()
    prefs.edit {
        putInt("count", existing.size)
        existing.forEachIndexed { i, (u, n) ->
            putString("uri_$i", u)
            putString("name_$i", n)
        }
    }
}

fun OfficeViewModel.getRecentFiles(context: Context): List<Pair<String, String>> {
    val prefs = context.getSharedPreferences("office_recent", Context.MODE_PRIVATE)
    val count = prefs.getInt("count", 0)
    return (0 until count).mapNotNull { i ->
        val uri = prefs.getString("uri_$i", null) ?: return@mapNotNull null
        val name = prefs.getString("name_$i", null) ?: return@mapNotNull null
        Pair(uri, name)
    }
}

fun OfficeViewModel.clearRecentFiles(context: Context) {
    context.getSharedPreferences("office_recent", Context.MODE_PRIVATE).edit { clear() }
}

fun OfficeViewModel.loadDocument(
    uri: Uri,
    fileName: String,
    onlineDocId: String? = null,
    onlineDocKey: ByteArray? = null) {
    stateMutable.value = ViewState.Loading
    isEditModeMutable.value = true
    hasUnsavedChangesMutable.value = false
    undoStack.clear(); redoStack.clear()
    canUndoMutable.value = false; canRedoMutable.value = false
    documentUri = uri
    originalUri = uri
    // Track (or clear) the online identity of the document now open.
    currentDocId = onlineDocId
    currentDocKey = onlineDocKey
    currentTree?.close(); currentTree = null
    currentCharKind = ""
    isOnlineMutable.value = onlineDocId != null
    if (onlineDocId == null) {
        // Offline document: you own your own local file and may edit it freely.
        currentRole = OfficeRoles.OWNER
        currentOwnerKey = null
        currentMembers.clear()
        memberKeyCache.clear()
    }
    viewModelScope.launch(Dispatchers.IO) {
        try {
            // Copy the source into app-owned storage so a revoked SAF grant can't leave us
            // unable to re-read the untouched package parts at save time. This is only a read
            // cache -- [originalUri] stays the save target.
            val localUri = persistToAppStorage(uri, fileName)
            documentUri = localUri
            val doc = DocumentImporter.open(getApplication(), localUri, fileName)
            stateMutable.value = ViewState.Loaded(doc)
            addToRecent(getApplication(), uri, fileName)
        } catch (expected: Exception) {
            stateMutable.value = ViewState.Error(expected.message ?: "Unknown error")
        }
    }
}

internal fun OfficeViewModel.persistToAppStorage(uri: Uri, fileName: String): Uri {
    val ctx: Context = getApplication()
    val dir = java.io.File(ctx.filesDir, "documents").apply { mkdirs() }
    if (uri.scheme == "file" && uri.path?.startsWith(dir.absolutePath) == true) return uri
    val safe = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "document" }
    val dest = java.io.File(dir, "${System.currentTimeMillis()}_$safe")
    ctx.contentResolver.openInputStream(uri)?.use { input ->
        dest.outputStream().use { output -> input.copyTo(output) }
    } ?: throw java.io.IOException("Cannot read $uri")
    return Uri.fromFile(dest)
}

fun OfficeViewModel.clearDocument() {
    stateMutable.value = ViewState.Empty
    isEditModeMutable.value = false
    hasUnsavedChangesMutable.value = false
    undoStack.clear(); redoStack.clear()
    canUndoMutable.value = false; canRedoMutable.value = false
    documentUri = null
    originalUri = null
    currentDocId = null
    currentDocKey = null
    currentTree?.close(); currentTree = null
    currentCharKind = ""
    OfficeSync.stopLive()
    livePollJob?.cancel()
    presenceTickJob?.cancel()
    remotePresenceMutable.value = emptyList()
    isOnlineMutable.value = false
    autoSaveJob?.cancel()
}

fun OfficeViewModel.createNewTextDocument() {
    currentDocId = null; currentDocKey = null; currentTree?.close(); currentTree = null; currentCharKind = ""
    currentRole = OfficeRoles.OWNER; currentOwnerKey = null; currentMembers.clear(); isOnlineMutable.value = false
    undoStack.clear(); redoStack.clear()
    canUndoMutable.value = false; canRedoMutable.value = false
    val doc = OdfDocument.TextDocument(
        title = "Untitled Document",
        content = listOf(OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan(text = "")))))
    )
    stateMutable.value = ViewState.Loaded(doc)
    isEditModeMutable.value = true
    hasUnsavedChangesMutable.value = true
    documentUri = null
    originalUri = null
}

fun OfficeViewModel.createNewSpreadsheet() {
    currentDocId = null; currentDocKey = null; currentTree?.close(); currentTree = null; currentCharKind = ""
    currentRole = OfficeRoles.OWNER; currentOwnerKey = null; currentMembers.clear(); isOnlineMutable.value = false
    undoStack.clear(); redoStack.clear()
    canUndoMutable.value = false; canRedoMutable.value = false
    val rows = (0 until 10).map { OdfRow(List(5) { OdfCell(text = "") }) }
    val doc = OdfDocument.Spreadsheet(
        title = "Untitled Spreadsheet",
        sheets = listOf(OdfSheet("Sheet 1", rows))
    )
    stateMutable.value = ViewState.Loaded(doc)
    isEditModeMutable.value = true
    hasUnsavedChangesMutable.value = true
    documentUri = null
    originalUri = null
}

fun OfficeViewModel.createNewPresentation() {
    currentDocId = null; currentDocKey = null; currentTree?.close(); currentTree = null; currentCharKind = ""
    currentRole = OfficeRoles.OWNER; currentOwnerKey = null; currentMembers.clear(); isOnlineMutable.value = false
    undoStack.clear(); redoStack.clear()
    canUndoMutable.value = false; canRedoMutable.value = false
    val doc = OdfDocument.Presentation(
        title = "Untitled Presentation",
        slides = listOf(OdfSlide(
            name = "Slide 1",
            elements = listOf(
                OdfSlideElement.Frame(OdfFrame(
                    x = 50f, y = 50f, width = 600f, height = 100f,
                    paragraphs = listOf(OdfParagraph(
                        listOf(OdfSpan(text = "Title", bold = true, fontSize = 36f)),
                        style = ParagraphStyle.HEADING1
                    ))
                ))
            )
        ))
    )
    stateMutable.value = ViewState.Loaded(doc)
    isEditModeMutable.value = true
    hasUnsavedChangesMutable.value = true
    documentUri = null
    originalUri = null
}

/** OOXML (Microsoft) extensions whose files must be written with the OOXML exporter. */
private val OOXML_EXTENSIONS = setOf(
    "docx", "docm", "dotx", "dotm", "xlsx", "xlsm", "xltx", "xltm",
    "pptx", "pptm", "potx", "potm", "ppsx", "ppsm")

internal fun uriExtension(uri: Uri?): String =
    uri?.lastPathSegment?.substringAfterLast('.', "")?.lowercase() ?: ""

fun OfficeViewModel.save(targetUri: Uri? = null) {
    val doc = (stateMutable.value as? ViewState.Loaded)?.document ?: return
    // Write back to the document the user opened, not to the app-private read cache.
    val target = targetUri ?: originalUri ?: return
    // An OOXML target (.docx/.xlsx/.pptx…) must be written by the OOXML exporter:
    // writing an ODF package over it (the old behaviour) destroyed the file.
    val targetIsOoxml = uriExtension(target) in OOXML_EXTENSIONS
    // Source may be null for a brand-new document; the writer then builds the package from scratch.
    // Never copy an OOXML source package's parts into an ODF package.
    val source = documentUri?.takeUnless { uriExtension(it) in OOXML_EXTENSIONS }
    isSavingMutable.value = true
    viewModelScope.launch(Dispatchers.IO) {
        try {
            if (targetIsOoxml) {
                val bytes = com.vayunmathur.library.ui.odf.OoxmlExporter.export(doc)
                getApplication<Application>().contentResolver.openOutputStream(target)
                    ?.use { it.write(bytes) }
                    ?: throw java.io.IOException("Cannot open $target for writing")
            } else {
                OdfWriter.save(getApplication(), source, doc, target)
            }
            hasUnsavedChangesMutable.value = false
            originalUri = target
            if (documentUri == null) documentUri = target
            // If this document lives online, push local edits + merge remote ones.
            if (currentDocId != null && currentDocKey != null) {
                runCatching {
                    OfficeSync.init(getApplication())
                    syncDoc(currentDocId!!, currentDocKey!!)
                }
            }
            launch(Dispatchers.Main) { AppMessages.show(getApplication<Application>().getString(UiR.string.saved)) }
        } catch (expected: Exception) {
            launch(Dispatchers.Main) { AppMessages.show(getApplication<Application>().getString(
                R.string.save_failed,
                expected.message)) }
        } finally {
            isSavingMutable.value = false
        }
    }
}

fun OfficeViewModel.needsSaveAs(): Boolean {
    val target = originalUri ?: return true
    return !isWritable(target)
}

internal fun OfficeViewModel.isWritable(uri: Uri): Boolean = when (uri.scheme) {
    "file" -> uri.path?.let { java.io.File(it).canWrite() } == true
    else -> getApplication<Application>().checkCallingOrSelfUriPermission(
        uri,
        Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
    ) == PackageManager.PERMISSION_GRANTED
}
