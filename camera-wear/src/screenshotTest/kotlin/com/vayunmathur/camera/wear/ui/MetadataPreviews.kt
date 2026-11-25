package com.vayunmathur.camera.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.wear.compose.material.MaterialTheme
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.camera.wear.domain.WearCameraState

/** Round watch, 454x454px class (227dp at 320dpi). */
private const val ROUND = "spec:width=227dp,height=227dp,dpi=320"

private val fixtureState = WearCameraState(
    mode = "PHOTO",
    zoomRatio = 1f,
    zoomPresets = listOf(0.5f, 1f, 2f, 5f),
    zoomMin = 0.5f,
    zoomMax = 5f,
    flash = "AUTO",
    hasFlashUnit = true,
    torch = false,
    timerSeconds = 0,
    lastCaptureKind = "Photo",
)

/**
 * Store listing images for `:camera-wear` — every top-level screen/route
 * (viewfinder, controls, last capture).
 *
 * Exception (honest, not faked): the viewfinder's live frame is a JPEG streamed
 * from the phone camera and the last-capture thumbnail is phone media; Layoutlib
 * has no phone camera, so both captures render their real waiting/no-capture
 * states with all controls visible — no synthetic camera image is invented.
 * `./gradlew :camera-wear:metadata` copies renders into `metadata_data/photos/camera-wear/wear/`.
 */
class MetadataPreviews {

    @PreviewTest
    @Preview(name = "1-viewfinder-round", device = ROUND, group = "wear")
    @Composable
    fun Preview1ViewfinderRound() {
        RoundPreview {
            WearViewfinderContent(connected = true, state = fixtureState, bitmap = null, rotation = 0, onOp = {}, onOpenControls = {}, onOpenLastCapture = {})
        }
    }

    @PreviewTest
    @Preview(name = "2-controls-round", device = ROUND, group = "wear")
    @Composable
    fun Preview2ControlsRound() {
        RoundPreview { WearControlsContent(state = fixtureState, onOp = {}, onBack = {}) }
    }

    @PreviewTest
    @Preview(name = "3-last-capture-round", device = ROUND, group = "wear")
    @Composable
    fun Preview3LastCaptureRound() {
        RoundPreview { WearLastCaptureContent(bitmap = null, lastCaptureKind = "", onBack = {}) }
    }
}

/** Round-watch framing: clip Layoutlib's rectangular canvas to the watch shape. */
@Composable
private fun RoundPreview(content: @Composable () -> Unit) {
    MaterialTheme {
        Box(Modifier.fillMaxSize().clip(CircleShape).background(MaterialTheme.colors.background)) { content() }
    }
}
