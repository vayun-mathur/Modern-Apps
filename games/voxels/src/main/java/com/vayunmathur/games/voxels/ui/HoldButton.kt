package com.vayunmathur.games.voxels.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.library.ui.Text

// A control that reports press/release (for hold-to-repeat actions like jump/ascend/descend) and an
// optional double-tap. Styled as a translucent rounded button; `dimmed` shows a disabled look while
// still accepting gestures.
@Composable
fun HoldButton(
    label: String,
    dimmed: Boolean = false,
    onPress: () -> Unit = {},
    onRelease: () -> Unit = {},
    onDoubleTap: (() -> Unit)? = null,
) {
    val press by rememberUpdatedState(onPress)
    val release by rememberUpdatedState(onRelease)
    val dtap by rememberUpdatedState(onDoubleTap)
    Box(
        Modifier
            // Minimum, not fixed, size: at large font scales the label grows the button instead of
            // being clipped (it was a fixed 96 x 52 dp box).
            .widthIn(min = 96.dp)
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (dimmed) HudFillDim else HudFill)
            .border(1.5.dp, HudOutline.copy(alpha = if (dimmed) 0.4f else 0.7f), RoundedCornerShape(12.dp))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = { press(); tryAwaitRelease(); release() },
                    onDoubleTap = { dtap?.invoke() }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
        Text(
            label, color = Color.White.copy(alpha = if (dimmed) 0.6f else 1f), fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
            style = TextStyle(shadow = HudTextShadow), textAlign = TextAlign.Center,
        )
        }
    }
}
