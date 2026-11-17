package com.vayunmathur.vpn.ui

import androidx.compose.ui.res.stringResource
import com.vayunmathur.vpn.R
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.DetailScaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.vpn.Route
import com.vayunmathur.vpn.data.WgConfigParser
import com.vayunmathur.vpn.platform.VpnViewModel
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.sharedText

/**
 * Read-only detail for an imported .conf tunnel — the only way to add is opening a .conf file.
 * Export as wg-quick .conf is supported.
 */
@Composable
fun ConfigDetailPage(backStack: NavBackStack<Route>, vm: VpnViewModel, id: Long) {
    val cfg = vm.configState(id)
    var exportText by remember { mutableStateOf<String?>(null) }

    DetailScaffold(
        title = stringResource(R.string.tunnel, cfg.name),
        backStack = backStack,
        scrollBehavior = appBarScrollBehavior(),
    ) {
        Text(
            stringResource(R.string.imported_from_wireguard_conf_using_gotat),
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.name, cfg.name),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.sharedText("vpn-title-$id"),
        )
        Text(
            stringResource(R.string.endpoint, cfg.peerEndpoint),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.sharedText("vpn-endpoint-$id"),
        )
        Text(stringResource(R.string.address, cfg.address), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Text(stringResource(R.string.dns, cfg.dns), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Text(stringResource(R.string.allowedips, cfg.peerAllowedIPs), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Text(stringResource(R.string.mtu_keepalive_s, cfg.mtu, cfg.peerKeepalive), fontSize = 12.sp)
        Text(stringResource(R.string.privatekey, cfg.privateKey.take(16)), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.publickey, cfg.publicKey), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        Text(stringResource(R.string.peer_publickey, cfg.peerPublicKey), fontSize = 11.sp, fontFamily = FontFamily.Monospace)

        Button({ exportText = WgConfigParser.toWgQuick(cfg) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.export_as_conf)) }

        if (exportText != null) {
            Text(exportText!!, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(64.dp))
    }
}
