package com.vayunmathur.globalesim.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.vayunmathur.globalesim.R
import com.vayunmathur.globalesim.domain.QuoteMath
import com.vayunmathur.globalesim.domain.QuoteMath.PAYMENT_MAX_CENTS
import com.vayunmathur.globalesim.domain.QuoteMath.PAYMENT_MIN_CENTS
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.R as UiR

/**
 * Custom-amount top-up: any dollar amount from $5 to $20, paid by card or
 * crypto (+5% bonus). Payment funds the eSIM wallet directly; the ViewModel
 * watches the balance, so there is no manual "I've paid" step.
 */
@Composable
fun TopUpCard(
    busy: Boolean,
    onBuyWithCard: (Long) -> Unit,
    onBuyWithCrypto: (Long) -> Unit,
    pendingInvoiceUrl: String?,
    onClearCryptoInvoice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var amountText by rememberSaveable { mutableStateOf("") }
    val amountCents = remember(amountText) { QuoteMath.parseAmountCents(amountText) }
    val amountOk = amountCents != null && amountCents in PAYMENT_MIN_CENTS..PAYMENT_MAX_CENTS
    val inputError: String? = when {
        amountText.isBlank() -> null
        amountCents == null -> stringResource(R.string.topup_invalid_amount)
        amountCents < PAYMENT_MIN_CENTS -> stringResource(R.string.topup_minimum_payment, QuoteMath.formatCents(PAYMENT_MIN_CENTS))
        amountCents > PAYMENT_MAX_CENTS -> stringResource(R.string.topup_max_payment, QuoteMath.formatCents(PAYMENT_MAX_CENTS))
        else -> null
    }
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = it },
                label = { Text(stringResource(R.string.topup_amount_label)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                isError = inputError != null,
                modifier = Modifier.fillMaxWidth(),
            )
            if (inputError != null) {
                Text(
                    inputError,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { amountCents?.let(onBuyWithCard) },
                    enabled = !busy && amountOk,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.card_topup))
                }
                BadgedBox(badge = "+5%", modifier = Modifier.weight(1f)) {
                    Button(
                        onClick = { amountCents?.let(onBuyWithCrypto) },
                        enabled = !busy && amountOk && pendingInvoiceUrl == null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.crypto_topup))
                    }
                }
            }
            if (pendingInvoiceUrl != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, pendingInvoiceUrl.toUri()))
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.reopen_checkout))
                    }
                    OutlinedButton(
                        onClick = onClearCryptoInvoice,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(UiR.string.cancel))
                    }
                }
            }
        }
    }
}

/** Small pill badge overlapped on the top-end corner of [content]. */
@Composable
private fun BadgedBox(
    badge: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier) {
        content()
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.tertiaryContainer,
            modifier = Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-8).dp),
        ) {
            Text(
                badge,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}
