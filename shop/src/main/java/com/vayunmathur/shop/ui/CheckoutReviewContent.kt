package com.vayunmathur.shop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.FilterChip
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.shop.data.CheckoutInputs
import com.vayunmathur.shop.data.NativeCheckoutState
import com.vayunmathur.shop.domain.MoneyFormat

/** Pure session presentation shared by the live checkout and deterministic screenshot fixtures. */
@Composable
internal fun CheckoutReviewContent(
    state: NativeCheckoutState,
    editable: Boolean,
    onInputsChange: (CheckoutInputs) -> Unit,
    onOpenUrl: (String) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val checkout = state.checkout ?: return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        state.submittedInputs?.let { buyer ->
            Text("Submitted buyer", style = MaterialTheme.typography.titleSmall)
            listOf("${buyer.firstName} ${buyer.lastName}".trim(), buyer.email, buyer.phone)
                .filter { it.isNotBlank() }.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (buyer.hasAddress) {
                Text(listOf(buyer.street, buyer.extendedAddress).filter { it.isNotBlank() }.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                Text(listOf(buyer.city, buyer.region, buyer.postalCode, buyer.country).filter { it.isNotBlank() }.joinToString(", "), style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("Session: ${checkout.status} (${checkout.version})", style = MaterialTheme.typography.labelLarge)
        Text("Currency: ${checkout.currency}")
        checkout.totalEntries.forEach { Text("${it.label}: ${MoneyFormat.format(it.money)}") }
        checkout.messages.forEach { message ->
            Text(listOfNotNull(message.type.takeIf { it.isNotBlank() }, message.path, message.code, message.text).joinToString(": "))
        }
        Text(checkout.expiresAt?.let { "Expires: $it" } ?: "Expires 6 hours after creation, unless the merchant changes it.", style = MaterialTheme.typography.bodySmall)
        if (state.inputs != state.submittedInputs) Text("Inputs have changed. Update the session to obtain current merchant totals and shipping choices.")
        checkout.review.shipping.forEach { method ->
            method.groups.forEach { group ->
                Text("Shipping options", style = MaterialTheme.typography.titleSmall)
                group.options.forEach { option ->
                    val selectionKey = "${method.id}/${group.id}"
                    FilterChip(
                        selected = (state.inputs.options[selectionKey] ?: group.selectedOptionId) == option.id,
                        enabled = editable,
                        onClick = { onInputsChange(state.inputs.copy(options = state.inputs.options + (selectionKey to option.id))) },
                        label = { Text(option.title) },
                    )
                    if (option.description.isNotBlank()) Text(option.description, style = MaterialTheme.typography.bodySmall)
                    option.totals.forEach { Text("${it.label}: ${MoneyFormat.format(it.money)}", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        if (checkout.status == "ready_for_complete") Text("The merchant reports payment readiness. Native payment is unavailable; no completion call will be made.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = {}, enabled = state.completionEnabled, modifier = Modifier.fillMaxWidth()) { Text("Native payment unavailable") }
        Text("Pending session only. Check purchase and payment status on the merchant website.", style = MaterialTheme.typography.bodySmall)
        checkout.review.links.forEach { link ->
            TextButton(onClick = { onOpenUrl(link.url) }) { Text(link.title.ifBlank { link.type }) }
        }
        TextButton(enabled = !state.busy, onClick = onRefresh) { Text("Refresh session (read only)") }
    }
}
