package com.vayunmathur.taxi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.Checkbox
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.taxi.R
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.taxi.data.NewCard
import java.util.Calendar

@Composable
internal fun AddCardDialog(
    adding: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSubmit: (NewCard, Boolean) -> Unit,
) {
    val context = LocalContext.current
    var number by remember { mutableStateOf("") }
    var expiry by remember { mutableStateOf("") }
    var cvc by remember { mutableStateOf("") }
    var zip by remember { mutableStateOf("") }
    var makeDefault by remember { mutableStateOf(false) }
    // Client-side validation error, shown until cleared by a successful re-validate; the
    // processor/Lyft [error] is surfaced verbatim underneath.
    var validationError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_card_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = number,
                    onValueChange = { number = it },
                    label = { Text(stringResource(R.string.card_number)) },
                    singleLine = true,
                    enabled = !adding,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = expiry,
                        onValueChange = { expiry = it },
                        label = { Text(stringResource(R.string.card_expiry)) },
                        singleLine = true,
                        enabled = !adding,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = cvc,
                        onValueChange = { cvc = it },
                        label = { Text(stringResource(R.string.card_cvc)) },
                        singleLine = true,
                        enabled = !adding,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    value = zip,
                    onValueChange = { zip = it },
                    label = { Text(stringResource(R.string.card_zip)) },
                    singleLine = true,
                    enabled = !adding,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = makeDefault,
                        onCheckedChange = { makeDefault = it },
                        enabled = !adding,
                    )
                    Text(stringResource(R.string.card_set_default))
                }
                val shownError = validationError ?: error
                if (shownError != null) {
                    Text(
                        shownError,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (adding) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.adding_card))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !adding,
                onClick = {
                    validateCard(context, number, expiry, cvc, zip).fold(
                        onSuccess = {
                            validationError = null
                            onSubmit(it, makeDefault)
                        },
                        onFailure = { validationError = it.message },
                    )
                },
            ) { Text(stringResource(UiR.string.add)) }
        },
        dismissButton = {
            TextButton(enabled = !adding, onClick = onDismiss) {
                Text(stringResource(UiR.string.cancel))
            }
        },
    )
}

/**
 * Validates the typed card fully client-side so an invalid card never reaches a live processor
 * call. Returns a [NewCard] with normalised (digits-only) number/cvc, or a failure whose message
 * is a user-facing string.
 */
internal fun validateCard(
    context: android.content.Context,
    number: String,
    expiry: String,
    cvc: String,
    zip: String,
): Result<NewCard> {
    val digits = number.filter { it.isDigit() }
    if (digits.length !in 13..19 || !luhnValid(digits)) {
        return Result.failure(IllegalArgumentException(context.getString(R.string.card_invalid_number)))
    }
    val parsed = parseExpiry(expiry)
        ?: return Result.failure(IllegalArgumentException(context.getString(R.string.card_invalid_expiry)))
    val (month, year) = parsed
    val now = Calendar.getInstance()
    val curYear = now.get(Calendar.YEAR)
    val curMonth = now.get(Calendar.MONTH) + 1
    if (year < curYear || (year == curYear && month < curMonth)) {
        return Result.failure(IllegalArgumentException(context.getString(R.string.card_expired)))
    }
    val cvcDigits = cvc.filter { it.isDigit() }
    if (cvcDigits.length !in 3..4) {
        return Result.failure(IllegalArgumentException(context.getString(R.string.card_invalid_cvc)))
    }
    return Result.success(NewCard(digits, month, year, cvcDigits, zip.trim()))
}

/** Parses `MM/YY` (or `MMYY`) into (month 1-12, full year). Null when malformed. */
internal fun parseExpiry(raw: String): Pair<Int, Int>? {
    val digits = raw.filter { it.isDigit() }
    if (digits.length != 4) return null
    val month = digits.substring(0, 2).toIntOrNull() ?: return null
    val yy = digits.substring(2, 4).toIntOrNull() ?: return null
    if (month !in 1..12) return null
    return month to (2000 + yy)
}

internal fun luhnValid(digits: String): Boolean {
    var sum = 0
    var alt = false
    for (i in digits.indices.reversed()) {
        var d = digits[i] - '0'
        if (alt) {
            d *= 2
            if (d > 9) d -= 9
        }
        sum += d
        alt = !alt
    }
    return sum % 10 == 0
}
