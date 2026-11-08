package com.vayunmathur.library.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import com.vayunmathur.library.util.AppMessages

/**
 * Launching other apps, without the crash.
 *
 * `startActivity` throws [ActivityNotFoundException] when nothing on the
 * device handles the intent, and 26 call sites across 14 apps were not
 * catching it - a phone with no SMS app, no mail client or no maps app would
 * take the whole app down from a contact row. The apps that did handle it each
 * did so differently, and three had their own "no app can open this" string.
 *
 * Every function here reports the failure rather than throwing, so a missing
 * handler is a message instead of a crash.
 */
object ExternalIntents {

    /**
     * Start [intent], reporting instead of throwing if nothing handles it.
     *
     * @param failureMessage overrides the generic message when the caller has
     *   something more specific to say.
     * @return whether an app was found.
     */
    fun launch(context: Context, intent: Intent, failureMessage: String? = null): Boolean =
        try {
            // startActivity from a non-Activity context (e.g. Application, as
            // several callers pass LocalContext/applicationContext) throws
            // AndroidRuntimeException without NEW_TASK; add it so the
            // "reports instead of throwing" contract below actually holds.
            val toStart = if (context !is Activity &&
                intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK == 0
            ) {
                Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            } else {
                intent
            }
            context.startActivity(toStart)
            true
        } catch (_: ActivityNotFoundException) {
            AppMessages.show(failureMessage ?: context.getString(R.string.no_app_found))
            false
        }

    /** Open a web or content URL in whichever app handles it. */
    fun openUrl(context: Context, url: String, failureMessage: String? = null): Boolean =
        launch(context, Intent(Intent.ACTION_VIEW, url.toUri()), failureMessage)

    /**
     * Open [packageName]'s store listing, so a missing app can be installed.
     *
     * `:appstore` claims the `market` scheme outright and turns `?id=` into a package, so this lands
     * on that app's install page. Reports rather than throws when nothing handles it, like everything
     * else here.
     */
    fun openAppListing(
        context: Context,
        packageName: String,
        failureMessage: String? = null,
    ): Boolean = openUrl(context, "market://details?id=$packageName", failureMessage)

    /** Open a file by content URI, granting read access to the receiving app. */
    fun openFile(
        context: Context,
        uri: Uri,
        mimeType: String? = null,
        failureMessage: String? = null,
    ): Boolean = launch(
        context,
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType ?: context.contentResolver.getType(uri))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        },
        failureMessage,
    )

    /** Compose an SMS to [number]. */
    fun sendSms(context: Context, number: String, failureMessage: String? = null): Boolean =
        launch(context, Intent(Intent.ACTION_SENDTO, "sms:$number".toUri()), failureMessage)

    /** Compose an email to [address]. */
    fun sendEmail(context: Context, address: String, failureMessage: String? = null): Boolean =
        launch(context, Intent(Intent.ACTION_SENDTO, "mailto:$address".toUri()), failureMessage)

    /** Dial [number] - the dialer, not a direct call, so no permission is needed. */
    fun dial(context: Context, number: String, failureMessage: String? = null): Boolean =
        launch(context, Intent(Intent.ACTION_DIAL, "tel:$number".toUri()), failureMessage)

    /** Show [query] on a map. */
    fun openMap(context: Context, query: String, failureMessage: String? = null): Boolean =
        launch(
            context,
            Intent(Intent.ACTION_VIEW, "geo:0,0?q=${Uri.encode(query)}".toUri()),
            failureMessage,
        )

    /**
     * Share text through the system chooser.
     *
     * Always wrapped in a chooser: a bare ACTION_SEND can resolve straight to
     * whichever app the user once picked as default, which is rarely what a
     * share button should do.
     */
    fun shareText(context: Context, text: String, chooserTitle: String? = null): Boolean =
        launch(
            context,
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                },
                chooserTitle,
            ),
        )

    /** Share a file by content URI through the system chooser. */
    fun shareFile(
        context: Context,
        uri: Uri,
        mimeType: String? = null,
        chooserTitle: String? = null,
    ): Boolean = launch(
        context,
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = mimeType ?: context.contentResolver.getType(uri) ?: "*/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            chooserTitle,
        ),
    )

    /**
     * Copy [text] to the clipboard.
     *
     * Deliberately silent: since Android 13 the system shows its own copy
     * confirmation, so an app that also reports it shows the user two.
     */
    fun copyToClipboard(context: Context, text: String, label: String = "") {
        context.getSystemService<ClipboardManager>()
            ?.setPrimaryClip(ClipData.newPlainText(label, text))
    }
}
