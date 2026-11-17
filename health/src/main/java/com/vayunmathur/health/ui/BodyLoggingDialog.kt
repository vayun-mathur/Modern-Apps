package com.vayunmathur.health.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.vayunmathur.health.R
import com.vayunmathur.health.platform.HealthViewModel
import java.time.Instant

/**
 * Simple single-value logger for a body composition metric (weight, height, body fat, etc.).
 * The entered value is in [HealthMetricConfig.unit]; height is converted from cm to meters
 * before storage to match how records are persisted.
 */
@Composable
fun LogBodyMetricDialog(
    viewModel: HealthViewModel,
    config: HealthMetricConfig,
    onDismiss: () -> Unit,
) {
    var valueStr by remember { mutableStateOf("") }
    val value = valueStr.toDoubleOrNull()
    val isValid = value != null && value > 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.log_body_metric, stringResource(config.titleRes))) },
        text = {
            OutlinedTextField(
                value = valueStr,
                onValueChange = { valueStr = it },
                label = { Text(stringResource(config.titleRes)) },
                suffix = { Text(config.unit) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(
                enabled = isValid,
                onClick = {
                    if (value != null) {
                        val storedValue =
                            if (config == HealthMetricConfig.HEIGHT) value / 100.0 else value
                        viewModel.logBodyMetric(config.recordType, storedValue, Instant.now())
                        onDismiss()
                    }
                },
            ) { Text(stringResource(UiR.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.cancel)) } },
    )
}
