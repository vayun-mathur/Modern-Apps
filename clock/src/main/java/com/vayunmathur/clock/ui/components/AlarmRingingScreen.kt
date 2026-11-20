package com.vayunmathur.clock.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.clock.R
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.ButtonDefaults
import com.vayunmathur.library.ui.FilledTonalButton
import com.vayunmathur.library.ui.IconAlarm
import com.vayunmathur.library.ui.IconPause
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text

@Composable
fun AlarmRingingScreen(
    alarmTime: String,
    alarmName: String,
    snoozeMinutes: Int,
    onDismiss: () -> Unit,
    onSnooze: () -> Unit,
    snoozeEnabled: Boolean = true,
) {
    AppScaffold(topBar = {}, modifier = Modifier.fillMaxSize()) { paddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(paddingValues).padding(horizontal = 24.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 32.dp)) {
                Text(text = alarmTime, style = MaterialTheme.typography.displayLarge.copy(fontSize = 80.sp, fontWeight = FontWeight.Black, letterSpacing = (-2).sp), color = MaterialTheme.colorScheme.onSurface)
                if (alarmName.isNotEmpty()) {
                    Text(text = alarmName.uppercase(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Surface(modifier = Modifier.size(160.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                    IconAlarm(modifier = Modifier.padding(40.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().height(96.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 8.dp)
                ) {
                    Text(text = stringResource(R.string.button_stop), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                }
                FilledTonalButton(onClick = onSnooze, enabled = snoozeEnabled, modifier = Modifier.fillMaxWidth().height(64.dp), shape = MaterialTheme.shapes.extraLarge) {
                    IconPause(); Spacer(modifier = Modifier.width(8.dp)); Text(text = stringResource(R.string.button_snooze, snoozeMinutes), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}
