package com.vayunmathur.vpn.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import com.vayunmathur.library.ui.Text
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.LazyListScaffold
import com.vayunmathur.library.ui.SnackbarHost
import com.vayunmathur.library.ui.SnackbarHostState
import com.vayunmathur.library.util.sharedText
import com.vayunmathur.vpn.R
import com.vayunmathur.vpn.data.VpnConfig

/**
 * The tunnel list, with no ViewModel and no back stack so it can be rendered from a
 * `@Preview` — see `src/screenshotTest`, which is where the store listing images come from.
 */
@Composable
fun ConfigListContent(
    configs: List<VpnConfig>,
    connectingId: Long?,
    activeId: Long?,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    onImport: () -> Unit = {},
    onGeoPicker: () -> Unit = {},
    onToggleConnect: (VpnConfig) -> Unit = {},
    onOpen: (VpnConfig) -> Unit = {},
    onDelete: (VpnConfig) -> Unit = {},
) {
    LazyListScaffold(
        title = stringResource(R.string.vpn_wireguard_gotatun),
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(onClick = onImport) {
                IconAdd()
            }
        },
        horizontalPadding = 16.dp,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Button(onGeoPicker, Modifier.fillMaxWidth()) { Text(stringResource(R.string.browse_by_location)) }
        }
        if (configs.isEmpty()) {
            item {
                Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.no_tunnels_yet), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.add_a_connection_by_opening_a_conf_file), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Button(onImport) { Text(stringResource(R.string.open_conf_file)) }
                    }
                }
            }
        } else {
            items(configs, key = { it.id }) { cfg ->
                ConfigRow(
                    cfg,
                    isActive = activeId == cfg.id,
                    isConnecting = connectingId == cfg.id,
                    onConnect = { onToggleConnect(cfg) },
                    onClick = { onOpen(cfg) },
                    onDelete = { onDelete(cfg) },
                )
            }
        }
    }
}

@Composable
private fun ConfigRow(
    cfg: VpnConfig,
    isActive: Boolean,
    isConnecting: Boolean,
    onConnect: () -> Unit,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = if (isActive) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        else CardDefaults.cardColors(),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(
                        cfg.name.ifBlank { "Unnamed" },
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        modifier = Modifier.sharedText("vpn-title-${cfg.id}"),
                    )
                    if (cfg.provider.isNotBlank()) {
                        Text(
                            listOf(cfg.provider, cfg.city, cfg.countryCode).filter { it.isNotBlank() }
                                .joinToString(" — "),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        cfg.peerEndpoint,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.sharedText("vpn-endpoint-${cfg.id}"),
                    )
                }
                if (isConnecting) Text("\u2026")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onConnect) {
                    Text(if (isActive) "Disconnect" else "Connect")
                }
                androidx.compose.material3.IconButton(onClick = onDelete) { IconDelete() }
            }
            if (isActive) {
                Text(stringResource(R.string.connected_via_gotatun_wireguard), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Text(stringResource(R.string.address, cfg.address), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Text(stringResource(R.string.allowed, cfg.peerAllowedIPs), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
    }
}
