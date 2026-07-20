package com.vayunmathur.travel.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Train
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.Icon
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text

/**
 * Placeholder for the Trains vertical. No free global rail-pricing API exists,
 * so this is a scaffold-only "coming soon" panel (see the plan's out-of-scope
 * note).
 */
@Composable
fun TrainsComingSoon(modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.Filled.Train,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp),
        )
        Text("Trains are coming soon", style = MaterialTheme.typography.titleMedium)
        Text(
            "We're working on rail search. For now, try Flights, Hotels, or Cars.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
