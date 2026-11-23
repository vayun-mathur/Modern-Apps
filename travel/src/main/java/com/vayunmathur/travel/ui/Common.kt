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
