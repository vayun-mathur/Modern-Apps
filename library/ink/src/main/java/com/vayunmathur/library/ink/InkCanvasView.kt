package com.vayunmathur.library.ink

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.core.graphics.withTranslation
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
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
    // Lazy: a view showing only text elements (or nothing yet) must not
    // require libink; the renderer is created on the first stroke draw.
    private val renderer by lazy { CanvasStrokeRenderer.create() }
    private val identityMatrix = Matrix()
    private val textPaint = Paint().apply { isAntiAlias = true }
    private val selectionPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = SELECTION_STROKE_WIDTH
    }
    private val selectionFillPaint = Paint().apply {
        style = Paint.Style.FILL
    }

    fun setSelectionColor(color: Int) {
        selectionPaint.color = color
        selectionFillPaint.color = android.graphics.Color.argb(
            SELECTION_FILL_ALPHA,
            android.graphics.Color.red(color),
            android.graphics.Color.green(color),
            android.graphics.Color.blue(color),
        )
    }
    private val scratchRect = RectF()

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        strokes.forEachIndexed { index, stroke ->
            renderer.draw(canvas, stroke, identityMatrix)
            if (selectedStrokeIndex == index) {
                stroke.shape.computeBoundingBox()?.let { box ->
                    scratchRect.set(
                        box.xMin - SELECTION_PADDING,
                        box.yMin - SELECTION_PADDING,
                        box.xMax + SELECTION_PADDING,
                        box.yMax + SELECTION_PADDING
                    )
                    canvas.drawRect(scratchRect, selectionFillPaint)
                    canvas.drawRect(scratchRect, selectionPaint)
                }
            }
        }

        textElements.forEachIndexed { index, elem ->
            canvas.withTranslation(elem.x, elem.y) {
                rotate(elem.rotation)

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

                drawText(elem.text, 0f, textPaint.textSize, textPaint)

                if (selectedTextIndex == index) {
                    val textWidth = textPaint.measureText(elem.text)
                    val textHeight = textPaint.textSize
                    scratchRect.set(
                        -SELECTION_PADDING,
                        -SELECTION_PADDING,
                        textWidth + SELECTION_PADDING,
                        textHeight + SELECTION_TEXT_EXTRA
                    )
                    drawRect(scratchRect, selectionFillPaint)
                    drawRect(scratchRect, selectionPaint)
                }
            }
        }
    }
}

private const val SELECTION_STROKE_WIDTH = 3f
private const val SELECTION_FILL_ALPHA = 40
private const val SELECTION_PADDING = 4f
private const val SELECTION_TEXT_EXTRA = 8f
private const val ERASER_HIT_RADIUS = 20f

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
    modifier: Modifier = Modifier,
    onStrokeErased: ((Stroke) -> Unit)? = null,
    eraserMode: Boolean = false,
    enabled: Boolean = true,
    textElements: List<CanvasTextElement> = emptyList(),
    selectedStrokeIndex: Int? = null,
    selectedTextIndex: Int? = null,
    selectionColor: Color = MaterialTheme.colorScheme.primary,
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
            finishedStrokesView.setSelectionColor(selectionColor.toArgb())
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
        if (state.eraserMode) return handleEraser(event)
        return handleStroke(v, event)
    }

    private fun handleEraser(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN ||
            event.actionMasked == MotionEvent.ACTION_MOVE
        ) {
            eraseAt(event.x, event.y)
        }
        return true
    }

    private fun eraseAt(touchX: Float, touchY: Float) {
        finishedStrokesView.strokes.lastOrNull { stroke ->
            stroke.shape.computeBoundingBox()?.let { box ->
                box.xMin <= touchX + ERASER_HIT_RADIUS &&
                    box.xMax >= touchX - ERASER_HIT_RADIUS &&
                    box.yMin <= touchY + ERASER_HIT_RADIUS &&
                    box.yMax >= touchY - ERASER_HIT_RADIUS
            } ?: false
        }?.let { hitStroke ->
            state.onStrokeErased?.invoke(hitStroke)
        }
    }

    private fun handleStroke(v: View, event: MotionEvent): Boolean {
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
