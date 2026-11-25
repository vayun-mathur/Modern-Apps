package com.vayunmathur.camera.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path

/**
 * SCREENSHOT-ONLY fake camera viewfinder scene.
 *
 * This file lives in `src/screenshotTest`, so it is never compiled into the
 * app APK. It is the provider passed to the `screenshotFakePreview` seam of
 * `CameraScreen` / `CameraPreviewSurface` (production default: null, and no
 * production caller passes it). Layoutlib cannot open a camera or bind a
 * TextureView, so without this the listing captures show a black pane; with
 * it they show a representative scene behind the REAL top bar, grid overlay,
 * shutter and mode controls.
 *
 * The scene is a deliberately synthetic, deterministic landscape drawn from
 * fixed fractions of the canvas size — no bitmaps, no randomness, no time —
 * so every render is byte-stable. It does not pretend to be a photograph
 * taken by the camera: flat shapes and a gradient sky, in the same spirit as
 * the gradient tiles `:photos` uses for its thumbnails.
 */
@Composable
internal fun FakeCameraPreview(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height

        // Sky: fixed vertical gradient, warm horizon to blue zenith.
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color(0xFF7FB2E0),
                    Color(0xFFBFD9EE),
                    Color(0xFFF6D9A8),
                ),
                startY = 0f,
                endY = h * 0.62f,
            ),
            size = Size(w, h * 0.62f),
        )
        // Sun, upper right.
        drawCircle(Color(0xFFFFE9A8), radius = w * 0.07f, center = Offset(w * 0.78f, h * 0.18f))
        drawCircle(Color(0xFFFFF6D8), radius = w * 0.045f, center = Offset(w * 0.78f, h * 0.18f))

        // Distant mountains (fixed ridge line).
        val ridge = Path().apply {
            moveTo(0f, h * 0.52f)
            lineTo(w * 0.18f, h * 0.34f)
            lineTo(w * 0.34f, h * 0.46f)
            lineTo(w * 0.52f, h * 0.30f)
            lineTo(w * 0.68f, h * 0.44f)
            lineTo(w * 0.84f, h * 0.36f)
            lineTo(w, h * 0.48f)
            lineTo(w, h * 0.62f)
            lineTo(0f, h * 0.62f)
            close()
        }
        drawPath(ridge, Color(0xFF6E8FB4))

        // Lake / meadow band.
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color(0xFF9CC3D6), Color(0xFF7FAE7A), Color(0xFF5E9455)),
                startY = h * 0.58f,
                endY = h,
            ),
            topLeft = Offset(0f, h * 0.58f),
            size = Size(w, h * 0.42f),
        )

        // Foreground hill silhouettes.
        val hill = Path().apply {
            moveTo(0f, h * 0.78f)
            quadraticTo(w * 0.30f, h * 0.66f, w * 0.58f, h * 0.78f)
            quadraticTo(w * 0.82f, h * 0.88f, w, h * 0.80f)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        drawPath(hill, Color(0xFF3E6B35))

        // Fixed trees: trunk + canopy at deterministic positions.
        val treeXs = listOf(0.10f, 0.24f, 0.62f, 0.88f)
        treeXs.forEachIndexed { index, fx ->
            val baseY = h * (0.84f + (index % 2) * 0.05f)
            val treeH = h * (0.13f + (index % 3) * 0.02f)
            drawRect(
                Color(0xFF4A3421),
                topLeft = Offset(w * fx - w * 0.008f, baseY - treeH * 0.35f),
                size = Size(w * 0.016f, treeH * 0.35f),
            )
            drawCircle(
                Color(0xFF2F5D2A),
                radius = treeH * 0.38f,
                center = Offset(w * fx, baseY - treeH * 0.62f),
            )
        }

        // Fixed foreground dots (wildflowers), positions from a fixed table.
        val flowerColors = listOf(Color(0xFFFFE082), Color(0xFFF8BBD0), Color.White)
        for (i in 0 until 18) {
            val fx = ((i * 37) % 100) / 100f
            val fy = 0.86f + ((i * 53) % 12) / 100f
            drawCircle(flowerColors[i % flowerColors.size], radius = w * 0.006f + 2f, center = Offset(w * fx, h * fy))
        }
    }
}
