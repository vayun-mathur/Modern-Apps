package com.vayunmathur.library.ink

import androidx.ink.brush.Brush
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class SerializedStroke(
    val brushFamily: String,
    val brushSize: Float,
    val brushColor: Int,
    val brushEpsilon: Float,
    val points: List<SerializedPoint>,
)

@Serializable
data class SerializedPoint(
    val x: Float,
    val y: Float,
    val timeMillis: Long,
    val pressure: Float = 1f,
    val tiltRadians: Float = 0f,
    val orientationRadians: Float = 0f,
)

private val brushFamilyMap: Map<String, BrushFamily> by lazy {
    mapOf(
        "pressurePen" to StockBrushes.pressurePen(),
        "highlighter" to StockBrushes.highlighter(),
        "marker" to StockBrushes.marker(),
    )
}

private val reverseBrushFamilyMap: Map<BrushFamily, String> by lazy {
    brushFamilyMap.entries.associate { (k, v) -> v to k }
}

fun BrushFamily.toName(): String = reverseBrushFamilyMap[this] ?: "pressurePen"

fun String.toBrushFamily(): BrushFamily = brushFamilyMap[this] ?: StockBrushes.pressurePen()

fun Stroke.serialize(): SerializedStroke {
    val inputs = this.inputs
    val points = (0 until inputs.size).map { i ->
        val si = inputs[i]
        SerializedPoint(
            x = si.x,
            y = si.y,
            timeMillis = si.elapsedTimeMillis,
            pressure = si.pressure,
            tiltRadians = si.tiltRadians,
            orientationRadians = si.orientationRadians,
        )
    }
    return SerializedStroke(
        brushFamily = brush.family.toName(),
        brushSize = brush.size,
        brushColor = brush.colorIntArgb,
        brushEpsilon = brush.epsilon,
        points = points,
    )
}

fun SerializedStroke.deserialize(): Stroke {
    val family = brushFamily.toBrushFamily()
    val brush = Brush.createWithColorIntArgb(
        family = family,
        colorIntArgb = brushColor,
        size = brushSize,
        epsilon = brushEpsilon,
    )
    val batch = MutableStrokeInputBatch()
    val input = StrokeInput()
    points.forEach { p ->
        input.update(
            x = p.x,
            y = p.y,
            elapsedTimeMillis = p.timeMillis,
            toolType = InputToolType.TOUCH,
            pressure = p.pressure,
            tiltRadians = p.tiltRadians,
            orientationRadians = p.orientationRadians,
        )
        batch.add(input)
    }
    return Stroke(brush, batch)
}

private val json = Json { ignoreUnknownKeys = true }

fun SerializedStroke.toBytes(): ByteArray = json.encodeToString(this).encodeToByteArray()

fun ByteArray.toSerializedStroke(): SerializedStroke =
    json.decodeFromString(this.decodeToString())

fun Stroke.translate(dx: Float, dy: Float): Stroke {
    val inputs = this.inputs
    val batch = MutableStrokeInputBatch()
    val input = StrokeInput()
    for (i in 0 until inputs.size) {
        val si = inputs[i]
        input.update(
            x = si.x + dx,
            y = si.y + dy,
            elapsedTimeMillis = si.elapsedTimeMillis,
            toolType = InputToolType.TOUCH,
            pressure = si.pressure,
            tiltRadians = si.tiltRadians,
            orientationRadians = si.orientationRadians,
        )
        batch.add(input)
    }
    return Stroke(brush, batch)
}
