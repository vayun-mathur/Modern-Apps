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
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.parentalcontrols.R

/** Actions the PIN setup screen can take. */
data class PinSetupActions(
    val onSet: (String) -> Boolean,
    val onCancel: () -> Unit,
)

/**
 * First-run PIN creation (and later PIN change, after the old PIN passes [PinGateScreen]).
 *
 * Asks twice and only enables saving when both entries match and meet the length floor, so a
 * typo cannot lock the parent out of their own controls.
 */
@Composable
fun PinSetupScreen(actions: PinSetupActions) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    val mismatch = second.isNotEmpty() && first != second
    val ready = first.length >= SETUP_MIN_LENGTH && first == second

    AppScaffold(
        title = stringResource(R.string.pin_setup_title),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Text(stringResource(R.string.pin_setup_hint))
            OutlinedTextField(
                value = first,
                onValueChange = { first = it.filter(Char::isDigit).take(SETUP_MAX_LENGTH) },
                label = { Text(stringResource(R.string.pin_new_label)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                singleLine = true,
            )
            OutlinedTextField(
                value = second,
                onValueChange = { second = it.filter(Char::isDigit).take(SETUP_MAX_LENGTH) },
                label = { Text(stringResource(R.string.pin_confirm_label)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                isError = mismatch,
                supportingText =
                    if (mismatch) {
                        { Text(stringResource(R.string.pin_mismatch)) }
                    } else {
                        null
                    },
                singleLine = true,
            )
            TextButton(
                onClick = { if (actions.onSet(first)) { first = ""; second = "" } },
                enabled = ready,
            ) {
                Text(stringResource(UiR.string.save))
            }
            TextButton(onClick = actions.onCancel) {
                Text(stringResource(UiR.string.cancel))
            }
        }
    }
}

private const val SETUP_MIN_LENGTH = 4
private const val SETUP_MAX_LENGTH = 16
