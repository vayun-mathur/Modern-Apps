package com.vayunmathur.travel.util

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

/**
 * Opens an affiliate [bookingUrl] in the user's browser / a Custom Tab via a
 * plain `ACTION_VIEW` intent. v1 deep-links out to the provider to complete the
 * booking — there are no in-app payments.
 */
fun openBooking(context: Context, bookingUrl: String) {
    if (bookingUrl.isBlank()) return
    val intent = Intent(Intent.ACTION_VIEW, bookingUrl.toUri())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
