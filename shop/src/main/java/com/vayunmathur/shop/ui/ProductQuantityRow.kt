package com.vayunmathur.shop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.SegmentedButton
import com.vayunmathur.library.ui.SegmentedButtonDefaults
import com.vayunmathur.library.ui.SingleChoiceSegmentedButtonRow
import com.vayunmathur.library.ui.Text

@Composable
internal fun ProductQuantityRow(quantity: Int, onChange: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = false,
                onClick = { onChange((quantity - 1).coerceAtLeast(1)) },
                enabled = quantity > 1,
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
                modifier = Modifier.semantics { contentDescription = "Decrease quantity"; role = Role.Button },
                label = { Text("-") },
            )
            SegmentedButton(
                selected = false,
                onClick = {},
                enabled = false,
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
                modifier = Modifier.clearAndSetSemantics {
                    contentDescription = "Quantity: $quantity"
                    disabled()
                },
                label = { Text("$quantity", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium) },
            )
            SegmentedButton(
                selected = false,
                onClick = { onChange((quantity + 1).coerceAtMost(99)) },
                enabled = quantity < 99,
                shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
                modifier = Modifier.semantics { contentDescription = "Increase quantity"; role = Role.Button },
                label = { Text("+") },
            )
        }
    }
}
