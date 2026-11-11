package com.vayunmathur.maps.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.ConfirmDialog
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.maps.R
import com.vayunmathur.maps.data.MapPreferences
import com.vayunmathur.maps.util.MapSettingsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * First-launch Google services consent.
 *
 * Shown once, on the first map open: enabling turns on Google place details
 * (photos, hours, phone/website, popular times) and Street View — the only two
 * features that contact Google. Ratings, review counts and review summaries
 * always come from the offline archive. Disabled (the default) means zero
 * Google traffic: search, pins, routing, traffic, and transit stay fully
 * offline/first-party.
 *
 * Either answer records [MapPreferences.KEY_GOOGLE_CONSENT_ASKED] so the
 * dialog never returns; the choice stays changeable in Settings.
 */
@Composable
fun GoogleConsentDialog(settingsViewModel: MapSettingsViewModel, onDone: () -> Unit) {
    var visible by remember { mutableStateOf(true) }
    if (!visible) return
    ConfirmDialog(
        title = stringResource(R.string.google_consent_title),
        message = stringResource(R.string.google_consent_message),
        confirmLabel = stringResource(R.string.google_consent_enable),
        dismissLabel = stringResource(R.string.google_consent_disable),
        onConfirm = {
            settingsViewModel.setGoogleServices(true)
            visible = false
            onDone()
        },
        onDismiss = {
            settingsViewModel.setGoogleServices(false)
            visible = false
            onDone()
        },
    )
}

/** True when the first-launch consent dialog still needs to be shown. */
fun shouldShowGoogleConsent(ds: DataStoreUtils): Boolean =
    !ds.getBoolean(MapPreferences.KEY_GOOGLE_CONSENT_ASKED, false)

/** Synchronous gate for non-composable call sites: defaults to off. */
fun googleServicesEnabled(ds: DataStoreUtils): Boolean =
    ds.getBoolean(
        MapPreferences.KEY_GOOGLE_SERVICES,
        MapPreferences.DEFAULT_GOOGLE_SERVICES,
    )

/** Record an explicit choice without going through the ViewModel (tests, previews). */
fun recordGoogleConsent(ds: DataStoreUtils, enabled: Boolean) {
    CoroutineScope(Dispatchers.IO).launch {
        ds.setBoolean(MapPreferences.KEY_GOOGLE_SERVICES, enabled)
        ds.setBoolean(MapPreferences.KEY_GOOGLE_CONSENT_ASKED, true)
    }
}
