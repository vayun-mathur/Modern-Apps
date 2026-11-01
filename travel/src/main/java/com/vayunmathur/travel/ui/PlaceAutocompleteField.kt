package com.vayunmathur.travel.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.ExposedDropdownMenu
import com.vayunmathur.library.ui.ExposedDropdownMenuAnchorType
import com.vayunmathur.library.ui.ExposedDropdownMenuBox
import com.vayunmathur.library.ui.IconFlight
import com.vayunmathur.library.ui.IconApartment
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.travel.network.PlaceDto
import com.vayunmathur.travel.util.TravelViewModel
import com.vayunmathur.travel.util.autocomplete

private const val MAX_SUGGESTIONS = 6

/**
 * Airport/city autocomplete field using the shared exposed dropdown menu.
 * Owns its editing text (seeded from [initialText]). [onChange] reports the
 * code to search — the IATA / city code when a suggestion is picked, or the
 * raw typed text as a fallback so power users can type "LON" — together with
 * the visible text, so a hoisted form can restore the field as it was.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaceAutocompleteField(
    label: String,
    viewModel: TravelViewModel,
    onChange: (code: String, text: String) -> Unit,
    modifier: Modifier = Modifier,
    initialText: String = "",
) {
    var text by remember { mutableStateOf(initialText) }
    var suggestions by remember { mutableStateOf<List<PlaceDto>>(emptyList()) }
    var expanded by remember { mutableStateOf(false) }
    // Restoring the saved text must not pop the suggestion menu open.
    var justSelected by remember { mutableStateOf(initialText.isNotBlank()) }

    LaunchedEffect(text) {
        if (justSelected) {
            justSelected = false
            return@LaunchedEffect
        }
        if (text.length < 2) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(100)
        suggestions = viewModel.autocomplete(text).take(MAX_SUGGESTIONS)
        expanded = suggestions.isNotEmpty()
    }

    ExposedDropdownMenuBox(
        expanded = expanded && suggestions.isNotEmpty(),
        onExpandedChange = { expanded = it && suggestions.isNotEmpty() },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                onChange(it.trim(), it)
            },
            label = { Text(label) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Ascii,
                capitalization = KeyboardCapitalization.Characters,
            ),
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded && suggestions.isNotEmpty(),
            onDismissRequest = { expanded = false },
        ) {
            suggestions.forEach { place ->
                DropdownMenuItem(
                    text = { PlaceSuggestion(place) },
                    leadingIcon = { if (place.type == "city") IconApartment() else IconFlight() },
                    onClick = {
                        text = "${place.code} · ${place.city.ifBlank { place.name }}"
                        onChange(place.code, text)
                        justSelected = true
                        suggestions = emptyList()
                        expanded = false
                    },
                )
            }
        }
    }
}

/** Two-line row: code + city on top, airport name / country underneath. */
@Composable
private fun PlaceSuggestion(place: PlaceDto) {
    Column {
        Text(
            listOf(place.code, place.city.ifBlank { place.name }).filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val detail = listOf(place.name.takeIf { it != place.city && it != place.code }, place.country)
            .filterNot { it.isNullOrBlank() }
            .joinToString(", ")
        if (detail.isNotBlank()) {
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
