package com.vayunmathur.madeveloper.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.IconCheckCircle
import com.vayunmathur.library.ui.IconInfo
import com.vayunmathur.library.ui.IconSchedule
import com.vayunmathur.library.ui.IconWarning
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.madeveloper.domain.CiStatus

/** Small pill showing a CI/install status in its semantic container colour. */
@Composable
fun StatusChip(label: String, status: CiStatus, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val container = when (status) {
        CiStatus.Passed -> colors.tertiaryContainer
        CiStatus.Failed -> colors.errorContainer
        CiStatus.Running -> colors.secondaryContainer
        CiStatus.Unknown -> colors.surfaceContainerHigh
    }
    val content = when (status) {
        CiStatus.Passed -> colors.onTertiaryContainer
        CiStatus.Failed -> colors.onErrorContainer
        CiStatus.Running -> colors.onSecondaryContainer
        CiStatus.Unknown -> colors.onSurfaceVariant
    }
    Surface(modifier = modifier, shape = MaterialTheme.shapes.small, color = container, contentColor = content) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val iconModifier = Modifier.size(14.dp)
            when (status) {
                CiStatus.Passed -> IconCheckCircle(iconModifier, tint = content)
                CiStatus.Failed -> IconWarning(iconModifier, tint = content)
                CiStatus.Running -> IconSchedule(iconModifier, tint = content)
                CiStatus.Unknown -> IconInfo(iconModifier, tint = content)
            }
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = content,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}
