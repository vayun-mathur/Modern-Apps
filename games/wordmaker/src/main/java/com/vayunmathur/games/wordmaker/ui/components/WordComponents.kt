// RAW ANIMATION EXCEPTION: AnimatedLetter carries the in-flight Animatable that WordGameScreen
// drives for a letter travelling from the chooser to its grid cell; the type has to name it.
package com.vayunmathur.games.wordmaker.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

data class AnimatedLetter(
    val char: Char,
    val startOffset: Offset,
    val endOffset: Offset,
    val progress: Animatable<Float, AnimationVector1D>
)

data class WordToAnimate(val word: String, val letterIds: List<Int>)

/**
 * Line height for a letter drawn on a tile, relative to its glyph. The theme's body line height
 * is in sp and grows with the user's font size on its own, which overflows a dp-sized tile at
 * large font scales even when the glyph itself is dp-sized.
 */
internal val TILE_GLYPH_LINE_HEIGHT = 1.2.em


@Composable
fun SurfaceText(modifier: Modifier, surfaceShape: Shape, surfaceColor: Color, text: String, textModifier: Modifier, fontWeight: FontWeight? = null, fontSize: TextUnit = TextUnit.Unspecified, surfaceSize: Dp?, textColor: Color = Color.Unspecified, lineHeight: TextUnit = TextUnit.Unspecified) {
    val modifier2 = if(surfaceSize != null) modifier.size(surfaceSize) else modifier
    Surface(modifier2, surfaceShape, surfaceColor) {
        Box(if(surfaceSize == null) Modifier else Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text, textModifier, color = textColor, fontWeight = fontWeight, fontSize = fontSize, lineHeight = lineHeight)
        }
    }
}
