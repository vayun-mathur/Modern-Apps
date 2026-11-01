package com.vayunmathur.globalesim.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.vayunmathur.globalesim.domain.CardValidation
import com.vayunmathur.globalesim.domain.CardValidation.ValidCard
import com.vayunmathur.globalesim.domain.QuoteMath
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.ModalBottomSheet
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text

/**
 * App-themed card payment sheet: own number/expiry/CVC fields. Emits
 * validated [ValidCard] params only — no Stripe imports here, so previews
 * render launcher-free and no PAN ever touches logging or disk. Callers
 * build `PaymentMethodCreateParams` from the result and must drop field
 * references after confirm.
 */
@Composable
fun CardPaymentSheet(
    amountCents: Long,
    busy: Boolean,
    confirming: Boolean,
    onPayWithCard: (ValidCard) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = modifier) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text(
                "Pay ${QuoteMath.formatCents(amountCents)}",
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                "Credit lands in your eSIM wallet shortly.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 2.dp),
            )
            var number by rememberSaveable { mutableStateOf("") }
            var expiry by rememberSaveable { mutableStateOf("") }
            var cvc by rememberSaveable { mutableStateOf("") }
            // Validate only after interaction, like TopUpCard's amount field.
            var touched by rememberSaveable { mutableStateOf(false) }
            val numberError = remember(number, touched) {
                if (touched) CardValidation.numberError(number) else null
            }
            val expiryError = remember(expiry, touched) {
                if (touched) CardValidation.expiryError(expiry) else null
            }
            val cvcError = remember(cvc, touched) {
                if (touched) CardValidation.cvcError(cvc) else null
            }
            val validCard = remember(number, expiry, cvc) {
                CardValidation.validCard(number, expiry, cvc)
            }
            val focus = LocalFocusManager.current
            val fieldsEnabled = !busy && !confirming
            OutlinedTextField(
                value = number,
                onValueChange = { number = it.filter { c -> c.isDigit() || c == ' ' }.take(23); touched = true },
                label = { Text("Card number") },
                placeholder = { Text("4242 4242 4242 4242") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Next,
                ),
                keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
                singleLine = true,
                enabled = fieldsEnabled,
                isError = numberError != null,
                supportingText = numberError?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = expiry,
                    onValueChange = { expiry = it.take(7); touched = true },
                    label = { Text("Expiry") },
                    placeholder = { Text("MM/YY") },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next,
                    ),
                    keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
                    singleLine = true,
                    enabled = fieldsEnabled,
                    isError = expiryError != null,
                    supportingText = expiryError?.let { { Text(it) } },
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = cvc,
                    onValueChange = { cvc = it.filter { c -> c.isDigit() }.take(4); touched = true },
                    label = { Text("CVC") },
                    placeholder = { Text("123") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                    singleLine = true,
                    enabled = fieldsEnabled,
                    isError = cvcError != null,
                    supportingText = cvcError?.let { { Text(it) } },
                    modifier = Modifier.weight(1f),
                )
            }
            val paying = busy || confirming
            Button(
                onClick = {
                    touched = true
                    validCard?.let(onPayWithCard)
                },
                enabled = !paying && validCard != null,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) {
                Text(if (confirming) "Confirming…" else "Pay ${QuoteMath.formatCents(amountCents)}")
            }
        }
    }
}
