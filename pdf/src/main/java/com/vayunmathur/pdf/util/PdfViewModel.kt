package com.vayunmathur.pdf.util

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.compose.ui.geometry.Rect
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.pdf.EditablePdfDocument
import androidx.pdf.PdfPasswordException
import androidx.pdf.SandboxedPdfLoader
import com.vayunmathur.pdf.R
import com.vayunmathur.pdf.model.CapturedImage
import com.vayunmathur.pdf.model.Quadrilateral
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for the PDF app.
 *
 * Owns:
 *  - the captured image list (with committed per-image crop rects) used by the
 *    capture-to-PDF flow
 *  - the loaded [EditablePdfDocument] + password-load error state
 *  - PDF export / write invocations (off the main thread)
 *
 * UI-only state stays in compose: CameraX PreviewView, FocusRequester, LazyListState,
 * the in-progress drag rect inside [com.vayunmathur.pdf.ui.CropScreen], dialog visibility
 * booleans, search query/index, animation specs, and [androidx.pdf.compose.PdfViewerState]
 * (which is a compose state holder for the viewer).
 */
class PdfViewModel(application: Application) : AndroidViewModel(application) {

    private val pdfLoader = SandboxedPdfLoader(application)

    // --- Captured images / cropping ----------------------------------------

    private val _capturedImages = MutableStateFlow<List<CapturedImage>>(emptyList())
    val capturedImages: StateFlow<List<CapturedImage>> = _capturedImages.asStateFlow()

    fun addCapturedImage(uri: Uri): Int {
        val updated = _capturedImages.value + CapturedImage(uri)
        _capturedImages.value = updated
        return updated.lastIndex
    }

    fun removeCapturedImage(index: Int) {
        val current = _capturedImages.value
        if (index !in current.indices) return
        _capturedImages.value = current.toMutableList().also { it.removeAt(index) }
    }

    fun moveCapturedImage(from: Int, to: Int) {
        val current = _capturedImages.value
        if (from !in current.indices || to !in current.indices) return
        _capturedImages.value = current.toMutableList().also { it.add(to, it.removeAt(from)) }
    }

    /** Updates the committed crop rect for the image at [index]. */
    fun updateCrop(index: Int, crop: Rect) {
        val current = _capturedImages.value
        if (index !in current.indices) return
        _capturedImages.value = current.toMutableList().also {
            it[index] = it[index].copy(cropRect = crop, quadrilateral = Quadrilateral.fromRect(crop))
        }
    }
    
    /** Updates the committed quadrilateral for the image at [index]. */
    fun updateQuadrilateral(index: Int, quadrilateral: Quadrilateral) {
        val current = _capturedImages.value
        if (index !in current.indices) return
        _capturedImages.value = current.toMutableList().also {
            it[index] = it[index].copy(quadrilateral = quadrilateral, cropRect = quadrilateral.toBoundingRect())
        }
    }

    // --- PDF export from captured images -----------------------------------

    /** Result of a PDF write requested via [exportCapturedPdf] or [saveDocumentChanges]. */
    data class PdfWriteResult(val targetUri: Uri, val success: Boolean)

    private val _pdfWriteResults = MutableSharedFlow<PdfWriteResult>(extraBufferCapacity = 1)
    val pdfWriteResults: SharedFlow<PdfWriteResult> = _pdfWriteResults.asSharedFlow()

    /**
     * Writes the current [capturedImages] (with their crop rects applied) as a PDF
     * to [targetUri]. Emits the outcome on [pdfWriteResults].
     */
    fun exportCapturedPdf(targetUri: Uri) {
        val snapshot = _capturedImages.value
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            val ok = savePdfToUri(ctx, snapshot, targetUri)
            _pdfWriteResults.emit(PdfWriteResult(targetUri, ok))
        }
    }

    /**
     * Writes pending edits in [document] (a previously-opened [EditablePdfDocument])
     * to [targetUri]. Used for "Save As" and inline-edit autosave.
     */
    fun saveDocumentChanges(document: EditablePdfDocument, targetUri: Uri) {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    ctx.contentResolver.openFileDescriptor(targetUri, "w")?.use { pfd ->
                        document.createWriteHandle().writeTo(pfd)
                    }
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "Error saving PDF to $targetUri", e)
                    false
                }
            }
            _pdfWriteResults.emit(PdfWriteResult(targetUri, ok))
        }
    }

    fun clearCapturedImages() {
        _capturedImages.value = emptyList()
    }

    // --- Document loading ---------------------------------------------------

    private val _pdfDocument = MutableStateFlow<EditablePdfDocument?>(null)
    val pdfDocument: StateFlow<EditablePdfDocument?> = _pdfDocument.asStateFlow()

    private val _passwordRequired = MutableStateFlow(false)
    val passwordRequired: StateFlow<Boolean> = _passwordRequired.asStateFlow()

    private val _passwordError = MutableStateFlow<String?>(null)
    val passwordError: StateFlow<String?> = _passwordError.asStateFlow()

    /**
     * Asynchronously opens [uri] with the given [password]. Updates [pdfDocument]
     * on success; on a [PdfPasswordException] sets [passwordRequired] (and
     * [passwordError] if a password was actually supplied).
     */
    fun loadDocument(uri: Uri, password: String?) {
        val ctx = getApplication<Application>()
        // Clear any prior error eagerly so the password dialog doesn't show stale
        // text while the next load attempt is in flight.
        _passwordError.value = null
        viewModelScope.launch {
            // Preserve the previous 1-second delay so the UI has time to render.
            delay(1000)
            try {
                val doc = pdfLoader.openDocument(uri, password) as EditablePdfDocument
                _pdfDocument.value = doc
                _passwordRequired.value = false
                _passwordError.value = null
            } catch (_: PdfPasswordException) {
                if (password != null) {
                    _passwordError.value = ctx.getString(R.string.incorrect_password)
                }
                _passwordRequired.value = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open PDF $uri", e)
            }
        }
    }

    fun clearDocument() {
        _pdfDocument.value = null
        _passwordRequired.value = false
        _passwordError.value = null
        _linkDestinations.value = emptyMap()
    }

    // --- Internal link resolution -------------------------------------------

    private val _linkDestinations = MutableStateFlow<Map<String, Int>>(emptyMap())
    val linkDestinations: StateFlow<Map<String, Int>> = _linkDestinations.asStateFlow()

    fun buildLinkIndex(document: EditablePdfDocument) {
        viewModelScope.launch(Dispatchers.Default) {
            val map = mutableMapOf<String, Int>()
            for (page in 0 until document.pageCount) {
                val links = try {
                    document.getPageLinks(page)
                } catch (e: Exception) {
                    continue
                }
                val gotos = links.gotoLinks.filter { it.bounds.isNotEmpty() }
                val externals = links.externalLinks.filter {
                    it.bounds.isNotEmpty() && it.uri.scheme == "file"
                }
                if (gotos.isEmpty() || externals.isEmpty()) continue

                for (ext in externals) {
                    val extY = ext.bounds.first().centerY()
                    val match = gotos.minByOrNull {
                        kotlin.math.abs(it.bounds.first().centerY() - extY)
                    } ?: continue
                    if (kotlin.math.abs(match.bounds.first().centerY() - extY) < 20f) {
                        map[ext.uri.toString()] = match.destination.pageNumber
                    }
                }
            }
            Log.d(TAG, "Link index: ${map.size} entries")
            for ((uri, dest) in map) {
                Log.d(TAG, "  $uri -> page $dest")
            }
            _linkDestinations.value = map
        }
    }

    companion object {
        private const val TAG = "PdfViewModel"
    }
}
