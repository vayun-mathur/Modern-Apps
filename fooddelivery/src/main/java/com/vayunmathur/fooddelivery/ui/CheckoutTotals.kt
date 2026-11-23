package com.vayunmathur.fooddelivery.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.fooddelivery.R
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text

@Composable
internal fun CheckoutTotals(
    subtotal: Double,
    fetchingPrices: Boolean,
    taxesDollars: Double?,
    deliveryFeeDollars: Double?,
    feesDollars: Double?,
    tipsDollars: Double?,
    rewardsApplied: Double,
    payTotal: Double?,
    displayTotal: Double?,
) {
    Spacer(Modifier.height(4.dp))
    HorizontalDivider()
    Spacer(Modifier.height(12.dp))

    PriceRow("Subtotal", subtotal)
    if (fetchingPrices) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Fetching is shown by the checkout scaffold's refresh indicator; text stays here.
            Text(
                stringResource(R.string.calculating_tax_fees),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else if (taxesDollars != null) {
        PriceRow("Tax", taxesDollars)
        if ((deliveryFeeDollars ?: 0.0) > 0) PriceRow("Delivery fee", deliveryFeeDollars ?: 0.0)
        if ((feesDollars ?: 0.0) > 0) PriceRow("Service fees", feesDollars ?: 0.0)
        if ((tipsDollars ?: 0.0) > 0) PriceRow("Tip", tipsDollars ?: 0.0)
        // Whatever the charge nets out below the component sum is a discount
        // (rewards / deal / promo / referral) — show it instead of silently
        // letting the total disagree with the components above it.
        if (rewardsApplied > 0.005) PriceRow("Rewards", -rewardsApplied)
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                stringResource(R.string.total),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "$%.2f".format(payTotal ?: displayTotal ?: subtotal),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

@Composable
internal fun PriceRow(label: String, amount: Double) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            label, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "$%.2f".format(amount), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
