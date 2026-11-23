package com.vayunmathur.camera.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.camera.R
import com.vayunmathur.camera.platform.GuideDot
import com.vayunmathur.camera.platform.GuideDotState

@Composable
internal fun PanoramaOverlay(
    isSweeping: Boolean,
    isStitching: Boolean,
    guideDots: List<GuideDot>,
    currentAngle: Float,
    currentPitch: Float,
    modifier: Modifier = Modifier
) {
    if (isStitching) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = stringResource(R.string.processing_panorama_u2026), color = Color.White, fontSize = 16.sp)
            }
        }
        return
    }

    if (!isSweeping) return

    val capturedCount = guideDots.count { it.state == GuideDotState.CAPTURED }
    val totalDots = guideDots.size
    val halfFOV = 30f

    val flashAlpha = remember { Animatable(0f) }
    LaunchedEffect(capturedCount) {
        if (capturedCount > 1) {
            flashAlpha.snapTo(0.3f)
            flashAlpha.animateTo(0f, tween(250))
        }
    }

    Box(modifier.fillMaxSize()) {
        if (flashAlpha.value > 0f) {
            Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = flashAlpha.value)))
        }

        Canvas(Modifier.fillMaxSize()) {
            val centerX = size.width / 2
            val centerY = size.height / 2
            val halfVertFOV = 40f

            guideDots.forEach { dot ->
                val angleOffset = dot.targetAngle - currentAngle
                val pitchOffset = dot.targetPitch - currentPitch
                if (Math.abs(angleOffset) <= halfFOV * 1.2f && Math.abs(pitchOffset) <= halfVertFOV * 1.2f) {
                    val dotX = centerX + (angleOffset / halfFOV) * (size.width / 2)
                    val dotY = centerY - (pitchOffset / halfVertFOV) * (size.height / 2)
                    val baseRadius = 14.dp.toPx()

                    when (dot.state) {
                        GuideDotState.PENDING -> {
                            drawCircle(Color.White.copy(alpha = 0.7f), baseRadius, Offset(dotX, dotY), style = Stroke(2.dp.toPx()))
                            drawCircle(Color.White.copy(alpha = 0.3f), 3.dp.toPx(), Offset(dotX, dotY))
                        }
                        GuideDotState.ALIGNING, GuideDotState.CAPTURING -> {
                            drawCircle(Color(0xFF4FC3F7), baseRadius * 1.2f, Offset(dotX, dotY))
                            drawCircle(Color.White, baseRadius * 1.2f, Offset(dotX, dotY), style = Stroke(2.dp.toPx()))
                        }
                        GuideDotState.CAPTURED -> {
                            drawCircle(Color(0xFF4CAF50), baseRadius, Offset(dotX, dotY))
                            val checkPath = Path().apply {
                                moveTo(dotX - baseRadius * 0.35f, dotY)
                                lineTo(dotX - baseRadius * 0.05f, dotY + baseRadius * 0.3f)
                                lineTo(dotX + baseRadius * 0.4f, dotY - baseRadius * 0.3f)
                            }
                            drawPath(checkPath, Color.White, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
                        }
                    }
                }
            }

            val crossSize = 20.dp.toPx()
            val crossGap = 6.dp.toPx()
            val crossStroke = 2.dp.toPx()
            val crossColor = Color.White
            drawLine(crossColor, Offset(centerX - crossSize, centerY), Offset(centerX - crossGap, centerY), crossStroke)
            drawLine(crossColor, Offset(centerX + crossGap, centerY), Offset(centerX + crossSize, centerY), crossStroke)
            drawLine(crossColor, Offset(centerX, centerY - crossSize), Offset(centerX, centerY - crossGap), crossStroke)
            drawLine(crossColor, Offset(centerX, centerY + crossGap), Offset(centerX, centerY + crossSize), crossStroke)
            drawCircle(crossColor, 2.dp.toPx(), Offset(centerX, centerY))
        }

        Text(
            "$capturedCount / $totalDots",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 60.dp)
                .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp, vertical = 6.dp)
        )
    }
}
