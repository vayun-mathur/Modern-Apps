package com.vayunmathur.parentalcontrols.platform

import android.content.Context
import androidx.core.content.edit

/**
 * The "Filter explicit sites" preference behind the Web content filters row.
 *
 * Stored in SharedPreferences alongside the other on-device parental settings. Actual browser
 * enforcement is not wired from this app (it would need a supervision-aware browser); this keeps
 * the parent's choice so it can drive enforcement once a hook exists.
 *
 * ## Why there is no DNS-level filter here (hook audit)
 *
 * The repo was checked for a reusable DNS/VPN hook before this was left as a preference:
 * `networklocation/` is a location provider with no DNS or VPN primitive at all, and the
 * `vpn/` app's `VpnTunnelService` is a complete WireGuard client — its own app, its own
 * `VpnService` consent, INTERNET and a native tunnel — not a library this module can call.
 * Android allows exactly one active `VpnService`, so claiming it for parental DNS would
 * silently replace the user's VPN, and this app deliberately holds no INTERNET permission.
 * Bolting on a local DNS filter is therefore a platform/product decision (new consent
 * flow, VPN coexistence, upstream resolver), not an in-repo hook to wire up. Until then
 * the UI marks this row supervision-tier-only rather than inert-but-plausible; see
 * `ui/WebContentFiltersScreen.kt`.
 */
object WebContentFilters {

    private const val PREFS = "parentalcontrols_web_filters"
    private const val KEY_ENABLED = "filter_explicit_sites"

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_ENABLED, enabled) }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
