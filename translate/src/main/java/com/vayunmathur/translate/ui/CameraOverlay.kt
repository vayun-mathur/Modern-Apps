package com.vayunmathur.translate.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.library.ui.IconBack
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconFlashOff
import com.vayunmathur.library.ui.IconFlashOn
import com.vayunmathur.library.ui.IconPause
import com.vayunmathur.library.ui.IconPlay
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.translate.R
import com.vayunmathur.translate.domain.Languages
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Overlay plates for the detected lines plus the bottom control bar. */
@Composable
internal fun CameraOverlay(
    overlays: List<OverlayBox>,
    translations: Map<String, String>,
    translationAvailable: Boolean,
    frameW: Int,
    frameH: Int,
    viewW: Int,
    viewH: Int,
    sourceLang: String,
    targetLang: String,
    paused: Boolean,
    onTogglePaused: () -> Unit,
    torchOn: Boolean,
    onToggleTorch: () -> Unit,
    onBack: () -> Unit,
    onOpenLanguagePicker: (Boolean) -> Unit,
    // False on the gallery-image screen, which has no live capture to pause or torch.
    showCaptureControls: Boolean = true,
) {
    val density = LocalDensity.current
    // Back button (top-left). The preview runs edge-to-edge behind the system bars,
    // so every control on top of it has to inset itself out from under them.
    IconButton(
        onClick = onBack,
        modifier = Modifier
            .statusBarsPadding()
            .padding(8.dp)
            .background(Color(0x99000000), RoundedCornerShape(24.dp)),
    ) {
        IconBack(tint = Color.White)
    }

    if (!translationAvailable) {
        Text(
            text = stringResource(R.string.translation_model_not_installed_showing),
            color = Color.White,
            modifier = Modifier
                .statusBarsPadding()
                .padding(top = 12.dp)
                .background(Color(0xB3000000), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }

    // The analysed frame *is* the visible region now, so placing a box is a
    // straight stretch — no centre-crop arithmetic that can disagree with what
    // PreviewView chose to show.
    if (frameW > 0 && frameH > 0 && viewW > 0 && viewH > 0) {
        val sx = viewW.toFloat() / frameW
        val sy = viewH.toFloat() / frameH
        overlays.forEach { ob ->
            // Until a line's translation lands, leave the original text visible
            // rather than covering it with a blank plate — a screenful of black
            // boxes over text nobody can read was most of why this looked broken.
            // With no model installed none will ever land, so show what was read.
            val label = translations[ob.text]
                ?: if (translationAvailable) return@forEach else ob.text
            // sx and sy are independent, so the on-screen angle has to be
            // re-derived from the *mapped* top edge; a bitmap-space angle
            // would be wrong under an anisotropic stretch.
            val p = ob.corners.map { Offset(it.x * sx, it.y * sy) }
            val runX = p[1].x - p[0].x
            val runY = p[1].y - p[0].y
            val w = hypot(runX, runY)
            val h = hypot(p[3].x - p[0].x, p[3].y - p[0].y)
            if (w <= 0f || h <= 0f) return@forEach
            val angle = (atan2(runY, runX) * 180f / PI).toFloat()
            Box(
                modifier = Modifier
                    // Anchored at the start of the run and turned onto it,
                    // so a slanted line gets a slanted plate instead of an
                    // opaque one the size of its bounding box covering its
                    // neighbours.
                    .offset { IntOffset(p[0].x.roundToInt(), p[0].y.roundToInt()) }
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(0f, 0f)
                        rotationZ = angle
                    }
                    .size(
                        width = with(density) { w.toDp() },
                        height = with(density) { h.toDp() },
                    )
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xF2000000))
                    .padding(horizontal = 3.dp, vertical = 1.dp),
                contentAlignment = Alignment.Center,
            ) {
                // A translation is usually longer than the source line it has to
                // sit on top of, so a fixed font size ellipsised nearly every
                // label down to "…". Shrink to fit instead. One line only: the
                // plate is exactly as tall as the line it covers, so allowing two
                // just halved the font and spilled out of the box.
                BasicText(
                    text = label,
                    style = TextStyle(color = Color.White, textAlign = TextAlign.Center),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    autoSize = TextAutoSize.StepBased(
                        minFontSize = 6.sp,
                        maxFontSize = 24.sp,
                        stepSize = 0.5.sp,
                    ),
                )
            }
        }
    }

    // Bottom controls: language pickers + pause/resume + torch. Inset above the
    // gesture bar — sitting in it meant a tap on pause was liable to be swallowed by
    // system navigation instead. The fillMaxSize + BottomCenter wrapper pins the bar
    // to the bottom of whatever box hosts this overlay: without it the row lands at
    // the top, under the status bar and camera cutout, where it can't be tapped.
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Row(
            modifier = Modifier
                .navigationBarsPadding()
                .fillMaxWidth()
                .padding(12.dp)
                .background(Color(0xB3000000), RoundedCornerShape(24.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            LanguageFieldButton(
                label = Languages.displayName(sourceLang),
                onClick = { onOpenLanguagePicker(true) },
                modifier = Modifier.width(120.dp),
            )
            if (showCaptureControls) {
                IconButton(onClick = onTogglePaused) {
                    if (paused) IconPlay(tint = Color.White) else IconPause(tint = Color.White)
                }
                IconButton(onClick = onToggleTorch) {
                    if (torchOn) IconFlashOn(tint = Color.White) else IconFlashOff(tint = Color.White)
                }
            }
            LanguageFieldButton(
                label = Languages.displayName(targetLang),
                onClick = { onOpenLanguagePicker(false) },
                modifier = Modifier.width(120.dp),
            )
        }
    }
}

/**
 * A language field inside the camera bottom bar: white text on the bar's black pill,
 * opening the full-screen picker. Carries its own minimum touch target so the narrow
 * width doesn't make it hard to hit.
 */
@Composable
internal fun LanguageFieldButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onClick, modifier = modifier) {
        Text(
            text = label,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
