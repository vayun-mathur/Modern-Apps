package com.vayunmathur.web.platform

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import java.net.URISyntaxException

/**
 * Handles navigations to non-web schemes (intent:, market:, tel:, mailto:, custom app links).
 *
 * intent:// URLs carry an encoded Intent payload plus an optional browser_fallback_url; they must be
 * decoded with [Intent.parseUri] rather than handed to ACTION_VIEW as raw data. When no app can
 * handle the intent we honor browser_fallback_url, then fall back to the Play Store for the target
 * package.
 *
 * @param loadFallback loads an http(s) URL back in the WebView (used for browser_fallback_url).
 * @return true if the navigation was consumed and the WebView should not load the URL itself.
 */
fun openExternalUri(context: Context, url: String, loadFallback: (String) -> Unit): Boolean {
    val intent = try {
        if (url.startsWith("intent:", ignoreCase = true)) {
            Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
        } else {
            Intent(Intent.ACTION_VIEW, url.toUri())
        }
    } catch (_: URISyntaxException) {
        return false
    }

    // Harden against web content targeting arbitrary internal components.
    intent.addCategory(Intent.CATEGORY_BROWSABLE)
    intent.component = null
    intent.selector = null
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    try {
        context.startActivity(intent)
        return true
    } catch (_: ActivityNotFoundException) {
        intent.getStringExtra("browser_fallback_url")?.takeIf { it.isNotBlank() }?.let {
            loadFallback(it)
            return true
        }
        intent.`package`?.let { pkg ->
            return try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, "market://details?id=$pkg".toUri())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                true
            } catch (_: ActivityNotFoundException) {
                false
            }
        }
        return false
    }
}
