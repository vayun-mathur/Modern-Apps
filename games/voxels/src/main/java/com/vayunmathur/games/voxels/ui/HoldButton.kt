package com.vayunmathur.games.voxels.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
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
            .width(96.dp)
            .height(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = if (dimmed) 0.12f else 0.24f))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = { press(); tryAwaitRelease(); release() },
                    onDoubleTap = { dtap?.invoke() }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.White.copy(alpha = if (dimmed) 0.5f else 0.95f), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}
