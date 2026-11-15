package com.vayunmathur.photos.util

import android.app.Application
import android.content.Context
import android.content.IntentSender
import android.graphics.Bitmap
import android.net.Uri
import com.vayunmathur.library.log.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.vayunmathur.photos.data.AdjustmentLayer
import com.vayunmathur.photos.data.BitmapReference
import com.vayunmathur.photos.data.EditDocument
import com.vayunmathur.photos.data.Layer
import com.vayunmathur.photos.data.LayerAdjustment
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.PhotosRepository
import com.vayunmathur.photos.data.Selection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.math.roundToInt

/**
 * Document-centric editor view model. The single source of truth is [document]; the
 * compositor renders a high-res [compositedPreview] (up to 2048px, matching the decode
 * size so the on-screen image stays sharp) and a full-res bitmap on export. Undo/redo
 * snapshots whole [EditDocument]s.
 *
 * Drawing (ink) strokes and text overlays remain screen-local state and are baked onto
 * the composite at save time (see [savePhoto]).
 */
class PhotoEditViewModel(
    application: Application,
    private val repository: PhotosRepository,
) : AndroidViewModel(application) {

    internal val compositor = LayerCompositor()
    private val history = UndoRedoManager<EditDocument>()

    /** Coroutine scope shared with the edit controllers (same ViewModel lifecycle). */
    internal val editScope get() = viewModelScope

    /** Application context shared with the edit controllers. */
    internal val appContext: Context get() = getApplication()

    /** Layer-stack operations (delegated so this class stays under the function cap). */
    val layers = LayerEditController(this)

    /** Pixel and geometry operations (delegated so this class stays under the function cap). */
    val pixel = PixelEditController(this)

    /** Adjustment-layer operations (delegated so this class stays under the function cap). */
    val adjustments = AdjustmentEditController(this)

    /** Save/export flow (delegated so this class stays under the function cap). */
    val saver = PhotoEditSaver(this)

    /** The photo being edited, resolved from the DB (or a stand-in built from a raw uri). */
    private val _photo = MutableStateFlow<Photo?>(null)
    val photo: StateFlow<Photo?> = _photo.asStateFlow()
    private var photoJob: Job? = null

    /** Loads the [id]'s photo from the DB, falling back to a stand-in built from [initialUri]. */
    fun loadPhoto(id: Long, initialUri: String?) {
        photoJob?.cancel()
        photoJob = viewModelScope.launch {
            repository.getByIdFlow(id).collect { fromDb ->
                _photo.value = fromDb ?: initialUri?.let { uri ->
                    Photo(
                        id = 0, name = uri.substringAfterLast("/"), uri = uri,
                        date = System.currentTimeMillis(), width = 0, height = 0,
                        dateModified = System.currentTimeMillis() / 1000, exifSet = false,
                        lat = null, long = null, videoData = null, panoData = null,
                    )
                }
            }
        }
    }

    private val _document = MutableStateFlow(EditDocument())
    val document: StateFlow<EditDocument> = _document.asStateFlow()

    private val _compositedPreview = MutableStateFlow<Bitmap?>(null)
    val compositedPreview: StateFlow<Bitmap?> = _compositedPreview.asStateFlow()

    /** The background (bottom) pixel-layer bitmap, used for filter thumbnails. */
    private val _baseBitmap = MutableStateFlow<Bitmap?>(null)
    val baseBitmap: StateFlow<Bitmap?> = _baseBitmap.asStateFlow()

    private val _selection = MutableStateFlow<Selection?>(null)
    val selection: StateFlow<Selection?> = _selection.asStateFlow()

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()
    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    /** When true the preview is rendered without the document crop so crop handles work. */
    internal var croppingPreview = false

    /** Write-consent request state, owned by [saver]. */
    val writePermissionRequest: StateFlow<IntentSender?> get() = saver.writePermissionRequest

    private var previewJob: Job? = null
    private var lastDecodedUri: String? = null

    val activeLayer: Layer? get() = _document.value.activeLayer
    val activeAdjustment: LayerAdjustment?
        get() = (_document.value.activeLayer as? AdjustmentLayer)?.adjustment

    // --- decode ---------------------------------------------------------------

    fun decode(uri: Uri) {
        val uriStr = uri.toString()
        if (uriStr == lastDecodedUri && _document.value.layers.isNotEmpty()) return
        val ctx: Context = getApplication()
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val previousBase = _baseBitmap.value
                val source = android.graphics.ImageDecoder.createSource(ctx.contentResolver, uri)
                val bmp = android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val w = info.size.width
                    val h = info.size.height
                    val target = PREVIEW_MAX_DIM
                    if (w > target || h > target) {
                        val scale = target.toFloat() / maxOf(w, h)
                        decoder.setTargetSize((w * scale).roundToInt(), (h * scale).roundToInt())
                    }
                    decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                }
                val argb = if (bmp.config == Bitmap.Config.ARGB_8888) bmp
                else bmp.copy(Bitmap.Config.ARGB_8888, true)
                lastDecodedUri = uriStr
                _baseBitmap.value = argb
                previousBase?.recycle()
                history.clear()
                _canUndo.value = false
                _canRedo.value = false
                compositor.invalidateCache()
                _document.value = EditDocument.fromBackground(
                    BitmapReference(argb), argb.width, argb.height,
                )
                requestPreviewUpdate(immediate = true)
            } catch (e: IOException) {
                Log.error(TAG, "decode failed for $uri", e)
            } catch (e: SecurityException) {
                Log.error(TAG, "decode failed for $uri", e)
            }
        }
    }

    // --- document mutation ----------------------------------------------------

    fun updateDocument(pushUndo: Boolean = true, transform: (EditDocument) -> EditDocument) {
        val prev = _document.value
        val next = transform(prev)
        if (next === prev) return
        if (pushUndo) {
            history.push(prev)
            _canUndo.value = history.canUndo
            _canRedo.value = history.canRedo
        }
        compositor.invalidateCache()
        _document.value = next
        requestPreviewUpdate(immediate = true)
    }

    fun undo() {
        val current = _document.value
        val prev = history.undo(current) ?: return
        _canUndo.value = history.canUndo
        _canRedo.value = history.canRedo
        compositor.invalidateCache()
        _document.value = prev
        requestPreviewUpdate(immediate = true)
    }

    fun redo() {
        val current = _document.value
        val next = history.redo(current) ?: return
        _canUndo.value = history.canUndo
        _canRedo.value = history.canRedo
        compositor.invalidateCache()
        _document.value = next
        requestPreviewUpdate()
    }

    // --- selection ------------------------------------------------------------

    fun setSelection(sel: Selection?) { _selection.value = sel }

    // --- preview --------------------------------------------------------------

    internal fun requestPreviewUpdate(immediate: Boolean = false) {
        previewJob?.cancel()
        previewJob = viewModelScope.launch(Dispatchers.Default) {
            if (!immediate) delay(PREVIEW_DEBOUNCE_MS)
            val doc = _document.value
            if (doc.layers.isEmpty()) {
                _compositedPreview.value = null
                return@launch
            }
            val renderDoc = if (croppingPreview) doc.copy(cropRect = EditDocument.FULL_CROP, rotation = 0f) else doc
            val preview = try {
                compositor.compositePreview(renderDoc, PREVIEW_MAX_DIM)
            } catch (e: IllegalArgumentException) {
                Log.error(TAG, "preview composite failed", e)
                return@launch
            } catch (e: IllegalStateException) {
                Log.error(TAG, "preview composite failed", e)
                return@launch
            }
            _compositedPreview.value = preview
        }
    }

    override fun onCleared() {
        previewJob?.cancel()
        _compositedPreview.value?.recycle()
        _baseBitmap.value?.recycle()
        _compositedPreview.value = null
        _baseBitmap.value = null
        _document.value = EditDocument()
    }

    /** Shared constants and helpers for PhotoEditViewModel. */
    companion object {
        private const val TAG = "PhotoEditViewModel"
        private const val PREVIEW_MAX_DIM = 2048
        private const val PREVIEW_DEBOUNCE_MS = 150L
    }
}

@Suppress("FunctionName")
fun PhotoEditViewModelFactory(
    application: Application,
    repository: PhotosRepository,
): ViewModelProvider.Factory =
    viewModelFactory {
        initializer { PhotoEditViewModel(application, repository) }
    }

/** Output formats for saving/exporting an edited photo. */
enum class ExportFormat(
    val display: String,
    val mime: String,
    val ext: String,
    val compress: Bitmap.CompressFormat,
    val quality: Int = 95,
) {
    Jpeg("JPEG", "image/jpeg", "jpg", Bitmap.CompressFormat.JPEG, quality = 92),
    Png("PNG", "image/png", "png", Bitmap.CompressFormat.PNG, quality = 100),
    Webp("WebP", "image/webp", "webp", Bitmap.CompressFormat.WEBP_LOSSY, quality = 92),
}
