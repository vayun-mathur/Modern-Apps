package com.vayunmathur.pdf.ui

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.vayunmathur.pdf.platform.SafePdfDocument
import com.vayunmathur.pdf.platform.addCallout
import com.vayunmathur.pdf.platform.addImageStamp
import com.vayunmathur.pdf.platform.addNote
import com.vayunmathur.pdf.platform.addPoly
import com.vayunmathur.pdf.platform.addText
import com.vayunmathur.pdf.platform.deleteAnnotation
import com.vayunmathur.pdf.platform.detachAnnotation
import com.vayunmathur.pdf.platform.duplicateAnnotation
import com.vayunmathur.pdf.platform.editText
import com.vayunmathur.pdf.platform.moveAnnotation
import com.vayunmathur.pdf.platform.reattachAnnotation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Mutable edit/search/session state for [SafePdfViewerScreen].
 *
 * Split out verbatim so the screen stays under the FileLength limit: every
 * field here was a `remember` in the screen body, and every method was a
 * screen-local lambda with the same logic. The document handle itself is
 * still owned by the screen's load effect and passed in as a parameter,
 * because it arrives asynchronously.
 */
internal class SafePdfViewerState(val scope: CoroutineScope) {
    var editMode by mutableStateOf(false)
    var showSaveMenu by mutableStateOf(false)
    // Set once any edit is made; keeps the Save control visible thereafter.
    var dirty by mutableStateOf(false)
    var tool by mutableStateOf(EditTool.SELECT)
    var shape by mutableStateOf(ShapeKind.RECT_OUTLINE)
    var markup by mutableStateOf(MarkupKind.HIGHLIGHT)
    // Pending note/callout awaiting text entry: page + point(s).
    var pendingNote by mutableStateOf<Pair<Int, Offset>?>(null)
    var pendingCallout by mutableStateOf<Triple<Int, Offset, Offset>?>(null)
    // In-progress polyline/Bézier (built by tapping points; committed via the check button).
    var polyDraft by mutableStateOf<PolyDraft?>(null)
    var color by mutableStateOf(Color.Red)
    var opacity by mutableFloatStateOf(1f)
    var strokeWidth by mutableFloatStateOf(2f)
    var showStyle by mutableStateOf(false)
    // Undo/redo of annotation add/remove/move (backed by native ops).
    val undoStack = mutableStateListOf<EditAction>()
    val redoStack = mutableStateListOf<EditAction>()
    // Set by non-undoable edits (forms, flatten, redactions) so Save still shows.
    var nonUndoDirty by mutableStateOf(false)
    var pageCount by mutableIntStateOf(0)
    var pageMgrVersion by mutableIntStateOf(0)
    // Per-page render version: bumping one page's entry re-renders ONLY that page,
    // so an edit doesn't force every visible page to re-decode.
    val pageVersions = mutableStateMapOf<Int, Int>()
    var selected by mutableStateOf<Pair<Int, Long>?>(null)
    // Effective annotation color including the opacity slider's alpha.
    val drawColor get() = color.copy(alpha = opacity)

    // Search state. Matches as (pageIndex, page-space rect).
    var searching by mutableStateOf(false)
    var query by mutableStateOf("")
    var caseSensitive by mutableStateOf(false)
    var matches by mutableStateOf<List<Pair<Int, Rect>>>(emptyList())
    var matchIndex by mutableIntStateOf(0)

    // Inline text-editing session (draws a live text field on the page).
    var textSession by mutableStateOf<TextSession?>(null)
    // Image-stamp target awaiting a picked image.
    var imageTarget by mutableStateOf<Pair<Int, Offset>?>(null)

    var showEncrypt by mutableStateOf(false)
    var pendingEncryptPw by mutableStateOf<String?>(null)

    // Re-render the edited page and record that an edit was made.
    fun markEdited(page: Int) {
        pageVersions[page] = (pageVersions[page] ?: 0) + 1
        dirty = true
    }

    // Register a freshly created annotation for undo.
    fun registerCreated(page: Int, id: Long) {
        if (id != 0L) {
            undoStack.add(EditAction(page, id, EditKind.ADDED))
            redoStack.clear()
        }
    }

    fun undo(document: SafePdfDocument?) {
        val a = undoStack.removeLastOrNull()
        val doc = document
        if (a != null && doc != null) {
            scope.launch {
                when (a.kind) {
                    EditKind.ADDED -> doc.detachAnnotation(a.page, a.annotId)
                    EditKind.REMOVED -> doc.reattachAnnotation(a.page, a.annotId)
                    EditKind.MOVED -> a.oldRect?.let {
                        doc.moveAnnotation(a.page, a.annotId, it[0], it[1], it[2], it[3])
                    }
                }
                redoStack.add(a); selected = null; markEdited(a.page)
            }
        }
    }

    fun redo(document: SafePdfDocument?) {
        val a = redoStack.removeLastOrNull()
        val doc = document
        if (a != null && doc != null) {
            scope.launch {
                when (a.kind) {
                    EditKind.ADDED -> doc.reattachAnnotation(a.page, a.annotId)
                    EditKind.REMOVED -> doc.detachAnnotation(a.page, a.annotId)
                    EditKind.MOVED -> a.newRect?.let {
                        doc.moveAnnotation(a.page, a.annotId, it[0], it[1], it[2], it[3])
                    }
                }
                undoStack.add(a); selected = null; markEdited(a.page)
            }
        }
    }

    // Record an annotation move for undo/redo.
    fun registerMoved(page: Int, id: Long, oldR: List<Float>, newR: List<Float>) {
        undoStack.add(EditAction(page, id, EditKind.MOVED, oldR, newR))
        redoStack.clear()
    }

    // Persist a finished text session as a FreeText annotation.
    fun commitText(session: TextSession?, document: SafePdfDocument?) {
        val s = session ?: return
        val doc = document ?: return
        val txt = s.value.text.trim()
        scope.launch {
            val newId = when {
                s.annotId != null && txt.isEmpty() -> { doc.deleteAnnotation(s.page, s.annotId); 0L }
                s.annotId != null -> { doc.editText(s.page, s.annotId, txt); 0L }
                txt.isNotEmpty() -> doc.addText(
                    s.page, s.origin.x, s.origin.y - s.size * 1.3f, s.origin.x + 220f, s.origin.y,
                    s.color, s.size, txt,
                )
                else -> return@launch
            }
            registerCreated(s.page, newId)
            markEdited(s.page)
        }
    }

    fun commitTextAndClear(document: SafePdfDocument?) {
        commitText(textSession, document); textSession = null
    }

    fun startText(document: SafePdfDocument?, session: TextSession) {
        commitText(textSession, document); textSession = session
    }

    // Finish the in-progress polyline/Bézier: flatten (Bézier) and store as an
    // open PolyLine annotation. Needs >= 2 points; otherwise just discards.
    fun commitPoly(document: SafePdfDocument?) {
        val d = polyDraft
        val doc = document
        if (d != null && doc != null && d.points.size >= 2) {
            val pts = if (d.bezier) flattenSmooth(d.points) else d.points
            val flat = FloatArray(pts.size * 2)
            pts.forEachIndexed { i, p -> flat[i * 2] = p.x; flat[i * 2 + 1] = p.y }
            scope.launch {
                val id = doc.addPoly(d.page, flat, drawColor.toArgb(), strokeWidth, fill = false, closed = false)
                registerCreated(d.page, id)
                markEdited(d.page)
            }
        }
        polyDraft = null
    }

    fun addPolyPoint(index: Int, point: Offset) {
        val d = polyDraft
        polyDraft = when {
            d == null -> PolyDraft(index, listOf(point), tool == EditTool.BEZIER)
            d.page == index -> d.copy(points = d.points + point)
            else -> d // ignore taps on other pages while drafting
        }
    }

    fun toggleEditMode(document: SafePdfDocument?) {
        commitText(textSession, document); textSession = null
        commitPoly(document)
        editMode = !editMode; selected = null
    }

    fun deleteSelected(document: SafePdfDocument?) {
        val sel = selected
        val doc = document
        if (sel != null && doc != null) {
            scope.launch {
                // Detach (not delete) so it can be undone.
                doc.detachAnnotation(sel.first, sel.second)
                undoStack.add(EditAction(sel.first, sel.second, EditKind.REMOVED))
                redoStack.clear()
                selected = null
                markEdited(sel.first)
            }
        }
    }

    fun duplicateSelected(document: SafePdfDocument?) {
        val sel = selected
        val doc = document
        if (sel != null && doc != null) {
            scope.launch {
                val newId = doc.duplicateAnnotation(sel.first, sel.second, 14f, -14f)
                registerCreated(sel.first, newId)
                if (newId != 0L) selected = sel.first to newId
                markEdited(sel.first)
            }
        }
    }

    fun applyRedactions(document: SafePdfDocument?) {
        val doc = document
        if (doc != null) scope.launch {
            doc.applyRedactions(); pageMgrVersion++; nonUndoDirty = true
        }
    }

    fun createNote(document: SafePdfDocument?, page: Int, point: Offset, text: String) {
        val doc = document
        if (doc != null) scope.launch {
            val id = doc.addNote(page, point.x, point.y, drawColor.toArgb(), text)
            registerCreated(page, id); markEdited(page)
        }
        pendingNote = null
    }

    fun createCallout(document: SafePdfDocument?, page: Int, a: Offset, b: Offset, text: String) {
        val doc = document
        if (doc != null) scope.launch {
            val id = doc.addCallout(page, a.x, a.y, b.x, b.y, drawColor.toArgb(), 14f, text)
            registerCreated(page, id); markEdited(page)
        }
        pendingCallout = null
    }

    fun onImagePicked(document: SafePdfDocument?, context: Context, imageUri: Uri?) {
        val doc = document
        val target = imageTarget
        imageTarget = null
        if (imageUri != null && doc != null && target != null) {
            scope.launch {
                val jpeg = readAsJpeg(context, imageUri) ?: return@launch
                val (index, pt) = target
                // Default 150pt-wide stamp preserving aspect ratio.
                val w = 150f
                val h = w * jpeg.height / jpeg.width.coerceAtLeast(1)
                doc.addImageStamp(
                    index, pt.x, pt.y - h, pt.x + w, pt.y, jpeg.width, jpeg.height, jpeg.bytes
                ).also { registerCreated(index, it) }
                markEdited(index)
            }
        }
    }
}

@Composable
internal fun rememberSafePdfViewerState(): SafePdfViewerState {
    val scope = rememberCoroutineScope()
    return remember { SafePdfViewerState(scope) }
}
