package com.vayunmathur.camera.ui

import android.os.SystemClock
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.MeteringPoint
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.SurfaceRequest
import androidx.camera.viewfinder.compose.MutableCoordinateTransformer
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import com.vayunmathur.library.log.Log
import kotlinx.coroutines.delay

/** Minimum gap between two tap-to-focus requests; faster repeats are one gesture, not two. */
private const val FOCUS_TAP_DEBOUNCE_MS = 120L

/** How long the focus ring stays up (its own fade runs inside [FocusIndicator]). */
private const val FOCUS_INDICATOR_LIFETIME_MS = 1_350L

/**
 * Normalises a coordinate-transformer output (surface-buffer pixels) into the
 * 0..1 range [MeteringPoint] expects, clamped to the
 * surface. Pure so it stays unit-testable.
 *
 * The clamp matters: with `ContentScale.Crop` a tap at the box edge can map
 * just outside the buffer, and CameraX rejects an out-of-range point — the
 * future then fails and, before this fix, was discarded unread, so the tap
 * silently did nothing. A non-finite transform (matrix not populated yet) or
 * a degenerate surface falls back to the centre rather than dropping the tap.
 */
internal fun normalizedMeteringCoordinates(
    transformedX: Float,
    transformedY: Float,
    surfaceWidth: Float,
    surfaceHeight: Float,
): Pair<Float, Float> {
    if (!transformedX.isFinite() || !transformedY.isFinite() || surfaceWidth <= 0f || surfaceHeight <= 0f) {
        return 0.5f to 0.5f
    }
    return (transformedX / surfaceWidth).coerceIn(0f, 1f) to
        (transformedY / surfaceHeight).coerceIn(0f, 1f)
}

/**
 * In-screen tap-to-focus state for one preview: where the ring is, whether
 * the tap locked metering, and the debounce clock. Deliberately NOT
 * navigation — a focus point is transient state of this one screen.
 */
internal class TapToFocusState {
    var point by mutableStateOf<Offset?>(null)
        private set
    var tapId by mutableIntStateOf(0)
        private set
    var locked by mutableStateOf(false)
        private set
    var lastTapMs by mutableLongStateOf(0L)

    fun show(point: Offset, locked: Boolean) {
        this.point = point
        this.locked = locked
        tapId++
    }

    fun clear() {
        point = null
    }
}

private fun meteringPoint(
    tapOffset: Offset,
    surfaceRequest: SurfaceRequest?,
    coordinateTransformer: MutableCoordinateTransformer,
): MeteringPoint? {
    val request = surfaceRequest ?: return null
    return try {
        val transformed = with(coordinateTransformer) { tapOffset.transform() }
        val width = request.resolution.width.toFloat()
        val height = request.resolution.height.toFloat()
        val (nx, ny) = normalizedMeteringCoordinates(transformed.x, transformed.y, width, height)
        val factory = if (width > 0f && height > 0f) {
            SurfaceOrientedMeteringPointFactory(width, height)
        } else {
            SurfaceOrientedMeteringPointFactory(1f, 1f)
        }
        val surfaceW = if (width > 0f) width else 1f
        val surfaceH = if (height > 0f) height else 1f
        factory.createPoint(nx * surfaceW, ny * surfaceH)
    } catch (e: Exception) {
        Log.status("TapToFocus", "metering-point conversion failed; using centre point", e)
        try {
            SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(0.5f, 0.5f)
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Tap-to-focus gestures on the preview surface itself, in the same single
 * detector as long-press-to-lock. CameraXViewfinder's built-in tap gesture
 * stays disabled: it raced this detector, gave no tap indicator, silently
 * returned when the transformed point left 0..1, and its result was
 * invisible to the app.
 *
 * Keyed on [surfaceRequest] + [mirrorPreview], NOT only on a settings key:
 * with a stale key the block captured the first-composed surfaceRequest
 * (null before the session binds), so every metering point came back null.
 */
internal fun Modifier.tapToFocus(
    state: TapToFocusState,
    surfaceRequest: SurfaceRequest?,
    mirrorPreview: Boolean,
    coordinateTransformer: MutableCoordinateTransformer,
    onFocus: (FocusMeteringAction) -> Unit,
    onLockFocus: (FocusMeteringAction) -> Unit,
    onFallback: () -> Unit,
): Modifier = pointerInput(surfaceRequest, mirrorPreview) {
    fun showIndicator(tapOffset: Offset, locked: Boolean) {
        // tapOffset is in the (possibly extra-mirrored) preview layer's
        // coordinates; the indicator is drawn by a sibling outside that
        // layer, so flip back into visual coordinates.
        val visual = if (mirrorPreview) {
            Offset(size.width - tapOffset.x, tapOffset.y)
        } else {
            tapOffset
        }
        state.show(visual, locked)
    }
    detectTapGestures(
        onTap = { tapOffset ->
            val now = SystemClock.uptimeMillis()
            if (now - state.lastTapMs < FOCUS_TAP_DEBOUNCE_MS) return@detectTapGestures
            state.lastTapMs = now
            // Indicator first, regardless of AF outcome: it proves the tap
            // registered even if metering then fails.
            showIndicator(tapOffset, locked = false)
            val point = meteringPoint(tapOffset, surfaceRequest, coordinateTransformer)
            if (point == null) {
                Log.status("TapToFocus", "tap with no surface request; falling back to default metering")
                onFallback()
            } else {
                onFocus(FocusMeteringAction.Builder(point).build())
            }
        },
        onLongPress = { tapOffset ->
            showIndicator(tapOffset, locked = true)
            val point = meteringPoint(tapOffset, surfaceRequest, coordinateTransformer)
            if (point == null) {
                Log.status("TapToFocus", "long-press with no surface request; cannot lock metering")
                onFallback()
            } else {
                onLockFocus(FocusMeteringAction.Builder(point).disableAutoCancel().build())
            }
        }
    )
}

/**
 * Hosts the tap-to-focus ring above the preview overlays (below the
 * settings column) and expires it after [FOCUS_INDICATOR_LIFETIME_MS].
 * The Canvas carries no pointer input, so it never eats the next tap.
 */
@Composable
internal fun TapToFocus(
    state: TapToFocusState,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(state.tapId) {
        if (state.tapId > 0) {
            delay(FOCUS_INDICATOR_LIFETIME_MS)
            state.clear()
        }
    }
    val point = state.point ?: return
    FocusIndicator(
        point = point,
        tapId = state.tapId,
        locked = state.locked,
        modifier = modifier,
    )
}
