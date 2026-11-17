package com.vayunmathur.maps.ui.nav
import com.vayunmathur.maps.data.string

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CardDefaults
import com.vayunmathur.library.ui.FilledTonalButton
import com.vayunmathur.library.ui.IconCheckCircle
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.maps.R
import com.vayunmathur.library.ui.R as UiR

/**
 * End-of-trip arrival card. Shows a confirmation with
 * the destination name (when known) and a dismiss action that tears down the
 * navigation session.
 */
@Composable
fun ArrivalSummary(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    destinationName: String? = null,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
    ) {
        Column(
            Modifier.padding(20.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IconCheckCircle(Modifier.size(40.dp), MaterialTheme.colorScheme.onTertiaryContainer)
            Spacer(Modifier.height(8.dp))
            Text(
                androidx.compose.ui.res.stringResource(R.string.nav_arrived_title),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            if (!destinationName.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    destinationName,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(onClick = onDismiss) {
                Text(androidx.compose.ui.res.stringResource(UiR.string.dismiss))
            }
        }
    }
}
