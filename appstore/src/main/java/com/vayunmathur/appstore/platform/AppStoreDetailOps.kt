package com.vayunmathur.appstore.platform

import androidx.lifecycle.viewModelScope
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.UnifiedApp
import kotlinx.coroutines.launch

// ---- Detail ----
// Moved from AppStoreViewModel.kt (FileLength split); behaviour identical apart
// from the route-driven entry point below.

/**
 * Load the detail page for [packageName] — the argument of `Route.Detail`.
 *
 * The route, not a ViewModel selection, decides which app is on screen: the
 * Detail entry calls this with its own argument, so a restored back stack
 * shows the app its entry names instead of whatever a stale flow last held.
 * The previous listing is dropped synchronously, before the fetch starts, so
 * a new destination never renders the app the last one loaded.
 *
 * Resolution order mirrors what a tap used to do: the catalogue row wins
 * whenever there is one, even for a package Play also lists. It is the row an
 * install would actually use — it carries the signer and hash an authenticated
 * index published — so showing the Play listing here would describe a download
 * this store is not going to make. Only F-Droid and Modern Apps rows are ever
 * cached, so this never replaces one Play listing with another. Next,
 * Accrescent's signed allowlist decides whether its full listing is fetched,
 * then Play fills in a listing for anything it hosts. A package none of them
 * knows (e.g. a `market://` link) settles as a Play shell named by its last
 * segment while Play is asked, and stays that shell if Play cannot answer.
 */
fun AppStoreViewModel.loadDetail(packageName: String) {
    detailJob?.cancel()
    detailAppFlow.value = null
    isLoadingDetailsFlow.value = true
    detailJob = viewModelScope.launch {
        val cached = catalog.byPackage(packageName)
        if (cached != null) {
            detailAppFlow.value = cached
            isLoadingDetailsFlow.value = false
            return@launch
        }
        // Accrescent listings from the home carousel are shells (no version
        // code, no signer yet); fetch the full listing before the page settles.
        if (AppSource.ACCRESCENT in enabledSources.value &&
            packageName in accrescentPackagesFlow.value
        ) {
            val details = accrescent.details(packageName)
            detailAppFlow.value = details ?: UnifiedApp(
                packageName = packageName,
                source = AppSource.ACCRESCENT,
                name = packageName.substringAfterLast('.'),
            )
            isLoadingDetailsFlow.value = false
            return@launch
        }
        // Play listings from a cluster are shells: no description, no
        // screenshots, no version code. Fill them in before the page settles;
        // the shell keeps the page addressable by its package while it loads.
        detailAppFlow.value = UnifiedApp(
            packageName = packageName,
            source = AppSource.PLAYSTORE,
            name = packageName.substringAfterLast('.'),
        )
        if (AppSource.PLAYSTORE in enabledSources.value) {
            val details = play.details(packageName)
            if (details != null) detailAppFlow.value = details
        }
        isLoadingDetailsFlow.value = false
    }
}
