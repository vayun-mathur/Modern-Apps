package com.vayunmathur.maps.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import org.maplibre.compose.camera.CameraState
import org.maplibre.spatialk.geojson.Position

fun DrawScope.UserIcon(userPosition: Position, userBearing: Float, camera: CameraState) {
    if (userPosition != Position(0.0, 0.0) && camera.projection != null) {
        val offset =
            camera.projection!!.screenLocationFromPosition(userPosition)

        val centerOffset = Offset(offset.x.toPx(), offset.y.toPx())
        val arcRadius = 20.dp.toPx() // The distance from center to the arc's stroke
        val strokeWidth = 8.dp.toPx() // How thick the arc is

        drawCircle(
            Color(0xFFFFFFFF),
            center = centerOffset,
            radius = 9.5.dp.toPx()
        )
        drawCircle(
            Color(0xFF0E35F1),
            center = centerOffset,
            radius = 8.dp.toPx()
        )

// Create a radial gradient that fades out
        val fadingBrush = Brush.radialGradient(
            0f to Color(0xFF0E35F1),          // Opaque blue at the very center
            0.8f to Color(0xFF0E35F1),        // Stay opaque until the arc's edge
            1.0f to Color.Transparent,        // Fade to nothing
            center = centerOffset,
            radius = arcRadius + strokeWidth  // Gradient ends just past the stroke
        )


        drawArc(
            brush = fadingBrush,
            startAngle = userBearing - 90f - 30f,           // Start position (3 o'clock)
            sweepAngle = 60f,         // Length of the arc
            useCenter = false,         // Set to false for a curved line, true for a pie slice
            topLeft = Offset(centerOffset.x - arcRadius, centerOffset.y - arcRadius),
            size = Size(arcRadius * 2, arcRadius * 2),
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )
    }
}