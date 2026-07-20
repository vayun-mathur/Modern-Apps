package com.vayunmathur.travel.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Hotel
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.Card
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

/** A row of up to [max] stars, [stars] of them filled (hotel rating). */
@Composable
fun StarRow(stars: Int, max: Int = 5, modifier: Modifier = Modifier) {
    if (stars <= 0) return
    val filled = MaterialTheme.colorScheme.tertiary
    Row(modifier) {
        repeat(stars.coerceIn(0, max)) {
            Icon(Icons.Filled.Star, contentDescription = null, tint = filled, modifier = Modifier.size(16.dp))
        }
    }
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
) {
    var show by remember { mutableStateOf(false) }
    val display = if (value.isBlank()) "" else runCatching {
        LocalDate.parse(value).format(DateTimeFormatter.ofPattern("EEE, MMM d"))
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

/** A +/- stepper for a small integer count (passengers, rooms). */
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

/** A price like "$412" / "€220"; "See prices" when [price] is 0 (unknown). */
fun formatPrice(price: Double, currency: String): String {
    if (price <= 0.0) return "See prices"
    val symbol = when (currency.lowercase()) {
        "usd" -> "$"
        "eur" -> "€"
        "gbp" -> "£"
        else -> currency.uppercase() + " "
    }
    return symbol + (if (price % 1.0 == 0.0) price.toInt().toString() else "%.2f".format(price))
}

/**
 * A reusable result card: title + subtitle on the left, a price and a "Book"
 * button on the right, and a favorite toggle.
 */
@Composable
fun ResultCard(
    title: String,
    subtitle: String,
    price: Double,
    currency: String,
    isFavorite: Boolean,
    onFavorite: () -> Unit,
    onBook: () -> Unit,
    modifier: Modifier = Modifier,
    extra: (@Composable () -> Unit)? = null,
) {
    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                extra?.invoke()
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    formatPrice(price, currency),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onFavorite) {
                        if (isFavorite) {
                            Icon(Icons.Filled.Favorite, contentDescription = "Saved", tint = MaterialTheme.colorScheme.tertiary)
                        } else {
                            Icon(Icons.Filled.FavoriteBorder, contentDescription = "Save")
                        }
                    }
                    TextButton(onClick = onBook) { Text("Book") }
                }
            }
        }
    }
}

/** Centered loading/error/empty state used by every results page. */
@Composable
fun StatusBox(
    loading: Boolean,
    error: String?,
    isEmpty: Boolean,
    modifier: Modifier = Modifier,
    emptyMessage: String = "No results found. Try different dates or destinations.",
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

fun verticalIcon(vertical: com.vayunmathur.travel.data.Vertical): ImageVector = when (vertical) {
    com.vayunmathur.travel.data.Vertical.FLIGHTS -> Icons.Filled.Flight
    com.vayunmathur.travel.data.Vertical.HOTELS -> Icons.Filled.Hotel
    com.vayunmathur.travel.data.Vertical.CARS -> Icons.Filled.DirectionsCar
}
