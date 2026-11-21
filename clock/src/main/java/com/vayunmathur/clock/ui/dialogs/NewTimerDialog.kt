package com.vayunmathur.clock.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.clock.R
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.clock.Route
import com.vayunmathur.clock.data.Timer
import com.vayunmathur.clock.ui.components.sendTimerNotification
import com.vayunmathur.clock.platform.ClockViewModel
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Composable
fun NewTimerDialog(
    backStack: NavBackStack<Route>,
    clockViewModel: ClockViewModel,
    initialLengthSeconds: Int? = null,
    initialMessage: String? = null
) {
    var name by remember { mutableStateOf(initialMessage ?: "") }
    
    // Internal state is just the raw digits (up to 6)
    var timeInput by remember {
        mutableStateOf(
            initialLengthSeconds?.let {
                val h = it / 3600
                val m = (it % 3600) / 60
                val s = it % 60
                "%02d%02d%02d".format(h, m, s).trimStart('0')
            } ?: ""
        )
    }
    
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = { backStack.pop() },
        title = { Text(stringResource(R.string.label_new_timer)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.field_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
                
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.label_timer),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    OutlinedTextField(
                        value = timeInput,
                        onValueChange = { input ->
                            val digits = input.filter { it.isDigit() }
                            if (digits.length <= 6) {
                                timeInput = digits.trimStart('0')
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        visualTransformation = TimeVisualTransformation(),
                        singleLine = true
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val padded = timeInput.padStart(6, '0')
                    val h = padded.substring(0, 2).toIntOrNull() ?: 0
                    val m = padded.substring(2, 4).toIntOrNull() ?: 0
                    val s = padded.substring(4, 6).toIntOrNull() ?: 0
                    
                    if (h > 0 || m > 0 || s > 0) {
                        val duration = h.hours + m.minutes + s.seconds
                        val timer = Timer(true, name, Clock.System.now(), duration, duration, remainingStartElapsedRealtime = com.vayunmathur.clock.platform.DualTimeBase.elapsedRealtimeMs())
                        clockViewModel.upsert(timer) {
                            sendTimerNotification(context, timer.copy(id = it), true)
                        }
                        backStack.pop()
                    }
                },
                enabled = timeInput.isNotEmpty() && timeInput.any { it != '0' }
            ) {
                Text(stringResource(UiR.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = { backStack.pop() }) {
                Text(stringResource(UiR.string.cancel))
            }
        }
    )
}

/** Formats up to six raw digits as an hh:mm:ss display string for the new-timer field. */
class TimeVisualTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text.padStart(6, '0')
        val out = "${raw.substring(0, 2)}:${raw.substring(2, 4)}:${raw.substring(4, 6)}"

        val offsetMapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = 8
            override fun transformedToOriginal(offset: Int): Int = text.text.length
        }

        return TransformedText(AnnotatedString(out), offsetMapping)
    }
}
