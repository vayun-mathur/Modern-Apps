package com.vayunmathur.fooddelivery.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.fooddelivery.R
import com.vayunmathur.fooddelivery.data.CartItem
import com.vayunmathur.fooddelivery.data.Deal
import com.vayunmathur.fooddelivery.data.SavedAddress
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text

/** The scrollable checkout sections: summary, fulfilment, address, tip, deals and totals. */
@Composable
fun CheckoutOrderContent(
    items: List<CartItem>,
    isPickup: Boolean,
    onPickupChange: (Boolean) -> Unit,
    addresses: List<SavedAddress>,
    addressesLoaded: Boolean,
    selectedAddress: SavedAddress?,
    onAddressSelect: (SavedAddress) -> Unit,
    deliveryInstructions: String,
    onInstructionsChange: (String) -> Unit,
    tipCents: Int,
    onTipChange: (Int) -> Unit,
    deals: List<Deal>,
    selectedDealId: Int?,
    onDealSelect: (Int?) -> Unit,
    promoCode: String,
    onPromoChange: (String) -> Unit,
    subtotal: Double,
    fetchingPrices: Boolean,
    taxesDollars: Double?,
    deliveryFeeDollars: Double?,
    feesDollars: Double?,
    tipsDollars: Double?,
    rewardsApplied: Double,
    payTotal: Double?,
    displayTotal: Double?,
    error: String?,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
    ) {
        item { CheckoutOrderSummary(items) }
        item { CheckoutOrderTypePicker(isPickup) { onPickupChange(it) } }

        if (!isPickup) {
            item {
                CheckoutAddressPicker(
                    addresses = addresses,
                    addressesLoaded = addressesLoaded,
                    selectedAddress = selectedAddress,
                    onSelect = onAddressSelect,
                    deliveryInstructions = deliveryInstructions,
                    onInstructionsChange = onInstructionsChange,
                )
            }
        }

        item { CheckoutTipPicker(tipCents) { onTipChange(it) } }

        item {
            CheckoutDealsPicker(
                deals = deals,
                selectedDealId = selectedDealId,
                onSelect = onDealSelect,
                promoCode = promoCode,
                onPromoChange = onPromoChange,
            )
        }

        item {
            CheckoutTotals(
                subtotal = subtotal,
                fetchingPrices = fetchingPrices,
                taxesDollars = taxesDollars,
                deliveryFeeDollars = deliveryFeeDollars,
                feesDollars = feesDollars,
                tipsDollars = tipsDollars,
                rewardsApplied = rewardsApplied,
                payTotal = payTotal,
                displayTotal = displayTotal,
            )
        }

        if (error != null) {
            item {
                Spacer(Modifier.height(8.dp))
                Text(error, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
