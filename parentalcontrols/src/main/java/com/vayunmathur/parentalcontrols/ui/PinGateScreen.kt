package com.vayunmathur.parentalcontrols.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.parentalcontrols.R

/** Actions the PIN challenge screen can take. */
data class PinGateActions(
    val onVerify: (String) -> Boolean,
    val onCancel: () -> Unit,
)

/**
 * The challenge shown before any imposed-enforcement surface.
 *
 * Stateless except for the in-progress entry: verification itself lives in the host, which
 * owns the [ParentPin] and decides what unlocking means (open an editor, grant a bonus,
 * dismiss a lock screen). Wrong entries show the remaining-attempts hint via [error]; a
 * lockout disables the field until it lapses.
 */
@Composable
fun PinGateScreen(error: String?, lockedOut: Boolean, actions: PinGateActions) {
    var entry by remember { mutableStateOf("") }

    AppScaffold(
        title = stringResource(R.string.pin_title),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Text(stringResource(R.string.pin_challenge_hint))
            OutlinedTextField(
                value = entry,
                onValueChange = { entry = it.filter(Char::isDigit).take(MAX_PIN_LENGTH) },
                label = { Text(stringResource(R.string.pin_label)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                enabled = !lockedOut,
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                singleLine = true,
            )
            TextButton(
                onClick = { if (actions.onVerify(entry)) entry = "" },
                enabled = entry.length >= MIN_PIN_LENGTH && !lockedOut,
            ) {
                Text(stringResource(R.string.pin_unlock))
            }
            TextButton(onClick = actions.onCancel) {
                Text(stringResource(UiR.string.cancel))
            }
        }
    }
}

private const val MIN_PIN_LENGTH = 4
private const val MAX_PIN_LENGTH = 16
