package com.vayunmathur.fooddelivery.ui

import androidx.compose.ui.res.stringResource
import com.vayunmathur.fooddelivery.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconEdit
import com.vayunmathur.library.ui.IconShoppingCart
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.fooddelivery.data.CartItem
import com.vayunmathur.fooddelivery.data.SelectedModifier

@Composable
fun CartScreen(
    items: List<CartItem>,
    onRemoveItem: (Int) -> Unit,
    onCheckout: () -> Unit,
    onEditModifiers: (Int, List<SelectedModifier>) -> Unit = { _, _ -> },
) {
    // Index of the line being re-customised, if any.
    var editingIndex by remember { mutableStateOf<Int?>(null) }

    editingIndex?.let { idx ->
        items.getOrNull(idx)?.let { editing ->
            ModifierDialog(
                item = editing.menuItem,
                initialSelection = editing.selectedModifiers,
                confirmLabel = stringResource(R.string.save_changes),
                onDismiss = { editingIndex = null },
                onConfirm = { updated ->
                    onEditModifiers(idx, updated)
                    editingIndex = null
                },
            )
        }
    }

    AppScaffold(topBar = {}) { padding ->
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconShoppingCart(modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.your_cart_is_empty), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.add_items_from_a_restaurant),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            val subtotal = items.sumOf { it.totalPrice }
            Column(Modifier.fillMaxSize().padding(padding)) {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    itemsIndexed(items, key = { _, item -> item.lineId }) { index, item ->
                        CartItemRow(
                            item = item,
                            onRemove = { onRemoveItem(index) },
                            onEdit = if (item.menuItem.modifierGroups.isNotEmpty()) {
                                { editingIndex = index }
                            } else null,
                        )
                    }
                }
                Card(
                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(R.string.subtotal), style = MaterialTheme.typography.bodyMedium)
                            Text("$%.2f".format(subtotal),
                                style = MaterialTheme.typography.bodyMedium)
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(R.string.estimated_tax), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("$%.2f".format(subtotal * 0.09),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(R.string.total), fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleSmall)
                            Text("$%.2f".format(subtotal * 1.09), fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleSmall)
                        }
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = onCheckout,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.checkout))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CartItemRow(item: CartItem, onRemove: () -> Unit, onEdit: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("${item.quantity}x ${item.menuItem.name}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium)
            if (item.selectedModifiers.isNotEmpty()) {
                Text(
                    item.selectedModifiers.joinToString(", ") { it.name },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text("$%.2f".format(item.totalPrice),
            style = MaterialTheme.typography.bodyMedium)
        if (onEdit != null) {
            IconButton(onClick = onEdit) { IconEdit() }
        }
        IconButton(onClick = onRemove) {
            IconDelete(tint = MaterialTheme.colorScheme.error)
        }
    }
}
