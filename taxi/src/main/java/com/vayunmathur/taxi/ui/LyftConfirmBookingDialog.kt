package com.vayunmathur.taxi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.taxi.R
import com.vayunmathur.taxi.data.ChargeAccount
import com.vayunmathur.taxi.data.RideQuote
import com.vayunmathur.taxi.network.lyft.LyftProvider

@Composable
fun LyftConfirmBookingDialog(
    quote: RideQuote,
    account: ChargeAccount?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.confirm_booking)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(quote.displayName, fontWeight = FontWeight.SemiBold)
                Text(formatFareRange(quote))
                quote.pickupEtaMinutes?.let { Text(stringResource(R.string.eta_minutes, it)) }
                account?.let { Text(stringResource(R.string.booking_charge_to, it.label)) }
                if (!LyftProvider.BOOKING_LIVE) {
                    Spacer(Modifier.size(4.dp))
                    Text(
                        stringResource(R.string.booking_dry_run_notice),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    stringResource(R.string.book),
                    color = if (LyftProvider.BOOKING_LIVE) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.dismiss)) } },
    )
}

private fun formatFareRange(quote: RideQuote): String {
    fun money(minor: Long) = "$%.2f".format(minor / 100.0)
    return if (quote.isRange) {
        "${money(quote.fareLowMinor)} – ${money(quote.fareHighMinor)}"
    } else {
        money(quote.fareLowMinor)
    }
}
