package com.vayunmathur.uniflix.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.IconArrowDownward
import com.vayunmathur.library.ui.IconArrowUpward
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconRefresh
import com.vayunmathur.library.ui.IconStar
import com.vayunmathur.library.ui.IconStarBorder
import com.vayunmathur.library.ui.IconWarning
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Switch
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.uniflix.platform.AddonRow

/** One installed addon: identity, toggles, ordering, health, and actions. */
@Composable
fun AddonCard(
    row: AddonRow,
    onToggle: () -> Unit,
    onSetDefault: () -> Unit,
    onClearDefault: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
    onRefresh: () -> Unit,
    onOpenDetail: () -> Unit,
) {
    val entity = row.entity
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(entity.name, style = MaterialTheme.typography.titleMedium)
                    if (entity.version.isNotBlank()) {
                        Text("v${entity.version}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                IconButton(onClick = { if (row.isDefault) onClearDefault() else onSetDefault() }) {
                    if (row.isDefault) IconStar() else IconStarBorder()
                }
                Switch(checked = entity.enabled, onCheckedChange = { onToggle() })
            }
            entity.lastError?.let { error ->
                TextButton(onClick = onOpenDetail) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconWarning()
                        Text(error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = onMoveUp) { IconArrowUpward() }
                IconButton(onClick = onMoveDown) { IconArrowDownward() }
                IconButton(onClick = onRefresh) { IconRefresh() }
                IconButton(onClick = onRemove) { IconDelete() }
                TextButton(onClick = onOpenDetail) { Text("Details") }
            }
        }
    }
}
