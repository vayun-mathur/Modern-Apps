package com.vayunmathur.travel.ui

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import com.vayunmathur.library.ui.DateString
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text

// --- Formatting helpers ------------------------------------------------------

/**
 * An airline logo loaded from an SVG URL via Coil, falling back to a round
 * badge with the [iata] code while loading or on error / when no URL is set.
 */
@Composable
fun AirlineLogo(
    logoUrl: String,
    iata: String,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 32.dp,
) {
    if (logoUrl.isBlank()) {
        IataBadge(iata, size, modifier)
        return
    }
    // library:image includes SVG decoding (internal Android stdlib) built-in.
    com.vayunmathur.library.image.compose.AsyncImage(
        model = logoUrl,
        contentDescription = iata,
        modifier = modifier.size(size),
    )
}

@Composable
private fun IataBadge(
    iata: String,
    size: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(size)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            iata.take(2),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}
