package com.vayunmathur.appstore.platform
import com.vayunmathur.appstore.R

import android.content.Intent
import androidx.lifecycle.viewModelScope
import com.vayunmathur.appstore.data.PlayStoreLinks
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.security.VerificationResult
import com.vayunmathur.library.util.AppMessages
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ---- Actions ----
// Moved from AppStoreViewModel.kt (FileLength split); behavior identical.

internal fun AppStoreViewModel.installImpl(app: UnifiedApp) {
    viewModelScope.launch {
        val outcome = installer.install(app)
        AppMessages.show(
            when (val v = outcome.verification) {
                is VerificationResult.Rejected ->
                    context.getString(R.string.install_blocked, app.name, v.reason)
                is VerificationResult.Unverified ->
                    if (outcome.started) context.getString(R.string.install_started_unverified, app.name)
                    else context.getString(R.string.install_failed, app.name)
                is VerificationResult.Verified ->
                    if (outcome.started) context.getString(R.string.install_started, app.name)
                    else context.getString(R.string.install_failed, app.name)
            }
        )
        if (outcome.started) {
            // PackageInstaller commits asynchronously; give it a moment before the
            // installed list is re-read, or the row still shows the old version.
            delay(AppStoreViewModel.INSTALL_SETTLE_MS)
            installedRepo.refresh()
        }
    }
}

internal fun AppStoreViewModel.openAppImpl(packageName: String) {
    val launchIntent = runCatching {
        context.packageManager.getLaunchIntentForPackage(packageName)
    }.getOrNull()
    if (launchIntent == null) {
        openInPlayStoreImpl(packageName)
        return
    }
    startActivity(launchIntent)
}

internal fun AppStoreViewModel.uninstallAppImpl(packageName: String) {
    val started = startActivity(
        Intent(Intent.ACTION_DELETE, "package:$packageName".toUri())
            .putExtra(Intent.EXTRA_RETURN_RESULT, true)
    )
    if (!started) AppMessages.show(context.getString(R.string.uninstaller_unavailable))
}

internal fun AppStoreViewModel.openInPlayStoreImpl(packageName: String) {
    if (startActivity(Intent(Intent.ACTION_VIEW, "market://details?id=$packageName".toUri()))) return
    startActivity(Intent(Intent.ACTION_VIEW, PlayStoreLinks.playStoreUrl(packageName).toUri()))
}

internal fun AppStoreViewModel.openInBrowserImpl(url: String) {
    if (url.isBlank()) return
    startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
}

internal fun AppStoreViewModel.shareAppImpl(app: UnifiedApp) {
    val link = app.website ?: PlayStoreLinks.playStoreUrl(app.packageName)
    val share = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, context.getString(R.string.share_app_text, app.name, link))
    }
    startActivity(Intent.createChooser(share, null))
}
