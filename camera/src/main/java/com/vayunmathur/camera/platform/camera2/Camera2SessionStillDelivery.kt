package com.vayunmathur.camera.platform.camera2

/**
 * Still-delivery plumbing for [Camera2Session]: pairing the JPEG bytes
 * and the capture-result metadata of an in-flight still (they arrive
 * independently, by output tag) and delivering or failing the pending
 * capture exactly once. Moved verbatim from the session as extensions;
 * all state is touched only on the session executor, as before.
 */

internal fun Camera2Session.onStillResult(pending: Camera2Session.PendingStill, metadata: RawStillMetadata) {
    // Real driver callbacks already land on the executor; fakes may
    // call synchronously. State is only touched on the executor.
    try {
        executor.execute {
            if (pendingStill !== pending) return@execute
            pending.rawMetadata = metadata
            deliverPendingIfReady(pending)
        }
    } catch (e: java.util.concurrent.RejectedExecutionException) {
        // Session is gone; the capture outcome no longer matters.
    }
}

internal fun Camera2Session.onJpegAvailable(bytes: ByteArray, width: Int, height: Int) {
    onStillBytesAvailable(bytes, width, height, StillOutputTag.JPEG)
}

internal fun Camera2Session.onUltraHdrJpegAvailable(bytes: ByteArray, width: Int, height: Int) {
    onStillBytesAvailable(bytes, width, height, StillOutputTag.ULTRA_HDR_JPEG_R)
}

internal fun Camera2Session.onStillBytesAvailable(
    bytes: ByteArray,
    width: Int,
    height: Int,
    tag: StillOutputTag,
) {
    try {
        executor.execute {
            val pending = pendingStill ?: return@execute
            // Bytes from the output the pending capture did not target
            // are not this still (both readers share the session).
            if (pending.tag != tag) return@execute
            pending.jpegBytes = bytes
            pending.jpegWidth = width
            pending.jpegHeight = height
            deliverPendingIfReady(pending)
        }
    } catch (e: java.util.concurrent.RejectedExecutionException) {
    }
}

internal fun Camera2Session.deliverPendingIfReady(pending: Camera2Session.PendingStill) {
    val raw = pending.rawMetadata ?: return
    val bytes = pending.jpegBytes ?: return
    pendingStill = null
    pending.callback.onStillCaptured(
        CapturedStill(
            jpegBytes = bytes,
            jpegWidth = pending.jpegWidth,
            jpegHeight = pending.jpegHeight,
            metadata = StillMetadataMapper.map(
                raw, pending.params.jpegOrientationDegrees, pending.params.mirrored,
            ),
            tag = pending.tag,
        ),
    )
}

internal fun Camera2Session.failPending(pending: Camera2Session.PendingStill, message: String) {
    try {
        executor.execute {
            if (pendingStill !== pending) return@execute
            pendingStill = null
            pending.callback.onStillFailed(message)
        }
    } catch (e: java.util.concurrent.RejectedExecutionException) {
    }
}
