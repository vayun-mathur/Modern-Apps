package com.vayunmathur.games.logicgate.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import com.vayunmathur.games.logicgate.domain.model.Chips

/** The classic schematic outline a chip is drawn with; everything else is a labelled box. */
enum class GateShape { AND, OR, XOR, NOT, BOX }

/** Outline plus whether it carries an inversion bubble. */
data class GateLook(val shape: GateShape, val inverted: Boolean)

/** Builds gate outlines inside a node's rect, leaving room for pin stubs and the bubble. */
object GateShapes {
    const val STUB = 10f
    const val BUBBLE_R = 4f
    private const val INSET_Y = 6f

    fun look(chipId: String): GateLook = when (chipId) {
        Chips.AND -> GateLook(GateShape.AND, false)
        Chips.NAND -> GateLook(GateShape.AND, true)
        Chips.OR -> GateLook(GateShape.OR, false)
        Chips.NOR -> GateLook(GateShape.OR, true)
        Chips.XOR -> GateLook(GateShape.XOR, false)
        Chips.XNOR -> GateLook(GateShape.XOR, true)
        Chips.NOT -> GateLook(GateShape.NOT, true)
        else -> GateLook(GateShape.BOX, false)
    }

    /** Right edge of the outline, before the bubble. */
    fun bodyRight(rect: Rect, look: GateLook) = rect.right - STUB - if (look.inverted) BUBBLE_R * 2 else 0f

    fun bubbleCenter(rect: Rect, look: GateLook) = Offset(bodyRight(rect, look) + BUBBLE_R, rect.center.y)

    fun outline(rect: Rect, look: GateLook): Path {
        val x0 = rect.left + STUB
        val x1 = bodyRight(rect, look)
        val top = rect.top + INSET_Y
        val bot = rect.bottom - INSET_Y
        val cy = rect.center.y
        val w = x1 - x0
        return Path().apply {
            when (look.shape) {
                GateShape.AND -> {
                    val r = minOf((bot - top) / 2, w)
                    val flat = x1 - r
                    moveTo(x0, top)
                    lineTo(flat, top)
                    arcTo(Rect(flat - r, top, flat + r, bot), -90f, 180f, false)
                    lineTo(x0, bot)
                    close()
                }
                GateShape.OR, GateShape.XOR -> {
                    val s = if (look.shape == GateShape.XOR) XOR_GAP else 0f
                    moveTo(x0 + s, top)
                    quadraticTo(x0 + s + (w - s) * 0.6f, top, x1, cy)
                    quadraticTo(x0 + s + (w - s) * 0.6f, bot, x0 + s, bot)
                    quadraticTo(x0 + s + w * 0.25f, cy, x0 + s, top)
                    close()
                }
                GateShape.NOT -> {
                    moveTo(x0, top)
                    lineTo(x1, cy)
                    lineTo(x0, bot)
                    close()
                }
                GateShape.BOX -> addRoundRect(androidx.compose.ui.geometry.RoundRect(rect, CORNER, CORNER))
            }
        }
    }

    /** The extra back curve of an XOR, drawn as a stroke. */
    fun xorBack(rect: Rect): Path {
        val x0 = rect.left + STUB
        val top = rect.top + INSET_Y
        val bot = rect.bottom - INSET_Y
        return Path().apply {
            moveTo(x0, top)
            quadraticTo(x0 + (rect.width - 2 * STUB) * 0.25f, rect.center.y, x0, bot)
        }
    }

    const val CORNER = 8f
    private const val XOR_GAP = 6f
}
