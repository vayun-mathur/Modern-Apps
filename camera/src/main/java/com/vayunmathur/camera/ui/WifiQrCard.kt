package com.vayunmathur.camera.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.camera.R
import com.vayunmathur.camera.domain.WifiQrConfig
import com.vayunmathur.camera.platform.WifiConnectResult
import com.vayunmathur.camera.platform.WifiConnector
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconWifiFull
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.AppMessages

/**
 * Standalone action for WiFi QR codes, styled like [FidoQrCard]: a single
 * "Connect to WiFi" button with the network SSID and the dismiss X inside
 * it, rendered directly above the zoom bar. Tapping it hands the network
 * to the system as a WiFi suggestion (see [WifiConnector]); the password
 * is never shown, logged or persisted.
 */
@Composable
internal fun WifiQrCard(
    config: WifiQrConfig,
    onDismiss: () -> Unit,
    context: Context,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = {
            WifiConnector(context.applicationContext).connect(config) { result ->
                when (result) {
                    WifiConnectResult.SUGGESTED -> {
                        AppMessages.show(context.getString(R.string.wifi_connect_suggested, config.ssid))
                        onDismiss()
                    }
                    WifiConnectResult.ALREADY_SUGGESTED ->
                        AppMessages.show(context.getString(R.string.wifi_already_suggested, config.ssid))
                    WifiConnectResult.FELL_BACK_TO_SETTINGS ->
                        AppMessages.show(context.getString(R.string.wifi_connect_use_settings, config.ssid))
                    WifiConnectResult.FAILED ->
                        AppMessages.show(context.getString(R.string.wifi_connect_failed, config.ssid))
                }
            }
        },
        modifier = modifier.padding(16.dp),
    ) {
        IconWifiFull(
            modifier = Modifier
                .padding(end = 8.dp)
                .size(18.dp),
            tint = MaterialTheme.colorScheme.onPrimary,
        )
        Column {
            Text(config.ssid)
            Text(stringResource(R.string.connect_to_wifi))
        }
        IconClose(
            modifier = Modifier
                .padding(start = 8.dp)
                .size(18.dp)
                .clickable(onClick = onDismiss),
            tint = MaterialTheme.colorScheme.onPrimary,
        )
    }
}
