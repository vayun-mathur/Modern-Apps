package com.vayunmathur.travel.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Remove
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.DatePicker
import com.vayunmathur.library.ui.DatePickerDialog
import com.vayunmathur.library.ui.ElevatedCard
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.Icon
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.rememberDatePickerState
import com.vayunmathur.travel.network.PlaceDto
import com.vayunmathur.travel.util.TravelViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

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

/**
 * Airport/city autocomplete field. Owns its own text; reports the chosen code
 * back via [onCodeChange] — either an IATA/city code when a suggestion is
 * tapped, or the raw typed text as a fallback so power users can type "LON".
 */
@Composable
fun PlaceAutocompleteField(
    label: String,
    viewModel: TravelViewModel,
    onCodeChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    initialText: String = "",
) {
    var text by remember { mutableStateOf(initialText) }
    var suggestions by remember { mutableStateOf<List<PlaceDto>>(emptyList()) }
    var justSelected by remember { mutableStateOf(false) }

    LaunchedEffect(text) {
        if (justSelected) {
            justSelected = false
            return@LaunchedEffect
        }
        if (text.length < 2) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(250)
        suggestions = viewModel.autocomplete(text)
    }

    Column(modifier) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                onCodeChange(it.trim())
            },
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (suggestions.isNotEmpty()) {
            ElevatedCard(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Column {
                    suggestions.take(6).forEach { place ->
                        ListItem(
                            modifier = Modifier.clickable {
                                onCodeChange(place.code)
                                text = "${place.code} · ${place.city.ifBlank { place.name }}"
                                justSelected = true
                                suggestions = emptyList()
                            },
                        ) { Text(place.label) }
                    }
                }
            }
        }
    }
}

/**
 * A read-only field that opens a Material date picker on tap. [value] is an ISO
 * `YYYY-MM-DD` string (empty when unset); [onDate] receives the picked ISO date.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(
    label: String,
    value: String,
    onDate: (String) -> Unit,
    modifier: Modifier = Modifier,
    dateFormat: String = "EEE, MMM d",
) {
    var show by remember { mutableStateOf(false) }
    val display = if (value.isBlank()) "" else runCatching {
        LocalDate.parse(value).format(DateTimeFormatter.ofPattern(dateFormat))
    }.getOrDefault(value)

    Box(modifier) {
        OutlinedTextField(
            value = display,
            onValueChange = {},
            readOnly = true,
            enabled = false,
            label = { Text(label) },
            leadingIcon = { Icon(Icons.Filled.CalendarMonth, contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
        )
        Box(
            Modifier
                .matchParentSize()
                .clickable { show = true }
        )
    }

    if (show) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = runCatching { LocalDate.parse(value) }
                .getOrNull()
                ?.atStartOfDay(ZoneOffset.UTC)
                ?.toInstant()
                ?.toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { show = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms ->
                        val date = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                        onDate(date.toString())
                    }
                    show = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { show = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
}

/** A +/- stepper for a small integer count (passengers). */
@Composable
fun CountStepper(
    label: String,
    count: Int,
    onCount: (Int) -> Unit,
    modifier: Modifier = Modifier,
    min: Int = 1,
    max: Int = 9,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onCount((count - 1).coerceAtLeast(min)) }, enabled = count > min) {
                Icon(Icons.Filled.Remove, contentDescription = "Fewer")
            }
            Text("$count", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 8.dp))
            IconButton(onClick = { onCount((count + 1).coerceAtMost(max)) }, enabled = count < max) {
                Icon(Icons.Filled.Add, contentDescription = "More")
            }
        }
    }
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

/** A money label like "$412" / "€220.50" from a Duffel decimal-string amount. */
fun formatMoney(amount: String, currency: String): String {
    val value = amount.toDoubleOrNull() ?: return "$currency $amount"
    val symbol = when (currency.uppercase()) {
        "USD" -> "$"
        "EUR" -> "€"
        "GBP" -> "£"
        else -> currency.uppercase() + " "
    }
    return symbol + (if (value % 1.0 == 0.0) value.toInt().toString() else "%.2f".format(value))
}

/** "510" minutes -> "8h 30m". */
fun formatDuration(minutes: Long): String {
    if (minutes <= 0) return ""
    val h = minutes / 60
    val m = minutes % 60
    return buildString {
        if (h > 0) append("${h}h")
        if (m > 0) {
            if (h > 0) append(" ")
            append("${m}m")
        }
    }
}

/** "2026-09-01T10:00:00" -> "10:00"; falls back to the raw time part. */
fun formatTime(iso: String): String = runCatching {
    LocalDateTime.parse(iso.take(19)).format(DateTimeFormatter.ofPattern("HH:mm"))
}.getOrDefault(iso.substringAfter('T').take(5))

/** Stops label: "Nonstop" / "1 stop" / "N stops". */
fun stopsLabel(stops: Long): String = when (stops) {
    0L -> "Nonstop"
    1L -> "1 stop"
    else -> "$stops stops"
}
