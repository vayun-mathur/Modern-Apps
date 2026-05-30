package com.vayunmathur.weather.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DrawerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.R as LibraryR
import com.vayunmathur.weather.data.SavedLocation
import kotlinx.coroutines.launch

/**
 * 56 dp `CircleShape` pill `Surface` with a hamburger icon on the left and
 * the active location name centered. The whole surface is also clickable
 * to toggle the drawer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainSearchBar(
    paddingValues: PaddingValues,
    drawerState: DrawerState,
    activeLocation: SavedLocation?,
) {
    val scope = rememberCoroutineScope()
    val toggleDrawer = {
        scope.launch {
            drawerState.apply { if (isClosed) open() else close() }
        }
        Unit
    }
    val locationText = activeLocation?.let { loc ->
        buildString {
            append(loc.name)
            if (loc.country.isNotBlank()) {
                append(", "); append(loc.country)
            }
        }
    } ?: "••••"

    Surface(
        color = getSearchBarColor(),
        shape = CircleShape,
        shadowElevation = 1.dp,
        modifier = Modifier
            .padding(
                top = paddingValues.calculateTopPadding() + 8.dp,
                start = 16.dp,
                end = 16.dp,
            )
            .fillMaxWidth()
            .clickable { toggleDrawer() },
    ) {
        Row(
            modifier = Modifier
                .height(56.dp)
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { toggleDrawer() }) {
                Icon(
                    painter = painterResource(LibraryR.drawable.baseline_menu_24),
                    contentDescription = "Show locations menu",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = locationText,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(4.dp))
        }
    }
}

@Composable
private fun getSearchBarColor() =
    MaterialTheme.colorScheme.surface.copy(alpha = 0.4f)
