package com.vayunmathur.education.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.vayunmathur.education.Route
import com.vayunmathur.education.platform.EducationViewModel
import com.vayunmathur.library.util.NavBackStack
import androidx.compose.ui.res.stringResource
import com.vayunmathur.education.R

/**
 * PIN gate shown as a dialog. Deliberately trivial for an adult, hard for a
 * young child. On success it replaces itself with the parent page.
 */
@Composable
fun ParentGatePage(backStack: NavBackStack<Route>, viewModel: EducationViewModel) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    Surface(
        shape = MaterialTheme.shapes.large,
        tonalElevation = 6.dp,
    ) {
        Column(
            Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.grown_ups_only), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.enter_the_parent_pin_to_manage_settings),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = pin,
                onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) { pin = it; error = false } },
                label = { Text(stringResource(R.string.pin)) },
                singleLine = true,
                isError = error,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
            )
            if (error) {
                Text(stringResource(R.string.incorrect_pin), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { backStack.pop() }) { Text(stringResource(UiR.string.cancel)) }
                Button(
                    onClick = {
                        if (viewModel.verifyPin(pin)) {
                            backStack.pop()
                            backStack.add(Route.Parent)
                        } else {
                            error = true
                        }
                    },
                    enabled = pin.length >= 4,
                ) { Text(stringResource(R.string.enter)) }
            }
        }
    }
}
