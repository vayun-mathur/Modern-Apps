package com.vayunmathur.office.util

import android.app.Application
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.library.util.DataStoreUtils
import kotlin.io.encoding.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.vayunmathur.office.odf.*
import com.vayunmathur.library.ui.odf.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Local metadata for a document in the online folder (title/key stay client-side; never sent in the clear). */
@Serializable
data class OfficeDocMeta(
    val docId: String,
    val title: String,
    val keyB64: String,
    val owner: Boolean,
    val charMode: Boolean = false,
    val role: String = OfficeRoles.EDITOR,
    val ownerKeyB64: String = "",
)

/** Document access roles. Enforced entirely client-side via signature checks (server is a pure relay). */
object OfficeRoles {
    const val OWNER = "owner"
    const val EDITOR = "editor"
    const val VIEWER = "viewer"
    const val REVOKED = "revoked"
    fun canEdit(role: String) = role == OWNER || role == EDITOR
}

/** Ephemeral presence for a collaborator in a document (relayed encrypted; never stored). */
@Serializable
data class OfficePresence(val id: String, val name: String, val typing: Boolean, val ts: Long, val caret: Int? = null)

/** A member of a document (who has access + their role). Distributed as owner-signed records. */
@Serializable
data class OfficeMember(val id: String, val name: String = "", val role: String = OfficeRoles.EDITOR)

/** An owner-signed member record (only records with a valid owner signature are honored). */
@Serializable
data class SignedMember(val member: OfficeMember, val sig: String)

/** An author-signed CRDT op batch: author id, signature over [ops], and the ops JSON. */
@Serializable
data class SignedOp(val author: String, val sig: String, val ops: String)

class OfficeViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow<ViewState>(ViewState.Empty)
    val state: StateFlow<ViewState> = _state

    private val _isEditMode = MutableStateFlow(false)
    val isEditMode: StateFlow<Boolean> = _isEditMode

    private val _hasUnsavedChanges = MutableStateFlow(false)
    val hasUnsavedChanges: StateFlow<Boolean> = _hasUnsavedChanges

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo

    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo

    private val _nightMode = MutableStateFlow(false)
    val nightMode: StateFlow<Boolean> = _nightMode

    // Incremented whenever the document changes shape via undo/redo so the UI can
    // reset/clamp hoisted selection state (active cell/slide/element). (A4)
    private val _selectionInvalidation = MutableStateFlow(0)
    val selectionInvalidation: StateFlow<Int> = _selectionInvalidation

    var documentUri: Uri? = null
        private set

    // --- Auto-save ---
    private var autoSaveJob: Job? = null
    private var _autoSaveEnabled = MutableStateFlow(false)
    val autoSaveEnabled: StateFlow<Boolean> = _autoSaveEnabled
    private var autoSaveIntervalMs: Long = 60_000L // default 1 minute

    fun setAutoSave(enabled: Boolean, intervalSeconds: Int = 60) {
        autoSaveIntervalMs = intervalSeconds * 1000L
        _autoSaveEnabled.value = enabled
        autoSaveJob?.cancel()
        if (enabled) {
            autoSaveJob = viewModelScope.launch {
                while (true) {
                    delay(autoSaveIntervalMs)
                    if (_hasUnsavedChanges.value && documentUri != null) save()
                }
            }
        }
    }

    // --- Settings ---
    fun loadSettings(context: Context) {
        val prefs = context.getSharedPreferences("office_settings", Context.MODE_PRIVATE)
        val autoSave = prefs.getBoolean("auto_save", false)
        val interval = prefs.getInt("auto_save_interval", 60)
        setAutoSave(autoSave, interval)
    }

    fun saveSettings(context: Context, autoSave: Boolean, autoSaveInterval: Int, defaultFontSize: Float) {
        val prefs = context.getSharedPreferences("office_settings", Context.MODE_PRIVATE).edit()
        prefs.putBoolean("auto_save", autoSave)
        prefs.putInt("auto_save_interval", autoSaveInterval)
        prefs.putFloat("default_font_size", defaultFontSize)
        prefs.apply()
        setAutoSave(autoSave, autoSaveInterval)
    }

    fun getDefaultFontSize(context: Context): Float {
        return context.getSharedPreferences("office_settings", Context.MODE_PRIVATE)
            .getFloat("default_font_size", 16f)
    }

    fun getAutoSaveInterval(context: Context): Int {
        return context.getSharedPreferences("office_settings", Context.MODE_PRIVATE)
            .getInt("auto_save_interval", 60)
    }

    fun getAutoSaveEnabled(context: Context): Boolean {
        return context.getSharedPreferences("office_settings", Context.MODE_PRIVATE)
            .getBoolean("auto_save", false)
    }

    // --- Undo/Redo ---
    private val undoStack = ArrayDeque<OdfDocument>(maxOf(1, MAX_UNDO))
    private val redoStack = ArrayDeque<OdfDocument>(maxOf(1, MAX_UNDO))

    private fun pushUndo(doc: OdfDocument) {
        undoStack.addLast(doc)
        if (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        redoStack.clear()
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = false
    }

    fun undo() {
        val current = (_state.value as? ViewState.Loaded)?.document ?: return
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(current)
        _state.value = ViewState.Loaded(previous)
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
        _hasUnsavedChanges.value = true
        _selectionInvalidation.value++
    }

    fun redo() {
        val current = (_state.value as? ViewState.Loaded)?.document ?: return
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(current)
        _state.value = ViewState.Loaded(next)
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
        _hasUnsavedChanges.value = true
        _selectionInvalidation.value++
    }

    private fun updateDocument(newDoc: OdfDocument) {
        val current = (_state.value as? ViewState.Loaded)?.document ?: return
        pushUndo(current)
        // Keep ordered-list numbering live after every text edit (matches the markdown editor).
        val stored = if (newDoc is OdfDocument.TextDocument) renumberLists(newDoc) else newDoc
        _state.value = ViewState.Loaded(stored)
        _hasUnsavedChanges.value = true
        onLocalEdit()
    }

    // --- Recent files ---
    fun addToRecent(context: Context, uri: Uri, name: String) {
        val prefs = context.getSharedPreferences("office_recent", Context.MODE_PRIVATE)
        val existing = getRecentFiles(context).toMutableList()
        existing.removeAll { it.first == uri.toString() }
        existing.add(0, Pair(uri.toString(), name))
        if (existing.size > MAX_RECENT) existing.subList(MAX_RECENT, existing.size).clear()
        val editor = prefs.edit()
        editor.putInt("count", existing.size)
        existing.forEachIndexed { i, (u, n) ->
            editor.putString("uri_$i", u)
            editor.putString("name_$i", n)
        }
        editor.apply()
    }

    fun getRecentFiles(context: Context): List<Pair<String, String>> {
        val prefs = context.getSharedPreferences("office_recent", Context.MODE_PRIVATE)
        val count = prefs.getInt("count", 0)
        return (0 until count).mapNotNull { i ->
            val uri = prefs.getString("uri_$i", null) ?: return@mapNotNull null
            val name = prefs.getString("name_$i", null) ?: return@mapNotNull null
            Pair(uri, name)
        }
    }

    fun clearRecentFiles(context: Context) {
        context.getSharedPreferences("office_recent", Context.MODE_PRIVATE).edit().clear().apply()
    }

    // --- Night mode ---
    fun toggleNightMode() { _nightMode.value = !_nightMode.value }

    // --- Load / Clear ---

    fun loadDocument(uri: Uri, fileName: String, onlineDocId: String? = null, onlineDocKey: ByteArray? = null) {
        _state.value = ViewState.Loading
        _isEditMode.value = true
        _hasUnsavedChanges.value = false
        undoStack.clear(); redoStack.clear()
        _canUndo.value = false; _canRedo.value = false
        documentUri = uri
        // Track (or clear) the online identity of the document now open.
        currentDocId = onlineDocId
        currentDocKey = onlineDocKey
        currentCrdt = null
        currentCharMode = false
        if (onlineDocId == null) {
            // Offline document: you own your own local file and may edit it freely.
            currentRole = OfficeRoles.OWNER
            currentOwnerKey = null
            currentMembers.clear()
            memberKeyCache.clear()
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Copy the source into app-owned storage immediately: SAF often grants only a
                // one-time read permission, so the original Uri may be unreadable on a later open.
                val localUri = persistToAppStorage(uri, fileName)
                documentUri = localUri
                val doc = DocumentImporter.open(getApplication(), localUri, fileName)
                _state.value = ViewState.Loaded(doc)
                addToRecent(getApplication(), localUri, fileName)
            } catch (e: Exception) {
                _state.value = ViewState.Error(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Copies the bytes behind [uri] into app-private storage and returns a file Uri we own. This is
     * required because the system file picker typically grants only a transient read grant, so the
     * original Uri can't be reopened later. Uris already inside our storage are returned as-is.
     */
    private fun persistToAppStorage(uri: Uri, fileName: String): Uri {
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

    fun clear() {
        _state.value = ViewState.Empty
        _isEditMode.value = false
        _hasUnsavedChanges.value = false
        undoStack.clear(); redoStack.clear()
        _canUndo.value = false; _canRedo.value = false
        documentUri = null
        currentDocId = null
        currentDocKey = null
        currentCrdt = null
        currentCharMode = false
        OfficeSync.stopLive()
        _remotePresence.value = emptyList()
        autoSaveJob?.cancel()
    }

    fun toggleEditMode() { _isEditMode.value = !_isEditMode.value }

    // --- Create new documents ---

    fun createNewTextDocument() {
        currentDocId = null; currentDocKey = null; currentCrdt = null; currentCharMode = false
        currentRole = OfficeRoles.OWNER; currentOwnerKey = null; currentMembers.clear()
        undoStack.clear(); redoStack.clear()
        _canUndo.value = false; _canRedo.value = false
        val doc = OdfDocument.TextDocument(
            title = "Untitled Document",
            content = listOf(OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan(text = "")))))
        )
        _state.value = ViewState.Loaded(doc)
        _isEditMode.value = true
        _hasUnsavedChanges.value = true
        documentUri = null
    }

    fun createNewSpreadsheet() {
        currentDocId = null; currentDocKey = null; currentCrdt = null; currentCharMode = false
        currentRole = OfficeRoles.OWNER; currentOwnerKey = null; currentMembers.clear()
        undoStack.clear(); redoStack.clear()
        _canUndo.value = false; _canRedo.value = false
        val rows = (0 until 10).map { OdfRow(List(5) { OdfCell(text = "") }) }
        val doc = OdfDocument.Spreadsheet(
            title = "Untitled Spreadsheet",
            sheets = listOf(OdfSheet("Sheet 1", rows))
        )
        _state.value = ViewState.Loaded(doc)
        _isEditMode.value = true
        _hasUnsavedChanges.value = true
        documentUri = null
    }

    fun createNewPresentation() {
        currentDocId = null; currentDocKey = null; currentCrdt = null; currentCharMode = false
        currentRole = OfficeRoles.OWNER; currentOwnerKey = null; currentMembers.clear()
        undoStack.clear(); redoStack.clear()
        _canUndo.value = false; _canRedo.value = false
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
        _state.value = ViewState.Loaded(doc)
        _isEditMode.value = true
        _hasUnsavedChanges.value = true
        documentUri = null
    }

    // --- Text document editing ---

    private fun curText(): OdfDocument.TextDocument? = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument

    fun updateParagraphText(blockIndex: Int, newText: String) {
        val doc = curText() ?: return
        updateDocument(doc.updateParagraphText(blockIndex, newText) ?: return)
    }

    /** Applies a span transform to the character range [start, end). If the range is empty, applies to the whole paragraph. */
    fun applySpanStyleToRange(blockIndex: Int, start: Int, end: Int, transform: (OdfSpan) -> OdfSpan) {
        val doc = curText() ?: return
        updateDocument(doc.applySpanStyleToRange(blockIndex, start, end, transform) ?: return)
    }

    /** True if every character in [start, end) (or the whole paragraph when empty) satisfies [predicate]. */
    fun rangeHasFormat(blockIndex: Int, start: Int, end: Int, predicate: (OdfSpan) -> Boolean): Boolean =
        curText()?.rangeHasFormat(blockIndex, start, end, predicate) ?: false

    private fun spansToChars(spans: List<OdfSpan>): MutableList<OdfSpan> {
        val out = ArrayList<OdfSpan>()
        for (span in spans) for (ch in span.text) out.add(span.copy(text = ch.toString()))
        return out
    }

    private fun charsToSpans(chars: List<OdfSpan>): List<OdfSpan> {
        if (chars.isEmpty()) return listOf(OdfSpan(text = ""))
        val out = ArrayList<OdfSpan>()
        var current = chars[0]
        val sb = StringBuilder(current.text)
        for (i in 1 until chars.size) {
            val c = chars[i]
            if (c.copy(text = "") == current.copy(text = "")) {
                sb.append(c.text)
            } else {
                out.add(current.copy(text = sb.toString()))
                current = c
                sb.setLength(0)
                sb.append(c.text)
            }
        }
        out.add(current.copy(text = sb.toString()))
        return out
    }

    // --- Continuous (multi-paragraph run) editing ---

    private fun runParas(start: Int, endInclusive: Int): List<OdfParagraph>? {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return null
        if (start < 0 || endInclusive >= doc.content.size || start > endInclusive) return null
        val list = ArrayList<OdfParagraph>()
        for (i in start..endInclusive) {
            val b = doc.content[i] as? OdfContentBlock.Paragraph ?: return null
            list.add(b.paragraph)
        }
        return list
    }

    private fun paraLens(paras: List<OdfParagraph>) = paras.map { p -> p.spans.sumOf { it.text.length } }

    private fun runLocate(lens: List<Int>, pos: Int): Pair<Int, Int> {
        var rem = pos
        for (i in lens.indices) {
            if (rem <= lens[i]) return i to rem
            rem -= lens[i] + 1 // consume paragraph chars + separator
            if (rem < 0) return i to lens[i]
        }
        return lens.lastIndex.coerceAtLeast(0) to (lens.lastOrNull() ?: 0)
    }

    /** Edits a run of consecutive paragraphs [start, endInclusive] as one continuous text (paragraphs joined by '\n'). */
    fun updateParagraphRun(start: Int, endInclusive: Int, newText: String) {
        val doc = curText() ?: return
        updateDocument(doc.updateParagraphRun(start, endInclusive, newText) ?: return)
    }

    /** Smart Enter: splits/continues a list line, or exits an empty list item. Returns the new caret. */
    fun handleListEnter(start: Int, endInclusive: Int, gPos: Int): Int? {
        val doc = curText() ?: return null
        val (newDoc, caret) = doc.handleListEnter(start, endInclusive, gPos) ?: return null
        updateDocument(newDoc)
        return caret
    }

    /** Smart Backspace at the start of a list item: outdent/remove the marker. Returns the new caret. */
    fun handleListBackspace(start: Int, endInclusive: Int, gPos: Int): Int? {
        val doc = curText() ?: return null
        val (newDoc, caret) = doc.handleListBackspace(start, endInclusive, gPos) ?: return null
        updateDocument(newDoc)
        return caret
    }

    /** Toggles a checklist item on/off for a paragraph. */
    fun toggleCheckbox(blockIndex: Int) {
        val doc = curText() ?: return
        updateDocument(doc.toggleCheckbox(blockIndex) ?: return)
    }

    /** Sets the checked state of a checklist item (tapping the box). */
    fun setCheckboxChecked(blockIndex: Int, checked: Boolean) {
        val doc = curText() ?: return
        updateDocument(doc.setCheckboxChecked(blockIndex, checked) ?: return)
    }

    /** Finds the link covering the caret in a run, or null. */
    fun linkAt(start: Int, endInclusive: Int, gPos: Int): OdfLinkSpan? =
        curText()?.linkAt(start, endInclusive, gPos)

    /** Plain text of the current run selection (used to pre-fill the link dialog). */
    fun runSelectedText(start: Int, endInclusive: Int, gStart: Int, gEnd: Int): String {
        val doc = curText() ?: return ""
        val full = (start..endInclusive)
            .mapNotNull { (doc.content.getOrNull(it) as? OdfContentBlock.Paragraph)?.paragraph }
            .joinToString("\n") { p -> p.spans.joinToString("") { it.text } }
        val s = minOf(gStart, gEnd).coerceIn(0, full.length)
        val e = maxOf(gStart, gEnd).coerceIn(s, full.length)
        return full.substring(s, e)
    }

    /** Replaces a run range with a single link span. */
    fun setLink(start: Int, endInclusive: Int, gStart: Int, gEnd: Int, text: String, url: String) {
        val doc = curText() ?: return
        updateDocument(doc.setLinkInRun(start, endInclusive, gStart, gEnd, text, url) ?: return)
    }

    /** Removes the link over a run range, keeping the text. */
    fun removeLinkInRun(start: Int, endInclusive: Int, gStart: Int, gEnd: Int) {
        val doc = curText() ?: return
        updateDocument(doc.applyRunSpanStyle(start, endInclusive, gStart, gEnd) {
            it.copy(href = null, underline = false, color = null)
        } ?: return)
    }

    /** Applies a span transform across a (possibly multi-paragraph) selection within a run. Empty selection = caret's whole paragraph. */
    fun applyRunSpanStyle(start: Int, endInclusive: Int, gStart: Int, gEnd: Int, transform: (OdfSpan) -> OdfSpan) {
        val doc = curText() ?: return
        updateDocument(doc.applyRunSpanStyle(start, endInclusive, gStart, gEnd, transform) ?: return)
    }

    /** True if every character in the run selection (or caret's whole paragraph) satisfies [predicate]. */
    fun runRangeHasFormat(start: Int, endInclusive: Int, gStart: Int, gEnd: Int, predicate: (OdfSpan) -> Boolean): Boolean =
        curText()?.runRangeHasFormat(start, endInclusive, gStart, gEnd, predicate) ?: false

    /** Paragraph index (within the document) at the given run-global caret position. */
    fun runParagraphIndexAt(start: Int, endInclusive: Int, gPos: Int): Int =
        curText()?.runParagraphIndexAt(start, endInclusive, gPos) ?: start

    /** Applies a paragraph-level mutation to every paragraph touched by the run selection. */
    fun mutateRunParagraphs(start: Int, endInclusive: Int, gStart: Int, gEnd: Int, transform: (OdfParagraph) -> OdfParagraph) {
        val doc = curText() ?: return
        updateDocument(doc.mutateRunParagraphs(start, endInclusive, gStart, gEnd, transform) ?: return)
    }

    /** Inserts literal text at the caret position within a paragraph run (B17/B18). */
    fun insertTextInRun(start: Int, endInclusive: Int, gPos: Int, insert: String) {
        val doc = curText() ?: return
        updateDocument(doc.insertTextInRun(start, endInclusive, gPos, insert) ?: return)
    }

    /** Inserts a real ODF text field (date/time/page-number/...) at the caret. (Priority 2) */
    fun insertFieldInRun(start: Int, endInclusive: Int, gPos: Int, kind: String, value: String) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
        val paras = runParas(start, endInclusive) ?: return
        val lens = paraLens(paras)
        val (pi, off) = runLocate(lens, gPos)
        val para = paras[pi]
        val chars = spansToChars(para.spans)
        val template = OdfSpan(text = "", field = kind)
        val fieldChars = value.map { template.copy(text = it.toString()) }
        val at = off.coerceIn(0, chars.size)
        chars.addAll(at, fieldChars)
        val newContent = doc.content.toMutableList()
        newContent[start + pi] = OdfContentBlock.Paragraph(para.copy(spans = charsToSpans(chars)))
        updateDocument(doc.copy(content = newContent))
    }

    /** Computes the current display value for a newly-inserted field. (Priority 2) */
    fun fieldDisplayValue(kind: String): String {
        val doc = (_state.value as? ViewState.Loaded)?.document
        val meta = doc?.metadata
        return when (kind) {
            "date" -> java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
            "time" -> java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
            "page-number" -> "1"
            "page-count" -> "1"
            "file-name" -> doc?.title ?: "Untitled"
            "author-name" -> meta?.author ?: meta?.creator ?: ""
            "title" -> meta?.title ?: doc?.title ?: ""
            "subject" -> meta?.subject ?: ""
            "description" -> meta?.description ?: ""
            else -> ""
        }
    }

    /** Clears all character formatting across a run selection (B24). */
    fun clearRunFormatting(start: Int, endInclusive: Int, gStart: Int, gEnd: Int) {
        val doc = curText() ?: return
        updateDocument(doc.clearRunFormatting(start, endInclusive, gStart, gEnd) ?: return)
    }

    /** Promote/demote a list item's nesting level (B13). */
    fun changeListLevel(blockIndex: Int, delta: Int) {
        val doc = curText() ?: return
        updateDocument(doc.changeListLevel(blockIndex, delta) ?: return)
    }

    /** Restart numbering at 1 for a numbered list item (B13). */
    fun restartNumbering(blockIndex: Int) {
        val doc = curText() ?: return
        updateDocument(doc.restartNumbering(blockIndex) ?: return)
    }

    /** Inserts an image (already-read bytes) after the given block (B14). */
    fun insertImage(blockIndex: Int, fileName: String, bytes: ByteArray) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
        val path = uniqueImagePath(doc.images.keys, fileName)
        val content = doc.content.toMutableList()
        val at = (blockIndex + 1).coerceIn(0, content.size)
        content.add(at, OdfContentBlock.Image(OdfImage(path = path, imageData = bytes)))
        updateDocument(doc.copy(content = content, images = doc.images + (path to bytes)))
    }

    /** Generates a unique package path for a newly-inserted image so two inserts never collide. (A6) */
    private fun uniqueImagePath(existing: Set<String>, fileName: String): String {
        val safe = fileName.substringAfterLast('/').ifBlank { "image" }
        val base = safe.substringBeforeLast('.', safe)
        val ext = safe.substringAfterLast('.', "")
        var candidate = "Pictures/$safe"
        var n = 1
        while (candidate in existing) {
            candidate = if (ext.isNotEmpty()) "Pictures/${base}_$n.$ext" else "Pictures/${base}_$n"
            n++
        }
        return candidate
    }

    /** Sets non-destructive crop insets on a text-document image block. (Phase 5) */
    fun setImageCrop(blockIndex: Int, left: Float, top: Float, right: Float, bottom: Float) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
        val block = doc.content.getOrNull(blockIndex) as? OdfContentBlock.Image ?: return
        val content = doc.content.toMutableList()
        content[blockIndex] = OdfContentBlock.Image(block.image.copy(
            cropLeftPct = left, cropTopPct = top, cropRightPct = right, cropBottomPct = bottom
        ))
        updateDocument(doc.copy(content = content))
    }

    /** Inserts a horizontal rule (rendered as a bordered empty paragraph) (B19). */
    fun insertHorizontalLine(blockIndex: Int) {
        val doc = curText() ?: return
        updateDocument(doc.insertHorizontalLine(blockIndex))
    }

    /** Generates a Table of Contents from headings and inserts it at the top (B21). */
    fun insertTableOfContents() {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
        val headingStyles = setOf(ParagraphStyle.HEADING1, ParagraphStyle.HEADING2, ParagraphStyle.HEADING3, ParagraphStyle.HEADING4)
        val entries = mutableListOf<OdfParagraph>()
        for (block in doc.content) {
            val para = (block as? OdfContentBlock.Paragraph)?.paragraph ?: continue
            if (para.style !in headingStyles) continue
            val text = para.spans.joinToString("") { it.text }.trim()
            if (text.isEmpty()) continue
            val level = when (para.style) { ParagraphStyle.HEADING1 -> 1; ParagraphStyle.HEADING2 -> 2; ParagraphStyle.HEADING3 -> 3; else -> 4 }
            entries.add(OdfParagraph(listOf(OdfSpan(text = text)), marginLeft = (level - 1) * 18f))
        }
        if (entries.isEmpty()) return
        val toc = OdfContentBlock.TableOfContents("Table of Contents", entries)
        val content = listOf(toc, OdfContentBlock.PageBreak) + doc.content
        updateDocument(doc.copy(content = content))
    }

    // --- Track changes accept/reject (Priority 6) ---

    private fun transformChangeSpans(doc: OdfDocument.TextDocument, id: String, removeSpans: Boolean): OdfDocument.TextDocument {
        fun mapSpans(spans: List<OdfSpan>): List<OdfSpan> {
            if (spans.none { it.changeId == id }) return spans
            val out = ArrayList<OdfSpan>()
            for (s in spans) {
                if (s.changeId == id) {
                    if (removeSpans) continue
                    out.add(s.copy(changeId = null, changeKind = null))
                } else out.add(s)
            }
            return out.ifEmpty { listOf(OdfSpan(text = "")) }
        }
        val newContent = doc.content.map { block ->
            when (block) {
                is OdfContentBlock.Paragraph -> OdfContentBlock.Paragraph(block.paragraph.copy(spans = mapSpans(block.paragraph.spans)))
                is OdfContentBlock.Table -> OdfContentBlock.Table(block.table.copy(rows = block.table.rows.map { r ->
                    r.copy(cells = r.cells.map { c -> c.copy(paragraphs = c.paragraphs.map { p -> p.copy(spans = mapSpans(p.spans)) }) })
                }))
                is OdfContentBlock.TableOfContents -> OdfContentBlock.TableOfContents(block.title, block.entries.map { it.copy(spans = mapSpans(it.spans)) })
                else -> block
            }
        }
        return doc.copy(content = newContent, changes = doc.changes.filterNot { it.id == id })
    }

    /** Accept a tracked change: insertions stay, deletions are applied (text removed). */
    fun acceptChange(id: String) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
        val type = doc.changes.find { it.id == id }?.type ?: return
        updateDocument(transformChangeSpans(doc, id, removeSpans = type == "deletion"))
    }

    /** Reject a tracked change: insertions are removed, deletions are reverted (text kept). */
    fun rejectChange(id: String) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
        val type = doc.changes.find { it.id == id }?.type ?: return
        updateDocument(transformChangeSpans(doc, id, removeSpans = type != "deletion"))
    }

    fun acceptAllChanges() {
        var doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
        for (id in doc.changes.map { it.id }) {
            val type = doc.changes.find { it.id == id }?.type ?: continue
            doc = transformChangeSpans(doc, id, removeSpans = type == "deletion")
        }
        updateDocument(doc)
    }

    fun rejectAllChanges() {
        var doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
        for (id in doc.changes.map { it.id }) {
            val type = doc.changes.find { it.id == id }?.type ?: continue
            doc = transformChangeSpans(doc, id, removeSpans = type != "deletion")
        }
        updateDocument(doc)
    }

    /** Updates page geometry (size/margins/orientation), persisted to styles.xml on save. (Priority 7) */
    fun setPageSetup(setup: OdfPageSetup) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
        updateDocument(doc.copy(pageSetup = setup))
    }

    /** Inserts a footnote with a citation marker at the caret (B15). */
    fun insertFootnote(start: Int, endInclusive: Int, gPos: Int, body: String, isEndnote: Boolean = false) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
        val citation = (doc.footnotes.size + 1).toString()
        val footnotes = doc.footnotes + OdfFootnote(citation, listOf(OdfParagraph(listOf(OdfSpan(text = body)))), isEndnote)
        // Insert the citation marker text at the caret, then attach the footnote.
        val paras = runParas(start, endInclusive)
        if (paras != null) {
            val full = paras.joinToString("\n") { p -> p.spans.joinToString("") { it.text } }
            val pos = gPos.coerceIn(0, full.length)
            val newText = full.substring(0, pos) + "[$citation]" + full.substring(pos)
            updateParagraphRun(start, endInclusive, newText)
        }
        val current = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
        updateDocument(current.copy(footnotes = footnotes))
    }

    /** Removes the annotation span at [spanIndex] within paragraph [blockIndex] (resolve comment). (C3) */
    fun resolveComment(blockIndex: Int, spanIndex: Int) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
        val block = doc.content.getOrNull(blockIndex) as? OdfContentBlock.Paragraph ?: return
        val spans = block.paragraph.spans.toMutableList()
        val span = spans.getOrNull(spanIndex) ?: return
        if (span.annotation == null) return
        spans.removeAt(spanIndex)
        if (spans.isEmpty()) spans.add(OdfSpan(text = ""))
        val content = doc.content.toMutableList()
        content[blockIndex] = OdfContentBlock.Paragraph(block.paragraph.copy(spans = spans))
        updateDocument(doc.copy(content = content))
    }

    /** Appends a comment/annotation marker to a paragraph (B16). */
    fun insertComment(blockIndex: Int, author: String, text: String) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
        val content = doc.content.toMutableList()
        val block = content.getOrNull(blockIndex) as? OdfContentBlock.Paragraph ?: return
        val annotation = OdfAnnotation(
            author = author.ifBlank { null },
            date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date()),
            paragraphs = listOf(OdfParagraph(listOf(OdfSpan(text = text))))
        )
        val newSpans = block.paragraph.spans + OdfSpan(text = " \uD83D\uDCDD ", annotation = annotation)
        content[blockIndex] = OdfContentBlock.Paragraph(block.paragraph.copy(spans = newSpans))
        updateDocument(doc.copy(content = content))
    }

    /** Sets header/footer text (in-session edit; B20). */
    fun setHeaderText(text: String) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
        val paras = if (text.isBlank()) emptyList() else text.split("\n").map { OdfParagraph(listOf(OdfSpan(text = it))) }
        updateDocument(doc.copy(headerParagraphs = paras))
    }

    fun setFooterText(text: String) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
        val paras = if (text.isBlank()) emptyList() else text.split("\n").map { OdfParagraph(listOf(OdfSpan(text = it))) }
        updateDocument(doc.copy(footerParagraphs = paras))
    }

    // --- Text-document table editing ---

    private fun withTextTable(blockIndex: Int, transform: (OdfTable) -> OdfTable) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
        val block = doc.content.getOrNull(blockIndex) as? OdfContentBlock.Table ?: return
        val newContent = doc.content.toMutableList()
        newContent[blockIndex] = OdfContentBlock.Table(transform(block.table))
        updateDocument(doc.copy(content = newContent))
    }

    fun updateTextTableCell(blockIndex: Int, row: Int, col: Int, newText: String) = withTextTable(blockIndex) { table ->
        val rows = table.rows.toMutableList()
        val r = rows.getOrNull(row) ?: return@withTextTable table
        val cells = r.cells.toMutableList()
        if (col !in cells.indices) return@withTextTable table
        val paras = newText.split("\n").map { OdfParagraph(listOf(OdfSpan(text = it))) }
        cells[col] = cells[col].copy(paragraphs = paras)
        rows[row] = OdfTableRow(cells)
        table.copy(rows = rows)
    }

    fun textTableAddRow(blockIndex: Int, afterRow: Int) = withTextTable(blockIndex) { table ->
        val colCount = table.rows.firstOrNull()?.cells?.size ?: 1
        val newRow = OdfTableRow(List(colCount) { OdfTableCell(paragraphs = listOf(OdfParagraph(listOf(OdfSpan(text = ""))))) })
        val rows = table.rows.toMutableList()
        val at = (afterRow + 1).coerceIn(0, rows.size)
        rows.add(at, newRow)
        table.copy(rows = rows)
    }

    fun textTableAddColumn(blockIndex: Int, afterCol: Int) = withTextTable(blockIndex) { table ->
        table.copy(rows = table.rows.map { row ->
            val cells = row.cells.toMutableList()
            val at = (afterCol + 1).coerceIn(0, cells.size)
            cells.add(at, OdfTableCell(paragraphs = listOf(OdfParagraph(listOf(OdfSpan(text = ""))))))
            OdfTableRow(cells)
        })
    }

    fun textTableDeleteRow(blockIndex: Int, row: Int) = withTextTable(blockIndex) { table ->
        if (table.rows.size <= 1 || row !in table.rows.indices) table
        else table.copy(rows = table.rows.toMutableList().apply { removeAt(row) })
    }

    fun textTableDeleteColumn(blockIndex: Int, col: Int) = withTextTable(blockIndex) { table ->
        table.copy(rows = table.rows.map { row ->
            if (row.cells.size <= 1 || col !in row.cells.indices) row
            else OdfTableRow(row.cells.toMutableList().apply { removeAt(col) })
        })
    }

    /** Per-cell character formatting for text-document tables (C26). */
    fun setTextTableCellSpanFormat(blockIndex: Int, row: Int, col: Int, transform: (OdfSpan) -> OdfSpan) = withTextTable(blockIndex) { table ->
        val rows = table.rows.toMutableList()
        val r = rows.getOrNull(row) ?: return@withTextTable table
        val cells = r.cells.toMutableList()
        val cell = cells.getOrNull(col) ?: return@withTextTable table
        val newParas = cell.paragraphs.map { p -> p.copy(spans = p.spans.map(transform)) }
        cells[col] = cell.copy(paragraphs = newParas)
        rows[row] = OdfTableRow(cells)
        table.copy(rows = rows)
    }

    fun setTextTableCellAlignment(blockIndex: Int, row: Int, col: Int, alignment: androidx.compose.ui.text.style.TextAlign?) = withTextTable(blockIndex) { table ->
        val rows = table.rows.toMutableList()
        val r = rows.getOrNull(row) ?: return@withTextTable table
        val cells = r.cells.toMutableList()
        val cell = cells.getOrNull(col) ?: return@withTextTable table
        cells[col] = cell.copy(paragraphs = cell.paragraphs.map { it.copy(alignment = alignment) })
        rows[row] = OdfTableRow(cells)
        table.copy(rows = rows)
    }

    fun setTextTableCellBackground(blockIndex: Int, row: Int, col: Int, color: Long?) = withTextTable(blockIndex) { table ->
        val rows = table.rows.toMutableList()
        val r = rows.getOrNull(row) ?: return@withTextTable table
        val cells = r.cells.toMutableList()
        val cell = cells.getOrNull(col) ?: return@withTextTable table
        cells[col] = cell.copy(backgroundColor = color)
        rows[row] = OdfTableRow(cells)
        table.copy(rows = rows)
    }

    /** Merge a rectangular block of text-table cells (C27). */
    fun mergeTextTableCells(blockIndex: Int, startRow: Int, startCol: Int, endRow: Int, endCol: Int) = withTextTable(blockIndex) { table ->
        val rows = table.rows.toMutableList()
        val colSpan = endCol - startCol + 1
        val rowSpan = endRow - startRow + 1
        if (colSpan < 1 || rowSpan < 1) return@withTextTable table
        for (r in startRow..endRow) {
            val rr = rows.getOrNull(r) ?: continue
            val cells = rr.cells.toMutableList()
            for (c in startCol..endCol) {
                if (c !in cells.indices) continue
                cells[c] = if (r == startRow && c == startCol) cells[c].copy(colSpan = colSpan, rowSpan = rowSpan)
                else cells[c].copy(isCovered = true)
            }
            rows[r] = OdfTableRow(cells)
        }
        table.copy(rows = rows)
    }

    fun unmergeTextTableCells(blockIndex: Int, row: Int, col: Int) = withTextTable(blockIndex) { table ->
        val rows = table.rows.toMutableList()
        val cell = rows.getOrNull(row)?.cells?.getOrNull(col) ?: return@withTextTable table
        if (cell.colSpan <= 1 && cell.rowSpan <= 1) return@withTextTable table
        for (r in row until minOf(row + cell.rowSpan, rows.size)) {
            val cells = rows[r].cells.toMutableList()
            for (c in col until minOf(col + cell.colSpan, cells.size)) {
                cells[c] = if (r == row && c == col) cells[c].copy(colSpan = 1, rowSpan = 1) else cells[c].copy(isCovered = false)
            }
            rows[r] = OdfTableRow(cells)
        }
        table.copy(rows = rows)
    }

    fun insertChart(blockIndex: Int, chart: OdfChart) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
        val content = doc.content.toMutableList()
        val at = (blockIndex + 1).coerceIn(0, content.size)
        content.add(at, OdfContentBlock.Chart(chart))
        updateDocument(doc.copy(content = content))
    }

    fun updateChart(blockIndex: Int, chart: OdfChart) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
        if (doc.content.getOrNull(blockIndex) !is OdfContentBlock.Chart) return
        val content = doc.content.toMutableList()
        content[blockIndex] = OdfContentBlock.Chart(chart)
        updateDocument(doc.copy(content = content))
    }

    fun addParagraphAfter(blockIndex: Int) {
        val doc = curText() ?: return
        updateDocument(doc.addParagraphAfter(blockIndex))
    }

    fun deleteParagraph(blockIndex: Int) {
        val doc = curText() ?: return
        updateDocument(doc.deleteParagraph(blockIndex) ?: return)
    }

    fun toggleBold(blockIndex: Int) = toggleSpanFormat(blockIndex) { it.copy(bold = !it.bold) }
    fun toggleItalic(blockIndex: Int) = toggleSpanFormat(blockIndex) { it.copy(italic = !it.italic) }
    fun toggleUnderline(blockIndex: Int) = toggleSpanFormat(blockIndex) { it.copy(underline = !it.underline) }
    fun toggleStrikethrough(blockIndex: Int) = toggleSpanFormat(blockIndex) { it.copy(strikethrough = !it.strikethrough) }

    fun setSpanColor(blockIndex: Int, color: Long?) = toggleSpanFormat(blockIndex) { it.copy(color = color) }
    fun setSpanFontSize(blockIndex: Int, size: Float) = toggleSpanFormat(blockIndex) { it.copy(fontSize = size) }

    fun setParagraphStyle(blockIndex: Int, style: ParagraphStyle) {
        val doc = curText() ?: return
        updateDocument(doc.setParagraphStyle(blockIndex, style) ?: return)
    }

    fun setParagraphAlignment(blockIndex: Int, alignment: androidx.compose.ui.text.style.TextAlign?) {
        val doc = curText() ?: return
        updateDocument(doc.setParagraphAlignment(blockIndex, alignment) ?: return)
    }

    fun replaceInDocument(search: String, replacement: String, replaceAll: Boolean, matchCase: Boolean = false, wholeWord: Boolean = false): Int {
        if (search.isEmpty()) return 0
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return 0
        val opts = if (matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE)
        val boundary = if (wholeWord) "\\b" else ""
        val pattern = try { Regex(boundary + Regex.escape(search) + boundary, opts) } catch (_: Exception) { return 0 }
        val content = doc.content.toMutableList()
        var count = 0
        for (i in content.indices) {
            val block = content[i] as? OdfContentBlock.Paragraph ?: continue
            val para = block.paragraph
            var changed = false
            val newSpans = para.spans.map { span ->
                if (!replaceAll && count > 0) return@map span
                val matches = pattern.findAll(span.text).toList()
                if (matches.isEmpty()) return@map span
                changed = true
                if (replaceAll) {
                    count += matches.size
                    span.copy(text = pattern.replace(span.text) { replacement })
                } else {
                    val m = matches.first()
                    count += 1
                    span.copy(text = span.text.substring(0, m.range.first) + replacement + span.text.substring(m.range.last + 1))
                }
            }
            if (changed) content[i] = OdfContentBlock.Paragraph(para.copy(spans = newSpans))
        }
        if (count > 0) updateDocument(doc.copy(content = content))
        return count
    }

    /** Returns the content block indices that contain a match for the query (B23 find-next navigation). */
    fun findMatchBlocks(search: String, matchCase: Boolean = false, wholeWord: Boolean = false): List<Int> {
        if (search.isEmpty()) return emptyList()
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return emptyList()
        val opts = if (matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE)
        val boundary = if (wholeWord) "\\b" else ""
        val pattern = try { Regex(boundary + Regex.escape(search) + boundary, opts) } catch (_: Exception) { return emptyList() }
        val result = mutableListOf<Int>()
        doc.content.forEachIndexed { i, block ->
            if (block is OdfContentBlock.Paragraph && block.paragraph.spans.any { pattern.containsMatchIn(it.text) }) result.add(i)
        }
        return result
    }

    fun duplicateParagraph(blockIndex: Int) {
        val doc = curText() ?: return
        updateDocument(doc.duplicateParagraph(blockIndex) ?: return)
    }

    fun moveParagraphUp(blockIndex: Int) {
        val doc = curText() ?: return
        updateDocument(doc.moveParagraphUp(blockIndex) ?: return)
    }

    fun moveParagraphDown(blockIndex: Int) {
        val doc = curText() ?: return
        updateDocument(doc.moveParagraphDown(blockIndex) ?: return)
    }

    fun toggleListItem(blockIndex: Int) {
        val doc = curText() ?: return
        updateDocument(doc.toggleListItem(blockIndex) ?: return)
    }

    fun toggleNumberedList(blockIndex: Int) {
        val doc = curText() ?: return
        updateDocument(doc.toggleNumberedList(blockIndex) ?: return)
    }

    fun indentParagraph(blockIndex: Int) {
        val doc = curText() ?: return
        updateDocument(doc.indentParagraph(blockIndex) ?: return)
    }

    fun outdentParagraph(blockIndex: Int) {
        val doc = curText() ?: return
        updateDocument(doc.outdentParagraph(blockIndex) ?: return)
    }

    fun insertTable(blockIndex: Int, rows: Int, cols: Int) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
        val content = doc.content.toMutableList()
        val tableRows = (0 until rows).map { OdfTableRow((0 until cols).map { OdfTableCell(listOf(OdfParagraph(listOf(OdfSpan(text = ""))))) }) }
        content.add(blockIndex + 1, OdfContentBlock.Table(OdfTable("Table", emptyList(), tableRows)))
        updateDocument(doc.copy(content = content))
    }

    fun insertPageBreak(blockIndex: Int) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
        val content = doc.content.toMutableList()
        content.add(blockIndex + 1, OdfContentBlock.PageBreak)
        updateDocument(doc.copy(content = content))
    }

    fun insertHyperlink(blockIndex: Int, text: String, url: String) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
        val content = doc.content.toMutableList()
        val linkSpan = OdfSpan(text = text, href = url, underline = true, color = 0xFF0066CCL)
        content.add(blockIndex + 1, OdfContentBlock.Paragraph(OdfParagraph(listOf(linkSpan))))
        updateDocument(doc.copy(content = content))
    }

    fun addBookmark(name: String, contentIndex: Int) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
        val bookmarks = doc.bookmarks.toMutableList()
        bookmarks.add(OdfBookmark(name, contentIndex))
        updateDocument(doc.copy(bookmarks = bookmarks))
    }

    fun removeBookmark(name: String) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
        val bookmarks = doc.bookmarks.filter { it.name != name }
        updateDocument(doc.copy(bookmarks = bookmarks))
    }

    /** Edit document metadata (G47). */
    fun updateMetadata(transform: (OdfMetadata) -> OdfMetadata) {
        val doc = (_state.value as? ViewState.Loaded)?.document ?: return
        val newDoc = when (doc) {
            is OdfDocument.TextDocument -> doc.copy(metadata = transform(doc.metadata))
            is OdfDocument.Spreadsheet -> doc.copy(metadata = transform(doc.metadata))
            is OdfDocument.Presentation -> doc.copy(metadata = transform(doc.metadata))
            is OdfDocument.Drawing -> doc.copy(metadata = transform(doc.metadata))
        }
        updateDocument(newDoc)
    }

    private fun toggleSpanFormat(blockIndex: Int, transform: (OdfSpan) -> OdfSpan) {
        val doc = curText() ?: return
        updateDocument(doc.toggleSpanFormat(blockIndex, transform) ?: return)
    }

    // --- Spreadsheet editing ---

    fun updateCellText(sheetIndex: Int, rowIndex: Int, cellIndex: Int, newText: String) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
        val sheets = doc.sheets.toMutableList()
        val sheet = sheets.getOrNull(sheetIndex) ?: return
        val rows = sheet.rows.toMutableList()
        val row = rows.getOrNull(rowIndex) ?: return
        val cells = row.cells.toMutableList()
        if (cellIndex !in cells.indices) return
        cells[cellIndex] = if (newText.startsWith("=")) {
            // Typed formula (H49): store as OpenFormula, drop cached numeric value.
            cells[cellIndex].copy(text = newText, formula = newText, valueType = "float", numberValue = null)
        } else {
            cells[cellIndex].copy(text = newText, formula = null,
                valueType = if (newText.toDoubleOrNull() != null) "float" else "string",
                numberValue = newText.toDoubleOrNull())
        }
        rows[rowIndex] = OdfRow(cells)
        sheets[sheetIndex] = sheet.copy(rows = rows)
        updateDocument(doc.copy(sheets = sheets))
    }

    fun addRow(sheetIndex: Int, afterRowIndex: Int) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
        val sheets = doc.sheets.toMutableList()
        val sheet = sheets.getOrNull(sheetIndex) ?: return
        val rows = sheet.rows.toMutableList()
        val colCount = rows.getOrNull(afterRowIndex)?.cells?.size ?: 1
        rows.add(afterRowIndex + 1, OdfRow(List(colCount) { OdfCell(text = "") }))
        sheets[sheetIndex] = sheet.copy(rows = rows)
        updateDocument(doc.copy(sheets = sheets))
    }

    fun addColumn(sheetIndex: Int) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
        val sheets = doc.sheets.toMutableList()
        val sheet = sheets.getOrNull(sheetIndex) ?: return
        val rows = sheet.rows.map { row -> OdfRow(row.cells + OdfCell(text = "")) }
        sheets[sheetIndex] = sheet.copy(rows = rows)
        updateDocument(doc.copy(sheets = sheets))
    }

    fun deleteRow(sheetIndex: Int, rowIndex: Int) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
        val sheets = doc.sheets.toMutableList()
        val sheet = sheets.getOrNull(sheetIndex) ?: return
        if (sheet.rows.size <= 1 || rowIndex !in sheet.rows.indices) return
        val rows = sheet.rows.toMutableList()
        rows.removeAt(rowIndex)
        sheets[sheetIndex] = sheet.copy(rows = rows)
        updateDocument(doc.copy(sheets = sheets))
    }

    fun deleteColumn(sheetIndex: Int, colIndex: Int) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
        val sheets = doc.sheets.toMutableList()
        val sheet = sheets.getOrNull(sheetIndex) ?: return
        val rows = sheet.rows.map { row ->
            val cells = row.cells.toMutableList()
            if (colIndex < cells.size && cells.size > 1) cells.removeAt(colIndex)
            OdfRow(cells)
        }
        sheets[sheetIndex] = sheet.copy(rows = rows)
        updateDocument(doc.copy(sheets = sheets))
    }

    fun renameSheet(sheetIndex: Int, newName: String) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
        val sheets = doc.sheets.toMutableList()
        val sheet = sheets.getOrNull(sheetIndex) ?: return
        sheets[sheetIndex] = sheet.copy(name = newName)
        updateDocument(doc.copy(sheets = sheets))
    }

    fun addSheet() {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
        val sheets = doc.sheets.toMutableList()
        val rows = (0 until 10).map { OdfRow(List(5) { OdfCell(text = "") }) }
        sheets.add(OdfSheet("Sheet ${sheets.size + 1}", rows))
        updateDocument(doc.copy(sheets = sheets))
    }

    fun deleteSheet(sheetIndex: Int) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
        if (doc.sheets.size <= 1 || sheetIndex !in doc.sheets.indices) return
        val sheets = doc.sheets.toMutableList()
        sheets.removeAt(sheetIndex)
        updateDocument(doc.copy(sheets = sheets))
    }

    fun setCellBold(sheetIndex: Int, rowIndex: Int, cellIndex: Int) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
        modifyCell(doc, sheetIndex, rowIndex, cellIndex) { it.copy(bold = !it.bold) }
    }

    fun setCellItalic(sheetIndex: Int, rowIndex: Int, cellIndex: Int) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
        modifyCell(doc, sheetIndex, rowIndex, cellIndex) { it.copy(italic = !it.italic) }
    }

    fun setCellColor(sheetIndex: Int, rowIndex: Int, cellIndex: Int, color: Long?) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
        modifyCell(doc, sheetIndex, rowIndex, cellIndex) { it.copy(textColor = color) }
    }

    fun setCellBgColor(sheetIndex: Int, rowIndex: Int, cellIndex: Int, color: Long?) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
        modifyCell(doc, sheetIndex, rowIndex, cellIndex) { it.copy(backgroundColor = color) }
    }

    fun setCellAlignment(sheetIndex: Int, rowIndex: Int, cellIndex: Int, alignment: androidx.compose.ui.text.style.TextAlign?) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
        modifyCell(doc, sheetIndex, rowIndex, cellIndex) { it.copy(alignment = alignment) }
    }

    /** Sets a cell border color. (C2) */
    fun setCellBorder(sheetIndex: Int, rowIndex: Int, cellIndex: Int, color: Long?) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
        modifyCell(doc, sheetIndex, rowIndex, cellIndex) { it.copy(borderColor = color) }
    }

    /** Sets a cell's number/date/currency/percentage display format. (C2/B6) */
    fun setCellNumberFormat(sheetIndex: Int, rowIndex: Int, cellIndex: Int, format: OdfNumberFormat?) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
        modifyCell(doc, sheetIndex, rowIndex, cellIndex) {
            it.copy(numberFormat = format, valueType = when {
                format == null -> it.valueType
                format.isDate -> "date"
                format.percent -> "percentage"
                format.currencySymbol != null -> "currency"
                else -> "float"
            })
        }
    }

    /** Sets freeze panes on a sheet: freeze the top [rows] rows and left [cols] columns. (C2) */
    fun setSheetFreeze(sheetIndex: Int, rows: Int, cols: Int) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
        val sheets = doc.sheets.toMutableList()
        val sheet = sheets.getOrNull(sheetIndex) ?: return
        sheets[sheetIndex] = sheet.copy(freezeRows = rows.coerceAtLeast(0), freezeCols = cols.coerceAtLeast(0))
        updateDocument(doc.copy(sheets = sheets))
    }

    /** Fills the source cell down to the last row of the sheet. (C2) */
    fun fillDownToEnd(sheetIndex: Int, srcRow: Int, col: Int) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
        val last = doc.sheets.getOrNull(sheetIndex)?.rows?.lastIndex ?: return
        fillDown(sheetIndex, srcRow, col, last)
    }

    /** Copies the source cell's value/formula/format down to the rows below it in the same column. (C2) */
    fun fillDown(sheetIndex: Int, srcRow: Int, col: Int, toRow: Int) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
        val sheets = doc.sheets.toMutableList()
        val sheet = sheets.getOrNull(sheetIndex) ?: return
        val src = sheet.rows.getOrNull(srcRow)?.cells?.getOrNull(col) ?: return
        if (toRow <= srcRow) return
        val rows = sheet.rows.toMutableList()
        for (r in (srcRow + 1)..toRow) {
            val row = rows.getOrNull(r) ?: continue
            if (col !in row.cells.indices) continue
            val cells = row.cells.toMutableList()
            cells[col] = cells[col].copy(
                text = src.text, formula = src.formula, valueType = src.valueType,
                numberValue = src.numberValue, numberFormat = src.numberFormat,
                bold = src.bold, italic = src.italic, textColor = src.textColor,
                backgroundColor = src.backgroundColor, alignment = src.alignment
            )
            rows[r] = OdfRow(cells)
        }
        sheets[sheetIndex] = sheet.copy(rows = rows)
        updateDocument(doc.copy(sheets = sheets))
    }

    fun mergeCells(sheetIndex: Int, startRow: Int, startCol: Int, endRow: Int, endCol: Int) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
        val sheets = doc.sheets.toMutableList()
        val sheet = sheets.getOrNull(sheetIndex) ?: return
        val rows = sheet.rows.toMutableList()
        val colSpan = endCol - startCol + 1
        val rowSpan = endRow - startRow + 1
        for (r in startRow..endRow) {
            if (r >= rows.size) continue
            val cells = rows[r].cells.toMutableList()
            for (c in startCol..endCol) {
                if (c >= cells.size) continue
                cells[c] = if (r == startRow && c == startCol) {
                    cells[c].copy(spannedColumns = colSpan, rowSpan = rowSpan)
                } else {
                    cells[c].copy(isCovered = true)
                }
            }
            rows[r] = OdfRow(cells)
        }
        sheets[sheetIndex] = sheet.copy(rows = rows)
        updateDocument(doc.copy(sheets = sheets))
    }

    fun unmergeCells(sheetIndex: Int, rowIndex: Int, cellIndex: Int) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
        val sheets = doc.sheets.toMutableList()
        val sheet = sheets[sheetIndex]
        val rows = sheet.rows.toMutableList()
        val cell = rows.getOrNull(rowIndex)?.cells?.getOrNull(cellIndex) ?: return
        if (cell.spannedColumns <= 1 && cell.rowSpan <= 1) return
        for (r in rowIndex until minOf(rowIndex + cell.rowSpan, rows.size)) {
            val cells = rows[r].cells.toMutableList()
            for (c in cellIndex until minOf(cellIndex + cell.spannedColumns, cells.size)) {
                cells[c] = if (r == rowIndex && c == cellIndex) cells[c].copy(spannedColumns = 1, rowSpan = 1) else cells[c].copy(isCovered = false)
            }
            rows[r] = OdfRow(cells)
        }
        sheets[sheetIndex] = sheet.copy(rows = rows)
        updateDocument(doc.copy(sheets = sheets))
    }

    fun sortRows(sheetIndex: Int, colIndex: Int, ascending: Boolean) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
        val sheets = doc.sheets.toMutableList()
        val sheet = sheets.getOrNull(sheetIndex) ?: return
        val sorted = sheet.rows.sortedWith(compareBy<OdfRow> {
            val text = it.cells.getOrNull(colIndex)?.text ?: ""
            text.toDoubleOrNull() ?: Double.MAX_VALUE
        }.thenBy { it.cells.getOrNull(colIndex)?.text ?: "" })
        sheets[sheetIndex] = sheet.copy(rows = if (ascending) sorted else sorted.reversed())
        updateDocument(doc.copy(sheets = sheets))
    }

    private fun modifyCell(doc: OdfDocument.Spreadsheet, sheetIndex: Int, rowIndex: Int, cellIndex: Int, transform: (OdfCell) -> OdfCell) {
        val sheets = doc.sheets.toMutableList()
        val sheet = sheets.getOrNull(sheetIndex) ?: return
        val rows = sheet.rows.toMutableList()
        val row = rows.getOrNull(rowIndex) ?: return
        val cells = row.cells.toMutableList()
        if (cellIndex !in cells.indices) return
        cells[cellIndex] = transform(cells[cellIndex])
        rows[rowIndex] = OdfRow(cells)
        sheets[sheetIndex] = sheet.copy(rows = rows)
        updateDocument(doc.copy(sheets = sheets))
    }

    // --- Presentation editing ---

    fun addSlide(afterIndex: Int) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
        val slides = doc.slides.toMutableList()
        slides.add(afterIndex + 1, OdfSlide(
            name = "Slide ${slides.size + 1}",
            elements = listOf(OdfSlideElement.Frame(OdfFrame(
                x = 50f, y = 50f, width = 600f, height = 100f,
                paragraphs = listOf(OdfParagraph(listOf(OdfSpan(text = "New Slide", bold = true, fontSize = 28f))))
            )))
        ))
        updateDocument(doc.copy(slides = slides))
    }

    fun deleteSlide(index: Int) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
        if (doc.slides.size <= 1) return
        val slides = doc.slides.toMutableList()
        slides.removeAt(index)
        updateDocument(doc.copy(slides = slides))
    }

    fun duplicateSlide(index: Int) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
        val slides = doc.slides.toMutableList()
        slides.add(index + 1, slides[index].copy(name = "${slides[index].name} (copy)"))
        updateDocument(doc.copy(slides = slides))
    }

    fun moveSlideUp(index: Int) {
        if (index <= 0) return
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
        val slides = doc.slides.toMutableList()
        val item = slides.removeAt(index)
        slides.add(index - 1, item)
        updateDocument(doc.copy(slides = slides))
    }

    fun moveSlideDown(index: Int) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
        if (index >= doc.slides.size - 1) return
        val slides = doc.slides.toMutableList()
        val item = slides.removeAt(index)
        slides.add(index + 1, item)
        updateDocument(doc.copy(slides = slides))
    }

    /** Edits the text of a slide element (frame or shape), preserving the first span's formatting (I62). */
    fun updateSlideElementText(slideIndex: Int, elementIndex: Int, newText: String) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
        val slides = doc.slides.toMutableList()
        val slide = slides.getOrNull(slideIndex) ?: return
        val elements = slide.elements.toMutableList()
        val el = elements.getOrNull(elementIndex) ?: return
        fun rebuild(old: List<OdfParagraph>): List<OdfParagraph> {
            val template = old.firstOrNull()?.spans?.firstOrNull()?.copy(text = "") ?: OdfSpan(text = "")
            val style = old.firstOrNull()?.style ?: ParagraphStyle.BODY
            return newText.split("\n").map { line -> OdfParagraph(listOf(template.copy(text = line)), style = style) }
        }
        elements[elementIndex] = when (el) {
            is OdfSlideElement.Frame -> OdfSlideElement.Frame(el.frame.copy(paragraphs = rebuild(el.frame.paragraphs)))
            is OdfSlideElement.Shape -> {
                val s = el.shape
                val t = rebuild(s.text)
                OdfSlideElement.Shape(when (s) {
                    is OdfShape.Rect -> s.copy(text = t)
                    is OdfShape.Ellipse -> s.copy(text = t)
                    is OdfShape.Line -> s.copy(text = t)
                    is OdfShape.CustomShape -> s.copy(text = t)
                    is OdfShape.Polyline -> s.copy(text = t)
                })
            }
        }
        slides[slideIndex] = slide.copy(elements = elements)
        updateDocument(doc.copy(slides = slides))
    }

    /** Adds an empty text box to a slide (I62). */
    fun addTextBoxToSlide(slideIndex: Int) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
        val slides = doc.slides.toMutableList()
        val slide = slides.getOrNull(slideIndex) ?: return
        val frame = OdfFrame(
            x = 60f, y = 200f + slide.elements.size * 20f, width = 600f, height = 80f,
            paragraphs = listOf(OdfParagraph(listOf(OdfSpan(text = "New text", fontSize = 20f))))
        )
        slides[slideIndex] = slide.copy(elements = slide.elements + OdfSlideElement.Frame(frame))
        updateDocument(doc.copy(slides = slides))
    }

    /** Adds a shape to a slide at a default centered rect. kind = "rect"|"ellipse"|"line". (Phase 3) */
    fun addShapeToSlide(slideIndex: Int, kind: String) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
        val slides = doc.slides.toMutableList()
        val slide = slides.getOrNull(slideIndex) ?: return
        val x = 329f; val y = 247f; val w = 400f; val h = 300f
        val shape: OdfShape = when (kind) {
            "ellipse" -> OdfShape.Ellipse(x, y, w, h, fillColor = 0xFFB3D1FFL, strokeColor = 0xFF1F6FC0L, strokeWidth = 2f)
            "line" -> OdfShape.Line(x, y, w, 0f, strokeColor = 0xFF333333L, strokeWidth = 2f, x2 = x + w, y2 = y)
            else -> OdfShape.Rect(x, y, w, h, fillColor = 0xFFB3D1FFL, strokeColor = 0xFF1F6FC0L, strokeWidth = 2f)
        }
        slides[slideIndex] = slide.copy(elements = slide.elements + OdfSlideElement.Shape(shape))
        updateDocument(doc.copy(slides = slides))
    }

    /** Inserts an image as a floating frame on a slide. (Phase 3) */
    fun insertImageIntoSlide(slideIndex: Int, fileName: String, bytes: ByteArray) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
        val slides = doc.slides.toMutableList()
        val slide = slides.getOrNull(slideIndex) ?: return
        val path = uniqueImagePath(doc.images.keys, fileName)
        val frame = OdfFrame(x = 300f, y = 200f, width = 400f, height = 300f, paragraphs = emptyList(),
            image = OdfImage(path = path, imageData = bytes))
        slides[slideIndex] = slide.copy(elements = slide.elements + OdfSlideElement.Frame(frame))
        updateDocument(doc.copy(slides = slides, images = doc.images + (path to bytes)))
    }

    /** Inserts a chart as a floating frame on a slide. (Phase 3) */
    fun insertChartIntoSlide(slideIndex: Int, chart: OdfChart) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
        val slides = doc.slides.toMutableList()
        val slide = slides.getOrNull(slideIndex) ?: return
        val frame = OdfFrame(x = 250f, y = 180f, width = 520f, height = 360f, paragraphs = emptyList(), chart = chart)
        slides[slideIndex] = slide.copy(elements = slide.elements + OdfSlideElement.Frame(frame))
        updateDocument(doc.copy(slides = slides))
    }

    // --- Slide chrome (background / transition / speaker notes) ---

    private fun mutateSlide(slideIndex: Int, transform: (OdfSlide) -> OdfSlide) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
        val slides = doc.slides.toMutableList()
        val slide = slides.getOrNull(slideIndex) ?: return
        slides[slideIndex] = transform(slide)
        updateDocument(doc.copy(slides = slides))
    }

    /** Sets speaker notes for a slide (one paragraph per line; blank clears them). */
    fun setSlideNotes(slideIndex: Int, text: String) = mutateSlide(slideIndex) { slide ->
        slide.copy(notes = if (text.isBlank()) emptyList() else text.split("\n").map { OdfParagraph(listOf(OdfSpan(it))) })
    }

    /** Current speaker-notes text for a slide, joined by newlines. */
    fun slideNotesText(slideIndex: Int): String {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return ""
        return doc.slides.getOrNull(slideIndex)?.notes?.joinToString("\n") { p -> p.spans.joinToString("") { it.text } } ?: ""
    }

    /** Sets (or clears with null) the solid background color of a slide. */
    fun setSlideBackgroundColor(slideIndex: Int, color: Long?) = mutateSlide(slideIndex) { it.copy(backgroundColor = color) }

    /** Sets the slide transition type (e.g. "fade","wipe","dissolve"; null clears) and speed. */
    fun setSlideTransition(slideIndex: Int, type: String?, speed: String?) =
        mutateSlide(slideIndex) { it.copy(transitionType = type, transitionSpeed = speed) }

    /** Rotates ANY slide element by delta degrees: shapes rotate via their angle, image frames via the image. */
    fun setSlideElementRotation(slideIndex: Int, elementIndex: Int, deltaDegrees: Float) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
        val slides = doc.slides.toMutableList()
        val slide = slides.getOrNull(slideIndex) ?: return
        val elements = slide.elements.toMutableList()
        val el = elements.getOrNull(elementIndex) ?: return
        elements[elementIndex] = rotateElement(el, deltaDegrees)
        slides[slideIndex] = slide.copy(elements = elements)
        updateDocument(doc.copy(slides = slides))
    }

    private fun rotateElement(el: OdfSlideElement, delta: Float): OdfSlideElement = when (el) {
        is OdfSlideElement.Frame -> el.frame.image?.let {
            OdfSlideElement.Frame(el.frame.copy(image = it.copy(rotationDegrees = (it.rotationDegrees + delta) % 360f)))
        } ?: el
        is OdfSlideElement.Shape -> {
            val s = el.shape; val nd = (s.rotationDegrees + delta) % 360f
            OdfSlideElement.Shape(when (s) {
                is OdfShape.Rect -> s.copy(rotationDegrees = nd)
                is OdfShape.Ellipse -> s.copy(rotationDegrees = nd)
                is OdfShape.Line -> s.copy(rotationDegrees = nd)
                is OdfShape.CustomShape -> s.copy(rotationDegrees = nd)
                is OdfShape.Polyline -> s.copy(rotationDegrees = nd)
            })
        }
    }

    // --- Spreadsheet cell comments & sizing ---

    /** Sets (or clears with blank text) a comment/note on a cell. */
    fun setCellComment(sheetIndex: Int, rowIndex: Int, cellIndex: Int, author: String, text: String) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
        modifyCell(doc, sheetIndex, rowIndex, cellIndex) {
            it.copy(annotation = if (text.isBlank()) null else OdfAnnotation(
                author = author.ifBlank { null },
                paragraphs = listOf(OdfParagraph(listOf(OdfSpan(text))))
            ))
        }
    }

    /** Current comment text on a cell, or "". */
    fun cellCommentText(sheetIndex: Int, rowIndex: Int, cellIndex: Int): String {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return ""
        val cell = doc.sheets.getOrNull(sheetIndex)?.rows?.getOrNull(rowIndex)?.cells?.getOrNull(cellIndex) ?: return ""
        return cell.annotation?.paragraphs?.joinToString("\n") { p -> p.spans.joinToString("") { it.text } } ?: ""
    }

    /** Sets a column width in px@96 (null resets to default). */
    fun setColumnWidth(sheetIndex: Int, col: Int, widthPx: Float?) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
        val sheets = doc.sheets.toMutableList()
        val sheet = sheets.getOrNull(sheetIndex) ?: return
        val widths = sheet.columnWidths.toMutableList()
        while (widths.size <= col) widths.add(null)
        widths[col] = widthPx
        sheets[sheetIndex] = sheet.copy(columnWidths = widths)
        updateDocument(doc.copy(sheets = sheets))
    }

    /** Sets a row height in px@96 (null resets to default). */
    fun setRowHeight(sheetIndex: Int, row: Int, heightPx: Float?) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
        val sheets = doc.sheets.toMutableList()
        val sheet = sheets.getOrNull(sheetIndex) ?: return
        val heights = sheet.rowHeights.toMutableList()
        while (heights.size <= row) heights.add(null)
        heights[row] = heightPx
        sheets[sheetIndex] = sheet.copy(rowHeights = heights)
        updateDocument(doc.copy(sheets = sheets))
    }

    // --- Spreadsheet floating objects (Phase 4) ---

    private fun mutateSheetFloating(sheetIndex: Int, transform: (List<OdfSlideElement>) -> List<OdfSlideElement>) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
        val sheets = doc.sheets.toMutableList()
        val sheet = sheets.getOrNull(sheetIndex) ?: return
        sheets[sheetIndex] = sheet.copy(floating = transform(sheet.floating))
        updateDocument(doc.copy(sheets = sheets))
    }

    fun addShapeToSheet(sheetIndex: Int, kind: String) {
        val x = 120f; val y = 120f; val w = 300f; val h = 200f
        val shape: OdfShape = when (kind) {
            "ellipse" -> OdfShape.Ellipse(x, y, w, h, fillColor = 0xFFB3D1FFL, strokeColor = 0xFF1F6FC0L, strokeWidth = 2f)
            "line" -> OdfShape.Line(x, y, w, 0f, strokeColor = 0xFF333333L, strokeWidth = 2f, x2 = x + w, y2 = y)
            else -> OdfShape.Rect(x, y, w, h, fillColor = 0xFFB3D1FFL, strokeColor = 0xFF1F6FC0L, strokeWidth = 2f)
        }
        mutateSheetFloating(sheetIndex) { it + OdfSlideElement.Shape(shape) }
    }

    fun insertImageIntoSheet(sheetIndex: Int, fileName: String, bytes: ByteArray) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
        val sheets = doc.sheets.toMutableList()
        val sheet = sheets.getOrNull(sheetIndex) ?: return
        val path = uniqueImagePath(doc.images.keys, fileName)
        val frame = OdfFrame(x = 100f, y = 100f, width = 320f, height = 240f, paragraphs = emptyList(),
            image = OdfImage(path = path, imageData = bytes))
        sheets[sheetIndex] = sheet.copy(floating = sheet.floating + OdfSlideElement.Frame(frame))
        updateDocument(doc.copy(sheets = sheets, images = doc.images + (path to bytes)))
    }

    fun insertChartIntoSheet(sheetIndex: Int, chart: OdfChart) {
        val frame = OdfFrame(x = 80f, y = 80f, width = 480f, height = 320f, paragraphs = emptyList(), chart = chart)
        mutateSheetFloating(sheetIndex) { it + OdfSlideElement.Frame(frame) }
    }

    fun setSheetElementBounds(sheetIndex: Int, elementIndex: Int, x: Float, y: Float, w: Float, h: Float) {
        mutateSheetFloating(sheetIndex) { list ->
            if (elementIndex !in list.indices) list
            else list.toMutableList().also { it[elementIndex] = setElementBounds(it[elementIndex], x, y, w, h) }
        }
    }

    fun deleteSheetElement(sheetIndex: Int, elementIndex: Int) {
        mutateSheetFloating(sheetIndex) { list -> list.filterIndexed { i, _ -> i != elementIndex } }
    }

    fun updateSheetElementText(sheetIndex: Int, elementIndex: Int, newText: String) {
        mutateSheetFloating(sheetIndex) { list ->
            if (elementIndex !in list.indices) return@mutateSheetFloating list
            list.toMutableList().also { it[elementIndex] = setSlideElementTextOn(it[elementIndex], newText) }
        }
    }

    private fun setSlideElementTextOn(el: OdfSlideElement, newText: String): OdfSlideElement {
        fun rebuild(old: List<OdfParagraph>): List<OdfParagraph> {
            val template = old.firstOrNull()?.spans?.firstOrNull()?.copy(text = "") ?: OdfSpan(text = "")
            val style = old.firstOrNull()?.style ?: ParagraphStyle.BODY
            return newText.split("\n").map { line -> OdfParagraph(listOf(template.copy(text = line)), style = style) }
        }
        return when (el) {
            is OdfSlideElement.Frame -> OdfSlideElement.Frame(el.frame.copy(paragraphs = rebuild(el.frame.paragraphs)))
            is OdfSlideElement.Shape -> {
                val s = el.shape; val t = rebuild(s.text)
                OdfSlideElement.Shape(when (s) {
                    is OdfShape.Rect -> s.copy(text = t); is OdfShape.Ellipse -> s.copy(text = t)
                    is OdfShape.Line -> s.copy(text = t); is OdfShape.CustomShape -> s.copy(text = t)
                    is OdfShape.Polyline -> s.copy(text = t)
                })
            }
        }
    }

    private fun elementParas(el: OdfSlideElement): List<OdfParagraph> = when (el) {
        is OdfSlideElement.Frame -> el.frame.paragraphs
        is OdfSlideElement.Shape -> el.shape.text
    }

    private fun mutateSlideElementSpans(slideIndex: Int, elementIndex: Int, transform: (OdfSpan) -> OdfSpan) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
        val slides = doc.slides.toMutableList()
        val slide = slides.getOrNull(slideIndex) ?: return
        val elements = slide.elements.toMutableList()
        val el = elements.getOrNull(elementIndex) ?: return
        fun map(ps: List<OdfParagraph>) = ps.map { p -> p.copy(spans = p.spans.map(transform)) }
        elements[elementIndex] = when (el) {
            is OdfSlideElement.Frame -> OdfSlideElement.Frame(el.frame.copy(paragraphs = map(el.frame.paragraphs)))
            is OdfSlideElement.Shape -> {
                val s = el.shape; val t = map(s.text)
                OdfSlideElement.Shape(when (s) {
                    is OdfShape.Rect -> s.copy(text = t); is OdfShape.Ellipse -> s.copy(text = t)
                    is OdfShape.Line -> s.copy(text = t); is OdfShape.CustomShape -> s.copy(text = t)
                    is OdfShape.Polyline -> s.copy(text = t)
                })
            }
        }
        slides[slideIndex] = slide.copy(elements = elements)
        updateDocument(doc.copy(slides = slides))
    }

    private fun firstSpan(slideIndex: Int, elementIndex: Int): OdfSpan? {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return null
        val el = doc.slides.getOrNull(slideIndex)?.elements?.getOrNull(elementIndex) ?: return null
        return elementParas(el).firstOrNull()?.spans?.firstOrNull()
    }

    fun toggleSlideElementBold(s: Int, e: Int) { val cur = firstSpan(s, e)?.bold == true; mutateSlideElementSpans(s, e) { it.copy(bold = !cur) } }
    fun toggleSlideElementItalic(s: Int, e: Int) { val cur = firstSpan(s, e)?.italic == true; mutateSlideElementSpans(s, e) { it.copy(italic = !cur) } }
    fun toggleSlideElementUnderline(s: Int, e: Int) { val cur = firstSpan(s, e)?.underline == true; mutateSlideElementSpans(s, e) { it.copy(underline = !cur) } }
    fun setSlideElementColor(s: Int, e: Int, color: Long?) { mutateSlideElementSpans(s, e) { it.copy(color = color) } }

    /** Sets paragraph alignment for all paragraphs in a slide element. */
    fun setSlideElementAlignment(slideIndex: Int, elementIndex: Int, alignment: androidx.compose.ui.text.style.TextAlign?) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
        val slides = doc.slides.toMutableList()
        val slide = slides.getOrNull(slideIndex) ?: return
        val elements = slide.elements.toMutableList()
        val el = elements.getOrNull(elementIndex) ?: return
        fun map(ps: List<OdfParagraph>) = ps.map { it.copy(alignment = alignment) }
        elements[elementIndex] = when (el) {
            is OdfSlideElement.Frame -> OdfSlideElement.Frame(el.frame.copy(paragraphs = map(el.frame.paragraphs)))
            is OdfSlideElement.Shape -> {
                val sh = el.shape; val t = map(sh.text)
                OdfSlideElement.Shape(when (sh) {
                    is OdfShape.Rect -> sh.copy(text = t); is OdfShape.Ellipse -> sh.copy(text = t)
                    is OdfShape.Line -> sh.copy(text = t); is OdfShape.CustomShape -> sh.copy(text = t)
                    is OdfShape.Polyline -> sh.copy(text = t)
                })
            }
        }
        slides[slideIndex] = slide.copy(elements = elements)
        updateDocument(doc.copy(slides = slides))
    }

    /** Moves/resizes a slide element (frame or shape). Coordinates are px@96. (Phase 1) */
    fun setSlideElementBounds(slideIndex: Int, elementIndex: Int, x: Float, y: Float, w: Float, h: Float) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
        val slides = doc.slides.toMutableList()
        val slide = slides.getOrNull(slideIndex) ?: return
        val elements = slide.elements.toMutableList()
        val el = elements.getOrNull(elementIndex) ?: return
        elements[elementIndex] = setElementBounds(el, x, y, w, h)
        slides[slideIndex] = slide.copy(elements = elements)
        updateDocument(doc.copy(slides = slides))
    }

    /** Removes a slide element. (Phase 1) */
    fun deleteSlideElement(slideIndex: Int, elementIndex: Int) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
        val slides = doc.slides.toMutableList()
        val slide = slides.getOrNull(slideIndex) ?: return
        if (elementIndex !in slide.elements.indices) return
        val elements = slide.elements.toMutableList()
        elements.removeAt(elementIndex)
        slides[slideIndex] = slide.copy(elements = elements)
        updateDocument(doc.copy(slides = slides))
    }

    /** Duplicates a slide element, offset slightly so it's visible. (C1) */
    fun duplicateSlideElement(slideIndex: Int, elementIndex: Int) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
        val slides = doc.slides.toMutableList()
        val slide = slides.getOrNull(slideIndex) ?: return
        val el = slide.elements.getOrNull(elementIndex) ?: return
        val b = el.bounds()
        val copy = setElementBounds(el, b[0] + 20f, b[1] + 20f, b[2], b[3])
        val elements = slide.elements.toMutableList()
        elements.add(elementIndex + 1, copy)
        slides[slideIndex] = slide.copy(elements = elements)
        updateDocument(doc.copy(slides = slides))
    }

    /** Reorders a slide element in document order (= ODF render z-order). delta<0 = back, delta>0 = front. (C1) */
    fun reorderSlideElement(slideIndex: Int, elementIndex: Int, toFront: Boolean) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
        val slides = doc.slides.toMutableList()
        val slide = slides.getOrNull(slideIndex) ?: return
        if (elementIndex !in slide.elements.indices) return
        val elements = slide.elements.toMutableList()
        val item = elements.removeAt(elementIndex)
        if (toFront) elements.add(item) else elements.add(0, item)
        slides[slideIndex] = slide.copy(elements = elements)
        updateDocument(doc.copy(slides = slides))
    }

    /** Rotates an image element on a slide by the given delta degrees. (C4) */
    fun rotateSlideImage(slideIndex: Int, elementIndex: Int, deltaDegrees: Float) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
        val slides = doc.slides.toMutableList()
        val slide = slides.getOrNull(slideIndex) ?: return
        val el = slide.elements.getOrNull(elementIndex) as? OdfSlideElement.Frame ?: return
        val img = el.frame.image ?: return
        val elements = slide.elements.toMutableList()
        elements[elementIndex] = OdfSlideElement.Frame(el.frame.copy(image = img.copy(rotationDegrees = (img.rotationDegrees + deltaDegrees) % 360f)))
        slides[slideIndex] = slide.copy(elements = elements)
        updateDocument(doc.copy(slides = slides))
    }

    /** Rotates a text-document image block by delta degrees. (C4) */
    fun rotateTextImage(blockIndex: Int, deltaDegrees: Float) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
        val block = doc.content.getOrNull(blockIndex) as? OdfContentBlock.Image ?: return
        val content = doc.content.toMutableList()
        content[blockIndex] = OdfContentBlock.Image(block.image.copy(rotationDegrees = (block.image.rotationDegrees + deltaDegrees) % 360f))
        updateDocument(doc.copy(content = content))
    }

    /** Rotates an image element on a sheet's floating layer by delta degrees. (C4) */
    fun rotateSheetImage(sheetIndex: Int, elementIndex: Int, deltaDegrees: Float) {
        mutateSheetFloating(sheetIndex) { list ->
            val el = list.getOrNull(elementIndex) as? OdfSlideElement.Frame ?: return@mutateSheetFloating list
            val img = el.frame.image ?: return@mutateSheetFloating list
            list.toMutableList().also { it[elementIndex] = OdfSlideElement.Frame(el.frame.copy(image = img.copy(rotationDegrees = (img.rotationDegrees + deltaDegrees) % 360f))) }
        }
    }

    /** Replaces a text-document image's bytes with a new picture, resetting crop/rotation. (C4) */
    fun replaceTextImage(blockIndex: Int, fileName: String, bytes: ByteArray) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
        val block = doc.content.getOrNull(blockIndex) as? OdfContentBlock.Image ?: return
        val path = uniqueImagePath(doc.images.keys, fileName)
        val content = doc.content.toMutableList()
        content[blockIndex] = OdfContentBlock.Image(block.image.copy(
            path = path, imageData = bytes, naturalWidthPx = 0f, naturalHeightPx = 0f,
            cropLeftPct = 0f, cropTopPct = 0f, cropRightPct = 0f, cropBottomPct = 0f, rotationDegrees = 0f
        ))
        updateDocument(doc.copy(content = content, images = doc.images + (path to bytes)))
    }

    /** Replaces a slide image element's bytes with a new picture. (C4) */
    fun replaceSlideImage(slideIndex: Int, elementIndex: Int, fileName: String, bytes: ByteArray) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
        val slides = doc.slides.toMutableList()
        val slide = slides.getOrNull(slideIndex) ?: return
        val el = slide.elements.getOrNull(elementIndex) as? OdfSlideElement.Frame ?: return
        val frameImage = el.frame.image ?: return
        val path = uniqueImagePath(doc.images.keys, fileName)
        val elements = slide.elements.toMutableList()
        elements[elementIndex] = OdfSlideElement.Frame(el.frame.copy(image = frameImage.copy(
            path = path, imageData = bytes, naturalWidthPx = 0f, naturalHeightPx = 0f,
            cropLeftPct = 0f, cropTopPct = 0f, cropRightPct = 0f, cropBottomPct = 0f, rotationDegrees = 0f
        )))
        slides[slideIndex] = slide.copy(elements = elements)
        updateDocument(doc.copy(slides = slides, images = doc.images + (path to bytes)))
    }

    /** Replaces a sheet floating image element's bytes with a new picture. (C4) */
    fun replaceSheetImage(sheetIndex: Int, elementIndex: Int, fileName: String, bytes: ByteArray) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
        val path = uniqueImagePath(doc.images.keys, fileName)
        val sheets = doc.sheets.toMutableList()
        val sheet = sheets.getOrNull(sheetIndex) ?: return
        val el = sheet.floating.getOrNull(elementIndex) as? OdfSlideElement.Frame ?: return
        val frameImage = el.frame.image ?: return
        val floating = sheet.floating.toMutableList()
        floating[elementIndex] = OdfSlideElement.Frame(el.frame.copy(image = frameImage.copy(
            path = path, imageData = bytes, naturalWidthPx = 0f, naturalHeightPx = 0f,
            cropLeftPct = 0f, cropTopPct = 0f, cropRightPct = 0f, cropBottomPct = 0f, rotationDegrees = 0f
        )))
        sheets[sheetIndex] = sheet.copy(floating = floating)
        updateDocument(doc.copy(sheets = sheets, images = doc.images + (path to bytes)))
    }

    /** Sets crop insets on an image frame element of a slide. (Phase 5) */
    fun setSlideImageCrop(slideIndex: Int, elementIndex: Int, left: Float, top: Float, right: Float, bottom: Float) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
        val slides = doc.slides.toMutableList()
        val slide = slides.getOrNull(slideIndex) ?: return
        val elements = slide.elements.toMutableList()
        elements[elementIndex] = cropElementImage(elements.getOrNull(elementIndex) ?: return, left, top, right, bottom)
        slides[slideIndex] = slide.copy(elements = elements)
        updateDocument(doc.copy(slides = slides))
    }

    /** Sets crop insets on an image frame element of a sheet's floating layer. (Phase 5) */
    fun setSheetImageCrop(sheetIndex: Int, elementIndex: Int, left: Float, top: Float, right: Float, bottom: Float) {
        mutateSheetFloating(sheetIndex) { list ->
            if (elementIndex !in list.indices) list
            else list.toMutableList().also { it[elementIndex] = cropElementImage(it[elementIndex], left, top, right, bottom) }
        }
    }

    private fun cropElementImage(el: OdfSlideElement, l: Float, t: Float, r: Float, b: Float): OdfSlideElement {
        val frameImage = (el as? OdfSlideElement.Frame)?.frame?.image
        return if (el is OdfSlideElement.Frame && frameImage != null)
            OdfSlideElement.Frame(el.frame.copy(image = frameImage.copy(cropLeftPct = l, cropTopPct = t, cropRightPct = r, cropBottomPct = b)))
        else el
    }

    /** Sets fill color on a slide shape/frame element. (extra) */
    fun setSlideElementFill(slideIndex: Int, elementIndex: Int, color: Long?) =
        setSlideElementColors(slideIndex, elementIndex, fill = color, setFill = true)

    /** Sets stroke (border) color on a slide shape/frame element. (extra) */
    fun setSlideElementStroke(slideIndex: Int, elementIndex: Int, color: Long?) =
        setSlideElementColors(slideIndex, elementIndex, stroke = color, setStroke = true)

    private fun setSlideElementColors(slideIndex: Int, elementIndex: Int, fill: Long? = null, stroke: Long? = null, setFill: Boolean = false, setStroke: Boolean = false) {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
        val slides = doc.slides.toMutableList()
        val slide = slides.getOrNull(slideIndex) ?: return
        val elements = slide.elements.toMutableList()
        val el = elements.getOrNull(elementIndex) ?: return
        elements[elementIndex] = when (el) {
            is OdfSlideElement.Frame -> OdfSlideElement.Frame(el.frame.copy(
                fillColor = if (setFill) fill else el.frame.fillColor,
                strokeColor = if (setStroke) stroke else el.frame.strokeColor
            ))
            is OdfSlideElement.Shape -> {
                val s = el.shape
                val nf = if (setFill) fill else s.fillColor
                val ns = if (setStroke) stroke else s.strokeColor
                OdfSlideElement.Shape(when (s) {
                    is OdfShape.Rect -> s.copy(fillColor = nf, strokeColor = ns)
                    is OdfShape.Ellipse -> s.copy(fillColor = nf, strokeColor = ns)
                    is OdfShape.Line -> s.copy(fillColor = nf, strokeColor = ns)
                    is OdfShape.CustomShape -> s.copy(fillColor = nf, strokeColor = ns)
                    is OdfShape.Polyline -> s.copy(fillColor = nf, strokeColor = ns)
                })
            }
        }
        slides[slideIndex] = slide.copy(elements = elements)
        updateDocument(doc.copy(slides = slides))
    }

    // --- CSV export ---

    fun exportCsv(delimiter: Char = ','): String {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return ""
        val sb = StringBuilder()
        val sheet = doc.sheets.firstOrNull() ?: return ""
        for (row in sheet.rows) {
            sb.appendLine(row.cells.joinToString(delimiter.toString()) { cell ->
                val text = cell.text
                if (text.contains(delimiter) || text.contains("\"") || text.contains("\n")) {
                    "\"${text.replace("\"", "\"\"")}\""
                } else text
            })
        }
        return sb.toString()
    }

    /** Markdown export for text documents (empty for other types). */
    fun exportMarkdown(): String {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return ""
        return MarkdownOdfConverter.odfToMarkdown(doc)
    }

    /** Best-effort OOXML (docx/xlsx/pptx) export. */
    fun exportOoxml(): ByteArray {
        val doc = (_state.value as? ViewState.Loaded)?.document ?: return ByteArray(0)
        return OoxmlExporter.export(doc)
    }

    fun ooxmlExtension(): String {
        val doc = (_state.value as? ViewState.Loaded)?.document ?: return "docx"
        return OoxmlExporter.extensionFor(doc)
    }

    /** HTML export for text documents (empty for other types). */
    fun exportHtml(): String {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return ""
        return HtmlOdfConverter.odfToHtml(doc)
    }

    /** RTF export for text documents (empty for other types). */
    fun exportRtf(): String {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return ""
        return RtfOdfConverter.odfToRtf(doc)
    }

    /** LaTeX export for text documents (empty for other types). */
    fun exportLatex(): String {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return ""
        return LatexExporter.export(doc)
    }

    /** EPUB export for text documents (empty for other types). */
    fun exportEpub(): ByteArray {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return ByteArray(0)
        return EpubExporter.export(doc)
    }

    /** PDF export for text documents (empty for other types). */
    fun exportPdf(): ByteArray {
        val doc = (_state.value as? ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return ByteArray(0)
        return PdfExporter.export(doc)
    }

    // --- Text export ---

    /** Flat ODF (.fodt/.fods/.fodp) export (K75). */
    fun exportFlat(): String {
        val doc = (_state.value as? ViewState.Loaded)?.document ?: return ""
        return OdfSerializer.serializeFlat(doc)
    }

    // --- End-to-end-encrypted cloud sync & sharing (via OfficeSync + :library:e2ee-p2p) ---

    private val _onlineDocs = MutableStateFlow<List<OfficeDocMeta>>(emptyList())
    /** Documents in the "online" folder: created/shared by you or shared with you. */
    val onlineDocs: StateFlow<List<OfficeDocMeta>> = _onlineDocs.asStateFlow()

    /** The online doc id/key of the currently open document, if it lives online. */
    private var currentDocId: String? = null
    private var currentDocKey: ByteArray? = null
    private var currentCrdt: DocumentCrdt? = null
    private var currentCharMode: Boolean = false
    private var currentRole: String = OfficeRoles.OWNER
    private var currentOwnerKey: ByteArray? = null
    private val currentMembers = java.util.concurrent.ConcurrentHashMap<String, String>() // id -> role
    private val memberKeyCache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>() // id -> pubkey PEM
    private val syncJson = Json { ignoreUnknownKeys = true }
    private val indexMutex = Mutex()
    private val syncMutex = Mutex()

    private val _remotePresence = MutableStateFlow<List<OfficePresence>>(emptyList())
    /** Other people currently in the open document (name + typing), for the presence indicator. */
    val remotePresence: StateFlow<List<OfficePresence>> = _remotePresence.asStateFlow()
    private var applyingRemote = false
    private var livePushJob: Job? = null
    private var localCaret = 0
    private var caretPresenceJob: Job? = null

    /** Called by the editor when the local caret/selection moves, to broadcast presence. */
    fun setLocalCaret(offset: Int) {
        localCaret = offset
        val docId = currentDocId ?: return
        val key = currentDocKey ?: return
        caretPresenceJob?.cancel()
        caretPresenceJob = viewModelScope.launch(Dispatchers.IO) {
            delay(120)
            runCatching {
                OfficeSync.sendPresence(docId, key, syncJson.encodeToString(
                    OfficePresence(OfficeSync.deviceId, myName(), typing = false, ts = System.currentTimeMillis(), caret = localCaret)
                ))
            }
        }
    }

    /** Display name broadcast to collaborators (device-derived; not sensitive). */
    private fun myName(): String = "User " + OfficeSync.deviceId.takeLast(4)

    /** Called after a local edit: debounced live push + a "typing" presence ping (online docs only). */
    private fun onLocalEdit() {
        val docId = currentDocId ?: return
        val key = currentDocKey ?: return
        if (applyingRemote) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                OfficeSync.sendPresence(docId, key, syncJson.encodeToString(
                    OfficePresence(OfficeSync.deviceId, myName(), typing = true, ts = System.currentTimeMillis(), caret = localCaret)
                ))
            }
        }
        livePushJob?.cancel()
        livePushJob = viewModelScope.launch(Dispatchers.IO) {
            delay(400)
            runCatching { OfficeSync.init(getApplication()); syncDoc(docId, key) }
        }
    }

    /** Subscribes to the live channel for [docId], applying incoming ops/presence in real time. */
    private fun startLive(docId: String, key: ByteArray) {
        OfficeSync.startLive(
            viewModelScope,
            docId,
            onConnected = { runCatching { syncDoc(docId, key) } }, // catch up on (re)connect
            onMessage = { raw -> handleLive(raw, docId, key) },
        )
    }

    /** Verifies an incoming signed op (author signature + editor/owner role) and applies it. */
    private suspend fun applySignedOp(crdt: DocumentCrdt, plain: String): Boolean {
        val so = runCatching { syncJson.decodeFromString<SignedOp>(plain) }.getOrNull() ?: return false
        // Role gate: our own ops are always ours; others must be owner/editor per the signed roster.
        val allowed = so.author == OfficeSync.deviceId ||
            OfficeRoles.canEdit(currentMembers[so.author] ?: OfficeRoles.VIEWER)
        if (!allowed) return false
        val authorKey = memberKey(so.author) ?: return false
        if (!OfficeSync.verify(authorKey, so.ops.encodeToByteArray(), Base64.decode(so.sig))) return false
        val ops = runCatching { syncJson.decodeFromString<List<DocumentCrdt.CrdtElement>>(so.ops) }.getOrNull() ?: return false
        crdt.apply(ops)
        return true
    }

    private fun handleLive(raw: String, docId: String, key: ByteArray) {
        val msg = OfficeSync.parseLive(raw) ?: return
        when (msg.t) {
            "actions" -> viewModelScope.launch(Dispatchers.IO) {
                syncMutex.withLock {
                    val crdt = currentCrdt ?: return@withLock
                    var changed = false
                    for (blob in msg.actions) {
                        val plain = OfficeSync.decrypt(key, blob) ?: continue
                        if (applySignedOp(crdt, plain)) changed = true
                    }
                    if (changed) {
                        val ds = DataStoreUtils.getInstance(getApplication())
                        ds.setLong("crdtCursor:$docId", msg.seq.toLong())
                        saveCrdt(ds, docId, crdt)
                        val merged = crdt.render()
                        if (merged != currentDocCells()) {
                            val doc = rebuildDoc(merged)
                            if (doc != null) withContext(Dispatchers.Main) {
                                applyingRemote = true
                                updateDocument(doc)
                                applyingRemote = false
                            }
                        }
                    }
                }
            }
            "presence" -> {
                val plain = OfficeSync.decrypt(key, msg.data) ?: return
                val p = runCatching { syncJson.decodeFromString<OfficePresence>(plain) }.getOrNull() ?: return
                if (p.id == OfficeSync.deviceId) return
                val now = System.currentTimeMillis()
                _remotePresence.value = _remotePresence.value.filter { it.id != p.id && now - it.ts < 8000 } + p.copy(ts = now)
            }
        }
    }

    /** This device's sync id. Empty until [initSync] has run. */
    val syncDeviceId: String get() = OfficeSync.deviceId

    /** Registers this device, loads the local online index, and pulls any new invites. */
    fun initSync() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                OfficeSync.init(getApplication())
                _onlineDocs.value = loadIndex(DataStoreUtils.getInstance(getApplication()))
            }
            refreshOnline()
        }
    }

    private suspend fun loadIndex(ds: DataStoreUtils): List<OfficeDocMeta> =
        ds.getString("officeDocsIndex")
            ?.let { runCatching { syncJson.decodeFromString<List<OfficeDocMeta>>(it) }.getOrNull() }
            ?: emptyList()

    private suspend fun saveIndex(ds: DataStoreUtils, list: List<OfficeDocMeta>) {
        ds.setString("officeDocsIndex", syncJson.encodeToString(list))
        _onlineDocs.value = list
    }

    /** Pulls new invites from this device's inbox and merges them into the online list. */
    fun refreshOnline() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                OfficeSync.init(getApplication())
                val ds = DataStoreUtils.getInstance(getApplication())
                val cursor = ds.getLong("officeInboxCursor")?.toInt() ?: 0
                val res = OfficeSync.pullInvites(cursor)
                if (res.invites.isNotEmpty()) {
                    indexMutex.withLock {
                        val index = loadIndex(ds).associateBy { it.docId }.toMutableMap()
                        for (inv in res.invites) {
                            if (!index.containsKey(inv.docId)) {
                                index[inv.docId] = OfficeDocMeta(inv.docId, inv.title, inv.key, owner = false, charMode = inv.charMode)
                            }
                        }
                        saveIndex(ds, index.values.toList())
                    }
                }
                ds.setLong("officeInboxCursor", res.seq.toLong())
            }
        }
    }

    // --- CRDT persistence & two-way sync ---

    private suspend fun loadCrdt(ds: DataStoreUtils, docId: String): DocumentCrdt? =
        ds.getString("crdt:$docId")
            ?.let { runCatching { DocumentCrdt.fromState(syncJson.decodeFromString<DocumentCrdt.State>(it)) }.getOrNull() }

    private suspend fun saveCrdt(ds: DataStoreUtils, docId: String, crdt: DocumentCrdt) {
        ds.setString("crdt:$docId", syncJson.encodeToString(crdt.toState()))
    }

    /** Parses flat-ODF text back into a document via a temp file (tolerant; null on failure). */
    private fun parseFlat(flat: String): OdfDocument? {
        val ctx: Context = getApplication()
        val f = java.io.File(ctx.cacheDir, "crdt_merge.fodt")
        f.writeText(flat)
        return runCatching { DocumentImporter.open(ctx, Uri.fromFile(f), "merge.fodt") }.getOrNull()
    }

    /**
     * Two-way CRDT sync for the open online document: diff local edits into ops and push them, then
     * pull + merge remote ops. If the merge changed the document, re-render it into the editor.
     */
    /** Projects the open document to CRDT cells: character cells for eligible text docs, else XML lines. */
    private fun currentDocCells(): List<String> {
        val doc = (_state.value as? ViewState.Loaded)?.document
        return if (currentCharMode && doc is OdfDocument.TextDocument) TextDocCodec.toCells(doc)
        else OfficeCrdtCodec.toLines(exportFlat())
    }

    /** Rebuilds a document from merged cells (char cells → model directly; else XML lines → parse). */
    private fun rebuildDoc(cells: List<String>): OdfDocument? {
        val base = (_state.value as? ViewState.Loaded)?.document
        return if (currentCharMode && base is OdfDocument.TextDocument)
            runCatching { TextDocCodec.fromCells(cells, base) }.getOrNull()
        else parseFlat(OfficeCrdtCodec.fromLines(cells))
    }

    private suspend fun syncDoc(docId: String, key: ByteArray) = syncMutex.withLock {
        val ds = DataStoreUtils.getInstance(getApplication())
        val crdt = currentCrdt ?: (loadCrdt(ds, docId) ?: DocumentCrdt(OfficeSync.deviceId)).also { currentCrdt = it }
        val cursor = ds.getLong("crdtCursor:$docId")?.toInt() ?: 0
        val localCells = currentDocCells()
        val localOps = crdt.update(localCells)
        // Only push signed ops if we're allowed to edit; viewers never push.
        if (localOps.isNotEmpty() && OfficeRoles.canEdit(currentRole)) {
            val opsJson = syncJson.encodeToString(localOps)
            val sig = Base64.encode(OfficeSync.sign(opsJson.encodeToByteArray()))
            val signed = syncJson.encodeToString(SignedOp(OfficeSync.deviceId, sig, opsJson))
            OfficeSync.appendDocActions(docId, key, listOf(signed))
        }
        // Pull from the OLD cursor so remote ops (and our just-pushed, idempotent ops) are merged.
        val pulled = OfficeSync.pullDocActions(docId, key, cursor)
        for (item in pulled.items) {
            applySignedOp(crdt, item)
        }
        ds.setLong("crdtCursor:$docId", pulled.seq.toLong())
        saveCrdt(ds, docId, crdt)
        val mergedCells = crdt.render()
        if (mergedCells != localCells) {
            val doc = rebuildDoc(mergedCells)
            if (doc != null) withContext(Dispatchers.Main) { updateDocument(doc) }
        }
    }

    /**
     * Shares the current document with [recipientId]. If it isn't online yet, it is first copied
     * into the online folder (new doc id + content key + CRDT upload), then the invite is sent.
     */
    /** Shares with a recipient. onResult receives null on success, or a human-readable error reason. */
    fun shareCurrentDocument(recipientId: String, role: String = OfficeRoles.EDITOR, onResult: (String?) -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            val error: String? = try {
                OfficeSync.init(getApplication())
                val ds = DataStoreUtils.getInstance(getApplication())
                val doc = (_state.value as? ViewState.Loaded)?.document
                val title = doc?.title ?: "Document"
                val charMode = doc != null && TextDocCodec.isEligible(doc)
                val firstShare = currentDocId == null
                val docId = currentDocId ?: OfficeSync.newDocumentId()
                val key = currentDocKey ?: OfficeSync.newDocumentKey()
                if (firstShare) {
                    currentRole = OfficeRoles.OWNER
                    currentOwnerKey = OfficeSync.publicBundle
                    currentMembers[OfficeSync.deviceId] = OfficeRoles.OWNER
                }
                when {
                    currentRole != OfficeRoles.OWNER -> "Only the owner can share this document."
                    OfficeSync.getKey(recipientId) == null ->
                        "That device id isn't registered yet. Ask them to open Office once (Online tab), then try again."
                    else -> {
                        currentDocId = docId
                        currentDocKey = key
                        currentCharMode = charMode
                        val ownerKeyB64 = Base64.encode(OfficeSync.publicBundle)
                        syncDoc(docId, key)
                        indexMutex.withLock {
                            val index = loadIndex(ds).associateBy { it.docId }.toMutableMap()
                            if (!index.containsKey(docId)) {
                                index[docId] = OfficeDocMeta(docId, title, Base64.encode(key), owner = true, charMode = charMode, role = OfficeRoles.OWNER, ownerKeyB64 = ownerKeyB64)
                                saveIndex(ds, index.values.toList())
                            }
                        }
                        startLive(docId, key)
                        recordMembers(docId, key, listOf(
                            OfficeMember(OfficeSync.deviceId, myName(), OfficeRoles.OWNER),
                            OfficeMember(recipientId, "", role),
                        ))
                        if (OfficeSync.sendInvite(recipientId, docId, key, title, charMode, role, ownerKeyB64)) null
                        else "Couldn't deliver the invite — check your connection."
                    }
                }
            } catch (e: Exception) {
                "Error: ${e.message ?: e.javaClass.simpleName}"
            }
            withContext(Dispatchers.Main) { onResult(error) }
        }
    }

    /** Pushes local edits and merges remote ones for the open online document (no-op if offline). */
    fun syncCurrentDocument() {
        val docId = currentDocId ?: return
        val key = currentDocKey ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { OfficeSync.init(getApplication()); syncDoc(docId, key) }
        }
    }

    /** Opens an online document by building its CRDT from pulled ops and rendering it into the editor. */
    fun openOnlineDocument(meta: OfficeDocMeta) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                OfficeSync.init(getApplication())
                val ds = DataStoreUtils.getInstance(getApplication())
                val key = Base64.decode(meta.keyB64)
                currentRole = meta.role
                currentOwnerKey = meta.ownerKeyB64.takeIf { it.isNotBlank() }?.let { Base64.decode(it) }
                currentMembers.clear()
                val crdt = loadCrdt(ds, meta.docId) ?: DocumentCrdt(OfficeSync.deviceId)
                // Refresh the (owner-signed) roster so op role-checks are correct before applying.
                runCatching { fetchMembers(meta.docId, key, currentOwnerKey) }
                val cursor = ds.getLong("crdtCursor:${meta.docId}")?.toInt() ?: 0
                val pulled = OfficeSync.pullDocActions(meta.docId, key, cursor)
                for (item in pulled.items) {
                    applySignedOp(crdt, item)
                }
                ds.setLong("crdtCursor:${meta.docId}", pulled.seq.toLong())
                saveCrdt(ds, meta.docId, crdt)
                val flat = if (meta.charMode) {
                    val base = OdfDocument.TextDocument(title = meta.title, content = emptyList())
                    val doc = runCatching { TextDocCodec.fromCells(crdt.render(), base) }.getOrNull() ?: base
                    OdfSerializer.serializeFlat(doc)
                } else {
                    OfficeCrdtCodec.fromLines(crdt.render())
                }
                val ctx: Context = getApplication()
                val safeTitle = meta.title.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "document" }
                val file = java.io.File(ctx.cacheDir, "online_${meta.docId}.fodt")
                file.writeText(flat)
                withContext(Dispatchers.Main) {
                    loadDocument(Uri.fromFile(file), "$safeTitle.fodt", meta.docId, key)
                    _isEditMode.value = OfficeRoles.canEdit(meta.role) // viewers are read-only
                }
                currentCrdt = crdt
                currentCharMode = meta.charMode
                startLive(meta.docId, key)
            }
        }
    }

    /** Computes the verification security code with a peer device id (compare out-of-band). */
    fun securityCodeWith(peerId: String, onResult: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val code = runCatching {
                OfficeSync.init(getApplication())
                val pem = OfficeSync.getKey(peerId) ?: return@runCatching null
                OfficeSync.securityCode(pem)
            }.getOrNull()
            withContext(Dispatchers.Main) { onResult(code) }
        }
    }

    /** This device's role in the open document (owner/editor/viewer). */
    fun currentDocRole(): String = currentRole

    /** True if the local user may edit the open document. */
    fun canEditCurrent(): Boolean = currentDocId == null || OfficeRoles.canEdit(currentRole)

    /** Canonical bytes an owner signs for a member record (binds id+role to the doc). */
    private fun memberSigningBytes(docId: String, m: OfficeMember): ByteArray =
        "$docId|${m.id}|${m.role}".encodeToByteArray()

    /** Owner-signs and appends member records to the (encrypted) members channel. Owner only. */
    private suspend fun recordMembers(docId: String, key: ByteArray, members: List<OfficeMember>) {
        if (currentRole != OfficeRoles.OWNER) return
        runCatching {
            val items = members.map { m ->
                val sig = Base64.encode(OfficeSync.sign(memberSigningBytes(docId, m)))
                syncJson.encodeToString(SignedMember(m, sig))
            }
            OfficeSync.appendDocActions("members:$docId", key, items)
        }
    }

    /** Reads + verifies the roster: only records with a valid OWNER signature are honored. */
    private suspend fun fetchMembers(docId: String, key: ByteArray, ownerKey: ByteArray?): List<OfficeMember> {
        if (ownerKey == null) return emptyList()
        val res = OfficeSync.pullDocActions("members:$docId", key, 0)
        val byId = LinkedHashMap<String, OfficeMember>()
        for (item in res.items) {
            val sm = runCatching { syncJson.decodeFromString<SignedMember>(item) }.getOrNull() ?: continue
            val ok = OfficeSync.verify(ownerKey, memberSigningBytes(docId, sm.member), Base64.decode(sm.sig))
            if (!ok) continue // not signed by the owner -> ignore (client-enforced authority)
            byId[sm.member.id] = sm.member
        }
        currentMembers.clear()
        byId.values.forEach { currentMembers[it.id] = it.role }
        return byId.values.toList()
    }

    /** Loads the people who have access to the currently open document (for the share menu). */
    fun documentMembers(onResult: (List<OfficeMember>) -> Unit) {
        val docId = currentDocId
        val key = currentDocKey
        if (docId == null || key == null) { onResult(emptyList()); return }
        viewModelScope.launch(Dispatchers.IO) {
            val members = runCatching { OfficeSync.init(getApplication()); fetchMembers(docId, key, currentOwnerKey) }.getOrDefault(emptyList())
            withContext(Dispatchers.Main) { onResult(members) }
        }
    }

    /** Owner sets/changes a member's role (editor/viewer) or revokes (role="revoked"). Owner only. */
    fun setMemberRole(memberId: String, role: String, onResult: (Boolean) -> Unit = {}) {
        val docId = currentDocId; val key = currentDocKey
        if (docId == null || key == null || currentRole != OfficeRoles.OWNER) { onResult(false); return }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                OfficeSync.init(getApplication())
                recordMembers(docId, key, listOf(OfficeMember(memberId, "", role)))
            }
            onResult(true)
        }
    }

    /** Verifies + returns the pubkey for a member id (cached). */
    private suspend fun memberKey(id: String): ByteArray? =
        memberKeyCache[id] ?: OfficeSync.getKey(id)?.also { memberKeyCache[id] = it }

    /** This device's own display name (for the roster/presence). */
    fun myDisplayName(): String = myName()

    fun exportAsPlainText(): String {
        val doc = (_state.value as? ViewState.Loaded)?.document ?: return ""
        val sb = StringBuilder()
        when (doc) {
            is OdfDocument.TextDocument -> {
                for (block in doc.content) {
                    when (block) {
                        is OdfContentBlock.Paragraph -> sb.appendLine(block.paragraph.spans.joinToString("") { it.text })
                        is OdfContentBlock.Table -> {
                            for (row in block.table.rows)
                                sb.appendLine(row.cells.filterNot { it.isCovered }.joinToString("\t") { cell -> cell.paragraphs.joinToString(" ") { p -> p.spans.joinToString("") { it.text } } })
                        }
                        is OdfContentBlock.PageBreak -> sb.appendLine("---")
                        is OdfContentBlock.Image -> sb.appendLine("[Image]")
                        is OdfContentBlock.Chart -> sb.appendLine("[Chart]")
                        is OdfContentBlock.Formula -> sb.appendLine("[Formula] " + OdfMath.parse(block.mathml)?.let { OdfMath.toText(it) }.orEmpty())
                        is OdfContentBlock.TableOfContents -> {
                            sb.appendLine(block.title)
                            for (entry in block.entries) sb.appendLine(entry.spans.joinToString("") { it.text })
                        }
                        is OdfContentBlock.SectionStart, OdfContentBlock.SectionEnd -> {}
                    }
                }
            }
            is OdfDocument.Spreadsheet -> {
                for (sheet in doc.sheets) {
                    sb.appendLine("=== ${sheet.name} ===")
                    for (row in sheet.rows) sb.appendLine(row.cells.filterNot { it.isCovered }.joinToString("\t") { it.text })
                    sb.appendLine()
                }
            }
            is OdfDocument.Presentation -> {
                for (slide in doc.slides) {
                    sb.appendLine("=== ${slide.name} ===")
                    for (el in slide.elements) when (el) {
                        is OdfSlideElement.Frame -> for (p in el.frame.paragraphs) sb.appendLine(p.spans.joinToString("") { it.text })
                        is OdfSlideElement.Shape -> for (p in el.shape.text) sb.appendLine(p.spans.joinToString("") { it.text })
                    }
                    sb.appendLine()
                }
            }
            is OdfDocument.Drawing -> {
                for (page in doc.pages) {
                    sb.appendLine("=== ${page.name} ===")
                    for (el in page.elements) when (el) {
                        is OdfSlideElement.Frame -> for (p in el.frame.paragraphs) sb.appendLine(p.spans.joinToString("") { it.text })
                        is OdfSlideElement.Shape -> for (p in el.shape.text) sb.appendLine(p.spans.joinToString("") { it.text })
                    }
                    sb.appendLine()
                }
            }
        }
        return sb.toString()
    }

    // --- Save ---

    fun save(targetUri: Uri? = null) {
        val doc = (_state.value as? ViewState.Loaded)?.document ?: return
        // Source may be null for a brand-new document; the writer then builds the package from scratch.
        val source = documentUri
        val target = targetUri ?: source ?: return
        _isSaving.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                OdfWriter.save(getApplication(), source, doc, target)
                _hasUnsavedChanges.value = false
                documentUri = target
                // If this document lives online, push local edits + merge remote ones.
                if (currentDocId != null && currentDocKey != null) {
                    runCatching {
                        OfficeSync.init(getApplication())
                        syncDoc(currentDocId!!, currentDocKey!!)
                    }
                }
                launch(Dispatchers.Main) { Toast.makeText(getApplication(), "Saved", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                launch(Dispatchers.Main) { Toast.makeText(getApplication(), "Save failed: ${e.message}", Toast.LENGTH_SHORT).show() }
            } finally {
                _isSaving.value = false
            }
        }
    }

    /** True when there's no backing file yet, so the UI should route Save to Save As. (Priority 1) */
    fun needsSaveAs(): Boolean = documentUri == null

    sealed class ViewState {
        data object Empty : ViewState()
        data object Loading : ViewState()
        data class Loaded(val document: OdfDocument) : ViewState()
        data class Error(val message: String) : ViewState()
    }

    companion object {
        private const val MAX_UNDO = 30
        private const val MAX_RECENT = 20
    }
}
