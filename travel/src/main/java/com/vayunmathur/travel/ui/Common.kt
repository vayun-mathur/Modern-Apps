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
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** Centered loading/error/empty state used by results pages. */
@Composable
fun StatusBox(
    loading: Boolean,
    error: String?,
    isEmpty: Boolean,
    modifier: Modifier = Modifier,
    emptyMessage: String = "No flights found. Try different dates or airports.",
) {
    Box(modifier.fillMaxWidth().heightIn(min = 200.dp), contentAlignment = Alignment.Center) {
        when {
            loading -> CircularProgressIndicator()
            error != null -> Text(
                error,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(24.dp),
            )
            isEmpty -> Text(
                emptyMessage,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        }
    }
}

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
// --- Formatting helpers ------------------------------------------------------
// (fare/money/duration/countdown labels now live in TravelFormat.kt)

/** "2026-09-01T10:00:00" -> "10:00" / "10:00 AM"; falls back to the raw time part. */
fun formatTime(iso: String, is24Hour: Boolean): String = runCatching {
    DateString.time(LocalDateTime.parse(iso.take(19)).time, is24Hour)
}.getOrDefault(iso.substringAfter('T').take(5))

/** Whole seconds from now until an ISO-8601 instant; [Long.MAX_VALUE] if unparseable. */
fun secondsUntil(iso: String): Long = runCatching {
    (Instant.parse(iso) - Clock.System.now()).inWholeSeconds
}.getOrDefault(Long.MAX_VALUE)

/**
 * Seconds remaining until [expiresAtIso], ticking once per second **only while
 * the app is in the foreground** (`RESUMED`). Backgrounding pauses the ticker;
 * returning resumes it (and reports 0 immediately if it already lapsed).
 */
@Composable
fun rememberSecondsUntil(expiresAtIso: String): Long {
    val lifecycleOwner = LocalLifecycleOwner.current
    var remaining by remember(expiresAtIso) { mutableStateOf(secondsUntil(expiresAtIso)) }
    LaunchedEffect(expiresAtIso, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                remaining = secondsUntil(expiresAtIso)
                if (remaining <= 0) break
                kotlinx.coroutines.delay(1000)
            }
        }
    }
    return remaining
}
