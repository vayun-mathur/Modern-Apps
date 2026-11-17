package com.vayunmathur.office.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.office.data.odf.MathNode
import com.vayunmathur.office.data.odf.OdfMath

@Composable
fun MathView(mathml: String) {
    val node = remember(mathml) { OdfMath.parse(mathml) }
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        if (node == null) {
            Text(mathml.take(200), style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic)
        } else {
            MathNodeView(node, 20f, MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun MathNodeView(node: MathNode, sizeSp: Float, color: Color) {
    when (node) {
        is MathNode.Row -> Row(verticalAlignment = Alignment.CenterVertically) {
            for (child in node.children) MathNodeView(child, sizeSp, color)
        }
        is MathNode.Token -> Text(
            node.text,
            fontSize = sizeSp.sp,
            color = color,
            fontStyle = if (!node.isOperator && node.text.length == 1 && node.text[0].isLetter()) FontStyle.Italic else FontStyle.Normal,
            modifier = Modifier.padding(horizontal = if (node.isOperator) 3.dp else 0.5.dp)
        )
        is MathNode.Frac -> Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 3.dp).width(IntrinsicSize.Max)) {
            MathNodeView(node.numerator, sizeSp * 0.95f, color)
            HorizontalDivider(color = color, thickness = 1.5.dp, modifier = Modifier.fillMaxWidth())
            MathNodeView(node.denominator, sizeSp * 0.95f, color)
        }
        is MathNode.Sup -> Row(verticalAlignment = Alignment.Top) {
            MathNodeView(node.base, sizeSp, color)
            MathNodeView(node.exponent, sizeSp * 0.7f, color)
        }
        is MathNode.Sub -> Row(verticalAlignment = Alignment.Bottom) {
            MathNodeView(node.base, sizeSp, color)
            MathNodeView(node.subscript, sizeSp * 0.7f, color)
        }
        is MathNode.SubSup -> Row(verticalAlignment = Alignment.CenterVertically) {
            MathNodeView(node.base, sizeSp, color)
            Column {
                MathNodeView(node.superscript, sizeSp * 0.7f, color)
                MathNodeView(node.subscript, sizeSp * 0.7f, color)
            }
        }
        is MathNode.Sqrt -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text("\u221A", fontSize = sizeSp.sp, color = color)
            Column { HorizontalDivider(
                color = color,
                thickness = 1.dp); Box(Modifier.padding(top = 1.dp)) { MathNodeView(node.radicand, sizeSp, color) } }
        }
        is MathNode.Root -> Row(verticalAlignment = Alignment.CenterVertically) {
            MathNodeView(node.index, sizeSp * 0.6f, color)
            Text("\u221A", fontSize = sizeSp.sp, color = color)
            Column { HorizontalDivider(
                color = color,
                thickness = 1.dp); Box(Modifier.padding(top = 1.dp)) { MathNodeView(node.radicand, sizeSp, color) } }
        }
        is MathNode.Fenced -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(node.open, fontSize = sizeSp.sp, color = color)
            MathNodeView(node.body, sizeSp, color)
            Text(node.close, fontSize = sizeSp.sp, color = color)
        }
        is MathNode.Under -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            MathNodeView(node.base, sizeSp, color)
            MathNodeView(node.under, sizeSp * 0.7f, color)
        }
        is MathNode.Over -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            MathNodeView(node.over, sizeSp * 0.7f, color)
            MathNodeView(node.base, sizeSp, color)
        }
        is MathNode.UnderOver -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            MathNodeView(node.over, sizeSp * 0.7f, color)
            MathNodeView(node.base, sizeSp, color)
            MathNodeView(node.under, sizeSp * 0.7f, color)
        }
        is MathNode.Table -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            for (r in node.rows) MathNodeView(r, sizeSp, color)
        }
        is MathNode.TableRow -> Row(verticalAlignment = Alignment.CenterVertically) {
            for (cell in node.cells) Box(Modifier.padding(horizontal = 4.dp)) { MathNodeView(cell, sizeSp, color) }
        }
        is MathNode.Multiscripts -> Row(verticalAlignment = Alignment.CenterVertically) {
            if (node.preSub != null || node.preSup != null) Column {
                node.preSup?.let { MathNodeView(it, sizeSp * 0.7f, color) }
                node.preSub?.let { MathNodeView(it, sizeSp * 0.7f, color) }
            }
            MathNodeView(node.base, sizeSp, color)
            if (node.postSub != null || node.postSup != null) Column {
                node.postSup?.let { MathNodeView(it, sizeSp * 0.7f, color) }
                node.postSub?.let { MathNodeView(it, sizeSp * 0.7f, color) }
            }
        }
    }
}
