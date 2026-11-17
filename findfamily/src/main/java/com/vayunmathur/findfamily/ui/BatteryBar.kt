package com.vayunmathur.findfamily.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.findfamily.R
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text

@Composable
fun BatteryBar(percent: Float, width: Dp = 24.dp, height: Dp = 12.dp) {
    val color = when {
        percent > 50 -> MaterialTheme.colorScheme.primary
        percent > 20 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(width, height).border(1.5.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(3.dp))) {
            Box(Modifier.fillMaxWidthFraction(percent).height(height).background(color, RoundedCornerShape(3.dp)))
        }
        Text(stringResource(R.string.battery_percentage, percent.toInt()), fontSize = 11.sp)
    }
}
