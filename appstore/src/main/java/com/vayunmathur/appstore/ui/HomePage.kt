package com.vayunmathur.appstore.ui
import com.vayunmathur.appstore.R
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.vayunmathur.library.ui.AppPullToRefreshBox
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.IconPackage
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.appstore.platform.AppSection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.platform.AppStoreViewModel
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CardDefaults
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.FilterChip
import com.vayunmathur.library.ui.IconChevronRight
import com.vayunmathur.library.ui.IconDownload
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text

/** Binds [AppStoreViewModel] to the stateless [HomeScreen]. */
@Composable
fun HomePage(
    viewModel: AppStoreViewModel,
    onAppClick: (UnifiedApp) -> Unit,
    onOpenUpdates: () -> Unit,
    onOpenBrowse: (AppSection) -> Unit,
) {
    val state by viewModel.home.collectAsState()
    // The updates banner counts every source, but the Play half of the update check only
    // ran when the Updates tab was opened — so a fresh launch showed the F-Droid count
    // until the user visited that tab (issue #701). Kick off one check from home.
    LaunchedEffect(Unit) {
        viewModel.checkForUpdates()
    }
    HomeScreen(
        state = state,
        actions = viewModel,
        onAppClick = onAppClick,
        onOpenUpdates = onOpenUpdates,
        onOpenBrowse = onOpenBrowse,
    )
}


@Composable
internal fun StatusLine(message: String, isSyncing: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isSyncing) {
            CircularProgressIndicator(Modifier.size(14.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(
            message.ifBlank { stringResource(R.string.sync_in_progress) },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun UpdatesBanner(count: Int, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconDownload()
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    pluralStringResource(R.plurals.updates_count, count, count),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.updates_banner_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconChevronRight()
        }
    }
}

@Composable
internal fun CategoryRow(
    categories: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    LazyRow(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item("all") {
            FilterChip(
                selected = selected == null,
                onClick = { onSelect(null) },
                label = { Text(stringResource(R.string.filter_all)) },
            )
        }
        items(categories, key = { it }) { category ->
            FilterChip(
                selected = selected == category,
                onClick = { onSelect(if (selected == category) null else category) },
                label = { Text(category) },
            )
        }
    }
}

@Composable
internal fun LoadingRow() {
    Row(
        Modifier.fillMaxWidth().height(160.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator()
    }
}
