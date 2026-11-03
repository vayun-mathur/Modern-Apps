package com.vayunmathur.games.logicgate.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vayunmathur.games.logicgate.R
import com.vayunmathur.games.logicgate.domain.model.Pin
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Text

/** A titled, comma-separated pin list with bus widths, shared by the brief and chip info sheets. */
@Composable
fun PinLine(title: String, pins: List<Pin>) {
    val text = if (pins.isEmpty()) {
        stringResource(R.string.lg_brief_none)
    } else {
        pins.map { if (it.width == 1) it.name else stringResource(R.string.lg_pin_width, it.name, it.width) }.joinToString()
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
