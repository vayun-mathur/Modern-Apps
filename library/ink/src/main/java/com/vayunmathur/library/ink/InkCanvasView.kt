package com.vayunmathur.library.ink

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.brush.Brush
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke

data class CanvasTextElement(
    val text: String,
    val x: Float,
    val y: Float,
    val rotation: Float,
    val color: Int,
    val fontSize: Float,
    val fontFamily: String = "sans-serif",
    val bold: Boolean = false,
    val italic: Boolean = false,
    val align: Int = 0,
)

private class FinishedStrokesView(context: Context) : View(context) {
    var strokes: List<Stroke> = emptyList()
    var textElements: List<CanvasTextElement> = emptyList()
    var selectedStrokeIndex: Int? = null
    var selectedTextIndex: Int? = null
    private val renderer = CanvasStrokeRenderer.create()
    private val identityMatrix = Matrix()
    private val textPaint = Paint().apply { isAntiAlias = true }
    private val selectionPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = android.graphics.Color.WHITE
    }
    private val selectionFillPaint = Paint().apply {
        style = Paint.Style.FILL
        color = android.graphics.Color.argb(40, 255, 255, 255)
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        strokes.forEachIndexed { index, stroke ->
            renderer.draw(canvas, stroke, identityMatrix)
            if (selectedStrokeIndex == index) {
                stroke.shape.computeBoundingBox()?.let { box ->
                    val rect = RectF(box.xMin - 4f, box.yMin - 4f, box.xMax + 4f, box.yMax + 4f)
                    canvas.drawRect(rect, selectionFillPaint)
                    canvas.drawRect(rect, selectionPaint)
                }
            }
        }

        textElements.forEachIndexed { index, elem ->
            canvas.save()
            canvas.translate(elem.x, elem.y)
            canvas.rotate(elem.rotation)

            textPaint.color = elem.color
            textPaint.textSize = elem.fontSize * resources.displayMetrics.density
            val style = when {
                elem.bold && elem.italic -> android.graphics.Typeface.BOLD_ITALIC
                elem.bold -> android.graphics.Typeface.BOLD
                elem.italic -> android.graphics.Typeface.ITALIC
                else -> android.graphics.Typeface.NORMAL
            }
            textPaint.typeface = android.graphics.Typeface.create(elem.fontFamily, style)
            textPaint.textAlign = when (elem.align) {
                1 -> Paint.Align.CENTER
                2 -> Paint.Align.RIGHT
                else -> Paint.Align.LEFT
            }

            canvas.drawText(elem.text, 0f, textPaint.textSize, textPaint)

            if (selectedTextIndex == index) {
                val textWidth = textPaint.measureText(elem.text)
                val textHeight = textPaint.textSize
                val rect = RectF(-4f, -4f, textWidth + 4f, textHeight + 8f)
                canvas.drawRect(rect, selectionFillPaint)
                canvas.drawRect(rect, selectionPaint)
            }

            canvas.restore()
        }
    }
}

private class InkState {
    var currentBrush: Brush? = null
    var enabled: Boolean = true
    var eraserMode: Boolean = false
    var onStrokeErased: ((Stroke) -> Unit)? = null
    var onStrokeFinished: ((Stroke) -> Unit)? = null
}

@Composable
fun InkCanvasView(
    currentBrush: Brush,
    finishedStrokes: List<Stroke>,
    onStrokeFinished: (Stroke) -> Unit,
    onStrokeErased: ((Stroke) -> Unit)? = null,
    eraserMode: Boolean = false,
    enabled: Boolean = true,
    textElements: List<CanvasTextElement> = emptyList(),
    selectedStrokeIndex: Int? = null,
    selectedTextIndex: Int? = null,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { context ->
            val frameLayout = FrameLayout(context)
            val state = InkState()

            val finishedStrokesView = FinishedStrokesView(context)
            frameLayout.addView(finishedStrokesView)

            val inProgressStrokesView = InProgressStrokesView(context)
            @SuppressLint("ClickableViewAccessibility")
            val unused = inProgressStrokesView.setOnTouchListener(StrokeTouchListener(
                inProgressStrokesView = inProgressStrokesView,
                finishedStrokesView = finishedStrokesView,
                state = state,
            ))
            frameLayout.addView(inProgressStrokesView)

            inProgressStrokesView.addFinishedStrokesListener(object : InProgressStrokesFinishedListener {
                override fun onStrokesFinished(strokes: Map<androidx.ink.authoring.InProgressStrokeId, Stroke>) {
                    strokes.values.forEach { stroke ->
                        state.onStrokeFinished?.invoke(stroke)
                    }
                    inProgressStrokesView.removeFinishedStrokes(strokes.keys)
                }
            })

            frameLayout.tag = arrayOf(finishedStrokesView, state)
            frameLayout
        },
        update = { frameLayout ->
            val tags = frameLayout.tag as Array<*>
            val finishedStrokesView = tags[0] as FinishedStrokesView
            val state = tags[1] as InkState

            state.currentBrush = currentBrush
            state.enabled = enabled
            state.eraserMode = eraserMode
            state.onStrokeErased = onStrokeErased
            state.onStrokeFinished = onStrokeFinished

            finishedStrokesView.strokes = finishedStrokes
            finishedStrokesView.textElements = textElements
            finishedStrokesView.selectedStrokeIndex = selectedStrokeIndex
            finishedStrokesView.selectedTextIndex = selectedTextIndex
            finishedStrokesView.invalidate()
        },
        modifier = modifier.fillMaxSize(),
    )
}

private class StrokeTouchListener(
    private val inProgressStrokesView: InProgressStrokesView,
    private val finishedStrokesView: FinishedStrokesView,
    private val state: InkState,
) : View.OnTouchListener {

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        if (!state.enabled) return false

        if (state.eraserMode) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN ||
                event.actionMasked == MotionEvent.ACTION_MOVE
            ) {
                val touchX = event.x
                val touchY = event.y
                val hitRadius = 20f
                finishedStrokesView.strokes.lastOrNull { stroke ->
                    stroke.shape.computeBoundingBox()?.let { box ->
                        box.xMin <= touchX + hitRadius && box.xMax >= touchX - hitRadius &&
                            box.yMin <= touchY + hitRadius && box.yMax >= touchY - hitRadius
                    } ?: false
                }?.let { hitStroke ->
                    state.onStrokeErased?.invoke(hitStroke)
                }
            }
            return true
        }

        val brush = state.currentBrush ?: return false
        val pointerId = event.getPointerId(event.actionIndex)

        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                v.requestUnbufferedDispatch(event)
                inProgressStrokesView.startStroke(event, pointerId, brush)
                true
            }

            MotionEvent.ACTION_MOVE -> {
                inProgressStrokesView.addToStroke(event, pointerId)
                true
            }

            MotionEvent.ACTION_UP -> {
                inProgressStrokesView.finishStroke(event, pointerId)
                true
            }

            MotionEvent.ACTION_CANCEL -> {
                inProgressStrokesView.cancelStroke(event, pointerId)
                true
            }

            else -> false
        }
    }
}
