package com.vayunmathur.camera.platform

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.provider.Settings
import com.vayunmathur.camera.domain.WifiQrConfig
import com.vayunmathur.camera.domain.WifiSuggestionPlan
import com.vayunmathur.camera.domain.suggestionPlan
import com.vayunmathur.library.log.Log

/** Outcome of a [WifiConnector.connect] attempt, reported via its callback. */
enum class WifiConnectResult {
    /** Suggestion accepted; the system asks the user to approve joining. */
    SUGGESTED,

    /** This app had already suggested the same network; nothing to do. */
    ALREADY_SUGGESTED,

    /** Suggestion API could not help; WiFi settings were opened instead. */
    FELL_BACK_TO_SETTINGS,

    /** Suggestion and the settings fallback both failed. */
    FAILED,
}

/**
 * Connects to a network from a scanned WiFi QR via [WifiNetworkSuggestion]
 * (API 29+; this app's minSdk is above that). The config — password
 * included — is used only to build the suggestion and is never logged
 * or persisted. WEP and any suggestion failure fall back to the system
 * WiFi settings screen so the user can join manually.
 */
class WifiConnector(private val context: Context) {

    /**
     * Suggests [config]'s network and reports the outcome to [onResult].
     * The callback fires synchronously on the calling thread.
     */
    fun connect(config: WifiQrConfig, onResult: (WifiConnectResult) -> Unit) {
        if (config.suggestionPlan() == WifiSuggestionPlan.UNSUPPORTED) {
            onResult(openWifiSettings())
            return
        }
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifiManager == null) {
            onResult(openWifiSettings())
            return
        }
        val status = try {
            wifiManager.addNetworkSuggestions(listOf(buildSuggestion(config)))
        } catch (e: IllegalArgumentException) {
            Log.status("WifiConnector", "addNetworkSuggestions rejected the suggestion", e)
            onResult(openWifiSettings())
            return
        } catch (e: SecurityException) {
            Log.status("WifiConnector", "addNetworkSuggestions denied", e)
            onResult(openWifiSettings())
            return
        }
        onResult(
            when (status) {
                WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS -> WifiConnectResult.SUGGESTED
                WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE ->
                    WifiConnectResult.ALREADY_SUGGESTED
                else -> {
                    Log.status("WifiConnector", "addNetworkSuggestions failed with status $status")
                    openWifiSettings()
                }
            },
        )
    }

    /** Builds the suggestion for [config]; never logs the passphrase. */
    private fun buildSuggestion(config: WifiQrConfig): WifiNetworkSuggestion {
        val builder = WifiNetworkSuggestion.Builder()
            .setSsid(config.ssid)
            .setIsHiddenSsid(config.hidden)
        when (config.suggestionPlan()) {
            // Only one passphrase setter may be called on a suggestion.
            // WPA2 is the common denominator: WPA3 routers run in
            // transition mode and accept a WPA2 suggestion.
            WifiSuggestionPlan.WPA_PASSPHRASE ->
                builder.setWpa2Passphrase(requireNotNull(config.password))
            WifiSuggestionPlan.OPEN, WifiSuggestionPlan.UNSUPPORTED -> Unit
        }
        return builder.build()
    }

    /** Opens system WiFi settings; [WifiConnectResult] reflects whether that worked. */
    private fun openWifiSettings(): WifiConnectResult = try {
        context.startActivity(
            Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        WifiConnectResult.FELL_BACK_TO_SETTINGS
    } catch (e: ActivityNotFoundException) {
        Log.status("WifiConnector", "No WiFi settings screen available", e)
        WifiConnectResult.FAILED
    }
}
