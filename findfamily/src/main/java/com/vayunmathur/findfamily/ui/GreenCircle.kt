package com.vayunmathur.findfamily.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.invisibleClickable

@Composable
fun GreenCircle(size: Dp, char: Char? = null, grayscale: Boolean = false, onClick: () -> Unit = {}
) {
    Box(Modifier.clip(CircleShape).size(size).border(2.dp, MaterialTheme.colorScheme.outline, CircleShape).background(if(grayscale) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer).invisibleClickable(onClick)) {
        char?.let {
            Text(char.toString(), Modifier.align(Alignment.Center), color = if(grayscale) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}
