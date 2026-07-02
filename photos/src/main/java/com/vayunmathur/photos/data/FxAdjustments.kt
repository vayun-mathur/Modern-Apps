package com.vayunmathur.photos.data

import android.graphics.Bitmap

/** Non-destructive wrappers so the FilterFx effects can live as adjustment layers. */

data class FilterBlurAdj(val blur: FilterBlur = FilterBlur()) : LayerAdjustment {
    override fun isIdentity(): Boolean = blur.isIdentity()
    override val label: String get() = "Blur"
    override fun applyToBitmap(bitmap: Bitmap): Bitmap = blur.applyToBitmap(bitmap)
}

data class UnsharpAdj(val mask: UnsharpMask = UnsharpMask()) : LayerAdjustment {
    override fun isIdentity(): Boolean = mask.isIdentity()
    override val label: String get() = "Sharpen"
    override fun applyToBitmap(bitmap: Bitmap): Bitmap = mask.applyToBitmap(bitmap)
}

data class NoiseAdj(val noise: NoiseParams = NoiseParams()) : LayerAdjustment {
    override fun isIdentity(): Boolean = noise.isIdentity()
    override val label: String get() = "Noise"
    override fun applyToBitmap(bitmap: Bitmap): Bitmap = noise.applyToBitmap(bitmap)
}

data class StylizeAdj(val stylize: StylizeParams = StylizeParams()) : LayerAdjustment {
    override fun isIdentity(): Boolean = stylize.isIdentity()
    override val label: String get() = "Stylize"
    override fun applyToBitmap(bitmap: Bitmap): Bitmap = stylize.applyToBitmap(bitmap)
}
