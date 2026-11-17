package com.vayunmathur.maps.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.toColorInt
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text

/**
 * The coloured transit line chip, shared by the departure board
 * ([DeparturesSheet]) and the transit directions list (`RouteSheet`) so both
 * render a line the same way.
 */
@Composable
fun LineBadge(line: String, routeColor: String?, modifier: Modifier = Modifier) {
    val bg = parseHexColor(routeColor) ?: MaterialTheme.colorScheme.primary
    Text(
        text = line.ifBlank { "—" },
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = onColorFor(bg),
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/**
 * Parse a 6-digit hex colour → [Color], or null when absent/malformed. Accepts
 * it with or without a leading `#`: `Departure.routeColor` has none while
 * `TransitLine.color` always does.
 */
fun parseHexColor(hex: String?): Color? {
    if (hex.isNullOrBlank()) return null
    return runCatching {
        Color(("#" + hex.removePrefix("#")).toColorInt())
    }.getOrNull()
}

/** Readable text colour (black/white) for a coloured badge background. */
fun onColorFor(bg: Color): Color {
    val luminance = 0.299 * bg.red + 0.587 * bg.green + 0.114 * bg.blue
    return if (luminance > 0.6) Color.Black else Color.White
}
