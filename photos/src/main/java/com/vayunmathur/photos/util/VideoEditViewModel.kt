package com.vayunmathur.photos.util

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.vayunmathur.library.log.Log
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.Crop
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.RgbAdjustment
import androidx.media3.effect.RgbFilter
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.PhotosRepository
import com.vayunmathur.photos.data.VideoEditState
import com.vayunmathur.photos.data.VideoFilterPreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Video editor view model. The single source of truth is [state]; both the live
 * preview (ExoPlayer `setVideoEffects` / clipping / volume in `VideoEditPage`)
 * and the export ([Transformer]) derive their transforms from it via
 * [buildVideoEffects], so what the user sees is what gets written.
 *
 * Save mirrors [PhotoEditViewModel]'s MediaStore conventions: a copy is inserted
 * into `MediaStore.Video`, and an overwrite falls back to
 * [MediaStore.createWriteRequest] + IntentSender when a direct write is denied.
 */
@UnstableApi
class VideoEditViewModel(
    application: Application,
    private val repository: PhotosRepository,
) : AndroidViewModel(application) {

    private val _photo = MutableStateFlow<Photo?>(null)
    val photo: StateFlow<Photo?> = _photo.asStateFlow()
    private var photoJob: Job? = null

    private val _state = MutableStateFlow(VideoEditState())
    val state: StateFlow<VideoEditState> = _state.asStateFlow()

    private val _exporting = MutableStateFlow(false)
    val exporting: StateFlow<Boolean> = _exporting.asStateFlow()

    /** Export progress in 0f..1f while [exporting] is true. */
    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _writePermissionRequest = MutableStateFlow<IntentSender?>(null)
    val writePermissionRequest: StateFlow<IntentSender?> = _writePermissionRequest.asStateFlow()

    private var transformer: Transformer? = null
    private var progressJob: Job? = null

    // Kept alive across the overwrite-permission round-trip.
    private var pendingExportFile: File? = null
    private var pendingExportUri: Uri? = null
    private var pendingOnComplete: (() -> Unit)? = null

    // The transformer's in-flight output. Tracked so cancel/clear can delete
    // it: Transformer.cancel() fires no listener callback, so the listener's
    // own tempFile.delete() never runs on that path.
    private var activeExportFile: File? = null

    /** Loads [id]'s video from the DB, falling back to a stand-in built from [initialUri]. */
    fun loadVideo(id: Long, initialUri: String?) {
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

    // --- state mutation -------------------------------------------------------

    private inline fun update(transform: (VideoEditState) -> VideoEditState) {
        _state.value = transform(_state.value)
    }

    /** Called once the player reports the real duration; seeds the trim range. */
    fun setDuration(durationMs: Long) {
        if (durationMs <= 0L) return
        update {
            if (it.durationMs == durationMs) it
            else it.copy(
                durationMs = durationMs,
                trimStartMs = it.trimStartMs.coerceIn(0L, durationMs),
                trimEndMs = if (it.trimEndMs <= 0L || it.trimEndMs > durationMs) durationMs else it.trimEndMs,
            )
        }
    }

    fun setTrim(startMs: Long, endMs: Long) = update {
        val d = it.durationMs
        it.copy(
            trimStartMs = startMs.coerceIn(0L, d),
            trimEndMs = endMs.coerceIn(0L, d),
        )
    }

    fun rotateLeft() = update { it.copy(rotationDegrees = ((it.rotationDegrees - 90) % 360 + 360) % 360) }
    fun rotateRight() = update { it.copy(rotationDegrees = (it.rotationDegrees + 90) % 360) }
    fun toggleFlip() = update { it.copy(flipHorizontal = !it.flipHorizontal) }

    fun setCrop(left: Float?, top: Float?, right: Float?, bottom: Float?) = update {
        it.copy(cropLeft = left, cropTop = top, cropRight = right, cropBottom = bottom)
    }
    fun clearCrop() = update { it.copy(cropLeft = null, cropTop = null, cropRight = null, cropBottom = null) }

    fun setMuted(muted: Boolean) = update { it.copy(muted = muted) }

    fun setBrightness(v: Float) = update { it.copy(brightness = v.coerceIn(-1f, 1f)) }
    fun setContrast(v: Float) = update { it.copy(contrast = v.coerceIn(-1f, 1f)) }
    fun setSaturation(v: Float) = update { it.copy(saturation = v.coerceIn(-1f, 1f)) }
    fun setFilterPreset(preset: VideoFilterPreset) = update { it.copy(filterPreset = preset) }

    // --- effects (shared by preview + export) ---------------------------------

    /** Maps [state] to the ordered list of Media3 video effects. */
    fun buildVideoEffects(state: VideoEditState): List<Effect> {
        val effects = mutableListOf<Effect>()
        when (state.filterPreset) {
            VideoFilterPreset.None -> {}
            VideoFilterPreset.Mono -> effects.add(RgbFilter.createGrayscaleFilter())
            VideoFilterPreset.Warm ->
                effects.add(RgbAdjustment.Builder().setRedScale(WARM_RED_SCALE).setBlueScale(WARM_BLUE_SCALE).build())
            VideoFilterPreset.Cool ->
                effects.add(RgbAdjustment.Builder().setRedScale(COOL_RED_SCALE).setBlueScale(COOL_BLUE_SCALE).build())
            VideoFilterPreset.Vivid ->
                effects.add(HslAdjustment.Builder().adjustSaturation(VIVID_SATURATION).build())
        }
        if (state.brightness != 0f) effects.add(Brightness(state.brightness))
        if (state.contrast != 0f) effects.add(Contrast(state.contrast))
        if (state.saturation != 0f) {
            effects.add(HslAdjustment.Builder().adjustSaturation(state.saturation * SATURATION_PERCENT).build())
        }
        if (state.isCropped) {
            val l = state.cropLeft!!; val t = state.cropTop!!
            val r = state.cropRight!!; val b = state.cropBottom!!
            // Normalized [0,1] top-left rect → NDC [-1,1] (Crop: left, right, bottom, top).
            effects.add(Crop(l * 2f - 1f, r * 2f - 1f, 1f - b * 2f, 1f - t * 2f))
        }
        if (state.rotationDegrees != 0 || state.flipHorizontal) {
            val builder = ScaleAndRotateTransformation.Builder()
            if (state.flipHorizontal) builder.setScale(-1f, 1f)
            if (state.rotationDegrees != 0) builder.setRotationDegrees(state.rotationDegrees.toFloat())
            effects.add(builder.build())
        }
        return effects
    }

    // --- export ---------------------------------------------------------------

    private fun buildMediaItem(uri: Uri, state: VideoEditState): MediaItem {
        val builder = MediaItem.Builder().setUri(uri)
        if (state.isTrimmed) {
            builder.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(state.trimStartMs)
                    .setEndPositionMs(state.trimEndMs)
                    .build()
            )
        }
        return builder.build()
    }

    /**
     * Exports the edited video to a temp file, then writes it into MediaStore as
     * a copy ([asCopy] == true) or over the original. Must be called on the main
     * thread (Transformer requires a Looper). [onComplete] fires on success or
     * unrecoverable failure; the overwrite-permission path resumes via
     * [onWritePermissionGranted].
     */
    fun export(context: Context, photo: Photo, asCopy: Boolean, onComplete: () -> Unit) {
        if (_exporting.value) return
        val state = _state.value
        val tempFile = File(context.cacheDir, "video_edit_${System.currentTimeMillis()}.mp4")
        activeExportFile = tempFile
        val mediaItem = buildMediaItem(photo.uri.toUri(), state)
        val editedItem = EditedMediaItem.Builder(mediaItem)
            .setRemoveAudio(state.muted)
            .setEffects(Effects(emptyList(), buildVideoEffects(state)))
            .build()

        val t = Transformer.Builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    stopProgress()
                    activeExportFile = null
                    finishExport(context, photo, asCopy, tempFile, onComplete)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    Log.error(TAG, "Export failed", exportException)
                    stopProgress()
                    tempFile.delete()
                    activeExportFile = null
                    _exporting.value = false
                    onComplete()
                }
            })
            .build()
        transformer = t
        _exporting.value = true
        _progress.value = 0f
        try {
            t.start(editedItem, tempFile.absolutePath)
        } catch (e: Exception) {
            // Synchronous start failure (e.g. the source URI vanished): no
            // listener callback will ever arrive, so tear down here or the
            // UI sits on a progress dialog that never resolves.
            Log.error(TAG, "Export start failed", e)
            tempFile.delete()
            activeExportFile = null
            transformer = null
            _exporting.value = false
            onComplete()
            return
        }
        startProgress()
    }

    private fun startProgress() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            val holder = ProgressHolder()
            while (isActive) {
                val stateInt = transformer?.getProgress(holder) ?: Transformer.PROGRESS_STATE_NOT_STARTED
                if (stateInt == Transformer.PROGRESS_STATE_AVAILABLE) {
                    _progress.value = holder.progress / PROGRESS_PERCENT_DIVISOR
                }
                delay(PROGRESS_POLL_MS)
            }
        }
    }

    private fun stopProgress() {
        progressJob?.cancel()
        progressJob = null
        transformer = null
    }

    fun cancelExport() {
        transformer?.cancel()
        stopProgress()
        activeExportFile?.delete()
        activeExportFile = null
        pendingExportFile?.delete()
        pendingExportFile = null
        pendingExportUri = null
        pendingOnComplete = null
        _exporting.value = false
    }

    private fun finishExport(
        context: Context,
        photo: Photo,
        asCopy: Boolean,
        tempFile: File,
        onComplete: () -> Unit,
    ) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                writeVideo(context, photo, tempFile, asCopy)
            }
            when (result) {
                is WriteResult.Success -> {
                    _exporting.value = false
                    onComplete()
                }
                is WriteResult.NeedsPermission -> {
                    pendingExportFile = result.tempFile
                    pendingExportUri = result.uri
                    pendingOnComplete = onComplete
                    _writePermissionRequest.value = result.intentSender
                }
                is WriteResult.Error -> {
                    Log.error(TAG, "Save failed", result.exception)
                    tempFile.delete()
                    _exporting.value = false
                    onComplete()
                }
            }
        }
    }

    fun onWritePermissionGranted() {
        val tempFile = pendingExportFile ?: return
        val uri = pendingExportUri ?: return
        val onComplete = pendingOnComplete ?: return
        pendingExportFile = null; pendingExportUri = null; pendingOnComplete = null
        _writePermissionRequest.value = null
        val ctx: Context = getApplication()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val resolver = ctx.contentResolver
                    resolver.openOutputStream(uri, "w")?.use { out ->
                        tempFile.inputStream().use { it.copyTo(out) }
                    } ?: throw IllegalStateException("openOutputStream returned null after permission grant")
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)
                        put(MediaStore.Video.Media.SIZE, tempFile.length())
                    }
                    resolver.update(uri, values, null, null)
                } catch (e: IOException) {
                    Log.error(TAG, "Overwrite FAILED after permission grant", e)
                } catch (e: SecurityException) {
                    Log.error(TAG, "Overwrite FAILED after permission grant", e)
                } finally {
                    tempFile.delete()
                }
            }
            _exporting.value = false
            onComplete()
        }
    }

    fun onWritePermissionDenied() {
        pendingExportFile?.delete()
        pendingExportFile = null; pendingExportUri = null; pendingOnComplete = null
        _writePermissionRequest.value = null
        _exporting.value = false
    }

    override fun onCleared() {
        progressJob?.cancel()
        transformer?.cancel()
        transformer = null
        activeExportFile?.delete()
        pendingExportFile?.delete()
    }

    companion object {
        private const val TAG = "VideoEditViewModel"

        private const val WARM_RED_SCALE = 1.1f
        private const val WARM_BLUE_SCALE = 0.9f
        private const val COOL_RED_SCALE = 0.9f
        private const val COOL_BLUE_SCALE = 1.1f
        private const val VIVID_SATURATION = 40f
        private const val SATURATION_PERCENT = 100f
        private const val PROGRESS_PERCENT_DIVISOR = 100f
        private const val PROGRESS_POLL_MS = 200L

        private sealed class WriteResult {
            data object Success : WriteResult()
            data class NeedsPermission(
                val intentSender: IntentSender,
                val tempFile: File,
                val uri: Uri,
            ) : WriteResult()
            data class Error(val exception: Exception) : WriteResult()
        }

        private fun writeVideo(
            context: Context,
            photo: Photo,
            tempFile: File,
            asCopy: Boolean,
        ): WriteResult {
            return if (asCopy) {
                writeVideoCopy(context, photo, tempFile)
            } else {
                overwriteVideo(context, photo, tempFile)
            }
        }

        private fun writeVideoCopy(context: Context, photo: Photo, tempFile: File): WriteResult {
            val resolver = context.contentResolver
            val nowSeconds = System.currentTimeMillis() / 1000
            val baseName = photo.name.substringBeforeLast('.')
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "Edited_$baseName.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.DATE_MODIFIED, nowSeconds)
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            return try {
                val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return WriteResult.Error(IllegalStateException("MediaStore insert returned null"))
                resolver.openOutputStream(uri)?.use { out ->
                    tempFile.inputStream().use { it.copyTo(out) }
                }
                val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
                tempFile.delete()
                WriteResult.Success
            } catch (e: IOException) {
                WriteResult.Error(e)
            } catch (e: SecurityException) {
                WriteResult.Error(e)
            } catch (e: IllegalArgumentException) {
                WriteResult.Error(e)
            }
        }

        private fun overwriteVideo(context: Context, photo: Photo, tempFile: File): WriteResult {
            val resolver = context.contentResolver
            val nowSeconds = System.currentTimeMillis() / 1000
            val uri = photo.uri.toUri()
            try {
                resolver.openOutputStream(uri, "w")?.use { out ->
                    tempFile.inputStream().use { it.copyTo(out) }
                }
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DATE_MODIFIED, nowSeconds)
                    put(MediaStore.Video.Media.SIZE, tempFile.length())
                }
                resolver.update(uri, values, null, null)
                tempFile.delete()
                return WriteResult.Success
            } catch (e: IOException) {
                Log.debug(TAG, "Direct write failed, falling back to createWriteRequest", e)
            } catch (e: SecurityException) {
                Log.debug(TAG, "Direct write failed, falling back to createWriteRequest", e)
            }
            val pendingIntent = MediaStore.createWriteRequest(resolver, listOf(uri))
            return WriteResult.NeedsPermission(pendingIntent.intentSender, tempFile, uri)
        }
    }
}

@Suppress("FunctionName")
fun VideoEditViewModelFactory(
    application: Application,
    repository: PhotosRepository,
): ViewModelProvider.Factory =
    viewModelFactory {
        initializer { VideoEditViewModel(application, repository) }
    }
