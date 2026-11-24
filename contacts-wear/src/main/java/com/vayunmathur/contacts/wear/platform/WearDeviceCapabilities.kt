package com.vayunmathur.contacts.wear.platform

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.vayunmathur.contacts.wear.sync.WearLinkBridge

/** What this watch hardware/software can actually do, probed honestly at runtime. */
data class WearDeviceCapabilities(
    val hasTelephony: Boolean = false,
    val hasDialer: Boolean = false,
    val hasSmsHandler: Boolean = false,
    val hasEmailHandler: Boolean = false,
    val phoneReachable: Boolean = false,
) {
    /** WearActionPolicy input: a watch call needs telephony AND a dialer handler. */
    val canCallOnWatch: Boolean get() = hasTelephony && hasDialer
}


object WearDeviceCapabilitiesProbe {

    /** Synchronous handler/telephony probe (cheap PackageManager queries). */
    fun probeHandlers(context: Context): WearDeviceCapabilities {
        val pm = context.packageManager
        return WearDeviceCapabilities(
            hasTelephony = pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY),
            hasDialer = resolves(context, Intent(Intent.ACTION_DIAL, Uri.parse("tel:0"))),
            hasSmsHandler = resolves(context, Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:0"))),
            hasEmailHandler = resolves(context, Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:a@b.c"))),
        )
    }

    /** Full probe including whether a WearLink phone session is currently live. */
    suspend fun probe(context: Context): WearDeviceCapabilities {
        val base = probeHandlers(context)
        val reachable = runCatching { WearLinkBridge.isConnected(context) }.getOrDefault(false)
        return base.copy(phoneReachable = reachable)
    }

    private fun resolves(context: Context, intent: Intent): Boolean =
        runCatching {
            context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null
        }.getOrDefault(false)
}
