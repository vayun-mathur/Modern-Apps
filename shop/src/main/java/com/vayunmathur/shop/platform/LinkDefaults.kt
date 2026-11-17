package com.vayunmathur.shop.platform

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import androidx.core.net.toUri
import android.provider.Settings
import androidx.core.content.edit

/**
 * Merchant links are declared without verification (we can't host assetlinks on merchant
 * sites), so Android only opens them here after the user selects them in "Open by default".
 */
object LinkDefaults {
    private const val PREFS = "shop_link_defaults"
    private const val KEY_DISMISSED = "dismissed"

    fun shouldPrompt(context: Context): Boolean {
        if (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DISMISSED, false)) return false
        val manager = context.getSystemService(DomainVerificationManager::class.java) ?: return false
        val state = runCatching { manager.getDomainVerificationUserState(context.packageName) }.getOrNull()
            ?: return false
        return state.hostToStateMap.values.any { it == DomainVerificationUserState.DOMAIN_STATE_NONE }
    }

    fun openSettings(context: Context) {
        dismiss(context)
        val packageUri = "package:${context.packageName}".toUri()
        val openByDefault = Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, packageUri)
        val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
        try {
            context.startActivity(openByDefault.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            context.startActivity(appDetails.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun dismiss(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_DISMISSED, true) }
    }
}
