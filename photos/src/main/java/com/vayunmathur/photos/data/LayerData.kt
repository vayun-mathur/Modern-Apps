package com.vayunmathur.photos.data

import android.graphics.Bitmap
import com.vayunmathur.library.ink.SerializedStroke
import java.util.UUID

/**
 * Wraps a [Bitmap] with a stable unique id. Equality is by id only, which keeps
 * [Layer] data-class equality (and therefore compositor cache invalidation) cheap:
 * editing a layer produces a *new* [BitmapReference] with a new id, while undo
 * snapshots can share the same reference without expensive pixel comparisons.
 */
class BitmapReference(
    val bitmap: Bitmap,
    val id: String = UUID.randomUUID().toString(),
) {
    override fun equals(other: Any?): Boolean = other is BitmapReference && other.id == id
    override fun hashCode(): Int = id.hashCode()
}

/**
 * Per-layer alpha mask. Stored as a normalized [alphaData] FloatArray (0..1) at
 * [width] x [height], the same representation used by selective-edit masks.
 * Equality is id-based so that producing a new mask invalidates compositor caches.
 */
class LayerMask(
    val alphaData: FloatArray,
    val width: Int,
    val height: Int,
    val id: String = UUID.randomUUID().toString(),
) {
    fun invert(): LayerMask =
        LayerMask(FloatArray(alphaData.size) { 1f - alphaData[it] }, width, height)

    override fun equals(other: Any?): Boolean = other is LayerMask && other.id == id
    override fun hashCode(): Int = id.hashCode()

    companion object {
        fun full(width: Int, height: Int): LayerMask =
            LayerMask(FloatArray(width * height) { 1f }, width, height)
    }
}

/**
 * Non-destructive layer effects (drop shadow, stroke, outer glow) rendered from
 * the layer's own alpha shape by the compositor, beneath the layer's pixels.
 */
data class LayerStyle(
    val dropShadow: Boolean = false,
    val shadowColor: Int = 0xAA000000.toInt(),
    val shadowDx: Float = 0.01f,
    val shadowDy: Float = 0.01f,
    val shadowBlur: Float = 0.01f,
    val stroke: Boolean = false,
    val strokeColor: Int = 0xFFFFFFFF.toInt(),
    val strokeWidth: Float = 0.004f,
    val outerGlow: Boolean = false,
    val glowColor: Int = 0xFFFFF176.toInt(),
    val glowRadius: Float = 0.02f,
) {
    fun isIdentity(): Boolean = !dropShadow && !stroke && !outerGlow
}

/**
 * A layer group (folder). Members are the contiguous run of layers sharing this
 * group's id. The group is composited as a unit, then blended into the stack
 * with [opacity]/[blendMode]/[visible].
 */
data class GroupInfo(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Group",
    val opacity: Float = 1f,
    val blendMode: LayerBlendMode = LayerBlendMode.Normal,
    val visible: Boolean = true,
    val collapsed: Boolean = false,
)

/**
 * Non-destructive adjustment that can back an [AdjustmentLayer] or be applied
 * directly. Each wrapper delegates to an existing `applyXToBitmap` helper, reusing
 * the established `data class + isIdentity() + applyToBitmap()` pattern.
 */
sealed interface LayerAdjustment {
    fun isIdentity(): Boolean
    fun applyToBitmap(bitmap: Bitmap): Bitmap
    val label: String
}

data class BasicAdjustment(val adjustments: ImageAdjustments = ImageAdjustments()) : LayerAdjustment {
    override fun isIdentity(): Boolean = adjustments == ImageAdjustments()
    override fun applyToBitmap(bitmap: Bitmap): Bitmap = adjustments.applyToBitmap(bitmap)
    override val label: String get() = "Adjustments"
}

data class CurvesAdj(val curves: CurvesAdjustment = CurvesAdjustment()) : LayerAdjustment {
    override fun isIdentity(): Boolean = curves.isIdentity()
    override fun applyToBitmap(bitmap: Bitmap): Bitmap = curves.applyLutToBitmap(bitmap)
    override val label: String get() = "Curves"
}

data class HslAdj(val hsl: HslAdjustments = HslAdjustments()) : LayerAdjustment {
    override fun isIdentity(): Boolean = hsl.isIdentity()
    override fun applyToBitmap(bitmap: Bitmap): Bitmap = hsl.applyHslToBitmap(bitmap)
    override val label: String get() = "HSL"
}

data class BlurAdj(val blur: BlurParams = BlurParams()) : LayerAdjustment {
    override fun isIdentity(): Boolean = blur.isIdentity()
    override fun applyToBitmap(bitmap: Bitmap): Bitmap = blur.applyBlurToBitmap(bitmap)
    override val label: String get() = "Blur"
}

data class SelectiveAdj(val selective: SelectiveEdits = SelectiveEdits()) : LayerAdjustment {
    override fun isIdentity(): Boolean = selective.isIdentity()
    override fun applyToBitmap(bitmap: Bitmap): Bitmap = selective.applySelectiveEdits(bitmap)
    override val label: String get() = "Selective"
}

data class LevelsAdj(val levels: LevelsAdjustment = LevelsAdjustment()) : LayerAdjustment {
    override fun isIdentity(): Boolean = levels.isIdentity()
    override fun applyToBitmap(bitmap: Bitmap): Bitmap = levels.applyToBitmap(bitmap)
    override val label: String get() = "Levels"
}

data class ColorBalanceAdj(val balance: ColorBalanceAdjustment = ColorBalanceAdjustment()) : LayerAdjustment {
    override fun isIdentity(): Boolean = balance.isIdentity()
    override fun applyToBitmap(bitmap: Bitmap): Bitmap = balance.applyToBitmap(bitmap)
    override val label: String get() = "Color Balance"
}

data class ChannelMixerAdj(val mixer: ChannelMixerAdjustment = ChannelMixerAdjustment()) : LayerAdjustment {
    override fun isIdentity(): Boolean = mixer.isIdentity()
    override fun applyToBitmap(bitmap: Bitmap): Bitmap = mixer.applyToBitmap(bitmap)
    override val label: String get() = "Channel Mixer"
}

data class GradientMapAdj(val gradient: GradientMapAdjustment = GradientMapAdjustment()) : LayerAdjustment {
    override fun isIdentity(): Boolean = gradient.isIdentity()
    override fun applyToBitmap(bitmap: Bitmap): Bitmap = gradient.applyToBitmap(bitmap)
    override val label: String get() = "Gradient Map"
}

data class BlackAndWhiteAdj(val bw: BlackAndWhiteAdjustment = BlackAndWhiteAdjustment(enabled = true)) : LayerAdjustment {
    override fun isIdentity(): Boolean = bw.isIdentity()
    override fun applyToBitmap(bitmap: Bitmap): Bitmap = bw.applyToBitmap(bitmap)
    override val label: String get() = "Black & White"
}

/**
 * A single entry in the document's layer stack. Common presentation properties are
 * declared here; concrete subtypes carry their own content. Use [copyBase] to update
 * the shared properties without caring about the concrete type.
 */
sealed class Layer {
    abstract val id: String
    abstract val name: String
    abstract val visible: Boolean
    abstract val opacity: Float
    abstract val blendMode: LayerBlendMode
    abstract val mask: LayerMask?
    abstract val locked: Boolean
    abstract val clipped: Boolean
    abstract val style: LayerStyle
    abstract val groupId: String?

    abstract fun copyBase(
        name: String = this.name,
        visible: Boolean = this.visible,
        opacity: Float = this.opacity,
        blendMode: LayerBlendMode = this.blendMode,
        mask: LayerMask? = this.mask,
        locked: Boolean = this.locked,
        clipped: Boolean = this.clipped,
        style: LayerStyle = this.style,
        groupId: String? = this.groupId,
    ): Layer
}

data class PixelLayer(
    val bitmapRef: BitmapReference,
    override val id: String = UUID.randomUUID().toString(),
    override val name: String = "Layer",
    override val visible: Boolean = true,
    override val opacity: Float = 1f,
    override val blendMode: LayerBlendMode = LayerBlendMode.Normal,
    override val mask: LayerMask? = null,
    override val locked: Boolean = false,
    override val clipped: Boolean = false,
    override val style: LayerStyle = LayerStyle(),
    override val groupId: String? = null,
) : Layer() {
    override fun copyBase(
        name: String, visible: Boolean, opacity: Float,
        blendMode: LayerBlendMode, mask: LayerMask?, locked: Boolean, clipped: Boolean, style: LayerStyle, groupId: String?,
    ): Layer = copy(
        name = name, visible = visible, opacity = opacity,
        blendMode = blendMode, mask = mask, locked = locked, clipped = clipped, style = style, groupId = groupId,
    )
}

data class AdjustmentLayer(
    val adjustment: LayerAdjustment,
    override val id: String = UUID.randomUUID().toString(),
    override val name: String = adjustment.label,
    override val visible: Boolean = true,
    override val opacity: Float = 1f,
    override val blendMode: LayerBlendMode = LayerBlendMode.Normal,
    override val mask: LayerMask? = null,
    override val locked: Boolean = false,
    override val clipped: Boolean = false,
    override val style: LayerStyle = LayerStyle(),
    override val groupId: String? = null,
) : Layer() {
    override fun copyBase(
        name: String, visible: Boolean, opacity: Float,
        blendMode: LayerBlendMode, mask: LayerMask?, locked: Boolean, clipped: Boolean, style: LayerStyle, groupId: String?,
    ): Layer = copy(
        name = name, visible = visible, opacity = opacity,
        blendMode = blendMode, mask = mask, locked = locked, clipped = clipped, style = style, groupId = groupId,
    )
}

data class TextLayer(
    val textElement: TextElement,
    override val id: String = UUID.randomUUID().toString(),
    override val name: String = "Text",
    override val visible: Boolean = true,
    override val opacity: Float = 1f,
    override val blendMode: LayerBlendMode = LayerBlendMode.Normal,
    override val mask: LayerMask? = null,
    override val locked: Boolean = false,
    override val clipped: Boolean = false,
    override val style: LayerStyle = LayerStyle(),
    override val groupId: String? = null,
) : Layer() {
    override fun copyBase(
        name: String, visible: Boolean, opacity: Float,
        blendMode: LayerBlendMode, mask: LayerMask?, locked: Boolean, clipped: Boolean, style: LayerStyle, groupId: String?,
    ): Layer = copy(
        name = name, visible = visible, opacity = opacity,
        blendMode = blendMode, mask = mask, locked = locked, clipped = clipped, style = style, groupId = groupId,
    )
}

data class DrawingLayer(
    val strokes: List<SerializedStroke>,
    val sourceWidth: Float,
    val sourceHeight: Float,
    override val id: String = UUID.randomUUID().toString(),
    override val name: String = "Drawing",
    override val visible: Boolean = true,
    override val opacity: Float = 1f,
    override val blendMode: LayerBlendMode = LayerBlendMode.Normal,
    override val mask: LayerMask? = null,
    override val locked: Boolean = false,
    override val clipped: Boolean = false,
    override val style: LayerStyle = LayerStyle(),
    override val groupId: String? = null,
) : Layer() {
    override fun copyBase(
        name: String, visible: Boolean, opacity: Float,
        blendMode: LayerBlendMode, mask: LayerMask?, locked: Boolean, clipped: Boolean, style: LayerStyle, groupId: String?,
    ): Layer = copy(
        name = name, visible = visible, opacity = opacity,
        blendMode = blendMode, mask = mask, locked = locked, clipped = clipped, style = style, groupId = groupId,
    )
}
