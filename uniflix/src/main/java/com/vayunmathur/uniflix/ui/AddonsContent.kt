package com.vayunmathur.uniflix.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.DesktopMaxWidthContainer
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconRefresh
import com.vayunmathur.library.ui.IconUpload
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Text
import com.vayunmathur.uniflix.platform.AddonRow

/**
 * Stateless addon-manager body: list + empty state. Split from [AddonsScreen]
 * so store-listing previews render without a ViewModel or database.
 */
@Composable
fun AddonsContent(
    rows: List<AddonRow>,
    adding: Boolean,
    refreshing: Boolean,
    onBack: () -> Unit,
    onAddClick: () -> Unit,
    onImportJsClick: () -> Unit,
    onToggle: (AddonRow) -> Unit,
    onSetDefault: (AddonRow) -> Unit,
    onClearDefault: () -> Unit,
    onMoveUp: (AddonRow) -> Unit,
    onMoveDown: (AddonRow) -> Unit,
    onRemove: (AddonRow) -> Unit,
    onRefresh: (AddonRow) -> Unit,
    onRefreshAll: () -> Unit,
    onOpenDetail: (addonDbId: Long) -> Unit,
) {
    AppScaffold(
        title = "Stream addons",
        onNavigateBack = onBack,
        actions = {
            IconButton(onClick = onRefreshAll, enabled = !refreshing) { IconRefresh() }
            IconButton(onClick = onImportJsClick) { IconUpload() }
            IconButton(onClick = onAddClick) { IconAdd() }
        },
    ) { padding ->
        DesktopMaxWidthContainer(Modifier.padding(padding)) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Addons supply streams. Uniflix ships none — paste an addon URL or import a .js plugin to watch.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (rows.isEmpty()) {
                    Text(
                        "No stream addons yet. Add one to enable playback.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onAddClick, enabled = !adding) { Text("Add addon") }
                        OutlinedButton(onClick = onImportJsClick, enabled = !adding) { Text("Import .js") }
                    }
                    return@Column
                }
                rows.forEach { row ->
                    AddonCard(
                        row = row,
                        onToggle = { onToggle(row) },
                        onSetDefault = { onSetDefault(row) },
                        onClearDefault = onClearDefault,
                        onMoveUp = { onMoveUp(row) },
                        onMoveDown = { onMoveDown(row) },
                        onRemove = { onRemove(row) },
                        onRefresh = { onRefresh(row) },
                        onOpenDetail = { onOpenDetail(row.entity.dbId) },
                    )
                }
                if (refreshing) CircularProgressIndicator()
            }
        }
    }
}
