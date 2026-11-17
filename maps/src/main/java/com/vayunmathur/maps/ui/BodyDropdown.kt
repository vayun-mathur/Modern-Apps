package com.vayunmathur.maps.ui
import com.vayunmathur.maps.data.string

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.map.MapBody
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.FilterChip
import com.vayunmathur.library.ui.FilterChipDefaults
import com.vayunmathur.library.ui.IconArrowDropDown
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.SelectableDropdownMenuItem
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.rememberHaptics
import com.vayunmathur.maps.R

/**
 * Earth/Moon body switch, shown INSTEAD of [CategoryChips] when the globe is
 * zoomed all the way out.
 *
 * One chip reading the current body ("Earth" by default) that opens a two-item
 * menu. Selecting Moon swaps the globe to the NASA SVS raster pair (LROC color
 * + LOLA elevation); Earth restores the vector basemap. Session-only — a
 * restart resets to Earth (see `MapChromeState.body`).
 *
 * Styled like a category chip (same container fill) so the swap reads as the
 * row changing, not as new chrome appearing.
 */
@Composable
fun BodyDropdown(
    selected: MapBody,
    onSelect: (MapBody) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val haptics = rememberHaptics()
    var expanded by remember { mutableStateOf(false) }
    val colors = FilterChipDefaults.filterChipColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
    Box(modifier.padding(contentPadding)) {
        FilterChip(
            selected = true,
            onClick = {
                haptics.confirm()
                expanded = true
            },
            label = { Text(stringResource(selected.labelRes)) },
            trailingIcon = { IconArrowDropDown() },
            colors = colors,
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MapBody.entries.forEach { body ->
                SelectableDropdownMenuItem(
                    selected = body == selected,
                    onClick = {
                        expanded = false
                        haptics.confirm()
                        onSelect(body)
                    },
                    text = { Text(stringResource(body.labelRes)) },
                )
            }
        }
    }
}

private val MapBody.labelRes: Int
    get() = when (this) {
        MapBody.Earth -> R.string.body_earth
        MapBody.Moon -> R.string.body_moon
    }
