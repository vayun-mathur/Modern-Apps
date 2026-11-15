package com.vayunmathur.photos.data

import androidx.compose.ui.geometry.Offset
import androidx.ink.brush.Brush
import androidx.ink.brush.StockBrushes
import kotlinx.serialization.Serializable

/** Kinds of drawing tool. */
@Serializable
enum class DrawingTool { Pointer, Pen, Highlighter, Eraser, Text }

fun DrawingTool.toBrush(color: Int, size: Float): Brush? = when (this) {
    DrawingTool.Pen -> Brush.createWithColorIntArgb(
        family = StockBrushes.pressurePen(),
        colorIntArgb = color,
        size = size,
        epsilon = 0.1f,
    )
    DrawingTool.Highlighter -> Brush.createWithColorIntArgb(
        family = StockBrushes.highlighter(),
        colorIntArgb = color,
        size = size,
        epsilon = 0.1f,
    )
    else -> null
}

/** Immutable value describing text element. */
@Serializable
data class TextElement(
    val id: String,
    val text: String,
    val x: Float,
    val y: Float,
    val rotation: Float,
    val color: Int,
    val fontSize: Float,
    val fontFamily: String = "sans-serif",
    val bold: Boolean = false,
    val italic: Boolean = false,
    /** 0 = left, 1 = center, 2 = right. */
    val align: Int = 0,
)

/** Font families offered by the text tool. */
val textFontFamilies: List<String> = listOf(
    "sans-serif", "serif", "monospace", "sans-serif-condensed", "sans-serif-light", "cursive",
)

/** Immutable value describing serializable offset; carries x, y. */
@Serializable
data class SerializableOffset(val x: Float, val y: Float)

fun Offset.toSerializable() = SerializableOffset(x, y)
