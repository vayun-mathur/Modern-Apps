package com.vayunmathur.pdf.platform

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.pdf.data.CapturedImage
import com.vayunmathur.pdf.data.Quadrilateral
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Pdf View Model: a application implementation for pdf view model. */
class PdfViewModel(application: Application) : AndroidViewModel(application) {

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

    fun updateQuadrilateral(index: Int, quadrilateral: Quadrilateral) {
        val current = _capturedImages.value
        if (index !in current.indices) return
        _capturedImages.value = current.toMutableList().also {
            it[index] = it[index].copy(quadrilateral = quadrilateral, cropRect = quadrilateral.toBoundingRect())
        }
    }

    // --- PDF export from captured images -----------------------------------

    /** Immutable value describing pdf write result; carries targetUri, success. */
    data class PdfWriteResult(val targetUri: Uri, val success: Boolean)

    private val _pdfWriteResults = MutableSharedFlow<PdfWriteResult>(extraBufferCapacity = 1)
    val pdfWriteResults: SharedFlow<PdfWriteResult> = _pdfWriteResults.asSharedFlow()

    fun exportCapturedPdf(
        targetUri: Uri,
        filter: ScanFilter = ScanFilter.NONE,
        addOcr: Boolean = false,
    ) {
        val snapshot = _capturedImages.value
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            val ok = savePdfToUri(ctx, snapshot, targetUri, filter, addOcr)
            _pdfWriteResults.emit(PdfWriteResult(targetUri, ok))
        }
    }

    fun clearCapturedImages() {
        _capturedImages.value = emptyList()
    }
}
