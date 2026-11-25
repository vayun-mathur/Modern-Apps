package com.vayunmathur.camera.platform.camera2

/**
 * Ring still captures for [Camera2Session]: serving a shutter from the
 * YUV reprocess ring (reprocess or direct encode) and handing RAW ring
 * bursts to the night merge. Moved verbatim from the session as
 * extensions (see Camera2SessionStillDelivery.kt); all state is touched
 * only on the session executor, as before.
 */

/**
 * Takes one still from the ring: picks the exposure-complete frame
 * nearest [shutterTimestampNs] (or the latest preview timestamp when
 * null), then either reprocesses it on the reprocessable session or
 * direct-encodes it, delivering through the same [Camera2Session.StillCallback]
 * shape as a plain still capture. A ring miss (empty / nothing fully exposed)
 * and a runtime reprocess refusal both fall back to the regular
 * still capture — the shutter is never lost to the ring.
 */
fun Camera2Session.captureFromRing(
    callback: Camera2Session.StillCallback,
    shutterTimestampNs: Long? = null,
    displayRotationDegrees: Int? = null,
    mirrored: Boolean = false,
) {
    executor.execute {
        val session = captureSession
        when {
            state != SessionState.STREAMING || session == null ->
                callback.onStillFailed("ring capture requires a streaming session")
            ringOutput == null ->
                callback.onStillFailed("no YUV ring output on this session")
            pendingStill != null ->
                callback.onStillFailed("a still capture is already in flight")
            else -> {
                val shutter = shutterTimestampNs
                    ?: latestPreviewTimestampNs
                    ?: ring.metas().maxOfOrNull { it.sensorTimestampNs }
                val selection = shutter?.let { ring.select(it) }
                if (selection !is RingSelection.Hit) {
                    // No already-exposed frame: take a fresh still.
                    startStillCapture(callback, displayRotationDegrees, mirrored)
                    return@execute
                }
                val held = ring.remove(selection.meta.sensorTimestampNs)
                if (held == null) {
                    startStillCapture(callback, displayRotationDegrees, mirrored)
                    return@execute
                }
                val params = StillCapturePlanner.resolve(
                    state = controls,
                    snapshot = snapshot,
                    displayRotationDegrees = displayRotationDegrees
                        ?: this@captureFromRing.displayRotationDegrees,
                    mirrored = mirrored,
                )
                val output = stillOutput
                val reprocessed = output != null && snapshot.supportsYuvRingReprocess &&
                    runCatching {
                        val pending = Camera2Session.PendingStill(params, callback)
                        pendingStill = pending
                        val started = session.captureReprocess(
                            params, output.surface, held,
                            object : StillResultCallback {
                                override fun onCompleted(metadata: RawStillMetadata) =
                                    onStillResult(pending, metadata)

                                override fun onFailed(message: String) =
                                    failPending(pending, message)
                            },
                        )
                        if (!started && pendingStill === pending) pendingStill = null
                        started
                    }.getOrDefault(false)
                if (reprocessed) {
                    // ImageWriter owns the held image from here.
                    return@execute
                }
                // Direct fallback: encode the already-exposed frame.
                val jpeg = held.encodeJpeg()
                // The ring's copy carries preview-result patches;
                // the held frame's own meta may predate them.
                val meta = selection.meta
                held.close()
                if (jpeg == null) {
                    startStillCapture(callback, displayRotationDegrees, mirrored)
                    return@execute
                }
                callback.onStillCaptured(
                    CapturedStill(
                        jpegBytes = jpeg,
                        jpegWidth = meta.width,
                        jpegHeight = meta.height,
                        metadata = StillMetadataMapper.map(
                            RawStillMetadata(
                                sensorTimestampNs = meta.sensorTimestampNs,
                                exposureTimeNs = meta.exposureTimeNs,
                                iso = meta.iso,
                                frameNumber = meta.frameNumber,
                            ),
                            params.jpegOrientationDegrees, params.mirrored,
                        ),
                    ),
                )
            }
        }
    }
}

/**
 * Hands the newest [count] exposure-complete RAW ring frames (oldest
 * first, so frame 0 is the merge reference) to [callback]. The frames
 * are removed from the ring; the caller owns closing them. Fewer than
 * two frames is a failure — a one-frame "merge" would silently be a
 * single RAW shot, and the caller falls back to the YUV night path.
 */
fun Camera2Session.captureRawBurst(
    count: Int,
    callback: Camera2Session.RawBurstCallback,
    shutterTimestampNs: Long? = null,
) {
    executor.execute {
        when {
            state != SessionState.STREAMING ->
                callback.onRawBurstFailed("RAW burst requires a streaming session")
            rawRingOutput == null ->
                callback.onRawBurstFailed("no RAW ring output on this session")
            else -> {
                val shutter = shutterTimestampNs
                    ?: latestPreviewTimestampNs
                    ?: rawRing.metas().maxOfOrNull { it.sensorTimestampNs }
                val burst = shutter?.let { rawRing.latestCompleteBurst(count, it) }
                    .orEmpty()
                if (burst.size < 2) {
                    callback.onRawBurstFailed(
                        "RAW ring holds ${burst.size} complete frames, need 2+",
                    )
                    return@execute
                }
                val frames = burst.mapNotNull { (frame, meta) ->
                    rawRing.remove(meta.sensorTimestampNs)?.let { frame }
                }
                if (frames.size < 2) {
                    frames.forEach { runCatching { it.close() } }
                    callback.onRawBurstFailed("RAW ring frames vanished during burst selection")
                    return@execute
                }
                callback.onRawBurst(frames, burst.map { it.second })
            }
        }
    }
}
