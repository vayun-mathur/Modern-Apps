package com.vayunmathur.pdf.ui

import com.vayunmathur.pdf.platform.BlendMode

/** Map the renderer's [BlendMode] to a Compose blend mode (SrcOver = normal). */
internal fun BlendMode.toCompose(): androidx.compose.ui.graphics.BlendMode = when (this) {
    BlendMode.Normal -> androidx.compose.ui.graphics.BlendMode.SrcOver
    BlendMode.Multiply -> androidx.compose.ui.graphics.BlendMode.Multiply
    BlendMode.Screen -> androidx.compose.ui.graphics.BlendMode.Screen
    BlendMode.Overlay -> androidx.compose.ui.graphics.BlendMode.Overlay
    BlendMode.Darken -> androidx.compose.ui.graphics.BlendMode.Darken
    BlendMode.Lighten -> androidx.compose.ui.graphics.BlendMode.Lighten
    BlendMode.ColorDodge -> androidx.compose.ui.graphics.BlendMode.ColorDodge
    BlendMode.ColorBurn -> androidx.compose.ui.graphics.BlendMode.ColorBurn
    BlendMode.HardLight -> androidx.compose.ui.graphics.BlendMode.Hardlight
    BlendMode.SoftLight -> androidx.compose.ui.graphics.BlendMode.Softlight
    BlendMode.Difference -> androidx.compose.ui.graphics.BlendMode.Difference
    BlendMode.Exclusion -> androidx.compose.ui.graphics.BlendMode.Exclusion
    BlendMode.Hue -> androidx.compose.ui.graphics.BlendMode.Hue
    BlendMode.Saturation -> androidx.compose.ui.graphics.BlendMode.Saturation
    BlendMode.Color -> androidx.compose.ui.graphics.BlendMode.Color
    BlendMode.Luminosity -> androidx.compose.ui.graphics.BlendMode.Luminosity
}

/** The [android.graphics.BlendMode] for a renderer [BlendMode]. */
internal fun BlendMode.toAndroid(): android.graphics.BlendMode = when (this) {
    BlendMode.Normal -> android.graphics.BlendMode.SRC_OVER
    BlendMode.Multiply -> android.graphics.BlendMode.MULTIPLY
    BlendMode.Screen -> android.graphics.BlendMode.SCREEN
    BlendMode.Overlay -> android.graphics.BlendMode.OVERLAY
    BlendMode.Darken -> android.graphics.BlendMode.DARKEN
    BlendMode.Lighten -> android.graphics.BlendMode.LIGHTEN
    BlendMode.ColorDodge -> android.graphics.BlendMode.COLOR_DODGE
    BlendMode.ColorBurn -> android.graphics.BlendMode.COLOR_BURN
    BlendMode.HardLight -> android.graphics.BlendMode.HARD_LIGHT
    BlendMode.SoftLight -> android.graphics.BlendMode.SOFT_LIGHT
    BlendMode.Difference -> android.graphics.BlendMode.DIFFERENCE
    BlendMode.Exclusion -> android.graphics.BlendMode.EXCLUSION
    BlendMode.Hue -> android.graphics.BlendMode.HUE
    BlendMode.Saturation -> android.graphics.BlendMode.SATURATION
    BlendMode.Color -> android.graphics.BlendMode.COLOR
    BlendMode.Luminosity -> android.graphics.BlendMode.LUMINOSITY
}

/** Set (or clear) the blend mode on a reused native [android.graphics.Paint]. */
internal fun android.graphics.Paint.setBlend(blend: BlendMode) {
    blendMode = if (blend == BlendMode.Normal) null else blend.toAndroid()
}
