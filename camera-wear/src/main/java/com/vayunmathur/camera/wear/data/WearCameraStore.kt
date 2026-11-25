package com.vayunmathur.camera.wear.data

import com.vayunmathur.camera.wear.domain.PreviewFrame
import com.vayunmathur.camera.wear.domain.WearCameraState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The watch's mirrored camera truth: the phone's last published
 * [WearCameraState], the newest preview frame and the last-capture
 * thumbnail. Deliberately in-memory only — unlike notes, nothing here is
 * usable without the phone (the watch has no camera), so there is no
 * offline copy to persist and no fake local state to show. A cold start
 * honestly shows "no phone nearby" until the link publishes again.
 */
class WearCameraStore {

    private val _linkConnected = MutableStateFlow(false)
    val linkConnected: StateFlow<Boolean> = _linkConnected.asStateFlow()

    private val _state = MutableStateFlow(WearCameraState())
    val state: StateFlow<WearCameraState> = _state.asStateFlow()

    private val _previewJpeg = MutableStateFlow<ByteArray?>(null)
    /** Newest preview JPEG (decode for display); stale sequences are dropped. */
    val previewJpeg: StateFlow<ByteArray?> = _previewJpeg.asStateFlow()

    private val _previewRotation = MutableStateFlow(0)
    val previewRotation: StateFlow<Int> = _previewRotation.asStateFlow()

    private val _thumbnailJpeg = MutableStateFlow<ByteArray?>(null)
    val thumbnailJpeg: StateFlow<ByteArray?> = _thumbnailJpeg.asStateFlow()

    private var lastPreviewSequence = -1L

    var droppedStaleFrames: Long = 0L
        private set

    fun setLinkConnected(connected: Boolean) {
        _linkConnected.value = connected
        if (!connected) {
            // Link down: no fake viewfinder. The last mirrored state stays
            // for context, but the live frame is gone.
            _previewJpeg.value = null
            lastPreviewSequence = -1L
        }
    }

    /** Applies a mirrored phone state (state mirroring is replace, never merge). */
    fun applyState(state: WearCameraState) {
        _state.value = state
    }

    /** Applies one preview frame body; returns false when malformed or stale. */
    fun applyPreviewFrame(body: ByteArray): Boolean {
        val decoded = PreviewFrame.decode(body) ?: return false
        if (decoded.sequence <= lastPreviewSequence) {
            droppedStaleFrames++
            return false
        }
        lastPreviewSequence = decoded.sequence
        _previewRotation.value = decoded.rotationDegrees
        _previewJpeg.value = decoded.jpeg
        return true
    }

    fun applyThumbnail(jpeg: ByteArray) {
        if (jpeg.isNotEmpty()) _thumbnailJpeg.value = jpeg
    }

    companion object {
        @Volatile
        private var instance: WearCameraStore? = null

        /** The process-wide store (bridge and UI must agree on one). */
        fun get(): WearCameraStore = instance ?: synchronized(this) {
            instance ?: WearCameraStore().also { instance = it }
        }
    }
}
