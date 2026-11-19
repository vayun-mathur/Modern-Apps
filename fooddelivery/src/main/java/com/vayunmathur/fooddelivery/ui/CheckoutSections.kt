package com.vayunmathur.fooddelivery.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.fooddelivery.R
import com.vayunmathur.fooddelivery.data.CartItem
import com.vayunmathur.fooddelivery.data.Deal
import com.vayunmathur.fooddelivery.data.SavedAddress
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.FilterChip
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.IconLocationOn
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.SegmentedButton
import com.vayunmathur.library.ui.SegmentedButtonDefaults
import com.vayunmathur.library.ui.SingleChoiceSegmentedButtonRow
import com.vayunmathur.library.ui.Text

@Composable
internal fun CheckoutOrderSummary(items: List<CartItem>) {
    Text(
        stringResource(R.string.order_summary),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )
    Spacer(Modifier.height(8.dp))
    items.forEach { item ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${item.quantity}x ${item.menuItem.name}",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (item.selectedModifiers.isNotEmpty()) {
                    Text(
                        item.selectedModifiers.joinToString(", ") { it.name },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text("$%.2f".format(item.totalPrice), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
internal fun CheckoutOrderTypePicker(
    isPickup: Boolean,
    onPickupChange: (Boolean) -> Unit,
) {
    Spacer(Modifier.height(4.dp))
    HorizontalDivider()
    Spacer(Modifier.height(12.dp))

    Text(
        stringResource(R.string.order_type),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )
    Spacer(Modifier.height(8.dp))

    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = !isPickup,
            onClick = { onPickupChange(false) },
            shape = SegmentedButtonDefaults.itemShape(0, 2),
            label = { Text(stringResource(R.string.delivery)) }
        )
        SegmentedButton(
            selected = isPickup,
            onClick = { onPickupChange(true) },
            shape = SegmentedButtonDefaults.itemShape(1, 2),
            label = { Text(stringResource(R.string.pickup)) }
        )
    }
}

@Composable
internal fun CheckoutAddressPicker(
    addresses: List<SavedAddress>,
    addressesLoaded: Boolean,
    selectedAddress: SavedAddress?,
    onSelect: (SavedAddress) -> Unit,
    deliveryInstructions: String,
    onInstructionsChange: (String) -> Unit,
) {
    Spacer(Modifier.height(4.dp))
    Text(
        stringResource(R.string.delivery_address),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )
    Spacer(Modifier.height(8.dp))

    if (addresses.isEmpty()) {
        if (addressesLoaded) {
            Text(
                stringResource(R.string.no_saved_addresses_add_one_in_account_se),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        addresses.forEach { addr ->
            val isSelected = selectedAddress?.id == addr.id
            Card(
                onClick = { onSelect(addr) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconLocationOn(
                        modifier = Modifier.size(20.dp),
                        tint = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            addr.label.ifEmpty { "Address" },
                            fontWeight = FontWeight.Medium,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            addr.addressStreet,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val cityStateZip = listOfNotNull(
                            addr.addressCity.ifEmpty { null },
                            addr.addressState.ifEmpty { null },
                            addr.addressZip.ifEmpty { null },
                        ).joinToString(", ")
                        if (cityStateZip.isNotEmpty()) {
                            Text(
                                cityStateZip,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (addr.aptUnit.isNotEmpty()) {
                            Text(
                                stringResource(R.string.apt_unit_2, addr.aptUnit),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (addr.gateCode.isNotEmpty()) {
                            Text(
                                stringResource(R.string.gate, addr.gateCode),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (isSelected) {
                        IconCheck(
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = deliveryInstructions,
        onValueChange = onInstructionsChange,
        label = { Text(stringResource(R.string.delivery_instructions_optional)) },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
internal fun CheckoutTipPicker(
    tipCents: Int,
    onTipChange: (Int) -> Unit,
) {
    Spacer(Modifier.height(4.dp))
    HorizontalDivider()
    Spacer(Modifier.height(12.dp))

    Text(
        stringResource(R.string.tip),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )
    Spacer(Modifier.height(8.dp))

    // Only four fixed chips: a scrollable Row, not FlowRow. FlowRow leaves
    // chips unplaced (0x0, unclickable) when a bounded-height parent (e.g. a
    // non-scrolling Column) gives it no remaining height; a Row still places
    // them, and scrolling keeps every chip reachable at large font scales
    // instead of running off-screen.
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        listOf(0, 200, 300, 500).forEach { cents ->
            val label = if (cents == 0) "None" else "$%.2f".format(cents / 100.0)
            FilterChip(
                selected = tipCents == cents,
                onClick = { onTipChange(cents) },
                label = { Text(label) }
            )
        }
    }
}

@Composable
internal fun CheckoutDealsPicker(
    deals: List<Deal>,
    selectedDealId: Int?,
    onSelect: (Int?) -> Unit,
    promoCode: String,
    onPromoChange: (String) -> Unit,
) {
    Spacer(Modifier.height(4.dp))
    HorizontalDivider()
    Spacer(Modifier.height(12.dp))

    if (deals.isNotEmpty()) {
        Text(
            stringResource(R.string.deals),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        deals.forEach { deal ->
            val chosen = selectedDealId == deal.id
            Card(
                // Tapping a chosen deal clears it, so a deal can be removed.
                onClick = { onSelect(if (chosen) null else deal.id) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            deal.title, fontWeight = FontWeight.Medium,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (deal.description.isNotEmpty()) {
                            Text(
                                deal.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (chosen) {
                        IconCheck(
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }

    Text(
        stringResource(R.string.promo_code),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = promoCode,
        onValueChange = onPromoChange,
        label = { Text(stringResource(R.string.promo_code_optional)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

// CheckoutTotals + PriceRow live in CheckoutTotals.kt.
